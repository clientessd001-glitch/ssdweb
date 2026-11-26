package com.seu.nexus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val appUrl = "https://clientessd001-glitch.github.io/nexusweb/"

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null

        if (result.resultCode != Activity.RESULT_OK) {
            stopUploadServiceSafely()
            callback?.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val uris = mutableListOf<Uri>()

        if (data?.clipData != null) {
            val count = data.clipData!!.itemCount
            for (i in 0 until count) {
                data.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else if (data?.data != null) {
            uris.add(data.data!!)
        }

        if (uris.isEmpty()) {
            stopUploadServiceSafely()
            callback?.onReceiveValue(null)
        } else {
            callback?.onReceiveValue(uris.toTypedArray())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                // Blinda o processo em primeiro plano antes de abrir a galeria/arquivos
                startUploadServiceSafely()

                return try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(Intent.createChooser(intent, "Selecionar arquivo"))
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    stopUploadServiceSafely()
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o seletor.", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){ return typeof window.handleAndroidBack === 'function' ? window.handleAndroidBack() : false; })();"
                ) { handled ->
                    if (handled != "true") {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.clearCache(true)
            webView.loadUrl(appUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun startUploadServiceSafely() {
        try {
            val serviceIntent = Intent(this, UploadKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    private fun stopUploadServiceSafely() {
        try {
            stopService(Intent(this, UploadKeepAliveService::class.java))
        } catch (_: Exception) {}
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun setUploadActive(active: Boolean) {
            runOnUiThread {
                if (active) startUploadServiceSafely() else stopUploadServiceSafely()
            }
        }

        @JavascriptInterface
        fun setFullscreen(enabled: Boolean) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val controller = window.insetsController ?: return@runOnUiThread
                    if (enabled) {
                        controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = if (enabled) {
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    } else {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        View.SYSTEM_UI_FLAG_VISIBLE
                    }
                }
            }
        }

        @JavascriptInterface
        fun toggleOrientation() {
            runOnUiThread {
                requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
        }

        @JavascriptInterface
        fun shareFile(base64Data: String, fileName: String, mimeType: String) {
            try {
                val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val cacheDir = File(cacheDir, "shared_files").apply { mkdirs() }
                val outFile = File(cacheDir, safeName)
                FileOutputStream(outFile).use { it.write(bytes) }

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${applicationContext.packageName}.fileprovider",
                    outFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                runOnUiThread {
                    startActivity(Intent.createChooser(shareIntent, "Compartilhar via SSD Nexus"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erro ao compartilhar arquivo.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
