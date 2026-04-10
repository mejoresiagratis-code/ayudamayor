package com.ayudamayor.app

import android.os.Bundle
import android.webkit.*
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.ayudamayor.app.bridge.NativeBridge
import com.ayudamayor.app.billing.BillingManager
import com.ayudamayor.app.permissions.PermissionManager

class MainActivity : AppCompatActivity() {

    companion object {
        // URL de producción — carga la vista del Mayor directamente
        const val SERVER_URL =
            "https://mejoresiagratis.com/ayudamayor/views/mayor/index.php"
    }

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
            builtInZoomControls = false
            displayZoomControls = false
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

        // WebChromeClient necesario para micrófono y permisos de cámara en WebView
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Conceder todos los permisos que el WebView solicite
                // (ya los pedimos al sistema en PermissionManager)
                request.grant(request.resources)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        this.permissions.onRequestPermissionsResult(requestCode, grantResults)
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
