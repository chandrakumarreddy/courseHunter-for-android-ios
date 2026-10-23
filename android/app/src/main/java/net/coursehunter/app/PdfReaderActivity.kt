package net.coursehunter.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.SeekBar
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Native PDF reader. pdf.js inside the WebView renderer froze or got the page killed on big books; here the
 * file is downloaded once into the cache and PdfRenderer draws only the pages on screen and one either side.
 * Pages scroll continuously, fit to width, with fling and pinch-zoom.
 */
class PdfReaderActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    // PdfRenderer isn't thread-safe; every call on it runs on this one thread.
    private val worker = HandlerThread("pdf-render").apply { start() }
    private val bg = Handler(worker.looper)
    private var renderer: PdfRenderer? = null

    private lateinit var url: String
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var doc: DocView
    private lateinit var status: TextView
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var closeBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var themeBtn: TextView
    private lateinit var pageLabel: TextView
    private lateinit var leftLabel: TextView
    private lateinit var seek: SeekBar
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blank = Paint().apply { color = Color.WHITE }
    private var call: Call? = null
    private var total = 0
    private var page = -1
    private var theme = 0
    private var barsShown = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        theme = prefs.getInt(KEY_THEME, 0).coerceIn(0, THEMES.size - 1)

        root = FrameLayout(this)
        doc = DocView()
        root.addView(doc, FrameLayout.LayoutParams(MATCH, MATCH))
        status = text(15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            setOnClickListener { if (call == null && total == 0) load() }
        }
        root.addView(status, FrameLayout.LayoutParams(MATCH, MATCH))

        closeBtn = barButton("✕").apply {
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        titleView = text(15f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            paint.isFakeBoldText = true
            text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Book"
        }
        themeBtn = barButton("Aa").apply {
            contentDescription = "Theme"
            setOnClickListener {
                theme = (theme + 1) % THEMES.size
                prefs.edit().putInt(KEY_THEME, theme).apply()
                applyTheme()
            }
        }
        top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            // Bars swallow their touches so they never reach the pages underneath.
            isClickable = true
            addView(closeBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(themeBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }

        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) doc.scrollToPage(value)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        pageLabel = text(12f).apply { alpha = 0.6f }
        leftLabel = text(12f).apply { alpha = 0.6f }
        val meta = LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
            addView(pageLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(leftLabel)
        }
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(14))
            isClickable = true
            addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        top.elevation = dp(4).toFloat()
        bottom.elevation = dp(4).toFloat()
        setContentView(root)
        applyTheme()
        load()
    }

    // Backgrounded: drop every page bitmap; the pages on screen are redrawn on return.
    override fun onStop() {
        if (::doc.isInitialized) doc.dropAll()
        super.onStop()
    }

    override fun onDestroy() {
        call?.cancel()
        if (::doc.isInitialized) doc.dropAll()
        bg.post {
            renderer?.close()
            renderer = null
        }
        worker.quitSafely()
        super.onDestroy()
    }

    // Cached by URL, so reopening a book (or the same book after a renderer restart) is instant.
    private fun load() {
        val file = File(cacheDir, "pdf/${sha1(url)}.pdf")
        if (file.exists()) return open(file)
        file.parentFile?.mkdirs()
        val part = File("${file.path}.part")
        status.visibility = View.VISIBLE
        status.text = "Downloading…"
        val request = Request.Builder().url(url)
            .header("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .header("User-Agent", intent.getStringExtra(EXTRA_USER_AGENT) ?: WebSettings.getDefaultUserAgent(this))
            .header("Referer", MainActivity.SITE_URL)
            .build()
        val c = http.newCall(request)
        call = c
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { failed("Couldn't download the book.") }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("Empty response")
                        val length = body.contentLength()
                        var done = 0L
                        var shown = 0L
                        part.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val now = SystemClock.uptimeMillis()
                                    if (now - shown > 150) {
                                        shown = now
                                        val d = done
                                        main.post { progress(d, length) }
                                    }
                                }
                            }
                        }
                    }
                    if (!part.renameTo(file)) throw IOException("Couldn't save the book")
                    main.post {
                        this@PdfReaderActivity.call = null
                        trimCache(file)
                        open(file)
                    }
                } catch (e: IOException) {
                    part.delete()
                    main.post { failed("Couldn't download the book.") }
                }
            }
        })
    }

    private fun progress(done: Long, length: Long) {
        if (isDestroyed) return
        status.text = if (length > 0) "Downloading… ${mb(done)} of ${mb(length)}" else "Downloading… ${mb(done)}"
    }

    private fun failed(message: String) {
        if (isDestroyed) return
        call = null
        status.visibility = View.VISIBLE
        status.text = "$message\n\nTap to retry"
    }

    // Lays the book out from the first page's shape so it opens at once; the other pages are measured in
    // small batches afterwards, between renders, and the layout is corrected where they differ.
    private fun open(file: File) {
        if (isDestroyed) return
        file.setLastModified(System.currentTimeMillis())
        status.text = "Opening…"
        bg.post {
            try {
                val r = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                renderer = r
                val count = r.pageCount
                val first = if (count > 0) ratio(r, 0) else 1f
                main.post { opened(FloatArray(count) { first }) }
                measure(1)
            } catch (e: Exception) {
                // Usually the site sent an HTML error or login page instead of the PDF; don't cache it.
                file.delete()
                main.post { failed("This book couldn't be opened.") }
            }
        }
    }

    private fun ratio(r: PdfRenderer, i: Int): Float {
        val p = r.openPage(i)
        try {
            return p.height.toFloat() / p.width
        } finally {
            p.close()
        }
    }

    private fun measure(from: Int) {
        bg.post {
            val r = renderer ?: return@post
            val to = min(r.pageCount, from + MEASURE_BATCH)
            if (from >= to) return@post
            val values = try {
                FloatArray(to - from) { ratio(r, from + it) }
            } catch (e: Exception) {
                return@post
            }
            main.post { if (!isDestroyed) doc.setRatios(from, values) }
            measure(to)
        }
    }

    private fun opened(ratios: FloatArray) {
        if (isDestroyed) return
        if (ratios.isEmpty()) return failed("This book has no pages.")
        total = ratios.size
        status.visibility = View.GONE
        seek.max = total - 1
        doc.padTop = (top.height + dp(8)).toFloat()
        doc.padBottom = (bottom.height + dp(8)).toFloat()
        doc.setDocument(ratios)
        doc.scrollToPage(prefs.getInt(KEY_PAGE + url, 0).coerceIn(0, total - 1))
    }

    // Keeps the newest books up to the cache budget; also clears downloads that were cut off.
    private fun trimCache(keep: File) {
        var used = 0L
        keep.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
            if (it == keep) return@forEach
            if (it.name.endsWith(".part") || used + it.length() + keep.length() > CACHE_BYTES) it.delete()
            else used += it.length()
        }
    }

    private fun onPageChanged(n: Int) {
        if (n == page) return
        page = n
        pageLabel.text = "Page ${n + 1} of $total"
        val left = total - n - 1
        leftLabel.text = if (left == 0) "End of book" else if (left == 1) "1 page left" else "$left pages left"
        seek.progress = n
        prefs.edit().putInt(KEY_PAGE + url, n).apply()
    }

    private fun toggleBars() {
        barsShown = !barsShown
        top.animate().translationY(if (barsShown) 0f else -top.height.toFloat()).alpha(if (barsShown) 1f else 0f)
            .setDuration(250)
        bottom.animate().translationY(if (barsShown) 0f else bottom.height.toFloat()).alpha(if (barsShown) 1f else 0f)
            .setDuration(250)
    }

    private fun applyTheme() {
        val t = THEMES[theme]
        root.setBackgroundColor(t.bg)
        paint.colorFilter = t.filter
        blank.colorFilter = t.filter
        doc.invalidate()
        top.setBackgroundColor(t.bar)
        bottom.setBackgroundColor(t.bar)
        for (v in listOf(status, closeBtn, titleView, themeBtn, pageLabel, leftLabel)) v.setTextColor(t.fg)
        seek.progressTintList = ColorStateList.valueOf(t.accent)
        seek.thumbTintList = ColorStateList.valueOf(t.accent)
        window.statusBarColor = t.bg
        window.navigationBarColor = t.bg
        var flags = 0
        if (t.light) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun text(size: Float) = TextView(this).apply { textSize = size }

    private fun barButton(label: String) = text(17f).apply {
        text = label
        gravity = Gravity.CENTER
        paint.isFakeBoldText = true
        isClickable = true
        isFocusable = true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * The whole document as one scrolling column. Coordinates: pages are laid out at zoom 1 in document space;
     * the screen shows document * zoom, offset by (sx, sy). Every nearby page keeps a fit-to-width bitmap; when
     * zoomed in, only the part of each page that is on screen is rendered sharp on top, once the view settles.
     */
    private inner class DocView : View(this@PdfReaderActivity) {
        var padTop = 0f
        var padBottom = 0f
        private var ratios = FloatArray(0)
        private var tops = FloatArray(0)
        private var docHeight = 0f
        private var zoom = 1f
        private var sx = 0f
        private var sy = 0f
        private var touching = false
        private val gap = dp(8).toFloat()
        private val scroller = OverScroller(context)
        private var zoomAnim: ValueAnimator? = null
        private val bitmaps = HashMap<Int, Bitmap>()
        // Pages whose base render is queued; avoids queueing the same render twice.
        private val requested = HashSet<Int>()
        private val details = HashMap<Int, Detail>()
        // Viewport the sharp crops were last requested for.
        private var detailZ = 0f
        private var detailX = 0f
        private var detailY = 0f
        // What the render thread may still draw: pages outside this range or from an older layout are skipped.
        @Volatile private var wantFirst = 0
        @Volatile private var wantLast = -1
        @Volatile private var gen = 0
        @Volatile private var detailGen = 0
        private val dst = RectF()

        private val pageWidth get() = width - gap * 2

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scroller.forceFinished(true)
                zoomAnim?.cancel()
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomAt(zoom * d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaler.isInProgress) return false
                scrollTo(sx + dx, sy + dy)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scaler.isInProgress) return false
                scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX().toInt(), 0, maxY().toInt())
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleBars()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateZoom(if (zoom > 1.01f) 1f else 2.5f, e.x, e.y)
                return true
            }
        })

        fun setDocument(r: FloatArray) {
            ratios = r
            layoutPages()
            dropAll()
        }

        // Corrects page shapes measured after opening, keeping whatever is at the top of the screen in place.
        fun setRatios(from: Int, values: FloatArray) {
            if (tops.isEmpty()) return
            val changed = values.indices.filter { abs(ratios[from + it] - values[it]) > 1e-4f }
            if (changed.isEmpty()) return
            val anchor = pageAt(sy / zoom)
            val frac = (sy / zoom - tops[anchor]) / (pageWidth * ratios[anchor])
            for (k in changed) {
                val i = from + k
                ratios[i] = values[k]
                bitmaps.remove(i)?.recycle()
                details.remove(i)?.bitmap?.recycle()
                requested.remove(i)
            }
            layoutPages()
            detailZ = 0f
            scrollTo(sx, (tops[anchor] + frac * pageWidth * ratios[anchor]) * zoom)
        }

        private fun layoutPages() {
            if (width == 0 || ratios.isEmpty()) return
            tops = FloatArray(ratios.size)
            var y = padTop
            for (i in ratios.indices) {
                tops[i] = y
                y += pageWidth * ratios[i] + gap
            }
            docHeight = y - gap + padBottom
            scrollTo(sx, sy)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (tops.isEmpty()) return
            val keep = page
            layoutPages()
            dropAll()
            scrollToPage(keep)
        }

        // Puts the page's top just under the top bar.
        fun scrollToPage(n: Int) {
            if (tops.isEmpty()) return
            scroller.forceFinished(true)
            scrollTo(sx, tops[n.coerceIn(0, tops.size - 1)] * zoom - padTop)
        }

        private fun maxX() = max(0f, width * zoom - width)
        private fun maxY() = max(0f, docHeight * zoom - height)

        private fun scrollTo(x: Float, y: Float) {
            sx = x.coerceIn(0f, maxX())
            sy = y.coerceIn(0f, maxY())
            invalidate()
        }

        private fun zoomAt(z: Float, fx: Float, fy: Float) {
            val nz = z.coerceIn(1f, MAX_ZOOM)
            val docX = (fx + sx) / zoom
            val docY = (fy + sy) / zoom
            zoom = nz
            scrollTo(docX * nz - fx, docY * nz - fy)
        }

        private fun animateZoom(to: Float, fx: Float, fy: Float) {
            zoomAnim?.cancel()
            zoomAnim = ValueAnimator.ofFloat(zoom, to).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener { zoomAt(it.animatedValue as Float, fx, fy) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) = invalidate()
                })
                start()
            }
        }

        // Last page whose top is at or above document y.
        private fun pageAt(docY: Float): Int {
            var lo = 0
            var hi = tops.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (tops[mid] <= docY) lo = mid else hi = mid - 1
            }
            return lo
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (tops.isEmpty()) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    invalidate()
                }
            }
            scaler.onTouchEvent(e)
            gestures.onTouchEvent(e)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            if (scroller.computeScrollOffset()) {
                sx = scroller.currX.toFloat()
                sy = scroller.currY.toFloat()
                postInvalidateOnAnimation()
            }
            if (tops.isEmpty()) return
            val first = pageAt(sy / zoom)
            val last = pageAt((sy + height) / zoom)
            for (i in first..last) {
                val left = gap * zoom - sx
                val topEdge = tops[i] * zoom - sy
                dst.set(left, topEdge, left + pageWidth * zoom, topEdge + pageWidth * ratios[i] * zoom)
                val b = bitmaps[i]
                if (b != null) canvas.drawBitmap(b, null, dst, paint) else canvas.drawRect(dst, blank)
                val d = details[i] ?: continue
                dst.set(d.rect.left * zoom - sx, d.rect.top * zoom - sy, d.rect.right * zoom - sx, d.rect.bottom * zoom - sy)
                canvas.drawBitmap(d.bitmap, null, dst, paint)
            }
            onPageChanged(pageAt((sy + height / 2f) / zoom))
            ensure(first, last)
        }

        // Renders what's on screen (plus a page either side, for scrolling) and frees everything else.
        private fun ensure(first: Int, last: Int) {
            val from = max(0, first - 1)
            val to = min(tops.size - 1, last + 1)
            wantFirst = from
            wantLast = to
            bitmaps.keys.filter { it !in from..to }.forEach { bitmaps.remove(it)?.recycle() }
            details.keys.filter { it !in first..last }.forEach { details.remove(it)?.bitmap?.recycle() }
            requested.retainAll { it in from..to }
            // Pages on screen first, then the ones just off it.
            val order = (first..last) + listOf(last + 1, first - 1).filter { it in from..to }
            for (i in order) if (i !in bitmaps && requested.add(i)) request(i)

            val settled = !touching && !scaler.isInProgress && zoomAnim?.isRunning != true && scroller.isFinished
            if (zoom <= 1.05f) {
                if (details.isNotEmpty()) {
                    details.values.forEach { it.bitmap.recycle() }
                    details.clear()
                }
                detailZ = 0f
            } else if (settled && (zoom != detailZ || sx != detailX || sy != detailY)) {
                detailZ = zoom
                detailX = sx
                detailY = sy
                requestDetail(first, last)
            }
        }

        private fun request(i: Int) {
            val w = max(1, min(pageWidth, sqrt(MAX_PIXELS / ratios[i])).roundToInt())
            val g = gen
            bg.post {
                if (g != gen || i !in wantFirst..wantLast) return@post
                val r = renderer ?: return@post
                try {
                    val p = r.openPage(i)
                    try {
                        val ratio = p.height.toFloat() / p.width
                        val bmp = Bitmap.createBitmap(w, max(1, (w * ratio).roundToInt()), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        val s = w / p.width.toFloat()
                        p.render(bmp, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        main.post {
                            if (g == gen && i in wantFirst..wantLast && requested.remove(i)) {
                                // A page measured here before the background pass reached it.
                                setRatios(i, floatArrayOf(ratio))
                                bitmaps.put(i, bmp)?.recycle()
                                invalidate()
                            } else {
                                bmp.recycle()
                            }
                        }
                    } finally {
                        p.close()
                    }
                } catch (e: Exception) {
                } catch (e: OutOfMemoryError) {
                }
            }
        }

        // For each page on screen, renders just its visible part at the current zoom: at most one screenful of pixels.
        private fun requestDetail(first: Int, last: Int) {
            val g = ++detailGen
            val z = zoom
            for (i in first..last) {
                val pageLeft = gap * z - sx
                val pageTop = tops[i] * z - sy
                val l = max(0f, pageLeft)
                val t = max(0f, pageTop)
                val r = min(width.toFloat(), pageLeft + pageWidth * z)
                val b = min(height.toFloat(), pageTop + pageWidth * ratios[i] * z)
                val w = (r - l).roundToInt()
                val h = (b - t).roundToInt()
                if (w <= 0 || h <= 0) continue
                val rect = RectF((l + sx) / z, (t + sy) / z, (l + sx) / z + w / z, (t + sy) / z + h / z)
                val scale = pageWidth * z
                val dx = l - pageLeft
                val dy = t - pageTop
                bg.post {
                    if (g != detailGen) return@post
                    val renderer = renderer ?: return@post
                    try {
                        val p = renderer.openPage(i)
                        try {
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            val s = scale / p.width
                            val m = Matrix().apply {
                                setScale(s, s)
                                postTranslate(-dx, -dy)
                            }
                            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            main.post {
                                if (g == detailGen && i in wantFirst..wantLast) {
                                    details.put(i, Detail(bmp, rect))?.bitmap?.recycle()
                                    invalidate()
                                } else {
                                    bmp.recycle()
                                }
                            }
                        } finally {
                            p.close()
                        }
                    } catch (e: Exception) {
                    } catch (e: OutOfMemoryError) {
                    }
                }
            }
        }

        fun dropAll() {
            gen++
            detailGen++
            detailZ = 0f
            bitmaps.values.forEach { it.recycle() }
            bitmaps.clear()
            details.values.forEach { it.bitmap.recycle() }
            details.clear()
            requested.clear()
            invalidate()
        }
    }

    private class Detail(val bitmap: Bitmap, val rect: RectF)

    private class ReaderTheme(
        val bg: Int,
        val fg: Int,
        val bar: Int,
        val accent: Int,
        val light: Boolean,
        val filter: ColorFilter?,
    )

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_USER_AGENT = "userAgent"
        private const val PREFS = "pdf_reader"
        private const val KEY_THEME = "theme"
        private const val KEY_PAGE = "page:"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MAX_ZOOM = 4f
        // ~32 MB per page bitmap.
        private const val MAX_PIXELS = 8e6f
        private const val MEASURE_BATCH = 32
        private const val CACHE_BYTES = 512L * 1024 * 1024

        private val http by lazy { OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build() }

        // Paper colour multiplied into pages; dark inverts them, then turns hues back so images keep their colours.
        private val THEMES = listOf(
            ReaderTheme(0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), 0xFF8200B0.toInt(), true, null),
            ReaderTheme(
                0xFFF6EEDB.toInt(), 0xFF4A3B2C.toInt(), 0xFFF6EEDB.toInt(), 0xFF9A5A1C.toInt(), true,
                ColorMatrixColorFilter(ColorMatrix().apply { setScale(0xF6 / 255f, 0xEE / 255f, 0xDB / 255f, 1f) }),
            ),
            ReaderTheme(
                0xFF1F1F1F.toInt(), 0xFFE6E6E6.toInt(), 0xFF1F1F1F.toInt(), 0xFFC58AF0.toInt(), false,
                ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            -0.76f, 0f, 0f, 0f, 224.4f,
                            0f, -0.76f, 0f, 0f, 224.4f,
                            0f, 0f, -0.76f, 0f, 224.4f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ).apply {
                        postConcat(
                            ColorMatrix(
                                floatArrayOf(
                                    -0.574f, 1.43f, 0.144f, 0f, 0f,
                                    0.426f, 0.43f, 0.144f, 0f, 0f,
                                    0.426f, 1.43f, -0.856f, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f,
                                ),
                            ),
                        )
                    },
                ),
            ),
        )

        private fun sha1(s: String) =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
    }
}
