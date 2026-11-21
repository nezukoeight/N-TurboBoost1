package com.turboboost.system

import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
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
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                picker.launch("image/*")
                return true
            }
        }
        web.addJavascriptInterface(bridge, "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderListener)
        requestPermissionIfNeeded()
    }

    private fun showError(t: Throwable) {
        val tv = TextView(this).apply {
            text = "Erro ao iniciar:\n\n" + t.stackTraceToString()
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
            textSize = 12f
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun requestPermissionIfNeeded() {
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(reqCode)
            }
        } catch (_: Throwable) {
            // Shizuku ainda não está ativo
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }
}
