package net.coursehunter.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.lynx.tasm.LynxError
import com.lynx.tasm.LynxView
import com.lynx.tasm.LynxViewBuilder
import com.lynx.tasm.LynxViewClient
import com.lynx.xelement.webview.BehaviorGenerator

class MainActivity : Activity() {
    private lateinit var container: FrameLayout
    private lateinit var refresh: SwipeRefreshLayout
    // Set by pdf-viewer.js on touch start: the finger is in a scrolled inner element, so a pull scrolls it instead.
    @Volatile private var innerScrolled = false
    // The re-rendered view waiting for its first screen before it replaces the current one.
    private var pending: LynxView? = null
    private lateinit var viewToggle: ImageButton
    private var desktop = false
    private var configuredWeb: WebView? = null
    private var lastDownload = "" to 0L
    private val pdfViewerJs by lazy { assets.open("pdf-viewer.js").bufferedReader().use { it.readText() } }

    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the last few books read this week, plus saved ones; anything older goes.
        if (!cacheTrimmed) {
            cacheTrimmed = true
            Thread { Books.trim(applicationContext) }.start()
        }
        container = FrameLayout(this).apply { setBackgroundColor(BG_COLOR) }
        refresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(ACCENT_COLOR)
            addView(container)
            // Only from the very top of the page; the WebView isn't the direct child, so check it here.
            setOnChildScrollUpCallback { _, _ ->
                val web = configuredWeb
                web == null || innerScrolled || web.scrollY > 0 || web.canScrollVertically(-1)
            }
            setOnRefreshListener {
                val web = configuredWeb
                if (web == null) isRefreshing = false else web.reload()
                // Never spin forever if the page never reports back.
                postDelayed({ isRefreshing = false }, REFRESH_TIMEOUT_MS)
            }
        }
        setContentView(refresh)
        desktop = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DESKTOP, false)
        viewToggle = ImageButton(this).apply {
            setBackgroundResource(R.drawable.bg_view_toggle)
            setPadding(dp(4), 0, 0, 0)
            elevation = dp(4).toFloat()
            visibility = View.GONE
            setOnClickListener { setDesktop(!desktop) }
        }
        container.addView(viewToggle, FrameLayout.LayoutParams(dp(44), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        updateToggleIcon()
        setDefaultLocaleCookie()
        render()
        if (BuildConfig.DEBUG) {
            // Exported so `adb shell am broadcast` reaches it; DUMP is held by shell but not by third-party apps.
            ContextCompat.registerReceiver(
                this, reloadReceiver, IntentFilter(RELOAD_ACTION), Manifest.permission.DUMP, null,
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    // Back gesture navigates the site's history first (the PDF reader pushes an entry, so it closes too).
    // On the first page, or while there's no live WebView (re-render, renderer restart), background the task
    // instead of finishing, so the process and the page survive.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = configuredWeb
        if (web?.canGoBack() == true) web.goBack() else moveTaskToBack(true)
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun setDesktop(enabled: Boolean) {
        desktop = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DESKTOP, enabled).apply()
        updateToggleIcon()
        configuredWeb?.let {
            configureWebView(it)
            it.reload()
        }
    }

    // Shows the mode a tap switches to.
    private fun updateToggleIcon() {
        viewToggle.setImageResource(if (desktop) R.drawable.ic_mobile else R.drawable.ic_desktop)
        viewToggle.contentDescription = if (desktop) "Switch to mobile view" else "Switch to desktop view"
    }

    // Lynx owns the WebView; tune it once and keep the user agent in sync with the chosen mode.
    private fun configureWebView(web: WebView) {
        if (configuredWeb !== web) {
            configuredWeb = web
            web.settings.apply {
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                download(url, userAgent, contentDisposition, mimeType)
            }
            // pdf-viewer.js hands PDFs to the native reader.
            web.addJavascriptInterface(object {
                @JavascriptInterface
                fun openPdf(url: String, title: String) = runOnUiThread { openPdf(url, title, web.settings.userAgentString) }

                @JavascriptInterface
                fun setInnerScrolled(scrolled: Boolean) {
                    innerScrolled = scrolled
                }

                @JavascriptInterface
                fun prefetchPdf(url: String) = runOnUiThread { Books.prefetch(this@MainActivity, url, web.settings.userAgentString) }
            }, "CourseHunterApp")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val lynxClient = web.webViewClient
                web.webViewClient = object : WebViewClient() {
                    // Start file downloads directly and open PDFs natively; letting them navigate leaves WebView on an error page.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (!request.isForMainFrame) return false
                        if (url.path?.endsWith(".pdf", ignoreCase = true) == true) {
                            openPdf(url.toString(), url.lastPathSegment ?: "", view.settings.userAgentString)
                            return true
                        }
                        if (url.path?.contains(DOWNLOAD_EXT) != true) return false
                        val ext = MimeTypeMap.getFileExtensionFromUrl(url.toString())
                        download(url.toString(), view.settings.userAgentString, null, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext))
                        return true
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        if (desktop) view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                        // Early, so the site's PDF <object> is stripped before it downloads much.
                        view.evaluateJavascript(pdfViewerJs, null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (desktop) view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                        // Fallback only; don't resend the script when onPageCommitVisible already ran it.
                        view.evaluateJavascript("!!window.__chPdf") { if (it != "true") view.evaluateJavascript(pdfViewerJs, null) }
                        viewToggle.visibility = View.VISIBLE
                        refresh.isRefreshing = false
                        lynxClient.onPageFinished(view, url)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) refresh.isRefreshing = false
                        lynxClient.onReceivedError(view, request, error)
                    }

                    // Renderer crashed or was OOM-killed: unhandled, Android kills the app. Drop the dead WebView
                    // (it must not be touched again) and re-render a fresh one.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (configuredWeb === view) {
                            configuredWeb = null
                            viewToggle.visibility = View.GONE
                            refresh.isRefreshing = false
                            if (pending == null) render()
                        }
                        return true
                    }
                }
            } else {
                viewToggle.visibility = View.VISIBLE
            }
        }
        // null restores WebView's default (mobile) user agent.
        web.settings.userAgentString = if (desktop) desktopUserAgent(WebSettings.getDefaultUserAgent(this)) else null
    }

    // WebView ignores downloads unless the host handles them; hand them to the system with the login cookie.
    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        if (!URLUtil.isNetworkUrl(url)) {
            Toast.makeText(applicationContext, "Can't download this file", Toast.LENGTH_SHORT).show()
            return
        }
        // The site's click handler and the link itself can both trigger the same download.
        val now = System.currentTimeMillis()
        if (lastDownload.first == url && now - lastDownload.second < 3000) return
        lastDownload = url to now
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", userAgent)
            .addRequestHeader("Referer", SITE_URL)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(applicationContext, "Downloading $name", Toast.LENGTH_SHORT).show()
    }

    private fun openPdf(url: String, title: String, userAgent: String) {
        if (!URLUtil.isNetworkUrl(url)) return
        startActivity(
            Intent(this, PdfReaderActivity::class.java)
                .putExtra(PdfReaderActivity.EXTRA_URL, url)
                .putExtra(PdfReaderActivity.EXTRA_TITLE, title)
                .putExtra(PdfReaderActivity.EXTRA_USER_AGENT, userAgent)
        )
    }

    private fun desktopUserAgent(mobile: String) = mobile
        .replace(Regex("""\(Linux; Android[^)]*\)"""), "(X11; Linux x86_64)")
        .replace(" Mobile", "")
        .replace("Version/4.0 ", "")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // WebView writes cookies to disk only every ~30s; persist the login before the process can be killed.
    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        configuredWeb?.onResume()
    }

    // Pauses the site's timers and media while the app isn't visible.
    override fun onStop() {
        configuredWeb?.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) unregisterReceiver(reloadReceiver)
        configuredWeb?.apply {
            removeJavascriptInterface("CourseHunterApp")
            setDownloadListener(null)
            stopLoading()
        }
        configuredWeb = null
        pending = null
        // Detach before destroy, as WebView.destroy() requires; LynxView.destroy() destroys its WebView.
        val views = (0 until container.childCount).mapNotNull { container.getChildAt(it) as? LynxView }
        views.forEach { container.removeView(it); it.destroy() }
        super.onDestroy()
    }

    // Default the site to English; keeps any locale the user picked on the site.
    private fun setDefaultLocaleCookie() {
        val cookies = CookieManager.getInstance()
        if (cookies.getCookie(SITE_URL)?.split("; ")?.any { it.startsWith("locale=") } == true) return
        cookies.setCookie(SITE_URL, "locale=en; Domain=coursehunter.net; Path=/; Max-Age=31536000; Secure")
        cookies.flush()
    }

    // Render underneath the current view and swap on first screen, so dev reloads never flash.
    private fun render() {
        val next = LynxViewBuilder()
            .setTemplateProvider(BundleProvider(this))
            .addBehaviors(BehaviorGenerator.getBehaviors())
            .build(this)
        next.setBackgroundColor(BG_COLOR)
        next.addLynxViewClient(object : LynxViewClient() {
            override fun onReceivedError(error: LynxError?) {
                runOnUiThread { reveal(next) }
            }

            override fun onFirstScreen() {
                runOnUiThread { reveal(next) }
            }
        })
        pending = next
        container.addView(next, 0)
        next.renderTemplateUrl(if (BuildConfig.DEBUG) DEV_BUNDLE_URL else "main.lynx.bundle", "")
    }

    private fun reveal(view: LynxView) {
        if (pending !== view) return
        pending = null
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child is LynxView && child !== view) {
                container.removeViewAt(i)
                child.destroy()
            }
        }
        // The webview element mounts with the first screen; a fresh one starts in mobile mode.
        container.post {
            if (isDestroyed) return@post
            findWebView(view)?.let {
                configureWebView(it)
                if (desktop) it.reload()
            }
        }
    }

    companion object {
        private var cacheTrimmed = false
        const val BG_COLOR = 0xFFFFFFFF.toInt()
        const val ACCENT_COLOR = 0xFF8200B0.toInt()
        const val REFRESH_TIMEOUT_MS = 15_000L
        const val RELOAD_ACTION = "net.coursehunter.app.RELOAD"
        const val SITE_URL = "https://coursehunter.net"
        val DOWNLOAD_EXT = Regex("""\.(zip|rar|7z|tar|gz|mp4|mkv|avi|mov|webm|mp3|epub|djvu)$""", RegexOption.IGNORE_CASE)
        const val PREFS = "settings"
        const val KEY_DESKTOP = "desktop_view"
        // Desktop UA alone isn't enough when the site sends width=device-width; force a desktop-width layout.
        const val DESKTOP_VIEWPORT_JS = "(function(){var c='width=1280,initial-scale='+(screen.width/1280)+',minimum-scale=0.1';function f(){var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m)}" +
            "if(m.content!==c)m.content=c}f();document.addEventListener('DOMContentLoaded',f)})()"
        const val DEV_BUNDLE_URL = "http://localhost:3000/main.lynx.bundle"
    }
}
