package net.coursehunter.app

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import io.legere.pdfiumandroid.PdfiumSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloaded books: a cache of the few most recently read, plus the ones saved for offline reading. There is at
 * most one download per URL, shared by the page that prefetches a book and the reader that opens it.
 */
object Books {
    private const val KEEP_RECENT = 3
    private val KEEP_MS = TimeUnit.DAYS.toMillis(7)
    private const val CACHE_BYTES = 512L * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    internal val pool = Executors.newCachedThreadPool()
    internal val http by lazy { OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build() }

    private val downloads = ConcurrentHashMap<String, Download>()

    fun key(url: String) =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun cacheDir(context: Context) = File(context.cacheDir, "pdf")
    private fun savedDir(context: Context) = File(context.filesDir, "books")
    private fun cached(context: Context, url: String) = File(cacheDir(context), "${key(url)}.pdf")
    private fun saved(context: Context, url: String) = File(savedDir(context), "${key(url)}.pdf")

    /** A complete copy on the phone, if there is one. */
    fun local(context: Context, url: String): File? {
        saved(context, url).takeIf { it.exists() }?.let { return it }
        return cached(context, url).takeIf { it.exists() }?.apply { setLastModified(System.currentTimeMillis()) }
    }

    fun isSaved(context: Context, url: String) = saved(context, url).exists() || downloads[url]?.keep == true

    /** Saved books are never cleared; a book still downloading is moved there once it finishes. */
    fun setSaved(context: Context, url: String, keep: Boolean) {
        downloads[url]?.keep = keep
        val from = if (keep) cached(context, url) else saved(context, url)
        val to = if (keep) saved(context, url) else cached(context, url)
        if (!from.exists()) return
        to.parentFile?.mkdirs()
        from.renameTo(to)
        to.setLastModified(System.currentTimeMillis())
    }

    fun download(context: Context, url: String, userAgent: String): Download =
        downloads[url]?.takeIf { it.error == null || it.retry() } ?: Download(context.applicationContext, url, userAgent).also {
            downloads[url] = it
            it.start()
        }

    /** Starts fetching a book the user is likely to open, unless it would use mobile data. */
    fun prefetch(context: Context, url: String, userAgent: String) {
        if (local(context, url) != null || downloads.containsKey(url)) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null || cm.isActiveNetworkMetered) return
        download(context, url, userAgent)
    }

    internal fun finished(context: Context, d: Download, part: File): File {
        val to = if (d.keep) saved(context, d.url) else cached(context, d.url)
        to.parentFile?.mkdirs()
        if (!part.renameTo(to)) throw IOException("Couldn't save the book")
        downloads.remove(d.url)
        trim(context)
        return to
    }

    /** Keeps the few books read most recently in the last week, including unfinished downloads to resume. */
    fun trim(context: Context) {
        val active = downloads.keys.map(::key).toSet()
        val groups = cacheDir(context).listFiles()?.groupBy { it.name.substringBefore('.') } ?: return
        val now = System.currentTimeMillis()
        var used = 0L
        var kept = 0
        groups.entries.sortedByDescending { (_, files) -> files.maxOf { it.lastModified() } }.forEach { (key, files) ->
            if (key in active) return@forEach
            val size = files.sumOf { it.length() }
            val fresh = now - files.maxOf { it.lastModified() } < KEEP_MS
            if (fresh && kept < KEEP_RECENT && used + size <= CACHE_BYTES) {
                kept++
                used += size
            } else {
                files.forEach { it.delete() }
            }
        }
    }
}

/** Why a book couldn't be loaded, in words the reader can act on. */
enum class BookError(val message: String, val action: String) {
    OFFLINE("You're offline. Connect to the internet to download this book.", "Try again"),
    SLOW("The connection is too slow or keeps dropping.", "Try again"),
    LOGIN("Your session has expired. Sign in on the site, then open the book again.", "Open site"),
    MISSING("This book is no longer available on the site.", "Close"),
    STORAGE("Not enough free space on the phone to download this book.", "Try again"),
    SERVER("The site couldn't send the book right now.", "Try again"),
    INVALID("This file isn't a readable PDF.", "Close"),
}

/**
 * One book being downloaded into a sparse file, in 64 KB chunks. A sequential pass fetches the whole file and resumes
 * from the first missing chunk after a dropped connection, or after the app restarts. While it runs, the reader can
 * already open the book: [Source] blocks on the chunks it needs and fetches them out of order with range requests.
 */
class Download internal constructor(private val context: Context, val url: String, private val userAgent: String) {
    interface Listener {
        fun onProgress(received: Long, length: Long) {}
        fun onReady() {}
        fun onComplete(file: File) {}
        fun onFailed(error: BookError) {}
    }

