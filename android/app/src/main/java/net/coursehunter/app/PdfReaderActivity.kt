package net.coursehunter.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.OverScroller
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.PdfiumSource
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Native PDF reader on PDFium. pdf.js inside the WebView renderer froze or got the page killed on big books. The book
 * opens while it downloads when the site allows range requests; pages scroll continuously, one or two per row, and
 * only what's near the screen is rendered. Text can be searched, selected and copied, and links followed.
 */
class PdfReaderActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    // PDFium isn't thread-safe; every call on the document runs on this one thread.
    private val worker = HandlerThread("pdf-render").apply { start() }
    private val bg = Handler(worker.looper)
    private var core: PdfiumCore? = null
    private var pdf: PdfDocument? = null
    private var source: PdfiumSource? = null

    private lateinit var url: String
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var doc: DocView
    private lateinit var statusBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var statusAction: TextView
    private lateinit var top: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var searchRow: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchCount: TextView
    private lateinit var loading: ProgressBar
    private lateinit var bottom: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var markBtn: ImageButton
    private lateinit var pageLabel: TextView
    private lateinit var leftLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var thumbBox: LinearLayout
    private lateinit var thumbImage: ImageView
    private lateinit var thumbLabel: TextView
    private lateinit var selectBar: LinearLayout
    private lateinit var brightBox: LinearLayout
    private val icons = ArrayList<ImageButton>()
    private val texts = ArrayList<TextView>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blank = Paint().apply { color = Color.WHITE }

    private var download: Download? = null
    private var opening = false
    private var complete = false
    private var error: BookError? = null
    private var total = 0
    private var page = -1
    private var theme = 0
    private var barsShown = true
    private var thumb: Bitmap? = null
    @Volatile private var thumbGen = 0
    private var marks: java.util.SortedSet<Int> = sortedSetOf()

    // Search: every match in the book, in page order.
    private val matches = ArrayList<Match>()
    private val matchesByPage = HashMap<Int, MutableList<Int>>()
    private var current = -1
    @Volatile private var searchGen = 0
    private var searching = false

    private var selection: Selection? = null

    private val listener = object : Download.Listener {
        override fun onProgress(received: Long, length: Long) {
            if (length > 0) loading.progress = (received * 1000 / length).toInt()
            if (total == 0 && error == null) {
                status.text = if (length > 0) "Downloading… ${mb(received)} of ${mb(length)}" else "Downloading… ${mb(received)}"
            }
        }

        // Range requests work: open the book now and fetch the pages it needs first.
        override fun onReady() {
            val d = download ?: return
            if (!opening) openSource(d)
        }

        override fun onComplete(file: File) {
            complete = true
            loading.visibility = View.GONE
            if (!opening) openFile(file) else if (total > 0) measure(1)
        }

        override fun onFailed(error: BookError) = failed(error)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        theme = prefs.getInt(KEY_THEME, 0).coerceIn(0, THEMES.size - 1)
        marks = prefs.getStringSet(KEY_MARKS + url, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSortedSet()

        root = FrameLayout(this)
        doc = DocView()
        root.addView(doc, FrameLayout.LayoutParams(MATCH, MATCH))

        status = text(15f).apply { gravity = Gravity.CENTER }
        statusAction = text(15f).apply {
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
            setOnClickListener { act() }
        }
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(statusAction, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
        }
        root.addView(statusBox, FrameLayout.LayoutParams(MATCH, MATCH))

        titleView = text(15f).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            paint.isFakeBoldText = true
            setPadding(dp(4), 0, dp(4), 0)
            text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Book"
        }
        markBtn = icon(R.drawable.ic_bookmark, "Bookmark this page") { toggleMark() }
        toolbar = row().apply {
            addView(icon(R.drawable.ic_close, "Close") { finish() }, square())
            addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(icon(R.drawable.ic_search, "Search") { openSearch() }, square())
            addView(icon(R.drawable.ic_toc, "Contents and bookmarks") { openPanel() }, square())
            addView(markBtn, square())
            addView(icon(R.drawable.ic_more, "More options") { showMenu(it) }, square())
        }
        searchField = EditText(this).apply {
            hint = "Search in book"
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    search(text.toString())
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }
        searchCount = text(13f).apply { alpha = 0.7f }
        searchRow = row().apply {
            visibility = View.GONE
            addView(icon(R.drawable.ic_back, "Close search") { closeSearch() }, square())
            addView(searchField, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(searchCount)
            addView(icon(R.drawable.ic_up, "Previous match") { step(-1) }, square())
            addView(icon(R.drawable.ic_down, "Next match") { step(1) }, square())
        }
        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Bars swallow their touches so they never reach the pages underneath.
            isClickable = true
            addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(searchRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(loading, LinearLayout.LayoutParams(MATCH, dp(3)))
        }

        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) preview(value)
                }

                override fun onStartTrackingTouch(s: SeekBar) = preview(s.progress)

                override fun onStopTrackingTouch(s: SeekBar) {
                    thumbBox.visibility = View.GONE
                    thumbGen++
                    doc.scrollToPage(s.progress)
                }
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

        thumbImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        thumbLabel = text(13f).apply { gravity = Gravity.CENTER }
        thumbBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
            visibility = View.GONE
            elevation = dp(8).toFloat()
            addView(thumbImage, LinearLayout.LayoutParams(dp(THUMB_DP), dp(THUMB_DP)))
            addView(thumbLabel, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }

        selectBar = row().apply {
            visibility = View.GONE
            elevation = dp(8).toFloat()
            setPadding(dp(6), dp(2), dp(6), dp(2))
            addView(barText("Copy") { copySelection() })
            addView(barText("Search") { searchSelection() })
            addView(icon(R.drawable.ic_close, "Clear selection") { doc.select(null) }, square())
        }

        val brightSeek = SeekBar(this).apply {
            max = 100
            val v = prefs.getFloat(KEY_BRIGHTNESS, -1f)
            progress = if (v < 0) 50 else (v * 100).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) setBrightness(max(0.02f, value / 100f))
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        brightBox = row().apply {
            visibility = View.GONE
            elevation = dp(8).toFloat()
            setPadding(dp(12), dp(6), dp(6), dp(6))
            addView(text(13f).apply { text = "Brightness" })
            addView(brightSeek, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(barText("Auto") { setBrightness(-1f) })
            addView(icon(R.drawable.ic_close, "Close brightness") { brightBox.visibility = View.GONE }, square())
        }

        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        root.addView(thumbBox, FrameLayout.LayoutParams(WRAP, WRAP))
        root.addView(selectBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
        root.addView(brightBox, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        top.elevation = dp(4).toFloat()
        bottom.elevation = dp(4).toFloat()
        setContentView(root)
        if (prefs.getBoolean(KEY_AWAKE, false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs.getFloat(KEY_BRIGHTNESS, -1f).let { if (it >= 0) setBrightness(it) }
        applyTheme()
        load()
    }

    // Backgrounded: drop every page bitmap; the pages on screen are redrawn on return.
    override fun onStop() {
        if (::doc.isInitialized) doc.dropAll()
        super.onStop()
    }

    override fun onDestroy() {
        download?.unlisten(listener)
        if (::doc.isInitialized) doc.dropAll()
        searchGen++
        // Unblocks a render waiting on the network, so the render thread can close the book and quit.
        source?.close()
        bg.post {
            pdf?.close()
            pdf = null
        }
        worker.quitSafely()
        thumb?.recycle()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selection != null -> doc.select(null)
            searchRow.visibility == View.VISIBLE -> closeSearch()
            brightBox.visibility == View.VISIBLE -> brightBox.visibility = View.GONE
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun load() {
        error = null
        statusAction.visibility = View.GONE
        Books.local(this, url)?.let {
            complete = true
            return openFile(it)
        }
        statusBox.visibility = View.VISIBLE
        status.text = "Downloading…"
        loading.visibility = View.VISIBLE
        val d = Books.download(this, url, intent.getStringExtra(EXTRA_USER_AGENT) ?: WebSettings.getDefaultUserAgent(this))
        download?.unlisten(listener)
        download = d
        d.listen(listener)
    }

    private fun failed(e: BookError) {
        if (isDestroyed) return
        error = e
        statusBox.visibility = View.VISIBLE
        status.text = e.message
        statusAction.text = e.action
        statusAction.visibility = View.VISIBLE
    }

    private fun act() {
        when (error) {
            BookError.LOGIN, BookError.MISSING, BookError.INVALID -> finish()
            else -> if (total > 0) {
                // The book is open; only the download needs restarting, then pages waiting on it render.
                error = null
                statusBox.visibility = View.GONE
                load()
                doc.dropAll()
            } else {
                load()
            }
        }
    }

    private fun openFile(file: File) {
        if (isDestroyed) return
        opening = true
        status.text = "Opening…"
        bg.post {
            try {
                val c = core ?: PdfiumCore(this).also { core = it }
                opened(c.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)))
            } catch (e: Exception) {
                // Usually the site sent an error page instead of the PDF; don't keep it.
                file.delete()
                main.post { failed(BookError.INVALID) }
            }
        }
    }

    // Opens the book while it downloads; PDFium's reads wait for the bytes they need.
    private fun openSource(d: Download) {
        if (isDestroyed) return
        opening = true
        status.text = "Opening…"
        val s = d.source()
        source = s
        bg.post {
            try {
                val c = core ?: PdfiumCore(this).also { core = it }
                opened(c.newDocument(s))
            } catch (e: Exception) {
                // Some files can't be read out of order; wait for the whole download instead.
                s.close()
                main.post {
                    if (isDestroyed) return@post
                    source = null
                    opening = false
                    d.file?.let(::openFile)
                }
            }
        }
    }

    // Render thread. Lays the book out from the first page's shape so it shows at once; the other pages are
    // measured in small batches once the whole file is here, and the layout is corrected where they differ.
    private fun opened(d: PdfDocument) {
        pdf = d
        val count = d.getPageCount()
        val first = if (count > 0) d.openPage(0).use { ratio(it) } else 1f
        // After layout, so the bars' heights and the view's width are known.
        doc.post {
            if (isDestroyed) return@post
            if (count == 0) return@post failed(BookError.INVALID)
            total = count
            statusBox.visibility = View.GONE
            seek.max = total - 1
            doc.padTop = (top.height + dp(8)).toFloat()
            doc.padBottom = (bottom.height + dp(8)).toFloat()
            doc.setDocument(FloatArray(count) { first })
            doc.scrollToPage(prefs.getInt(KEY_PAGE + url, 0).coerceIn(0, total - 1))
            if (complete) measure(1)
        }
    }

    private fun ratio(p: PdfPage) = p.getPageHeightPoint().toFloat() / max(1, p.getPageWidthPoint())

    private fun measure(from: Int) {
        bg.post {
            val d = pdf ?: return@post
            val to = min(d.getPageCount(), from + MEASURE_BATCH)
            if (from >= to) return@post
            val values = try {
                FloatArray(to - from) { d.openPage(from + it).use(::ratio) }
            } catch (e: Exception) {
                return@post
            }
            main.post { if (!isDestroyed) doc.setRatios(from, values) }
            measure(to)
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
        markBtn.setImageResource(if (n in marks) R.drawable.ic_bookmark_on else R.drawable.ic_bookmark)
        describe(n)
    }

    // Screen readers get the page's text, since the page itself is only a picture.
    private fun describe(n: Int) {
        val am = getSystemService(AccessibilityManager::class.java)
        if (am?.isEnabled != true) return
        doc.contentDescription = "Page ${n + 1} of $total"
        main.removeCallbacksAndMessages(DESCRIBE)
        main.postAtTime({
            bg.post {
                val text = try {
                    pdf?.openPage(n)?.use { p -> p.openTextPage().use { it.textPageGetText(0, it.textPageCountChars()) } }
                } catch (e: Exception) {
                    null
                }
                main.post { if (page == n) doc.contentDescription = "Page ${n + 1} of $total. ${text?.take(4000) ?: ""}" }
            }
        }, DESCRIBE, SystemClock.uptimeMillis() + 400)
    }

    private fun toggleBars() {
        barsShown = !barsShown
        top.animate().translationY(if (barsShown) 0f else -top.height.toFloat()).alpha(if (barsShown) 1f else 0f)
            .setDuration(250)
        bottom.animate().translationY(if (barsShown) 0f else bottom.height.toFloat()).alpha(if (barsShown) 1f else 0f)
            .setDuration(250)
    }

    // Slider: a small picture of the page you'd land on, jumped to when you let go.
    private fun preview(n: Int) {
        if (total == 0) return
        thumbLabel.text = "Page ${n + 1}"
        thumbBox.visibility = View.VISIBLE
        val track = seek.width - seek.paddingLeft - seek.paddingRight
        val x = bottom.left + seek.left + seek.paddingLeft + track * n / max(1, seek.max)
        val w = dp(THUMB_DP + 16)
        thumbBox.translationX = (x - w / 2).coerceIn(dp(8), root.width - w - dp(8)).toFloat()
        thumbBox.translationY = (bottom.top + bottom.translationY - dp(THUMB_DP + 52)).toFloat()
        val g = ++thumbGen
        val size = dp(THUMB_DP)
        bg.post {
            if (g != thumbGen) return@post
            val bmp = try {
                pdf?.openPage(n)?.use { p ->
                    val r = ratio(p)
                    val w2 = if (r >= 1) (size / r).roundToInt() else size
                    val h2 = if (r >= 1) size else (size * r).roundToInt()
                    Bitmap.createBitmap(max(1, w2), max(1, h2), Bitmap.Config.ARGB_8888).also { render(p, it, w2.toFloat()) }
                }
            } catch (e: Exception) {
                null
            } ?: return@post
            main.post {
                if (g != thumbGen || isDestroyed) return@post bmp.recycle()
                thumbImage.setImageBitmap(bmp)
                thumb?.recycle()
                thumb = bmp
            }
        }
    }

    private fun toggleMark() {
        if (page < 0) return
        if (!marks.remove(page)) marks.add(page)
        prefs.edit().putStringSet(KEY_MARKS + url, marks.map { it.toString() }.toSet()).apply()
        markBtn.setImageResource(if (page in marks) R.drawable.ic_bookmark_on else R.drawable.ic_bookmark)
        Toast.makeText(this, if (page in marks) "Bookmarked page ${page + 1}" else "Bookmark removed", Toast.LENGTH_SHORT).show()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val m = menu.menu
        THEME_NAMES.forEachIndexed { i, name -> m.add(1, 10 + i, i, name).isChecked = i == theme }
        m.setGroupCheckable(1, true, true)
        m.add(2, 1, 10, "Two pages side by side").setCheckable(true).isChecked = doc.spread
        m.add(2, 2, 11, "Brightness…")
        m.add(2, 3, 12, "Keep screen on").setCheckable(true).isChecked = prefs.getBoolean(KEY_AWAKE, false)
        m.add(2, 4, 13, "Save for offline").setCheckable(true).isChecked = Books.isSaved(this, url)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                in 10 until 10 + THEMES.size -> {
                    theme = item.itemId - 10
                    prefs.edit().putInt(KEY_THEME, theme).apply()
                    applyTheme()
                }
                1 -> {
                    prefs.edit().putInt(KEY_SPREAD, if (doc.spread) 0 else 1).apply()
                    doc.relayout()
                }
                2 -> brightBox.visibility = View.VISIBLE
                3 -> {
                    val on = !prefs.getBoolean(KEY_AWAKE, false)
                    prefs.edit().putBoolean(KEY_AWAKE, on).apply()
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                4 -> {
                    val keep = !Books.isSaved(this, url)
                    Books.setSaved(this, url, keep)
                    Toast.makeText(
                        this,
                        if (keep) "Saved: this book stays on the phone for offline reading" else "Removed from offline books",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            true
        }
        menu.show()
    }

    private fun setBrightness(value: Float) {
        prefs.edit().putFloat(KEY_BRIGHTNESS, value).apply()
        window.attributes = window.attributes.apply { screenBrightness = if (value < 0) -1f else value }
    }

    // Contents (the book's outline) and bookmarks, in one full-screen list.
    private fun openPanel() {
        if (total == 0) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(THEMES[theme].bg)
            isClickable = true
            elevation = dp(12).toFloat()
        }
        val list = ListView(this).apply { divider = null }
        val entries = ArrayList<Triple<String, Int, Int>>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(i: Int) = entries[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val v = convert as? TextView ?: text(15f).apply {
                    minHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                }
                val (title, target, depth) = entries[i]
                v.setTextColor(THEMES[theme].fg)
                v.setPadding(dp(20 + depth * 16), dp(8), dp(20), dp(8))
                v.text = if (target >= 0) "$title  ·  ${target + 1}" else title
                v.alpha = if (target >= 0) 1f else 0.6f
                return v
            }
        }
        list.adapter = adapter
        fun close() = root.removeView(panel)
        list.setOnItemClickListener { _, _, i, _ ->
            val target = entries[i].second
            if (target < 0) return@setOnItemClickListener
            close()
            doc.scrollToPage(target)
        }
        fun showMarks() {
            entries.clear()
            if (marks.isEmpty()) entries += Triple("No bookmarks yet. Tap the bookmark icon to add the page you're on.", -1, 0)
            marks.forEach { entries += Triple("Page ${it + 1}", it, 0) }
            adapter.notifyDataSetChanged()
        }
        fun showContents() {
            entries.clear()
            entries += Triple("Loading…", -1, 0)
            adapter.notifyDataSetChanged()
            bg.post {
                val flat = ArrayList<Triple<String, Int, Int>>()
                try {
                    fun walk(items: List<PdfDocument.Bookmark>, depth: Int) {
                        for (b in items) {
                            flat += Triple(b.title ?: "Untitled", b.pageIdx.toInt(), depth)
                            walk(b.children, depth + 1)
                        }
                    }
                    pdf?.let { walk(it.getTableOfContents(), 0) }
                } catch (e: Exception) {
                }
                main.post {
                    entries.clear()
                    entries += flat.ifEmpty { listOf(Triple("This book has no table of contents.", -1, 0)) }
                    adapter.notifyDataSetChanged()
                }
            }
        }
        val tabs = row().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        val contentsTab = barText("Contents") { showContents() }
        val marksTab = barText("Bookmarks") { showMarks() }
        tabs.addView(icon(R.drawable.ic_close, "Close") { close() }, square())
        tabs.addView(contentsTab)
        tabs.addView(marksTab)
        for (v in listOf(contentsTab, marksTab)) v.setTextColor(THEMES[theme].fg)
        tabs.getChildAt(0).let { (it as ImageButton).imageTintList = ColorStateList.valueOf(THEMES[theme].fg) }
        panel.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
        showContents()
    }

    private fun openSearch() {
        if (total == 0) return
        toolbar.visibility = View.GONE
        searchRow.visibility = View.VISIBLE
        searchField.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(searchField, 0)
    }

    private fun closeSearch() {
        hideKeyboard()
        searchGen++
        searching = false
        matches.clear()
        matchesByPage.clear()
        current = -1
        searchCount.text = ""
        searchRow.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        doc.invalidate()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    // Searches page by page on the render thread, one page per message so rendering keeps up in between.
    private fun search(query: String) {
        val q = query.trim()
        val g = ++searchGen
        matches.clear()
        matchesByPage.clear()
        current = -1
        doc.invalidate()
        if (q.isEmpty()) return
        searching = true
        searchCount.text = "Searching…"
        val start = max(0, page)
        fun next(p: Int) {
            bg.post {
                if (g != searchGen) return@post
                val d = pdf ?: return@post
                if (p >= d.getPageCount()) {
                    main.post { if (g == searchGen) searchDone() }
                    return@post
                }
                val found = ArrayList<List<RectF>>()
                try {
                    d.openPage(p).use { pg ->
                        pg.openTextPage().use { t ->
                            t.findStart(q, emptySet(), 0)?.use { f ->
                                while (f.findNext()) found += textRects(pg, t, f.getSchResultIndex(), f.getSchCount())
                            }
                        }
                    }
                } catch (e: Exception) {
                }
                if (found.isNotEmpty()) main.post { if (g == searchGen) addMatches(p, found, start) }
                next(p + 1)
            }
        }
        next(0)
    }

    private fun addMatches(p: Int, found: List<List<RectF>>, start: Int) {
        val list = matchesByPage.getOrPut(p) { ArrayList() }
        for (rects in found) {
            list += matches.size
            matches += Match(p, rects)
        }
        // First hit at or after where the reader is: go there.
        if (current < 0 && p >= start) show(list.first())
        searchCount.text = if (current >= 0) "${current + 1} of ${matches.size}…" else "${matches.size}…"
        doc.invalidate()
    }

    private fun searchDone() {
        searching = false
        if (matches.isEmpty()) {
            searchCount.text = "No results"
            return
        }
        if (current < 0) show(0)
        searchCount.text = "${current + 1} of ${matches.size}"
    }

    private fun step(dir: Int) {
        if (matches.isEmpty()) return
        show((current + dir + matches.size) % matches.size)
        searchCount.text = "${current + 1} of ${matches.size}" + if (searching) "…" else ""
    }

    private fun show(i: Int) {
        current = i
        val m = matches[i]
        doc.reveal(m.page, m.rects.firstOrNull())
        doc.invalidate()
    }

    // Render thread: rectangles of a run of characters, in page units (width 1, height = page ratio).
    private fun textRects(p: PdfPage, t: io.legere.pdfiumandroid.PdfTextPage, start: Int, count: Int): List<RectF> {
        val r = ratio(p)
        val h = (UNIT * r).roundToInt()
        val n = t.textPageCountRects(start, count)
        return (0 until n).mapNotNull { k ->
            val rect = t.textPageGetRect(k) ?: return@mapNotNull null
            val d = p.mapRectToDevice(0, 0, UNIT, h, 0, rect)
            RectF(min(d.left, d.right) / UNIT.toFloat(), min(d.top, d.bottom) / UNIT.toFloat(),
                max(d.left, d.right) / UNIT.toFloat(), max(d.top, d.bottom) / UNIT.toFloat())
        }
    }

    private fun copySelection() {
        val s = selection ?: return
        bg.post {
            val text = try {
                pdf?.openPage(s.page)?.use { p -> p.openTextPage().use { it.textPageGetText(s.from, s.to - s.from + 1) } }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (text.isNullOrBlank()) return@post
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Book text", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                doc.select(null)
            }
        }
    }

    private fun searchSelection() {
        val s = selection ?: return
        bg.post {
            val text = try {
                pdf?.openPage(s.page)?.use { p -> p.openTextPage().use { it.textPageGetText(s.from, s.to - s.from + 1) } }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (text.isNullOrBlank()) return@post
                doc.select(null)
                openSearch()
                searchField.setText(text.trim().take(100))
                hideKeyboard()
                search(searchField.text.toString())
            }
        }
    }

    private fun applyTheme() {
        val t = THEMES[theme]
        root.setBackgroundColor(t.bg)
        paint.colorFilter = t.filter
        blank.colorFilter = t.filter
        doc.themeChanged(t.filter)
        for (v in listOf(top, bottom)) v.setBackgroundColor(t.bar)
        for (v in listOf(thumbBox, selectBar, brightBox)) {
            v.background = GradientDrawable().apply {
                setColor(t.bar)
                cornerRadius = dp(12).toFloat()
            }
        }
        for (v in texts) v.setTextColor(t.fg)
        searchField.setTextColor(t.fg)
        searchField.setHintTextColor(t.fg and 0x80FFFFFF.toInt())
        statusAction.setTextColor(t.accent)
        for (v in icons) v.imageTintList = ColorStateList.valueOf(t.fg)
        for (s in listOf(seek, brightBox.getChildAt(1) as SeekBar)) {
            s.progressTintList = ColorStateList.valueOf(t.accent)
            s.thumbTintList = ColorStateList.valueOf(t.accent)
        }
        loading.progressTintList = ColorStateList.valueOf(t.accent)
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

    private fun text(size: Float) = TextView(this).apply { textSize = size }.also { texts += it }

    private fun barText(label: String, onClick: () -> Unit) = text(14f).apply {
        text = label
        gravity = Gravity.CENTER
        paint.isFakeBoldText = true
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(44)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun icon(res: Int, label: String, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        contentDescription = label
        background = null
        setOnClickListener(onClick)
    }.also { icons += it }

    private fun row() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun square() = LinearLayout.LayoutParams(dp(44), dp(44))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Render thread: draws page p at the given width into bmp, offset by (dx, dy) pixels.
    private fun render(p: PdfPage, bmp: Bitmap, width: Float, dx: Float = 0f, dy: Float = 0f) {
        bmp.eraseColor(Color.WHITE)
        val s = width / max(1, p.getPageWidthPoint())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate(-dx, -dy)
        }
        p.renderPageBitmap(bmp, m, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), true, false, Color.WHITE, Color.WHITE)
    }

    /**
     * The whole document as one scrolling column of rows, one or two pages each. Pages are laid out at zoom 1 in
     * document space; the screen shows document * zoom, offset by (sx, sy). Each nearby page keeps one bitmap at
     * fit width; when zoomed in, the screen is also covered in sharp tiles, rendered centre-first once it settles.
     */
    private inner class DocView : View(this@PdfReaderActivity) {
        var padTop = 0f
        var padBottom = 0f
        var spread = false
            private set
        private var ratios = FloatArray(0)
        private var px = FloatArray(0)
        private var py = FloatArray(0)
        private var pw = FloatArray(0)
        // Top of the row each page sits in; rows never overlap, so this is sorted.
        private var rowTop = FloatArray(0)
        private var docHeight = 0f
        private var zoom = 1f
        private var sx = 0f
        private var sy = 0f
        private var touching = false
        private var selecting = false
        private var lastDy = 0f
        private val gap = dp(8).toFloat()
        private val scroller = OverScroller(context)
        private val bitmaps = HashMap<Int, Bitmap>()
        private val requested = HashSet<Int>()
        private val links = HashMap<Int, List<Link>>()
        private val tiles = LinkedHashMap<String, Tile>()
        // What the render thread may still draw; anything else it skips.
        @Volatile private var wanted: Set<Int> = emptySet()
        @Volatile private var wantedTiles: Set<String> = emptySet()
        @Volatile private var gen = 0
        @Volatile private var selectGen = 0
        private var tileZoom = 0
        private var tileX = Float.NaN
        private var tileY = Float.NaN
        private val dst = RectF()
        private val hit = RectF()
        private val shimmer = Paint()
        private val shimmerMatrix = Matrix()
        private val shimmerShader = LinearGradient(
            0f, 0f, 1f, 0f, intArrayOf(0x00000000, 0x12000000, 0x00000000), null, Shader.TileMode.CLAMP,
        )
        private val matchPaint = Paint().apply { color = 0x66FFC107 }
        private val currentPaint = Paint().apply { color = 0xAAFF9800.toInt() }
        private val selectPaint = Paint().apply { color = 0x553D7BFF }

        init {
            shimmer.shader = shimmerShader
            isFocusable = true
        }

        fun themeChanged(filter: ColorFilter?) {
            shimmer.colorFilter = filter
            invalidate()
        }

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scroller.forceFinished(true)
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
                if (scaler.isInProgress || selecting) return false
                lastDy = dy
                scrollTo(sx + dx, sy + dy)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scaler.isInProgress || selecting) return false
                lastDy = -vy
                scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX().toInt(), 0, maxY().toInt())
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (selection != null) {
                    select(null)
                    return true
                }
                if (!follow(e.x, e.y)) toggleBars()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAt(if (zoom > 1.01f) 1f else 2.5f, e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val (i, u, v) = locate(e.x, e.y) ?: return
                selecting = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                pick(i, u, v, null)
            }
        })

        fun setDocument(r: FloatArray) {
            ratios = r
            layoutPages()
            dropAll()
        }

        fun relayout() {
            if (ratios.isEmpty()) return
            val keep = page
            layoutPages()
            dropAll()
            scrollToPage(keep)
        }

        // Corrects page shapes measured after opening, keeping whatever is at the top of the screen in place.
        fun setRatios(from: Int, values: FloatArray) {
            if (px.isEmpty()) return
            val changed = values.indices.filter { abs(ratios[from + it] - values[it]) > 1e-4f }
            if (changed.isEmpty()) return
            val anchor = pageAt(sy / zoom)
            val frac = (sy / zoom - py[anchor]) / pageH(anchor)
            for (k in changed) {
                val i = from + k
                ratios[i] = values[k]
                bitmaps.remove(i)?.let(::release)
                links.remove(i)
            }
            layoutPages()
            dropTiles()
            scrollTo(sx, (py[anchor] + frac * pageH(anchor)) * zoom)
        }

        private fun pageH(i: Int) = pw[i] * ratios[i]

        // Two per row when asked to, or by default on a wide screen showing portrait pages; the cover stays alone.
        private fun layoutPages() {
            if (width == 0 || ratios.isEmpty()) return
            val pref = prefs.getInt(KEY_SPREAD, -1)
            val wide = width > height || resources.configuration.smallestScreenWidthDp >= 600
            spread = if (pref >= 0) pref == 1 else wide && ratios[0] > 1f
            val n = ratios.size
            px = FloatArray(n)
            py = FloatArray(n)
            pw = FloatArray(n)
            rowTop = FloatArray(n)
            var y = padTop
            var i = 0
            while (i < n) {
                val pair = spread && i > 0 && i + 1 < n
                if (spread) {
                    val w = (width - gap * 3) / 2
                    val count = if (pair) 2 else 1
                    var rowH = 0f
                    for (k in 0 until count) rowH = max(rowH, w * ratios[i + k])
                    for (k in 0 until count) {
                        val j = i + k
                        pw[j] = w
                        px[j] = if (count == 1) (width - w) / 2 else gap + k * (w + gap)
                        py[j] = y + (rowH - w * ratios[j]) / 2
                        rowTop[j] = y
                    }
                    y += rowH + gap
                    i += count
                } else {
                    pw[i] = width - gap * 2
                    px[i] = gap
                    py[i] = y
                    rowTop[i] = y
                    y += pw[i] * ratios[i] + gap
                    i++
                }
            }
            docHeight = y - gap + padBottom
            scrollTo(sx, sy)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (ratios.isEmpty()) return
            relayout()
        }

        // Puts the page's top just under the top bar.
        fun scrollToPage(n: Int) {
            if (px.isEmpty()) return
            scroller.forceFinished(true)
            scrollTo(sx, rowTop[n.coerceIn(0, px.size - 1)] * zoom - padTop)
        }

        // Scrolls a search match into view, a third of the way down the screen.
        fun reveal(i: Int, r: RectF?) {
            if (px.isEmpty()) return
            if (r == null) return scrollToPage(i)
            scroller.forceFinished(true)
            val y = (py[i] + r.top * pw[i]) * zoom - height / 3f
            val x = (px[i] + r.centerX() * pw[i]) * zoom - width / 2f
            scrollTo(if (zoom > 1f) x else sx, y)
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

        // Last page whose row starts at or above document y.
        private fun pageAt(docY: Float): Int {
            var lo = 0
            var hi = rowTop.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (rowTop[mid] <= docY) lo = mid else hi = mid - 1
            }
            return lo
        }

        private fun rowStart(i: Int): Int {
            var j = i
            while (j > 0 && rowTop[j - 1] == rowTop[i]) j--
            return j
        }

        // Screen point → page and position on it in page units, if it's on a page.
        private fun locate(x: Float, y: Float): Triple<Int, Float, Float>? {
            if (px.isEmpty()) return null
            val dx = (x + sx) / zoom
            val dy = (y + sy) / zoom
            val last = pageAt(dy)
            for (i in rowStart(last)..last) {
                hit.set(px[i], py[i], px[i] + pw[i], py[i] + pageH(i))
                if (hit.contains(dx, dy)) return Triple(i, (dx - px[i]) / pw[i], (dy - py[i]) / pw[i])
            }
            return null
        }

        private fun follow(x: Float, y: Float): Boolean {
            val (i, u, v) = locate(x, y) ?: return false
            // A little slack around links, which are often a single line of small text.
            val slop = dp(6) / (pw[i] * zoom)
            val link = links[i]?.firstOrNull {
                u >= it.rect.left - slop && u <= it.rect.right + slop && v >= it.rect.top - slop && v <= it.rect.bottom + slop
            } ?: return false
            link.page?.let { scrollToPage(it) } ?: link.uri?.let {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                } catch (e: Exception) {
                }
            }
            return true
        }

        fun select(s: Selection?) {
            selection = s
            selectBar.visibility = if (s == null) View.GONE else View.VISIBLE
            if (s != null) positionSelectBar(s)
            invalidate()
        }

        private fun positionSelectBar(s: Selection) {
            val r = s.rects.firstOrNull() ?: return
            val top = (py[s.page] + r.top * pw[s.page]) * zoom - sy
            val above = top - dp(56)
            selectBar.translationY = if (above > this@PdfReaderActivity.top.height) above
            else (py[s.page] + (s.rects.last().bottom) * pw[s.page]) * zoom - sy + dp(8)
        }

        // Render thread finds the word under the finger, or extends the selection from `anchor` to it.
        private fun pick(i: Int, u: Float, v: Float, anchor: Int?) {
            val g = ++selectGen
            bg.post {
                if (g != selectGen) return@post
                val d = pdf ?: return@post
                val result = try {
                    d.openPage(i).use { p ->
                        p.openTextPage().use { t ->
                            val h = (UNIT * ratio(p)).roundToInt()
                            val pt = p.mapDeviceCoordsToPage(0, 0, UNIT, h, 0, (u * UNIT).roundToInt(), (v * UNIT).roundToInt())
                            val at = t.textPageGetCharIndexAtPos(pt.x.toDouble(), pt.y.toDouble(), 12.0, 12.0)
                            if (at < 0) return@use null
                            var from: Int
                            var to: Int
                            if (anchor == null) {
                                val n = t.textPageCountChars()
                                from = at
                                to = at
                                while (from > 0 && t.textPageGetUnicode(from - 1).isLetterOrDigit()) from--
                                while (to < n - 1 && t.textPageGetUnicode(to + 1).isLetterOrDigit()) to++
                            } else {
                                from = min(anchor, at)
                                to = max(anchor, at)
                            }
                            Selection(i, from, to, anchor ?: from, textRects(p, t, from, to - from + 1))
                        }
                    }
                } catch (e: Exception) {
                    null
                } ?: return@post
                main.post { if (g == selectGen && !isDestroyed) select(result) }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (px.isEmpty()) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_MOVE -> if (selecting) {
                    val s = selection
                    val hitAt = locate(e.x, e.y)
                    if (s != null && hitAt != null && hitAt.first == s.page) pick(s.page, hitAt.second, hitAt.third, s.anchor)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    selecting = false
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
            if (px.isEmpty()) return
            val first = rowStart(pageAt(sy / zoom))
            val last = pageAt((sy + height) / zoom)
            var loadingAny = false
            val now = SystemClock.uptimeMillis()
            for (i in first..last) {
                pageRect(i, dst)
                val b = bitmaps[i]
                if (b != null) {
                    canvas.drawBitmap(b, null, dst, paint)
                } else {
                    canvas.drawRect(dst, blank)
                    // A soft band sweeping across pages still being drawn.
                    val band = dst.width() * 0.6f
                    val t = (now % 1200) / 1200f
                    shimmerMatrix.setScale(band, 1f)
                    shimmerMatrix.postTranslate(dst.left - band + t * (dst.width() + band), 0f)
                    shimmerShader.setLocalMatrix(shimmerMatrix)
                    canvas.drawRect(dst, shimmer)
                    loadingAny = true
                }
            }
            // Older-zoom tiles first, so the current ones land on top.
            for (pass in 0..1) for (t in tiles.values) {
                if ((t.zoom == tileZoom) != (pass == 1) || t.page !in first..last) continue
                val b = t.bitmap ?: continue
                dst.set(t.rect.left * zoom - sx, t.rect.top * zoom - sy, t.rect.right * zoom - sx, t.rect.bottom * zoom - sy)
                canvas.drawBitmap(b, null, dst, paint)
            }
            for (i in first..last) {
                matchesByPage[i]?.forEach { k -> drawRects(canvas, i, matches[k].rects, if (k == current) currentPaint else matchPaint) }
                selection?.takeIf { it.page == i }?.let { drawRects(canvas, i, it.rects, selectPaint) }
            }
            onPageChanged(pageAt((sy + height / 2f) / zoom))
            ensure(first, last)
            if (loadingAny) postInvalidateOnAnimation()
        }

        private fun pageRect(i: Int, out: RectF) {
            val left = px[i] * zoom - sx
            val top = py[i] * zoom - sy
            out.set(left, top, left + pw[i] * zoom, top + pageH(i) * zoom)
        }

        private fun drawRects(canvas: Canvas, i: Int, rects: List<RectF>, p: Paint) {
            val s = pw[i] * zoom
            val left = px[i] * zoom - sx
            val top = py[i] * zoom - sy
            for (r in rects) canvas.drawRect(left + r.left * s, top + r.top * s, left + r.right * s, top + r.bottom * s, p)
        }

        // Renders what's on screen first, then two pages ahead in the scroll direction and one behind, plus where a
        // fling will land; frees everything else.
        private fun ensure(first: Int, last: Int) {
            val n = px.size
            val ahead = if (lastDy >= 0) 2 else 1
            val behind = if (lastDy >= 0) 1 else 2
            val order = ArrayList<Int>()
            for (i in first..last) order += i
            for (k in 1..max(ahead, behind)) {
                if (lastDy >= 0) {
                    if (k <= ahead && last + k < n) order += last + k
                    if (k <= behind && first - k >= 0) order += first - k
                } else {
                    if (k <= ahead && first - k >= 0) order += first - k
                    if (k <= behind && last + k < n) order += last + k
                }
            }
            if (!scroller.isFinished) {
                val fy = scroller.finalY.toFloat()
                val f = rowStart(pageAt(fy / zoom))
                val l = pageAt((fy + height) / zoom)
                for (i in f..l) if (i !in order) order += i
            }
            val want = order.toSet()
            wanted = want
            bitmaps.keys.filter { it !in want }.forEach { bitmaps.remove(it)?.let(::release) }
            links.keys.retainAll(want)
            // A render already queued for a page stays queued; it checks `wanted` when it runs, so requesting again
            // would only stack duplicates during a fling.
            for (i in order) if (i !in bitmaps && requested.add(i)) request(i)

            if (zoom <= 1.05f) {
                if (tiles.isNotEmpty()) dropTiles()
            } else if (!touching && !scaler.isInProgress && scroller.isFinished) {
                val zk = (zoom * 1000).roundToInt()
                if (zk != tileZoom || sx != tileX || sy != tileY) {
                    tileZoom = zk
                    tileX = sx
                    tileY = sy
                    requestTiles(first, last)
                }
            }
        }

        private fun request(i: Int) {
            val w = max(1, min(pw[i], sqrt(MAX_BASE_PIXELS / ratios[i])).roundToInt())
            val g = gen
            val withLinks = i !in links
            val done = { if (g == gen) requested.remove(i) }
            bg.post {
                val d = pdf
                if (g != gen || i !in wanted || d == null) {
                    main.post { done() }
                    return@post
                }
                try {
                    d.openPage(i).use { p ->
                        val r = ratio(p)
                        val bmp = obtain(w, max(1, (w * r).roundToInt()))
                        render(p, bmp, w.toFloat())
                        val found = if (withLinks) pageLinks(p, r) else null
                        main.post {
                            done()
                            if (g == gen && i in wanted) {
                                // A page measured here before the background pass reached it.
                                setRatios(i, floatArrayOf(r))
                                bitmaps.put(i, bmp)?.let(::release)
                                if (found != null) links[i] = found
                                invalidate()
                            } else {
                                release(bmp)
                            }
                        }
                    }
                } catch (e: Exception) {
                    main.post { done() }
                } catch (e: OutOfMemoryError) {
                    main.post { done() }
                }
            }
        }

        // Page bitmaps are mostly the same size; reusing them avoids a fresh multi-megabyte allocation per page.
        private val pool = ArrayList<Bitmap>()

        private fun obtain(w: Int, h: Int): Bitmap {
            synchronized(pool) {
                val k = pool.indexOfFirst { it.width == w && it.height == h }
                if (k >= 0) return pool.removeAt(k)
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }

        private fun release(b: Bitmap) {
            synchronized(pool) {
                if (pool.size < POOL_SIZE) {
                    pool.add(b)
                    return
                }
            }
            b.recycle()
        }

        // Render thread: the page's link annotations plus web addresses written in its text, in page units.
        private fun pageLinks(p: PdfPage, r: Float): List<Link> {
            val h = (UNIT * r).roundToInt()
            fun unit(rect: RectF): RectF {
                val d: Rect = p.mapRectToDevice(0, 0, UNIT, h, 0, rect)
                return RectF(min(d.left, d.right) / UNIT.toFloat(), min(d.top, d.bottom) / UNIT.toFloat(),
                    max(d.left, d.right) / UNIT.toFloat(), max(d.top, d.bottom) / UNIT.toFloat())
            }
            val out = ArrayList<Link>()
            try {
                for (l in p.getPageLinks()) out += Link(unit(l.bounds), l.destPageIdx, l.uri)
                p.openTextPage().use { t ->
                    t.loadWebLink()?.use { w ->
                        for (k in 0 until w.countWebLinks()) {
                            val uri = w.getURL(k, 2048) ?: continue
                            for (j in 0 until w.countRects(k)) out += Link(unit(w.getRect(k, j)), null, uri)
                        }
                    }
                }
            } catch (e: Exception) {
            }
            return out
        }

        // Tiles on a grid fixed in zoomed document space, so panning at one zoom reuses the ones already drawn.
        private fun requestTiles(first: Int, last: Int) {
            val z = tileZoom / 1000f
            val margin = TILE / 2f
            val vl = sx - margin
            val vt = sy - margin
            val vr = sx + width + margin
            val vb = sy + height + margin
            val cx = sx + width / 2f
            val cy = sy + height / 2f
            val fresh = ArrayList<Tile>()
            val keys = HashSet<String>()
            for (i in first..last) {
                val l = px[i] * z
                val t = py[i] * z
                val r = (px[i] + pw[i]) * z
                val b = (py[i] + pageH(i)) * z
                val x0 = floor(max(l, vl) / TILE).toInt()
                val x1 = floor((min(r, vr) - 1) / TILE).toInt()
                val y0 = floor(max(t, vt) / TILE).toInt()
                val y1 = floor((min(b, vb) - 1) / TILE).toInt()
                for (ty in y0..y1) for (tx in x0..x1) {
                    val key = "$i:$tileZoom:$tx:$ty"
                    keys += key
                    if (key in tiles) continue
                    val rl = max(l, tx * TILE.toFloat())
                    val rt = max(t, ty * TILE.toFloat())
                    val rr = min(r, (tx + 1) * TILE.toFloat())
                    val rb = min(b, (ty + 1) * TILE.toFloat())
                    if (rr - rl < 1 || rb - rt < 1) continue
                    val tile = Tile(i, tileZoom, RectF(rl / z, rt / z, rr / z, rb / z), rl - l, rt - t, pw[i] * z, key)
                    tiles[key] = tile
                    fresh += tile
                }
            }
            // Current-zoom tiles that have scrolled away go now; other zooms stay until these cover the screen.
            tiles.values.removeAll { (it.zoom == tileZoom && it.key !in keys).also { gone -> if (gone) it.bitmap?.recycle() } }
            wantedTiles = keys
            fresh.sortBy { hypot(it.rect.centerX() * z - cx, it.rect.centerY() * z - cy) }
            val g = gen
            for (tile in fresh) {
                val w = max(1, (tile.rect.width() * z).roundToInt())
                val h = max(1, (tile.rect.height() * z).roundToInt())
                bg.post {
                    if (g != gen || tile.key !in wantedTiles) return@post
                    val d = pdf ?: return@post
                    try {
                        d.openPage(tile.page).use { p ->
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            render(p, bmp, tile.pageWidth, tile.dx, tile.dy)
                            main.post {
                                if (g == gen && tiles[tile.key] === tile) {
                                    tile.bitmap = bmp
                                    if (tiles.values.none { it.zoom == tileZoom && it.bitmap == null }) {
                                        tiles.values.removeAll { (it.zoom != tileZoom).also { old -> if (old) it.bitmap?.recycle() } }
                                    }
                                    invalidate()
                                } else {
                                    bmp.recycle()
                                }
                            }
                        }
                    } catch (e: Exception) {
                    } catch (e: OutOfMemoryError) {
                    }
                }
            }
        }

        private fun dropTiles() {
            tiles.values.forEach { it.bitmap?.recycle() }
            tiles.clear()
            wantedTiles = emptySet()
            tileZoom = 0
        }

        fun dropAll() {
            gen++
            bitmaps.values.forEach { it.recycle() }
            bitmaps.clear()
            synchronized(pool) {
                pool.forEach { it.recycle() }
                pool.clear()
            }
            requested.clear()
            dropTiles()
            invalidate()
        }
    }

    private class Link(val rect: RectF, val page: Int?, val uri: String?)

    private class Match(val page: Int, val rects: List<RectF>)

    private class Selection(val page: Int, val from: Int, val to: Int, val anchor: Int, val rects: List<RectF>)

    private class Tile(
        val page: Int,
        val zoom: Int,
        val rect: RectF,
        val dx: Float,
        val dy: Float,
        val pageWidth: Float,
        val key: String,
    ) {
        var bitmap: Bitmap? = null
    }

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
        private const val KEY_MARKS = "marks:"
        private const val KEY_SPREAD = "spread"
        private const val KEY_AWAKE = "awake"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MAX_ZOOM = 4f
        // ~16 MB per page at fit width; zoomed detail comes from tiles.
        private const val MAX_BASE_PIXELS = 4e6f
        private const val TILE = 512
        private const val POOL_SIZE = 3
        private const val THUMB_DP = 120
        private const val MEASURE_BATCH = 32
        // Device size used to turn page coordinates into page units.
        private const val UNIT = 10000
        private val DESCRIBE = Any()

        private val THEME_NAMES = listOf("Light", "Sepia", "Dark")

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

        private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
    }
}
