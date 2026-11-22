package com.turboboost.system

import android.os.Bundle
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val reqCode = 1001
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else arrayOf())
        filePathCallback = null
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == reqCode && ::bridge.isInitialized) bridge.status()
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        requestPermissionIfNeeded()
        if (::bridge.isInitialized) bridge.status()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            start()
        } catch (t: Throwable) {
            showError(t)
        }
    }

    private fun start() {
        web = WebView(this)
        bridge = Bridge(web, this)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView,
                cb: ValueCallback<Array<Uri>>,
                p: FileChooserParams
            ): Boolean {
                filePathCallback = cb
                picker.launch("image/*")
                return true
            }
        }

        web.addJavascriptInterface(bridge, "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        requestPermissionIfNeeded()
    }

    private fun requestPermissionIfNeeded() {
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(reqCode)
            }
        } catch (_: Throwable) {}
    }

    private fun showError(t: Throwable) {
        val tv = TextView(this)
        tv.text = "Erro ao iniciar:\n\n${t.stackTraceToString()}"
        tv.textSize = 12f
        tv.setPadding(24, 48, 24, 24)
        val sv = ScrollView(this)
        sv.addView(tv)
        setContentView(sv)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
    }
}
