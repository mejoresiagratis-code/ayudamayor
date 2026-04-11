package com.ayudamayor.app

import android.os.Bundle
import android.webkit.*
import android.webkit.GeolocationPermissions
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.ayudamayor.app.bridge.NativeBridge
import com.ayudamayor.app.billing.BillingManager
import com.ayudamayor.app.permissions.PermissionManager

class MainActivity : AppCompatActivity() {

    companion object {
        const val SERVER_URL =
            "https://mejoresiagratis.com/ayudamayor/views/mayor/index.php"
        const val LOCATION_PERMISSION_REQUEST = 1002
    }

    // Guardamos el callback de geolocalización del WebView para resolverlo
    // cuando el usuario responda al diálogo nativo de Android
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String = ""

    private lateinit var webView: WebView
    private lateinit var bridge: NativeBridge
    private lateinit var billing: BillingManager
    private lateinit var permissions: PermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pantalla siempre encendida — el mayor no debe ver que se apaga
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        billing     = BillingManager(this)
        bridge      = NativeBridge(this, billing)
        permissions = PermissionManager(this)

        setupWebView()

        // Pedir permisos críticos y luego cargar la URL
        permissions.requestCriticalPermissions {
            webView.loadUrl(SERVER_URL)
        }
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowContentAccess               = true
            mediaPlaybackRequiresUserGesture = false   // TTS y micrófono sin gesto
            mixedContentMode                 = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // User agent identifica la app para que el servidor pueda detectarla
            userAgentString                 += " AyudaMayorAndroid/1.0"
            // Caché agresivo — mejora rendimiento en red lenta
            cacheMode                        = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false
            setGeolocationEnabled(true)   // necesario para que navigator.geolocation funcione
        }

        // Registrar el bridge — accesible desde JS como window.NativeBridge
        webView.addJavascriptInterface(bridge, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Inyectar variables globales que el JS puede leer
                val tier = billing.getCurrentTier()
                view.evaluateJavascript(
                    """
                    window.__isAndroid   = true;
                    window.__androidTier = '$tier';
                    """.trimIndent(),
                    null
                )
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // Solo mostrar error en la URL principal, no en recursos
                if (request.isForMainFrame) {
                    view.loadData(
                        """
                        <html><body style="font-family:sans-serif;text-align:center;padding:40px">
                        <h2>Sin conexión</h2>
                        <p>Comprueba tu conexión a internet y vuelve a intentarlo.</p>
                        <button onclick="location.reload()" style="padding:12px 24px;font-size:16px">
                            Reintentar
                        </button>
                        </body></html>
                        """.trimIndent(),
                        "text/html",
                        "utf-8"
                    )
                }
            }
        }

        // WebChromeClient — gestiona permisos del WebView de forma nativa
        webView.webChromeClient = object : WebChromeClient() {

            // Permisos de cámara y micrófono (MediaStream API)
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            // Geolocalización — callback separado específico para location
            // Sin esto el WebView redirige al diálogo de Chrome en lugar de usar Android nativo
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                // Verificar si tenemos el permiso de sistema concedido
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    // Permiso ya concedido por Android — pasar al WebView directamente
                    callback.invoke(origin, true, false)
                } else {
                    // Pedir el permiso al sistema y luego concederlo al WebView
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                        LOCATION_PERMISSION_REQUEST
                    )
                    // Guardamos el callback para invocarlo cuando el usuario responda
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin  = origin
                }
            }

            // Ocultar el prompt de geolocalización si el usuario lo cancela
            override fun onGeolocationPermissionsHidePrompt() {
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, false, false)
                pendingGeolocationCallback = null
                pendingGeolocationOrigin   = ""
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        this.permissions.onRequestPermissionsResult(requestCode, grantResults)

        // Resolver el permiso de geolocalización del WebView
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
            pendingGeolocationCallback = null
            pendingGeolocationOrigin   = ""
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
