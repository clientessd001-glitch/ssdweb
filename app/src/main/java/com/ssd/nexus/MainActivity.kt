package com.ssd.nexus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        // Coloque aqui a URL do seu GitHub Pages (onde está o index.html)
        private const val APP_URL = "https://clientessd001-glitch.github.io/nexusweb/"
        private const val PREFS_NAME = "ssd_nexus_native_prefs"
        private const val KEY_SYNC_TREE_URI = "sync_tree_uri"
        private const val LOCAL_SYNC_HOST = "ssd-local-sync.internal"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var isUploadRunning = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        if (result.resultCode != RESULT_OK || result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data!!
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        } else {
            data.data?.let { uris.add(it) }
        }
        callback.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    private val syncFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SYNC_TREE_URI, uri.toString())
                .apply()

            val doc = DocumentFile.fromTreeUri(this, uri)
            val label = doc?.name ?: uri.lastPathSegment ?: "Pasta selecionada"
            val safeLabel = JSONObject.quote(label)
            webView.post {
                webView.evaluateJavascript("window.onSyncFolderSelected && window.onSyncFolderSelected($safeLabel);", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permissão de notificação no Android 13+ (necessária para mostrar o status do upload)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 901)
            }
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(0xFF060913.toInt())
        }

        // Enquadramento seguro abaixo da câmera frontal / barra de status no Android 15
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val fallbackTop = resources.getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
            val topPad = maxOf(bars.top, fallbackTop)
            view.setPadding(bars.left, topPad, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webView = WebView(this).apply {
            setBackgroundColor(0xFF060913.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return super.shouldInterceptRequest(view, request)
                    if (url.host == LOCAL_SYNC_HOST) {
                        val fileUriStr = url.getQueryParameter("uri") ?: return null
                        val mime = url.getQueryParameter("mime") ?: "application/octet-stream"
                        return try {
                            val stream = contentResolver.openInputStream(Uri.parse(fileUriStr))
                            if (stream != null) {
                                WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), stream)
                            } else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    return try {
                        filePickerLauncher.launch(Intent.createChooser(intent, "Selecionar arquivos"))
                        true
                    } catch (_: Exception) {
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }
            }
        }

        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(container)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false;") { res ->
                    if (res != "true") {
                        if (webView.canGoBack()) webView.goBack()
                        else moveTaskToBack(true)
                    }
                }
            }
        })

        webView.loadUrl(APP_URL)
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun setUploadActive(active: Boolean) {
            isUploadRunning = active
            val intent = Intent(this@MainActivity, UploadForegroundService::class.java).apply {
                action = if (active) UploadForegroundService.ACTION_START else UploadForegroundService.ACTION_STOP
            }
            try {
                if (active) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                    else startService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun updateUploadProgress(pct: Int, statusText: String) {
            if (!isUploadRunning) return
            val intent = Intent(this@MainActivity, UploadForegroundService::class.java).apply {
                action = UploadForegroundService.ACTION_UPDATE
                putExtra(UploadForegroundService.EXTRA_PCT, pct)
                putExtra(UploadForegroundService.EXTRA_TEXT, statusText)
            }
            try {
                startService(intent)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun shareFile(base64Data: String, fileName: String, mimeType: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val cacheDir = File(cacheDir, "shared_files").apply { mkdirs() }
                val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val outFile = File(cacheDir, safeName)
                FileOutputStream(outFile).use { it.write(bytes) }

                val contentUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${applicationContext.packageName}.fileprovider",
                    outFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartilhar arquivo"))
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun setFullscreen(enabled: Boolean) {
            runOnUiThread {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (enabled) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        fun pickSyncFolder() {
            runOnUiThread {
                try {
                    syncFolderLauncher.launch(null)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun clearSyncFolder() {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SYNC_TREE_URI)
                .apply()
        }

        @JavascriptInterface
        fun getSyncFolderInfo(): String {
            val uriStr = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SYNC_TREE_URI, null)
            val obj = JSONObject()
            if (uriStr.isNullOrBlank()) {
                obj.put("configured", false)
                obj.put("label", "")
            } else {
                val doc = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(uriStr))
                obj.put("configured", doc != null && doc.exists())
                obj.put("label", doc?.name ?: "Pasta configurada")
            }
            return obj.toString()
        }

        @JavascriptInterface
        fun scanSyncFolder(): String {
            val uriStr = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SYNC_TREE_URI, null) ?: return "[]"
            val treeDoc = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(uriStr)) ?: return "[]"
            val arr = JSONArray()
            treeDoc.listFiles().forEach { f ->
                if (f.isFile && f.length() > 0) {
                    val name = f.name ?: return@forEach
                    val mime = f.type ?: "application/octet-stream"
                    val mod = f.lastModified()
                    val sig = "${name}_${f.length()}_${mod}"
                    val proxyUrl = "https://$LOCAL_SYNC_HOST/file?uri=${Uri.encode(f.uri.toString())}&mime=${Uri.encode(mime)}"
                    arr.put(JSONObject().apply {
                        put("name", name)
                        put("mime", mime)
                        put("modified", mod)
                        put("sig", sig)
                        put("url", proxyUrl)
                    })
                }
            }
            return arr.toString()
        }
    }
}
