package com.ssd.nexus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val appUrl = "https://clientessd001-glitch.github.io/nexusweb/"
    private val prefsName = "ssd_nexus_sync_prefs"
    private val keySyncTreeUri = "sync_folder_tree_uri"

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

    private val syncFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val treeUri = result.data?.data
            if (treeUri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                } catch (_: Exception) {}

                getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit()
                    .putString(keySyncTreeUri, treeUri.toString())
                    .apply()

                val label = extractFolderLabel(treeUri)
                val safeLabel = JSONObject.quote(label)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(typeof window.onSyncFolderSelected === 'function'){ window.onSyncFolderSelected($safeLabel); }",
                        null
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bgDark = Color.parseColor("#060913")
        window.decorView.setBackgroundColor(bgDark)

        val container = FrameLayout(this).apply {
            setBackgroundColor(bgDark)
        }

        webView = WebView(this).apply {
            setBackgroundColor(bgDark)
        }

        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(container)

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
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (reqUrl.host == "ssd-sync.local" && reqUrl.path == "/file") {
                    val uriParam = reqUrl.getQueryParameter("uri") ?: return null
                    val mimeParam = reqUrl.getQueryParameter("mime") ?: "application/octet-stream"
                    return try {
                        val stream = contentResolver.openInputStream(Uri.parse(uriParam)) ?: return null
                        val headers = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Cache-Control" to "no-store"
                        )
                        WebResourceResponse(mimeParam, null, 200, "OK", headers, stream)
                    } catch (_: Exception) {
                        null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

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

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.resumeTimers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun extractFolderLabel(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            docId.substringAfterLast(':').ifBlank { docId }
        } catch (_: Exception) {
            "Pasta Selecionada"
        }
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
        fun pickSyncFolder() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    }
                    syncFolderLauncher.launch(intent)
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o seletor de pastas.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun getSyncFolderInfo(): String {
            val uriStr = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString(keySyncTreeUri, null)
            val obj = JSONObject()
            if (uriStr.isNullOrBlank()) {
                obj.put("configured", false)
                obj.put("label", "")
            } else {
                obj.put("configured", true)
                obj.put("label", extractFolderLabel(Uri.parse(uriStr)))
            }
            return obj.toString()
        }

        @JavascriptInterface
        fun clearSyncFolder() {
            getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .remove(keySyncTreeUri)
                .apply()
        }

        @JavascriptInterface
        fun scanSyncFolder(): String {
            val arr = JSONArray()
            val uriStr = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString(keySyncTreeUri, null) ?: return arr.toString()

            return try {
                val treeUri = Uri.parse(uriStr)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )

                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idxId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val idxName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val idxMime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val idxSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val idxMod = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val childId = if (idxId >= 0) cursor.getString(idxId) else continue
                        val name = if (idxName >= 0) cursor.getString(idxName) ?: "arquivo" else "arquivo"
                        val mime = if (idxMime >= 0) cursor.getString(idxMime) ?: "application/octet-stream" else "application/octet-stream"
                        val size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L
                        val mod = if (idxMod >= 0) cursor.getLong(idxMod) else 0L

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR || size <= 0L) continue

                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val encUri = URLEncoder.encode(fileUri.toString(), "UTF-8")
                        val encMime = URLEncoder.encode(mime, "UTF-8")

                        val item = JSONObject().apply {
                            put("sig", "${name}_${size}_${mod}")
                            put("name", name)
                            put("mime", mime)
                            put("size", size)
                            put("modified", mod)
                            put("url", "https://ssd-sync.local/file?uri=$encUri&mime=$encMime")
                        }
                        arr.put(item)
                    }
                }
                arr.toString()
            } catch (_: Exception) {
                arr.toString()
            }
        }

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
