package com.ssd.nexus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SITE_URL = "https://clientessd001-glitch.github.io/nexusweb/"
        private const val ALLOWED_HOST = "clientessd001-glitch.github.io"
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isFullscreenMode: Boolean = false
    private var isLandscapeLocked: Boolean = false

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
            if (results == null) {
                results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            }
        }

        try {
            callback.onReceiveValue(results)
        } catch (_: Exception) {
        } finally {
            filePathCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#090D16")
        window.navigationBarColor = Color.parseColor("#090D16")
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        rootContainer = FrameLayout(this).apply {
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

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootContainer.addView(webView)
        rootContainer.addView(fullscreenContainer)
        setContentView(rootContainer)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            if (isFullscreenMode) {
                view.setPadding(0, 0, 0, 0)
            } else {
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            }
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
                injectAndroidEnhancements(view)
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

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.removeAllViews()
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                applyFullscreenMode(true)
            }

            override fun onHideCustomView() {
                exitCustomFullscreenView()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                try {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                } catch (_: Exception) {
                }
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                return try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, "Enviar para a Nuvem SSD Nexus")
                    )
                    true
                } catch (_: Exception) {
                    try {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                    } catch (_: Exception) {
                    }
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    exitCustomFullscreenView()
                    return
                }
                webView.evaluateJavascript(
                    """
                    (function() {
                      if (typeof window.__ssdCloseFullViewer === 'function' && window.__ssdCloseFullViewer()) return true;
                      if (typeof window.handleAndroidBack === 'function' && window.handleAndroidBack()) return true;
                      var modals = document.querySelectorAll('[id*="lightbox"], [id*="modal"], [class*="lightbox"], [class*="modal"]');
                      for (var i = 0; i < modals.length; i++) {
                        var st = window.getComputedStyle(modals[i]);
                        if (st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0') {
                          document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
                          return true;
                        }
                      }
                      return false;
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

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    private fun exitCustomFullscreenView() {
        if (customView == null) return
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        customView = null
        try {
            customViewCallback?.onCustomViewHidden()
        } catch (_: Exception) {
        }
        customViewCallback = null
        applyFullscreenMode(false)
    }

    private fun applyFullscreenMode(enabled: Boolean) {
        isFullscreenMode = enabled
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            rootContainer.setPadding(0, 0, 0, 0)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            isLandscapeLocked = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ViewCompat.requestApplyInsets(rootContainer)
        }
    }

    private fun injectAndroidEnhancements(view: WebView) {
        val jsHook = """
            (function() {
              if (window.__ssdNexusEnhancedV4) return;
              window.__ssdNexusEnhancedV4 = true;

              /* ---------- 1. CSS: NUVEM + COLUNAS (2/3/4) + TELA CHEIA REAL ---------- */
              var style = document.createElement('style');
              style.textContent = [
                '#ssdCloudFab {',
                '  position: fixed; right: 16px; bottom: 20px; z-index: 9400;',
                '  display: none; align-items: center; gap: 8px;',
                '  height: 52px; padding: 0 16px 0 14px; border-radius: 999px;',
                '  border: 1.5px solid rgba(125, 211, 252, 0.72);',
                '  background: linear-gradient(135deg, #0EA5E9 0%, #2563EB 100%);',
                '  color: #FFFFFF; font-family: inherit; font-size: 13.5px; font-weight: 800;',
                '  box-shadow: 0 10px 26px rgba(14, 165, 233, 0.45), 0 0 0 3px rgba(9, 13, 22, 0.65);',
                '  cursor: pointer; touch-action: manipulation; transition: transform 0.14s ease;',
                '}',
                '#ssdCloudFab:active { transform: scale(0.93); }',
                '#ssdCloudFab svg { width: 22px; height: 22px; flex-shrink: 0; }',
                '#ssdColBar {',
                '  position: fixed; left: 16px; bottom: 20px; z-index: 9400;',
                '  display: none; align-items: center; gap: 4px;',
                '  height: 52px; padding: 5px; border-radius: 999px;',
                '  background: rgba(15, 23, 42, 0.92);',
                '  border: 1px solid rgba(56, 189, 248, 0.35);',
                '  backdrop-filter: blur(10px);',
                '  box-shadow: 0 10px 26px rgba(0, 0, 0, 0.48);',
                '}',
                '.ssdColLabel {',
                '  font-size: 11px; font-weight: 800; color: #94A3B8;',
                '  padding: 0 6px 0 8px; letter-spacing: 0.04em; text-transform: uppercase;',
                '}',
                '.ssdColBtn {',
                '  width: 36px; height: 38px; border-radius: 999px; border: none;',
                '  background: transparent; color: #CBD5E1;',
                '  font-family: inherit; font-size: 13px; font-weight: 800;',
                '  cursor: pointer; transition: background 0.15s, color 0.15s;',
                '}',
                '.ssdColBtn.active {',
                '  background: linear-gradient(135deg, #38BDF8, #2563EB);',
                '  color: #FFFFFF; box-shadow: 0 4px 12px rgba(37, 99, 235, 0.45);',
                '}',
                'html.ssd-cols-2 [data-ssd-grid="1"] {',
                '  display: grid !important;',
                '  grid-template-columns: repeat(2, minmax(0, 1fr)) !important;',
                '  gap: 10px !important;',
                '}',
                'html.ssd-cols-3 [data-ssd-grid="1"] {',
                '  display: grid !important;',
                '  grid-template-columns: repeat(3, minmax(0, 1fr)) !important;',
                '  gap: 7px !important;',
                '}',
                'html.ssd-cols-4 [data-ssd-grid="1"] {',
                '  display: grid !important;',
                '  grid-template-columns: repeat(4, minmax(0, 1fr)) !important;',
                '  gap: 5px !important;',
                '}',
                'html.ssd-cols-3 [data-ssd-grid="1"] > *,',
                'html.ssd-cols-4 [data-ssd-grid="1"] > * {',
                '  min-width: 0 !important; border-radius: 11px !important;',
                '}',
                'html.ssd-cols-3 [data-ssd-grid="1"] [class*="thumb"] {',
                '  aspect-ratio: 1 / 1 !important; height: auto !important;',
                '}',
                'html.ssd-cols-3 [data-ssd-grid="1"] [class*="info"] {',
                '  padding: 6px 7px !important;',
                '}',
                'html.ssd-cols-3 [data-ssd-grid="1"] [class*="name"] {',
                '  font-size: 11px !important;',
                '}',
                'html.ssd-cols-3 [data-ssd-grid="1"] [class*="meta"] {',
                '  font-size: 9.5px !important;',
                '}',
                'html.ssd-cols-3 [data-ssd-grid="1"] [class*="badge"] {',
                '  font-size: 8.5px !important; padding: 2px 5px !important; top: 5px !important; right: 5px !important;',
                '}',
                'html.ssd-cols-4 [data-ssd-grid="1"] [class*="thumb"] {',
                '  aspect-ratio: 1 / 1 !important; height: auto !important;',
                '}',
                'html.ssd-cols-4 [data-ssd-grid="1"] [class*="info"] {',
                '  padding: 4px 5px !important;',
                '}',
                'html.ssd-cols-4 [data-ssd-grid="1"] [class*="name"] {',
                '  font-size: 9.5px !important;',
                '}',
                'html.ssd-cols-4 [data-ssd-grid="1"] [class*="meta"],',
                'html.ssd-cols-4 [data-ssd-grid="1"] [class*="badge"] {',
                '  display: none !important;',
                '}',
                '#ssdFullViewer {',
                '  position: fixed; inset: 0; width: 100vw; height: 100vh;',
                '  background: #000000; z-index: 999999;',
                '  display: none; flex-direction: column; overflow: hidden;',
                '}',
                '#ssdFullViewer.open { display: flex; }',
                '#ssdFvBar {',
                '  position: absolute; top: 0; left: 0; right: 0; z-index: 5;',
                '  display: flex; align-items: center; justify-content: space-between; gap: 8px;',
                '  padding: 12px 14px;',
                '  background: linear-gradient(180deg, rgba(0,0,0,0.82) 0%, rgba(0,0,0,0) 100%);',
                '  transition: opacity 0.2s ease;',
                '}',
                '#ssdFvBar.faded { opacity: 0; pointer-events: none; }',
                '#ssdFvTitle {',
                '  color: #F8FAFC; font-size: 13.5px; font-weight: 700;',
                '  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1;',
                '}',
                '.ssdFvBtn {',
                '  height: 38px; min-width: 38px; padding: 0 10px; border-radius: 10px;',
                '  border: 1px solid rgba(255,255,255,0.22); background: rgba(15, 23, 42, 0.78);',
                '  color: #F8FAFC; font-size: 14px; font-weight: 700; cursor: pointer;',
                '  display: inline-flex; align-items: center; justify-content: center;',
                '}',
                '#ssdFvStage {',
                '  flex: 1; width: 100%; height: 100%; display: flex;',
                '  align-items: center; justify-content: center; overflow: hidden; touch-action: none;',
                '}',
                '#ssdFvImg {',
                '  max-width: 100vw; max-height: 100vh; width: 100%; height: 100%;',
                '  object-fit: contain; transform-origin: center center; will-change: transform;',
                '}',
                '#ssdFvVideo {',
                '  width: 100vw; height: 100vh; object-fit: contain; background: #000000;',
                '}',
                '.ssdMediaFsChip {',
                '  position: absolute; top: 8px; right: 8px; z-index: 40;',
                '  padding: 6px 11px; border-radius: 999px;',
                '  border: 1px solid rgba(56, 189, 248, 0.7);',
                '  background: rgba(9, 13, 22, 0.86); color: #38BDF8;',
                '  font-size: 12px; font-weight: 800; cursor: pointer;',
                '  box-shadow: 0 4px 12px rgba(0,0,0,0.45);',
                '}',
                '[id*="lightbox"], [class*="lightbox"] {',
                '  width: 100vw !important; height: 100vh !important;',
                '  max-width: 100vw !important; max-height: 100vh !important;',
                '  inset: 0 !important; padding: 0 !important; background: #000000 !important;',
                '}',
                '[id*="lightbox"] img, [class*="lightbox"] img {',
                '  max-width: 100vw !important; max-height: 100vh !important;',
                '}'
              ].join('\n');
              document.head.appendChild(style);

              /* ---------- 2. BOTÃO NUVEM FLUTUANTE DE UPLOAD ---------- */
              var fab = document.createElement('button');
              fab.id = 'ssdCloudFab';
              fab.type = 'button';
              fab.setAttribute('aria-label', 'Enviar para a nuvem');
              fab.innerHTML =
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">' +
                '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>' +
                '<path d="M12 16V10"/>' +
                '<path d="m9.5 12.5 2.5-2.5 2.5 2.5"/>' +
                '</svg><span>Upload</span>';
              document.body.appendChild(fab);

              /* ---------- 3. SELETOR DE COLUNAS (2 | 3 | 4) ---------- */
              var COL_KEY = 'ssd_nexus_grid_cols';
              var colBar = document.createElement('div');
              colBar.id = 'ssdColBar';
              colBar.innerHTML =
                '<span class="ssdColLabel">Col</span>' +
                '<button type="button" class="ssdColBtn" data-cols="2">2</button>' +
                '<button type="button" class="ssdColBtn" data-cols="3">3</button>' +
                '<button type="button" class="ssdColBtn" data-cols="4">4</button>';
              document.body.appendChild(colBar);

              function applyGridCols(cols, announce) {
                var num = String(cols);
                if (num !== '2' && num !== '3' && num !== '4') num = '3';
                document.documentElement.classList.remove('ssd-cols-2', 'ssd-cols-3', 'ssd-cols-4');
                document.documentElement.classList.add('ssd-cols-' + num);
                try { localStorage.setItem(COL_KEY, num); } catch (_) {}
                var btns = colBar.querySelectorAll('.ssdColBtn');
                for (var i = 0; i < btns.length; i++) {
                  btns[i].classList.toggle('active', btns[i].getAttribute('data-cols') === num);
                }
                if (announce && window.AndroidBridge) {
                  window.AndroidBridge.showToast('Grade em ' + num + ' colunas');
                }
              }

              colBar.addEventListener('click', function(ev) {
                var btn = ev.target.closest ? ev.target.closest('.ssdColBtn') : null;
                if (!btn) return;
                ev.preventDefault();
                ev.stopPropagation();
                applyGridCols(btn.getAttribute('data-cols'), true);
              });

              var savedCols = '3';
              try {
                var stored = localStorage.getItem(COL_KEY);
                if (stored === '2' || stored === '3' || stored === '4') savedCols = stored;
              } catch (_) {}
              applyGridCols(savedCols, false);

              function markFileGrids() {
                var known = document.querySelectorAll('#filesGrid, .files-grid, #fileGrid, .file-grid, #mediaGrid, .media-grid, .gallery-grid');
                for (var i = 0; i < known.length; i++) {
                  known[i].setAttribute('data-ssd-grid', '1');
                }
                if (known.length > 0) return;

                var divs = document.querySelectorAll('main div, #appScreen div, section div');
                for (var j = 0; j < divs.length; j++) {
                  var el = divs[j];
                  if (el.getAttribute('data-ssd-grid') === '1') continue;
                  if (el.closest && (el.closest('.modal-card') || el.closest('.sidebar-foot') || el.closest('.tab-switch') || el.closest('#ssdFullViewer'))) continue;
                  var st = window.getComputedStyle(el);
                  if (st.display === 'grid' && el.children.length >= 2) {
                    var hasMediaOrCard = el.querySelector('img, video, [class*="card"], [class*="file"], [class*="item"]');
                    if (hasMediaOrCard) {
                      el.setAttribute('data-ssd-grid', '1');
                    }
                  }
                }
              }

              function getUploadInput() {
                var inputs = document.querySelectorAll('input[type="file"]');
                for (var i = 0; i < inputs.length; i++) {
                  if (!inputs[i].disabled) return inputs[i];
                }
                return null;
              }

              function isLoginVisible() {
                var tel = document.querySelector('input[type="tel"]');
                if (tel && tel.offsetParent !== null) return true;
                var auth = document.getElementById('authScreen') || document.getElementById('loginScreen');
                if (auth) {
                  var st = window.getComputedStyle(auth);
                  if (st.display !== 'none' && st.visibility !== 'hidden') return true;
                }
                return false;
              }

              function updateFloatingControlsVisibility() {
                var fv = document.getElementById('ssdFullViewer');
                var viewerOpen = fv && fv.classList.contains('open');
                var loggedIn = Boolean(getUploadInput()) && !isLoginVisible();
                var show = loggedIn && !viewerOpen;
                fab.style.display = show ? 'inline-flex' : 'none';
                colBar.style.display = show ? 'inline-flex' : 'none';
              }

              fab.addEventListener('click', function(ev) {
                ev.preventDefault();
                ev.stopPropagation();
                var inp = getUploadInput();
                if (!inp) {
                  if (window.AndroidBridge) window.AndroidBridge.showToast('Abra um cofre primeiro para fazer upload.');
                  return;
                }
                try {
                  inp.multiple = true;
                  inp.removeAttribute('accept');
                  inp.value = '';
                } catch (_) {}
                inp.click();
              });

              document.addEventListener('click', function(ev) {
                var target = ev.target;
                if (!target) return;
                if (target.tagName === 'INPUT' && target.type === 'file') {
                  try { target.value = ''; } catch (_) {}
                  return;
                }
                var btn = target.closest ? target.closest('button, label, [role="button"]') : null;
                if (btn && btn.id !== 'ssdCloudFab') {
                  var txt = (btn.textContent || '').toLowerCase();
                  if (txt.indexOf('enviar') !== -1 || txt.indexOf('upload') !== -1 || txt.indexOf('subir') !== -1) {
                    var inp = getUploadInput();
                    if (inp) {
                      try { inp.value = ''; inp.multiple = true; } catch (_) {}
                    }
                  }
                }
              }, true);

              document.addEventListener('change', function(ev) {
                var t = ev.target;
                if (t && t.tagName === 'INPUT' && t.type === 'file' && t.files && t.files.length > 0) {
                  if (window.AndroidBridge) {
                    window.AndroidBridge.showToast('Enviando ' + t.files.length + ' arquivo(s) para a nuvem...');
                  }
                }
              }, true);

              /* ---------- 4. VISUALIZADOR TELA CHEIA REAL (FOTOS + VÍDEOS) ---------- */
              var viewer = document.createElement('div');
              viewer.id = 'ssdFullViewer';
              viewer.innerHTML =
                '<div id="ssdFvBar">' +
                '  <div id="ssdFvTitle">Tela Cheia</div>' +
                '  <div style="display:flex;gap:6px;">' +
                '    <button id="ssdFvZoomOut" class="ssdFvBtn" type="button">−</button>' +
                '    <button id="ssdFvZoomIn" class="ssdFvBtn" type="button">＋</button>' +
                '    <button id="ssdFvRotate" class="ssdFvBtn" type="button">↻ Girar</button>' +
                '    <button id="ssdFvClose" class="ssdFvBtn" type="button">✕</button>' +
                '  </div>' +
                '</div>' +
                '<div id="ssdFvStage">' +
                '  <img id="ssdFvImg" style="display:none;" alt="Foto em tela cheia" />' +
                '  <video id="ssdFvVideo" style="display:none;" controls playsinline autoplay></video>' +
                '</div>';
              document.body.appendChild(viewer);

              var fvBar = document.getElementById('ssdFvBar');
              var fvTitle = document.getElementById('ssdFvTitle');
              var fvStage = document.getElementById('ssdFvStage');
              var fvImg = document.getElementById('ssdFvImg');
              var fvVideo = document.getElementById('ssdFvVideo');
              var fvZoomOut = document.getElementById('ssdFvZoomOut');
              var fvZoomIn = document.getElementById('ssdFvZoomIn');
              var fvRotate = document.getElementById('ssdFvRotate');
              var fvClose = document.getElementById('ssdFvClose');

              var fvScale = 1, fvX = 0, fvY = 0;
              var fvDragging = false, fvStartX = 0, fvStartY = 0;
              var fvTouchDist = 0, fvLastTap = 0;
              var fvSourceVideo = null;

              function applyFvTransform() {
                fvImg.style.transform = 'translate(' + fvX + 'px,' + fvY + 'px) scale(' + fvScale + ')';
              }

              function resetFvTransform() {
                fvScale = 1; fvX = 0; fvY = 0;
                applyFvTransform();
              }

              function openFullPhoto(src, title) {
                if (!src) return;
                fvSourceVideo = null;
                fvVideo.pause();
                fvVideo.removeAttribute('src');
                fvVideo.style.display = 'none';
                fvImg.src = src;
                fvImg.style.display = 'block';
                fvZoomOut.style.display = 'inline-flex';
                fvZoomIn.style.display = 'inline-flex';
                fvTitle.textContent = title || 'Foto';
                fvBar.classList.remove('faded');
                resetFvTransform();
                viewer.classList.add('open');
                updateFloatingControlsVisibility();
                if (window.AndroidBridge) window.AndroidBridge.setFullscreen(true);
              }

              function openFullVideo(videoEl) {
                if (!videoEl) return;
                var src = videoEl.currentSrc || videoEl.src;
                if (!src) {
                  var s = videoEl.querySelector('source');
                  if (s) src = s.src;
                }
                if (!src) return;
                fvSourceVideo = videoEl;
                var curTime = videoEl.currentTime || 0;
                try { videoEl.pause(); } catch (_) {}
                fvImg.style.display = 'none';
                fvImg.removeAttribute('src');
                fvZoomOut.style.display = 'none';
                fvZoomIn.style.display = 'none';
                fvVideo.src = src;
                fvVideo.style.display = 'block';
                fvTitle.textContent = 'Vídeo em Tela Cheia';
                fvBar.classList.remove('faded');
                viewer.classList.add('open');
                updateFloatingControlsVisibility();
                if (window.AndroidBridge) window.AndroidBridge.setFullscreen(true);
                fvVideo.onloadedmetadata = function() {
                  if (curTime > 0) {
                    try { fvVideo.currentTime = curTime; } catch (_) {}
                  }
                  fvVideo.play().catch(function() {});
                };
              }

              window.__ssdCloseFullViewer = function() {
                if (!viewer.classList.contains('open')) return false;
                if (fvSourceVideo && fvVideo.currentTime) {
                  try { fvSourceVideo.currentTime = fvVideo.currentTime; } catch (_) {}
                }
                fvVideo.pause();
                fvVideo.removeAttribute('src');
                fvImg.removeAttribute('src');
                viewer.classList.remove('open');
                if (window.AndroidBridge) window.AndroidBridge.setFullscreen(false);
                updateFloatingControlsVisibility();
                return true;
              };

              fvClose.addEventListener('click', function() { window.__ssdCloseFullViewer(); });
              fvRotate.addEventListener('click', function() {
                if (window.AndroidBridge) window.AndroidBridge.toggleOrientation();
              });
              fvZoomIn.addEventListener('click', function() {
                fvScale = Math.min(5, fvScale + 0.6);
                applyFvTransform();
              });
              fvZoomOut.addEventListener('click', function() {
                fvScale = Math.max(1, fvScale - 0.6);
                if (fvScale === 1) { fvX = 0; fvY = 0; }
                applyFvTransform();
              });

              fvStage.addEventListener('touchstart', function(e) {
                if (fvImg.style.display === 'none') return;
                if (e.touches.length === 2) {
                  fvTouchDist = Math.hypot(
                    e.touches[0].clientX - e.touches[1].clientX,
                    e.touches[0].clientY - e.touches[1].clientY
                  );
                } else if (e.touches.length === 1) {
                  var now = Date.now();
                  if (now - fvLastTap < 280) {
                    if (fvScale > 1) resetFvTransform();
                    else { fvScale = 2.6; applyFvTransform(); }
                  } else {
                    fvBar.classList.toggle('faded');
                  }
                  fvLastTap = now;
                  if (fvScale > 1) {
                    fvDragging = true;
                    fvStartX = e.touches[0].clientX - fvX;
                    fvStartY = e.touches[0].clientY - fvY;
                  }
                }
              }, { passive: true });

              fvStage.addEventListener('touchmove', function(e) {
                if (fvImg.style.display === 'none') return;
                if (e.touches.length === 2 && fvTouchDist > 0) {
                  var d = Math.hypot(
                    e.touches[0].clientX - e.touches[1].clientX,
                    e.touches[0].clientY - e.touches[1].clientY
                  );
                  fvScale = Math.max(1, Math.min(5, fvScale * (d / fvTouchDist)));
                  fvTouchDist = d;
                  if (fvScale === 1) { fvX = 0; fvY = 0; }
                  applyFvTransform();
                } else if (e.touches.length === 1 && fvDragging && fvScale > 1) {
                  fvX = e.touches[0].clientX - fvStartX;
                  fvY = e.touches[0].clientY - fvStartY;
                  applyFvTransform();
                }
              }, { passive: true });

              fvStage.addEventListener('touchend', function() {
                fvDragging = false;
                fvTouchDist = 0;
              });

              /* ---------- 5. MELHORAR GRADE, FOTOS, VÍDEOS E LIGHTBOX ---------- */
              var siteLightboxWasOpen = false;

              function enhanceMediaElements() {
                markFileGrids();
                updateFloatingControlsVisibility();

                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                  var v = videos[i];
                  if (v.id === 'ssdFvVideo') continue;
                  v.setAttribute('controls', 'controls');
                  v.setAttribute('playsinline', 'playsinline');
                  v.removeAttribute('controlsList');
                  var parent = v.parentElement;
                  if (parent && !parent.dataset.ssdVideoEnhanced) {
                    parent.dataset.ssdVideoEnhanced = '1';
                    if (window.getComputedStyle(parent).position === 'static') {
                      parent.style.position = 'relative';
                    }
                    var fsBtn = document.createElement('button');
                    fsBtn.type = 'button';
                    fsBtn.className = 'ssdMediaFsChip';
                    fsBtn.textContent = '⛶ Tela Cheia';
                    (function(videoRef) {
                      fsBtn.addEventListener('click', function(ev) {
                        ev.preventDefault();
                        ev.stopPropagation();
                        openFullVideo(videoRef);
                      });
                    })(v);
                    parent.appendChild(fsBtn);
                  }
                }

                var previews = document.querySelectorAll('[id*="inspector"] img, [class*="inspector"] img, [class*="preview"] img');
                for (var j = 0; j < previews.length; j++) {
                  var img = previews[j];
                  if (img.id === 'ssdFvImg' || !img.src) continue;
                  var p = img.parentElement;
                  if (p && !p.dataset.ssdImgEnhanced) {
                    p.dataset.ssdImgEnhanced = '1';
                    if (window.getComputedStyle(p).position === 'static') {
                      p.style.position = 'relative';
                    }
                    var imgFsBtn = document.createElement('button');
                    imgFsBtn.type = 'button';
                    imgFsBtn.className = 'ssdMediaFsChip';
                    imgFsBtn.textContent = '⛶ Tela Cheia';
                    (function(imgRef) {
                      imgFsBtn.addEventListener('click', function(ev) {
                        ev.preventDefault();
                        ev.stopPropagation();
                        openFullPhoto(imgRef.src, imgRef.alt || 'Imagem');
                      });
                    })(img);
                    p.appendChild(imgFsBtn);
                  }
                }

                var lb = document.querySelector('[id*="lightbox"], [class*="lightbox"]');
                var lbVisible = false;
                if (lb) {
                  var st = window.getComputedStyle(lb);
                  lbVisible = st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0';
                }
                if (lbVisible !== siteLightboxWasOpen) {
                  siteLightboxWasOpen = lbVisible;
                  if (!viewer.classList.contains('open') && window.AndroidBridge) {
                    window.AndroidBridge.setFullscreen(lbVisible);
                  }
                }
              }

              var observer = new MutationObserver(function() {
                enhanceMediaElements();
              });
              observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'src'] });
              setInterval(enhanceMediaElements, 900);
              enhanceMediaElements();

              /* ---------- 6. PONTE DE DOWNLOAD BLOB -> DOWNLOADS/SSD NEXUS ---------- */
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
        fun getAppVersion(): String = "2.3.0-android"

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun setFullscreen(enabled: Boolean) {
            runOnUiThread {
                applyFullscreenMode(enabled)
            }
        }

        @JavascriptInterface
        fun toggleOrientation() {
            runOnUiThread {
                isLandscapeLocked = !isLandscapeLocked
                requestedOrientation = if (isLandscapeLocked) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
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
