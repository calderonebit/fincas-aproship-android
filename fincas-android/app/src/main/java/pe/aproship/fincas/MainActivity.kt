package pe.aproship.fincas

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var barra: ProgressBar
    private val servidor = BuildConfig.SERVIDOR
    private val host = Uri.parse(BuildConfig.SERVIDOR).host

    private var archivosCallback: ValueCallback<Array<Uri>>? = null
    private var fotoUri: Uri? = null
    private var ubicacionPendiente: Pair<String, GeolocationPermissions.Callback>? = null
    private var urlFallida: String? = null

    companion object {
        private const val PEDIR_ARCHIVO = 10
        private const val PERMISO_UBICACION = 20
        private const val PERMISO_AVISOS = 30
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(estado: Bundle?) {
        setTheme(R.style.Tema)
        super.onCreate(estado)
        Avisos.crearCanal(this)

        web = WebView(this)
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DCBF6B"))
            visibility = View.GONE
        }
        val raiz = FrameLayout(this)
        raiz.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        raiz.addView(barra, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8))
        setContentView(raiz)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            userAgentString = "$userAgentString FincasAPROSHIP-Android/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.addJavascriptInterface(PuenteWeb(), "AndroidApp")
        web.webViewClient = Cliente()
        web.webChromeClient = Navegador()
        web.setDownloadListener { url, ua, disposicion, mime, _ -> descargar(url, ua, disposicion, mime) }

        if (estado != null) web.restoreState(estado)
        else web.loadUrl(destino(intent) ?: servidor)

        Avisos.programar(this)
        Sincronizar.programar(this)
        // Cuando se suben los registros hechos sin señal, la página actualiza su contador
        Sincronizar.alCambiar = { runOnUiThread { web.evaluateJavascript("window.dispatchEvent(new CustomEvent('cola-subida'))", null) } }
    }

    override fun onDestroy() {
        Sincronizar.alCambiar = null
        super.onDestroy()
    }

    /** Ruta pedida por una notificación o por un enlace de fincas.aproship.com */
    private fun destino(i: Intent?): String? {
        i?.getStringExtra("ruta")?.let { r -> return if (r.startsWith("/")) servidor + r else servidor }
        val d = i?.data ?: return null
        return if (d.host == host) d.toString() else null
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        destino(i)?.let { web.loadUrl(it) }
    }

    override fun onSaveInstanceState(s: Bundle) {
        super.onSaveInstanceState(s)
        web.saveState(s)
    }

    @Deprecated("Atrás del sistema")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    private inner class Cliente : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val u = r.url
            if (u.host == host && (u.scheme == "https" || u.scheme == "http")) {
                if (u.path?.trimEnd('/') == "/salir") Avisos.desvincular(this@MainActivity)
                return false
            }
            // WhatsApp, llamadas, correo, mapas y demás: con su propia app
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "No hay una app para abrir este enlace", Toast.LENGTH_SHORT).show(); true
            }
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (!r.isForMainFrame) return
            urlFallida = r.url.toString()
            v.loadUrl("file:///android_asset/sin_conexion.html")
        }

        override fun onPageFinished(v: WebView, url: String) {
            CookieManager.getInstance().flush()
        }
    }

    private inner class Navegador : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            barra.progress = p
            barra.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }

        override fun onGeolocationPermissionsShowPrompt(origen: String, cb: GeolocationPermissions.Callback) {
            if (Uri.parse(origen).host != host) { cb.invoke(origen, false, false); return }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                cb.invoke(origen, true, true)
            } else {
                // Si solo tiene la ubicación aproximada, se vuelve a pedir: Android ofrece pasar a "precisa"
                ubicacionPendiente = origen to cb
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PERMISO_UBICACION)
            }
        }

        override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
            archivosCallback?.onReceiveValue(null)
            archivosCallback = cb
            val elegir = p.createIntent().apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                if (p.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val extras = mutableListOf<Intent>()
            // Tomar foto con la cámara (etiquetas, análisis de suelo, documentos)
            val aceptaImagen = p.acceptTypes.isEmpty() || p.acceptTypes.any { it.isBlank() || it.startsWith("image") || it.contains("pdf") }
            if (aceptaImagen && Build.VERSION.SDK_INT >= 29) {
                val valores = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "fincas_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Fincas APROSHIP")
                }
                fotoUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
                fotoUri?.let { extras += Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, it).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            val selector = Intent.createChooser(elegir, "Elegir archivo").apply {
                if (extras.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
            }
            return try {
                startActivityForResult(selector, PEDIR_ARCHIVO); true
            } catch (e: ActivityNotFoundException) {
                archivosCallback = null; false
            }
        }
    }

    @Deprecated("API de resultados de la plataforma")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        if (codigo != PEDIR_ARCHIVO) { @Suppress("DEPRECATION") super.onActivityResult(codigo, resultado, datos); return }
        val cb = archivosCallback ?: return
        archivosCallback = null
        var uris: Array<Uri>? = null
        if (resultado == RESULT_OK) {
            uris = WebChromeClient.FileChooserParams.parseResult(resultado, datos)
            if (uris == null) datos?.clipData?.let { c -> uris = Array(c.itemCount) { c.getItemAt(it).uri } }
            // Si no volvió ningún archivo elegido, fue la foto de la cámara
            if (uris == null && fotoUri != null) uris = arrayOf(fotoUri!!)
        }
        if (uris == null) fotoUri?.let { contentResolver.delete(it, null, null) }   // foto cancelada: no deja un archivo vacío
        else if (uris!!.none { it == fotoUri }) fotoUri?.let { contentResolver.delete(it, null, null) }
        fotoUri = null
        cb.onReceiveValue(uris)
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        if (codigo == PERMISO_UBICACION) {
            val ok = resultados.any { it == PackageManager.PERMISSION_GRANTED }
            val precisa = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ubicacionPendiente?.let { (o, cb) -> cb.invoke(o, ok, ok) }
            ubicacionPendiente = null
            when {
                !ok -> pedirAjustesUbicacion("Sin permiso de ubicación no se pueden tomar puntos con el GPS. Ábrelo en Permisos → Ubicación → «Permitir mientras se usa la app» y activa «Usar ubicación precisa».")
                !precisa -> pedirAjustesUbicacion("La app solo tiene la ubicación aproximada (error de 1 a 3 km): el mapa te pondrá en el centro del pueblo y no tomará puntos. En Permisos → Ubicación activa «Usar ubicación precisa».")
            }
        }
    }

    /** Explica el problema y abre los permisos de la app en el teléfono. */
    private fun pedirAjustesUbicacion(texto: String) {
        AlertDialog.Builder(this)
            .setTitle("Ubicación precisa")
            .setMessage(texto)
            .setPositiveButton("Abrir permisos") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Abre Ajustes → Apps → Fincas APROSHIP → Permisos", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun descargar(url: String, ua: String, disposicion: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "Esta descarga se abre desde el navegador", Toast.LENGTH_SHORT).show(); return
        }
        val nombre = URLUtil.guessFileName(url, disposicion, mime)
        try {
            val pedido = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .addRequestHeader("User-Agent", ua)
                .setTitle(nombre)
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(pedido)
            Toast.makeText(this, "Descargando $nombre", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun pedirPermisoAvisos() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISO_AVISOS)
        }
    }

    /** Lo que la página web puede pedirle al teléfono (window.AndroidApp) */
    inner class PuenteWeb {
        @JavascriptInterface fun vinculado(usuario: Int): Boolean = Avisos.vinculado(this@MainActivity, usuario)

        @JavascriptInterface fun vincular(token: String, usuario: Int, servidorWeb: String) {
            if (Uri.parse(servidorWeb).host != host || !token.matches(Regex("^[a-f0-9]{64}$"))) return
            Avisos.vincular(this@MainActivity, token, usuario, servidorWeb.trimEnd('/'))
            runOnUiThread { pedirPermisoAvisos() }
        }

        @SuppressLint("HardwareIds")
        @JavascriptInterface fun equipo(): String {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)?.takeLast(6) ?: ""
            return "${Build.MANUFACTURER} ${Build.MODEL} $id".trim().take(80)
        }

        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME

        /* Registros hechos sin señal: se guardan en el teléfono y Android los sube solo cuando hay señal */
        @JavascriptInterface fun encolar(json: String): Boolean = Sincronizar.encolar(this@MainActivity, json)
        @JavascriptInterface fun pendientes(): String = Sincronizar.pendientes(this@MainActivity)
        @JavascriptInterface fun descartar(uuid: String) = Sincronizar.descartar(this@MainActivity, uuid)
        @JavascriptInterface fun subirAhora() {
            Thread { try { Sincronizar.subir(this@MainActivity) } catch (e: Exception) {} }.start()
        }

        /* Lindero que se está tomando en el campo: copia en la memoria propia de la app (además de la de la página),
           para que no se pierda aunque se cierre la app, se apague el celular o se borren los datos del navegador */
        @JavascriptInterface fun guardarBorrador(clave: String, json: String) {
            if (clave.length > 80 || json.length > 2_000_000) return
            this@MainActivity.getSharedPreferences("borradores", Context.MODE_PRIVATE).edit().putString(clave, json).apply()   // en segundo plano: no traba la pantalla
        }
        @JavascriptInterface fun leerBorrador(clave: String): String =
            this@MainActivity.getSharedPreferences("borradores", Context.MODE_PRIVATE).getString(clave, "") ?: ""
        @JavascriptInterface fun borrarBorrador(clave: String) {
            this@MainActivity.getSharedPreferences("borradores", Context.MODE_PRIVATE).edit().remove(clave).apply()
        }

        /* Mientras se recorre el borde, la pantalla no se apaga: con la pantalla bloqueada el GPS deja de tomar puntos */
        @JavascriptInterface fun pantallaEncendida(si: Boolean) {
            runOnUiThread {
                if (si) this@MainActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else this@MainActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /* Desde la pantalla "Sin conexión": abre la toma de puntos guardada en el teléfono */
        @JavascriptInterface fun abrirCaptura() {
            runOnUiThread { web.loadUrl("$servidor/captura") }
        }

        @JavascriptInterface fun reintentar() {
            runOnUiThread { web.loadUrl(urlFallida ?: servidor) }
        }
    }
}
