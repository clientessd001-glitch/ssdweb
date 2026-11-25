package com.ssd.nexus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SITE_URL = "https://clientessd001-glitch.github.io/nexusweb/"
        private const val ALLOWED_HOST = "clientessd001-glitch.github.io"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressTime: Long = 0L

    private val pendingDownloads = ConcurrentHashMap<String, DownloadSession>()

    private data class DownloadSession(
        val fileName: String,
        val mimeType: String,
        val outputStream: OutputStream,
        val mediaUri: Uri?
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        var results: Array<Uri>? = null

        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val clipData = data.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else if (data.data != null) {
                    results = arrayOf(data.data!!)
                }
            }
        }

        callback.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#090D16")
        window.navigationBarColor = Color.parseColor("#090D16")
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        val rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootContainer.addView(webView)
        setContentView(rootContainer)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == ALLOWED_HOST) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectAndroidDownloadHook(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val offlineHtml = """
                        <!DOCTYPE html>
                        <html lang="pt-BR">
                        <head>
                          <meta charset="UTF-8">
                          <meta name="viewport" content="width=device-width, initial-scale=1.0">
                          <style>
                            body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center; background:#090D16; color:#F8FAFC; font-family:sans-serif; padding:24px; text-align:center; }
                            .card { max-width:340px; background:#111827; border:1px solid rgba(148,163,184,0.2); border-radius:18px; padding:24px; }
                            h2 { margin:0 0 8px; font-size:20px; }
                            p { margin:0 0 20px; color:#94A3B8; font-size:14px; line-height:1.5; }
                            button { width:100%; padding:13px; border:none; border-radius:12px; background:#38BDF8; color:#090D16; font-weight:700; font-size:15px; }
                          </style>
                        </head>
                        <body>
                          <div class="card">
                            <h2>Sem conexão com a Nuvem SSD</h2>
                            <p>Verifique sua internet e toque abaixo para reconectar ao SSD Nexus.</p>
                            <button onclick="window.location.href='$SITE_URL'">Tentar novamente</button>
                          </div>
                        </body>
                        </html>
                    """.trimIndent()
                    view.loadDataWithBaseURL(null, offlineHtml, "text/html", "UTF-8", null)
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

                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (acceptTypes.size == 1) acceptTypes.first() else "*/*"
                    if (acceptTypes.size > 1) {
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                    }
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                return try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, "Selecionar arquivos para o SSD Nexus")
                    )
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    """
                    (function() {
                      if (typeof window.handleAndroidBack === 'function' && window.handleAndroidBack()) return true;
                      var modals = document.querySelectorAll('[id*="lightbox"], [id*="modal"], [class*="lightbox"], [class*="modal"]');
                      var closed = false;
                      for (var i = 0; i < modals.length; i++) {
                        var st = window.getComputedStyle(modals[i]);
                        if (st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0') {
                          document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
                          closed = true;
                          break;
                        }
                      }
                      return closed;
                    })();
                    """.trimIndent()
                ) { result ->
                    if (result == "true") {
                        return@evaluateJavascript
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                        return@evaluateJavascript
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000L) {
                        finish()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(
                            this@MainActivity,
                            "Toque novamente para sair do SSD Nexus",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })

        webView.loadUrl(SITE_URL)
    }

    private fun injectAndroidDownloadHook(view: WebView) {
        val jsHook = """
            (function() {
              if (window.__ssdNexusBridgeHooked) return;
              window.__ssdNexusBridgeHooked = true;

              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function() {
                if (this.href && this.href.indexOf('blob:') === 0 && window.AndroidBridge) {
                  var blobUrl = this.href;
                  var fileName = this.download || ('ssd_nexus_' + Date.now());
                  fetch(blobUrl)
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                      return blob.arrayBuffer().then(function(buf) {
                        var mime = blob.type || 'application/octet-stream';
                        var bytes = new Uint8Array(buf);
                        var id = 'dl_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7);
                        if (!window.AndroidBridge.startFileSave(id, fileName, mime)) return;
                        var CHUNK = 256 * 1024;
                        for (var offset = 0; offset < bytes.length; offset += CHUNK) {
                          var slice = bytes.subarray(offset, Math.min(offset + CHUNK, bytes.length));
                          var bin = '';
                          var step = 0x8000;
                          for (var i = 0; i < slice.length; i += step) {
                            bin += String.fromCharCode.apply(null, slice.subarray(i, i + step));
                          }
                          if (!window.AndroidBridge.appendFileChunk(id, btoa(bin))) {
                            window.AndroidBridge.cancelFileSave(id);
                            return;
                          }
                        }
                        window.AndroidBridge.finishFileSave(id);
                      });
                    })
                    .catch(function(err) {
                      window.AndroidBridge.showToast('Erro no download: ' + err);
                    });
                  return;
                }
                return origClick.apply(this, arguments);
              };
            })();
        """.trimIndent()
        view.evaluateJavascript(jsHook, null)
    }

    override fun onDestroy() {
        pendingDownloads.values.forEach { session ->
            try { session.outputStream.close() } catch (_: Exception) {}
        }
        pendingDownloads.clear()
        webView.destroy()
        super.onDestroy()
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun getAppVersion(): String = "2.1.0-android"

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun startFileSave(transferId: String, fileName: String, mimeType: String): Boolean {
            return try {
                val safeName = sanitizeFileName(fileName)
                val safeMime = mimeType.ifBlank { "application/octet-stream" }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, safeMime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SSD Nexus")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return false
                    val os = contentResolver.openOutputStream(uri) ?: return false
                    pendingDownloads[transferId] = DownloadSession(safeName, safeMime, os, uri)
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SSD Nexus")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, safeName)
                    val os = FileOutputStream(file)
                    pendingDownloads[transferId] = DownloadSession(safeName, safeMime, os, null)
                }
                true
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erro ao iniciar download: ${e.message}", Toast.LENGTH_LONG).show()
                }
                false
            }
        }

        @JavascriptInterface
        fun appendFileChunk(transferId: String, base64Chunk: String): Boolean {
            val session = pendingDownloads[transferId] ?: return false
            return try {
                val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                session.outputStream.write(bytes)
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finishFileSave(transferId: String): Boolean {
            val session = pendingDownloads.remove(transferId) ?: return false
            return try {
                session.outputStream.flush()
                session.outputStream.close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && session.mediaUri != null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    contentResolver.update(session.mediaUri, values, null, null)
                }
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Salvo em Downloads/SSD Nexus: ${session.fileName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erro ao concluir download: ${e.message}", Toast.LENGTH_LONG).show()
                }
                false
            }
        }

        @JavascriptInterface
        fun cancelFileSave(transferId: String) {
            val session = pendingDownloads.remove(transferId) ?: return
            try { session.outputStream.close() } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && session.mediaUri != null) {
                try { contentResolver.delete(session.mediaUri, null, null) } catch (_: Exception) {}
            }
        }

        private fun sanitizeFileName(name: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            return cleaned.ifBlank { "arquivo_ssd_nexus_${System.currentTimeMillis()}" }
        }
    }
}
