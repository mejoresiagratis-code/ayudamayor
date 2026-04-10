package com.ayudamayor.app

import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.ayudamayor.app.billing.BillingManager

class FamilyPanelActivity : AppCompatActivity() {

    companion object {
        const val SERVER_URL =
            "https://mejoresiagratis.com/ayudamayor/views/familiar/index.php"
    }

    private lateinit var webView: WebView
    private lateinit var billing: BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)  // Mismo layout — solo un WebView

        billing = BillingManager(this)

        setupWebView()
        webView.loadUrl(SERVER_URL)
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString                 += " AyudaMayorAndroid/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val tier = billing.getCurrentTier()
                view.evaluateJavascript(
                    "window.__isAndroid = true; window.__androidTier = '$tier';", null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