    @Volatile var keep = false
    @Volatile var length = -1L
        private set
    // Server honours Range, so the book can be read before it's all here.
    @Volatile var ranges = false
        private set
    @Volatile var file: File? = null
        private set
    @Volatile var error: BookError? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val key = Books.key(url)
    private val part = File(Books.cacheDir(context), "$key.part")
    private val map = File(Books.cacheDir(context), "$key.map")
    private val lock = Object()
    private val have = BitSet()
    private val fetching = BitSet()
    private var channel: FileChannel? = null
    private var readers = 0
    private var running = false
    private val listeners = ArrayList<Listener>()

    // Main thread.
    fun listen(l: Listener) {
        listeners += l
        file?.let { l.onComplete(it) } ?: error?.let { l.onFailed(it) } ?: run {
            if (length > 0) {
                l.onProgress(received(), length)
                if (ranges) l.onReady()
            }
        }
    }

    fun unlisten(l: Listener) {
        listeners -= l
    }

    fun received() = synchronized(lock) { min(length, have.cardinality().toLong() * CHUNK) }

    internal fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        Books.pool.execute(::run)
    }

    /** Restarts after a failure; false if it can't be retried. */
    internal fun retry(): Boolean {
        if (error == BookError.MISSING || error == BookError.INVALID) return false
        error = null
        start()
        return true
    }

    private fun run() {
        var attempt = 0
        try {
            Books.cacheDir(context).mkdirs()
            restore()
            while (true) {
                val before = received()
                try {
                    if (pass()) break
                } catch (e: IOException) {
                    val err = classify(e)
                    if (err == BookError.LOGIN || err == BookError.MISSING || err == BookError.STORAGE) throw e
                    if (received() > before) attempt = 0
                    if (++attempt > RETRIES) throw e
                    save()
                    // Waits 1, 2, 4… seconds before resuming from the first missing chunk.
                    Thread.sleep(1000L shl (attempt - 1))
                }
            }
            val done = synchronized(lock) {
                channel?.force(false)
                Books.finished(context, this, part).also {
                    file = it
                    if (readers == 0) closeChannel()
                    lock.notifyAll()
                }
            }
            map.delete()
            main.post { listeners.toList().forEach { it.onComplete(done) } }
        } catch (e: Exception) {
            save()
            val err = classify(e)
            synchronized(lock) {
                error = err
                running = false
                lock.notifyAll()
            }
            main.post { listeners.toList().forEach { it.onFailed(err) } }
        }
    }

    // One request from the first missing chunk to the end. True once every chunk is here.
    private fun pass(): Boolean {
        val from = synchronized(lock) { have.nextClearBit(0) }
        if (length >= 0 && from.toLong() * CHUNK >= length) return true
        val offset = from.toLong() * CHUNK
        val builder = request()
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        http(builder.build()).use { r ->
            var at = 0L
            if (r.code == 206) {
                at = r.header("Content-Range")?.substringAfter(' ')?.substringBefore('-')?.toLongOrNull() ?: offset
            } else if (offset > 0) {
                // Range ignored: start over.
                synchronized(lock) { have.clear() }
            }
            init(r, at)
            val input = r.body!!.byteStream()
            val buf = ByteArray(CHUNK)
            var shown = 0L
            while (true) {
                var n = 0
                while (n < CHUNK) {
                    val k = input.read(buf, n, CHUNK - n)
                    if (k < 0) break
                    n += k
                }
                if (n == 0) break
                write(at, buf, n)
                at += n
                val now = SystemClock.uptimeMillis()
                if (now - shown > 200) {
                    shown = now
                    progress()
                }
                if (n < CHUNK) break
            }
            if (length < 0) {
                // No size given up front: the end of the stream is the end of the book.
                length = at
                synchronized(lock) { have.set(0, ((at + CHUNK - 1) / CHUNK).toInt()) }
            }
        }
        progress()
        return synchronized(lock) { have.nextClearBit(0).toLong() * CHUNK >= length }
    }

    // First response: learns the size and whether ranges work, and sizes the sparse file.
    private fun init(r: Response, at: Long) {
        val total = if (r.code == 206) {
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
        } else {
            r.body?.contentLength() ?: -1L
        }
        val supportsRanges = r.code == 206 || r.header("Accept-Ranges") == "bytes"
        synchronized(lock) {
            if (total > 0 && length != total) {
                if (length > 0) have.clear()
                length = total
            }
            ranges = supportsRanges && length > 0
            if (channel == null) channel = RandomAccessFile(part, "rw").channel
            if (at == 0L && length > 0 && part.length() != length) RandomAccessFile(part, "rw").use { it.setLength(length) }
        }
        if (ranges) main.post { listeners.toList().forEach { it.onReady() } }
    }

    private fun write(at: Long, buf: ByteArray, n: Int) {
        val ch = synchronized(lock) { channel } ?: throw IOException("closed")
        val bb = ByteBuffer.wrap(buf, 0, n)
        var pos = at
        while (bb.hasRemaining()) pos += ch.write(bb, pos)
        synchronized(lock) {
            val first = (at / CHUNK).toInt()
            val end = at + n
            // Only whole chunks, or the last one, count as here.
            val last = if (end >= length && length > 0) ((end - 1) / CHUNK).toInt() else (end / CHUNK).toInt() - 1
            if (last >= first) have.set(first, last + 1)
            fetching.clear(first, last + 1)
            lock.notifyAll()
        }
    }

    private fun progress() {
        val r = received()
        val l = length
        main.post { listeners.toList().forEach { it.onProgress(r, l) } }
    }

    // Fetches a few chunks around what the reader is waiting for, ahead of the sequential pass.
    private fun fetch(first: Int, last: Int) {
        val end = min((last + 1).toLong() + RANGE_CHUNKS, (length + CHUNK - 1) / CHUNK).toInt()
        var a = first
        while (a < end && (have[a] || fetching[a])) a++
        if (a >= end) return
        var b = a
        while (b < end && !have[b] && !fetching[b]) b++
        fetching.set(a, b)
        val from = a.toLong() * CHUNK
        val to = min(b.toLong() * CHUNK, length) - 1
        Books.pool.execute {
            try {
                http(request().header("Range", "bytes=$from-$to").build()).use { r ->
                    if (r.code != 206) throw IOException("No range")
                    val bytes = r.body!!.bytes()
                    write(from, bytes, bytes.size)
                }
            } catch (e: IOException) {
                synchronized(lock) {
                    fetching.clear(a, b)
                    lock.notifyAll()
                }
            }
        }
    }

    private fun request() = Request.Builder().url(url)
        .header("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
        .header("User-Agent", userAgent)
        .header("Referer", MainActivity.SITE_URL)

    private fun http(request: Request): Response {
        val r = Books.http.newCall(request).execute()
        if (r.isSuccessful && r.header("Content-Type")?.contains("html", true) != true) return r
        r.close()
        throw HttpError(if (r.isSuccessful) 401 else r.code)
    }

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun classify(e: Exception): BookError = when {
        e is HttpError && (e.code == 401 || e.code == 403) -> BookError.LOGIN
        e is HttpError && (e.code == 404 || e.code == 410) -> BookError.MISSING
        e is HttpError -> BookError.SERVER
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException -> BookError.OFFLINE
        e is SocketTimeoutException -> BookError.SLOW
        e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true -> BookError.STORAGE
        else -> BookError.SLOW
    }

    // Chunk map, so a download cut off by the app closing resumes instead of starting over.
    private fun restore() {
        if (!part.exists() || !map.exists()) return
        try {
            DataInputStream(map.inputStream().buffered()).use { s ->
                val len = s.readLong()
                val bits = BitSet.valueOf(ByteArray(s.readInt()).also { s.readFully(it) })
                if (len != part.length()) return
                synchronized(lock) {
                    length = len
                    have.or(bits)
                }
            }
        } catch (e: IOException) {
        }
    }

    private fun save() {
        try {
            val (len, bytes) = synchronized(lock) { length to have.toByteArray() }
            if (len <= 0 || file != null) return
            DataOutputStream(map.outputStream().buffered()).use {
                it.writeLong(len)
                it.writeInt(bytes.size)
                it.write(bytes)
            }
        } catch (e: IOException) {
        }
    }

    private fun closeChannel() {
        try {
            channel?.close()
        } catch (e: IOException) {
        }
        channel = null
    }

    /** Opens the book for PDFium while it downloads. Only valid once [ranges] is true. */
    fun source(): PdfiumSource = Source()

    private inner class Source : PdfiumSource {
        @Volatile private var closed = false
        private val ch: FileChannel

        init {
            synchronized(lock) {
                if (channel == null) channel = RandomAccessFile(file ?: part, "rw").channel
                ch = channel!!
                readers++
            }
        }

        override val length get() = this@Download.length

        override fun read(position: Long, buffer: ByteArray, size: Int): Int {
            if (size <= 0) return 0
            val first = (position / CHUNK).toInt()
            val last = ((position + size - 1) / CHUNK).toInt()
            synchronized(lock) {
                while (true) {
                    if (closed) throw IOException("closed")
                    if (file != null || have.nextClearBit(first) > last) break
                    if (error != null) throw IOException("failed")
                    fetch(have.nextClearBit(first), last)
                    lock.wait(500)
                }
            }
            val bb = ByteBuffer.wrap(buffer, 0, size)
            var pos = position
            while (bb.hasRemaining()) {
                val n = ch.read(bb, pos)
                if (n < 0) break
                pos += n
            }
            return size - bb.remaining()
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(lock) {
                readers--
                if (readers == 0 && file != null) closeChannel()
                lock.notifyAll()
            }
        }
    }

    private fun min(a: Long, b: Long) = if (a < b) a else b

    private companion object {
        const val CHUNK = 64 * 1024
        // Read-ahead for out-of-order fetches: 256 KB past what was asked for.
        const val RANGE_CHUNKS = 4
        const val RETRIES = 5
    }
}
