package pe.aproship.fincas

import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Registros hechos en el campo sin señal: la página los entrega al teléfono (window.AndroidApp.encolar),
 * quedan en una base de datos del teléfono y se suben solos apenas hay señal, aunque la app esté cerrada.
 * Cada registro lleva un código único: el servidor lo registra una sola vez aunque llegue repetido.
 */
object Sincronizar {
    private const val JOB_ID = 2602
    private const val NOTIF_SUBIDOS = 2
    private val RUTA = Regex("^[a-z_]+(/[a-z0-9_]+){0,3}$")
    private val UUID = Regex("^[a-f0-9-]{36}$")

    /** Avisa a la pantalla abierta cuando cambia la lista de pendientes */
    @Volatile var alCambiar: (() -> Unit)? = null

    private class Base(c: Context) : SQLiteOpenHelper(c, "envios.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE envios (uuid TEXT PRIMARY KEY, ruta TEXT NOT NULL, metodo TEXT NOT NULL, datos TEXT, " +
                    "nombre TEXT, creado TEXT, error TEXT, intentos INTEGER NOT NULL DEFAULT 0)"
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, antes: Int, ahora: Int) {}
    }

    @Volatile private var base: Base? = null
    private fun db(c: Context): SQLiteDatabase {
        val b = base ?: synchronized(this) { base ?: Base(c.applicationContext).also { base = it } }
        return b.writableDatabase
    }

    /** Guarda un registro hecho sin señal. Devuelve false si el teléfono no está vinculado (lo guarda la página). */
    fun encolar(c: Context, json: String): Boolean {
        if (Avisos.prefs(c).getString("token", null) == null) return false
        return try {
            val o = JSONObject(json)
            val uuid = o.getString("uuid")
            val ruta = o.getString("ruta").trim('/')
            val metodo = o.optString("metodo", "POST").uppercase()
            // Las rutas permitidas las decide el servidor: así, registros nuevos no requieren actualizar el APK
            if (!UUID.matches(uuid) || !RUTA.matches(ruta) || metodo !in listOf("POST", "PUT", "DELETE")) return false
            val datos = if (o.isNull("datos")) null else o.get("datos")
            val v = ContentValues().apply {
                put("uuid", uuid); put("ruta", ruta); put("metodo", metodo)
                put("datos", datos?.toString())
                put("nombre", o.optString("nombre", "Registro")); put("creado", o.optString("creado"))
                // Parcela sin área: queda en el teléfono con el aviso y no se sube (el servidor la rechazaría)
                if (faltaArea(ruta, metodo, datos)) put("error", AVISO_AREA)
            }
            db(c).insertWithOnConflict("envios", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            programar(c)
            alCambiar?.invoke()
            true
        } catch (e: Exception) {
            false
        }
    }

    const val AVISO_AREA = "Falta el área de café de la parcela: ábrela, mide su lindero o escribe su área en hectáreas, y vuelve a guardarla."

    /** La parcela es el área de café: debe llevar su área o un lindero de al menos 3 puntos */
    fun faltaArea(ruta: String, metodo: String, datos: Any?): Boolean {
        val esParcela = ruta == "lotes" || Regex("^lotes/[0-9]+$").matches(ruta)
        if (!esParcela || metodo == "DELETE") return false
        val d = datos as? JSONObject ?: return false
        val area = d.optDouble("area_ha", Double.NaN)
        if (!area.isNaN() && area > 0) return false
        val pts = when (val p = d.opt("poligono")) {
            is JSONArray -> p.length()
            is String -> try { JSONArray(p).length() } catch (e: Exception) { 0 }
            else -> 0
        }
        return pts < 3
    }

    /** Lista de pendientes para mostrarla en la página */
    fun pendientes(c: Context): String {
        val a = JSONArray()
        db(c).rawQuery("SELECT uuid, ruta, nombre, creado, error, metodo, datos FROM envios ORDER BY creado", null).use { k ->
            while (k.moveToNext()) a.put(JSONObject().apply {
                put("uuid", k.getString(0)); put("ruta", k.getString(1)); put("nombre", k.getString(2))
                put("creado", k.getString(3)); put("error", if (k.isNull(4)) JSONObject.NULL else k.getString(4))
                put("metodo", k.getString(5))
                // Los datos van a la página para mostrar lo pendiente (por ejemplo, la labor programada sin señal)
                put("datos", if (k.isNull(6)) JSONObject.NULL else try { JSONObject(k.getString(6)) } catch (e: Exception) { JSONObject.NULL })
                put("nativo", true)
            })
        }
        return a.toString()
    }

    fun cantidad(c: Context): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM envios", null).use { k -> if (k.moveToFirst()) k.getInt(0) else 0 }

    fun descartar(c: Context, uuid: String) {
        db(c).delete("envios", "uuid = ?", arrayOf(uuid))
        alCambiar?.invoke()
    }

    /** Programa la subida para cuando haya señal (Android la ejecuta aunque la app esté cerrada). */
    fun programar(c: Context) {
        if (cantidad(c) == 0) return
        val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.allPendingJobs.any { it.id == JOB_ID }) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, SincronizarJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
        )
    }

    /** Sube lo pendiente. Devuelve false si hay que reintentar más tarde (sin señal o servidor con problemas). */
    fun subir(c: Context): Boolean {
        val p = Avisos.prefs(c)
        val token = p.getString("token", null) ?: return true
        val servidor = p.getString("servidor", BuildConfig.SERVIDOR) ?: BuildConfig.SERVIDOR
        data class Item(val uuid: String, val ruta: String, val metodo: String, val datos: String?, val nombre: String)
        val items = mutableListOf<Item>()
        db(c).rawQuery("SELECT uuid, ruta, metodo, datos, nombre FROM envios WHERE error IS NULL ORDER BY creado", null).use { k ->
            while (k.moveToNext()) items += Item(k.getString(0), k.getString(1), k.getString(2), if (k.isNull(3)) null else k.getString(3), k.getString(4) ?: "Registro")
        }
        if (items.isEmpty()) return true
        var subidos = 0
        var reintentar = false
        for (item in items) {
            val cuerpo = JSONObject().apply {
                put("uuid", item.uuid); put("ruta", item.ruta); put("metodo", item.metodo)
                put("datos", if (item.datos == null) JSONObject.NULL else try { JSONObject(item.datos) } catch (e: Exception) { JSONArray(item.datos) })
            }.toString()
            val con = (URL("$servidor/api/envio_app").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; requestMethod = "POST"; doOutput = true
                setRequestProperty("X-Token", token)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                con.outputStream.use { o -> o.write(cuerpo.toByteArray(Charsets.UTF_8)) }
                val codigo = con.responseCode
                val texto = (if (codigo < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { r -> r.readText() } ?: ""
                val j = try { JSONObject(texto) } catch (e: Exception) { null }
                when {
                    codigo == 401 -> { reintentar = false; break }          // el celular ya no está vinculado: se sube al volver a ingresar
                    codigo >= 500 || codigo == 429 || codigo == 409 || codigo == 419 -> { reintentar = true; break }   // 409: falta subir antes el registro del que depende
                    codigo in 200..299 && j?.optBoolean("ok") == true -> {
                        db(c).delete("envios", "uuid = ?", arrayOf(item.uuid)); subidos++
                    }
                    else -> {
                        val v = ContentValues().apply { put("error", j?.optString("error")?.takeIf { e -> e.isNotBlank() } ?: "El servidor respondió con un error ($codigo)") }
                        db(c).update("envios", v, "uuid = ?", arrayOf(item.uuid))
                    }
                }
            } catch (e: Exception) {
                reintentar = true; break                                   // se cortó la señal a la mitad
            } finally {
                con.disconnect()
            }
        }
        if (subidos > 0) avisar(c, subidos)
        alCambiar?.invoke()
        return !reintentar
    }

    private fun avisar(c: Context, n: Int) {
        Avisos.crearCanal(c)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        val errores = db(c).rawQuery("SELECT COUNT(*) FROM envios WHERE error IS NOT NULL", null).use { k -> if (k.moveToFirst()) k.getInt(0) else 0 }
        val titulo = if (n == 1) "Se subió 1 registro hecho sin señal" else "Se subieron $n registros hechos sin señal"
        nm.notify(NOTIF_SUBIDOS, Avisos.base(c, "/labores")
            .setContentTitle(titulo)
            .setContentText(if (errores > 0) "$errores no se pudieron subir: ábrelos en la app" else "Todo lo registrado en el campo ya está en el sistema")
            .build())
    }
}

/** Tarea de Android: sube lo pendiente cuando hay señal, aunque la app esté cerrada. */
class SincronizarJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ok = try { Sincronizar.subir(applicationContext) } catch (e: Exception) { false }
            jobFinished(params, !ok)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
