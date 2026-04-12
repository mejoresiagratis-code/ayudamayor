package com.ayudamayor.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.*
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ayudamayor.app.billing.BillingManager
import com.ayudamayor.app.bridge.NativeBridge

class FamilyPanelActivity : AppCompatActivity() {

    companion object {
        const val SERVER_URL = "https://mejoresiagratis.com/ayudamayor/views/familiar/index.php"
        const val MIC_REQUEST = 2001
    }

    private lateinit var webView: WebView
    private lateinit var billing: BillingManager
    private lateinit var bridge: NativeBridge
    private var pendingWebViewPermission: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        billing = BillingManager(this)
        bridge  = NativeBridge(this, billing) { js ->
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }

        setupWebView()
        webView.loadUrl(SERVER_URL)
    }

    private fun setupWebView() {
        // Persistir cookies en disco — imprescindible para mantener la sesión
        // entre aperturas de la app y cuando Android mata el proceso en background
        webView = findViewById(R.id.webView)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString                 += " AyudaMayorAndroid/3.2.42"
        }

        webView.addJavascriptInterface(bridge, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val tier = billing.getCurrentTier()
                view.evaluateJavascript(
                    "window.__isAndroid = true; window.__androidTier = '$tier';", null
                )
                // Enviar token FCM al servidor si hay uno pendiente
                val token = bridge.getFcmToken()
                if (token.isNotBlank()) {
                    view.evaluateJavascript(
                        """(async()=>{
                            try{await fetch(window.APP_BASE+'/api/push.php?action=register_fcm',
                            {method:'POST',credentials:'same-origin',
                             headers:{'Content-Type':'application/json'},
                             body:JSON.stringify({token:'$token'})});}catch(e){}
                           })();""", null
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val needed = mutableListOf<String>()
                request.resources.forEach {
                    when (it) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            needed.add(android.Manifest.permission.RECORD_AUDIO)
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            needed.add(android.Manifest.permission.CAMERA)
                    }
                }
                val allGranted = needed.all {
                    ContextCompat.checkSelfPermission(this@FamilyPanelActivity, it) ==
                            PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    request.grant(request.resources)
                } else {
                    pendingWebViewPermission = request
                    ActivityCompat.requestPermissions(
                        this@FamilyPanelActivity, needed.toTypedArray(), MIC_REQUEST
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) {
            val req = pendingWebViewPermission ?: return
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                req.grant(req.resources) else req.deny()
            pendingWebViewPermission = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
    override fun onPause() {
        super.onPause()
        // NO suspender JS — el familiar necesita recibir SOS en background
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
