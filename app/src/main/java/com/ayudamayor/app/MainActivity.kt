package com.ayudamayor.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ayudamayor.app.billing.BillingManager
import com.ayudamayor.app.bridge.NativeBridge
import com.ayudamayor.app.permissions.PermissionManager

class MainActivity : AppCompatActivity() {

    companion object {
        const val SERVER_URL =
            "https://mejoresiagratis.com/ayudamayor/views/mayor/index.php"
        const val LOCATION_PERMISSION_REQUEST = 1002
        const val CAMERA_PERMISSION_REQUEST   = 1003
        const val MIC_PERMISSION_REQUEST      = 1004
    }

    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String = ""
    private var pendingWebViewPermission: PermissionRequest? = null

    private lateinit var webView: WebView
    private lateinit var bridge: NativeBridge
    private lateinit var billing: BillingManager
    private lateinit var permissions: PermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        billing     = BillingManager(this)
        bridge      = NativeBridge(this, billing) { js ->
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
        permissions = PermissionManager(this)

        setupWebView()
        permissions.requestCriticalPermissions {
            webView.loadUrl(SERVER_URL)
        }

        // Pedir exclusión de optimización de batería (Doze)
        // → mantiene red activa aunque pantalla esté apagada
        (application as? AyudaMayorApp)?.requestIgnoreBatteryOptimizations(this)

        // WakeLock parcial para mantener CPU activa en background
        AyudaMayorApp.acquireWakeLock(application)
    }

    private fun setupWebView() {
        // Persistir cookies en disco — imprescindible para mantener la sesión
        // entre aperturas de la app y cuando Android mata el proceso en background
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowContentAccess               = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(true)
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false
            userAgentString     += " AyudaMayorAndroid/3.2.36"
            cacheMode            = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(bridge, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val tier = billing.getCurrentTier()
                view.evaluateJavascript(
                    "window.__isAndroid = true; window.__androidTier = \'$tier\';", null
                )
                // Enviar token FCM pendiente al servidor
                val token = bridge.getFcmToken()
                if (token.isNotBlank()) {
                    view.evaluateJavascript(
                        """(async()=>{try{await fetch(window.APP_BASE+'/api/push.php?action=register_fcm',
                        {method:'POST',credentials:'same-origin',
                         headers:{'Content-Type':'application/json'},
                         body:JSON.stringify({token:'$token'})});}catch(e){}})();""", null
                    )
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadData(
                        "<html><body style=\"font-family:sans-serif;text-align:center;padding:40px;background:#f4f7ff\">" +
                        "<h2>Sin conexion</h2><p>Comprueba tu conexion a internet.</p>" +
                        "<button onclick=\"location.reload()\" style=\"padding:14px 28px;font-size:16px;" +
                        "background:#6C63FF;color:#fff;border:none;border-radius:12px\">Reintentar</button>" +
                        "</body></html>",
                        "text/html", "utf-8"
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // Intercepta micrófono y cámara — resuelve desde Android, no desde Chrome
            override fun onPermissionRequest(request: PermissionRequest) {
                val androidPerms = mutableListOf<String>()
                request.resources.forEach { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            androidPerms.add(android.Manifest.permission.RECORD_AUDIO)
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            androidPerms.add(android.Manifest.permission.CAMERA)
                    }
                }
                val allGranted = androidPerms.all {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) ==
                            PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    request.grant(request.resources)
                } else {
                    pendingWebViewPermission = request
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        androidPerms.toTypedArray(),
                        if (androidPerms.contains(android.Manifest.permission.CAMERA))
                            CAMERA_PERMISSION_REQUEST else MIC_PERMISSION_REQUEST
                    )
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                request.deny()
            }

            // Intercepta GPS — resuelve desde Android, no desde Chrome
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin   = origin
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                        LOCATION_PERMISSION_REQUEST
                    )
                }
            }

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

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                val granted = grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
                pendingGeolocationCallback = null
                pendingGeolocationOrigin   = ""
            }
            CAMERA_PERMISSION_REQUEST, MIC_PERMISSION_REQUEST -> {
                val req = pendingWebViewPermission ?: return
                val allGranted = grantResults.isNotEmpty() &&
                        grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) req.grant(req.resources) else req.deny()
                pendingWebViewPermission = null
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        AyudaMayorApp.acquireWakeLock(application) // renovar si expiró
    }
    override fun onPause() {
        super.onPause()
        // NO llamar webView.onPause() — suspendería JS (GPS, SSE, timers)
        // El usuario puede minimizar la app y necesita seguir recibiendo alertas
        CookieManager.getInstance().flush() // persistir cookies al minimizar
    }
    override fun onDestroy() {
        AyudaMayorApp.releaseWakeLock()
        webView.destroy()
        super.onDestroy()
    }
}
