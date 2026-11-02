package com.webo.siteviewer

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object WebCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        try {
            val cm = CookieManager.getInstance()
            for (c in cookies) cm.setCookie(url.toString(), c.toString())
        } catch (e: Exception) {
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        try {
            val s = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            val out = ArrayList<Cookie>()
            for (part in s.split(";")) {
                val c = Cookie.parse(url, part.trim())
                if (c != null) out.add(c)
            }
            return out
        } catch (e: Exception) {
            return emptyList()
        }
    }
}

class Item(val url: String, val depth: Int)

class Run(val siteId: Long, val host: String, val root: String, val scope: Int, val startUrl: String) {
    val queue = ConcurrentLinkedQueue<Item>()
    val seen = ConcurrentHashMap.newKeySet<String>()
    val active = AtomicInteger(0)
    val errors = AtomicInteger(0)
    val pages = AtomicInteger(0)

    @Volatile
    var running = false

    @Volatile
    var note = ""
    var job: Job? = null
}

object Engine {
    private lateinit var appCtx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = ConcurrentHashMap<Long, Run>()

    @Volatile
    var ua: String = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private const val MAX_FILE = 400L * 1024 * 1024
    private const val MAX_URLS = 8000
    private const val WORKERS = 6

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebCookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    // ---------- التحكم ----------
    fun isRunning(siteId: Long): Boolean = runs[siteId]?.running == true
    fun note(siteId: Long): String = runs[siteId]?.note ?: ""

    fun pause(siteId: Long) {
        val r = runs[siteId] ?: return
        r.running = false
        r.job?.cancel()
    }

    fun start(siteId: Long) {
        val db = Db.get(appCtx)
        val site = db.getSite(siteId) ?: return
        val old = runs[siteId]
        if (old != null && old.running) return

        var host = site.host.lowercase()
        try {
            val h = URI(site.url).host
            if (h != null) host = h.lowercase()
        } catch (e: Exception) {
        }
        val run = Run(siteId, host, host.removePrefix("www."), site.scope, site.url)
        runs[siteId] = run
        for (u in db.knownUrls(siteId)) run.seen.add(u)
        for (q in db.pendingQueue(siteId)) run.queue.add(Item(q.first, q.second))
        if (!run.seen.contains(site.url)) enqueue(run, site.url, 0)
        run.running = true
        db.setDone(siteId, false)

        run.job = scope.launch {
            val ws = ArrayList<Job>()
            for (i in 1..WORKERS) ws.add(launch { worker(run) })
            ws.joinAll()
            if (run.running) {
                run.running = false
                if (run.queue.isEmpty()) db.setDone(siteId, true)
            }
        }
    }

    // يُستدعى من WebView عند كل طلب
    fun observe(siteId: Long, url: String) {
        val r = runs[siteId] ?: return
        if (!r.running) return
        enqueue(r, url, 1)
    }

    // ---------- العمال ----------
    private suspend fun worker(run: Run) {
        while (run.running) {
            val item = run.queue.poll()
            if (item == null) {
                if (run.active.get() == 0) break
                delay(250)
                continue
            }
            run.active.incrementAndGet()
            try {
                process(run, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                run.errors.incrementAndGet()
                Db.get(appCtx).markQueue(run.siteId, item.url, 2)
            } finally {
                run.active.decrementAndGet()
            }
        }
    }

    private fun enqueue(run: Run, raw: String, depth: Int): Boolean {
        val url = clean(raw) ?: return false
        if (run.seen.size >= MAX_URLS) return false
        if (!run.seen.add(url)) return false
        Db.get(appCtx).enqueue(run.siteId, url, depth)
        run.queue.add(Item(url, depth))
        return true
    }

    private fun clean(u: String): String? {
        val s = u.trim().substringBefore('#')
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return null
        if (s.length > 2000) return null
        return s.replace(" ", "%20")
    }

    private fun resolve(base: String, ref: String): String? {
        val r = ref.trim().replace("&amp;", "&")
        if (r.isEmpty()) return null
        val low = r.lowercase()
        if (low.startsWith("data:") || low.startsWith("javascript:") || low.startsWith("mailto:") ||
            low.startsWith("tel:") || low.startsWith("blob:") || low.startsWith("about:")
        ) return null
        return try {
            clean(URI(base).resolve(r.replace(" ", "%20")).toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun nameOf(url: String): String {
        val noQuery = url.substringBefore('?')
        val rest = noQuery.substringAfter("://")
        val p = rest.substringAfter('/', "")
        var seg = p.substringAfterLast('/')
        if (seg.isEmpty()) return rest.substringBefore('/') + ".html"
        try {
            seg = URLDecoder.decode(seg, "UTF-8")
        } catch (e: Exception) {
        }
        return seg
    }

    private fun pathOf(url: String): String {
        val rest = url.substringBefore('?').substringAfter("://")
        return "/" + rest.substringAfter('/', "")
    }

    private fun sameSite(run: Run, u: String): Boolean {
        val h = try {
            URI(u).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        return h == run.host || h == run.root || h.endsWith("." + run.root)
    }

    private fun readText(f: File, limit: Int): String {
        FileInputStream(f).use { input ->
            val n = minOf(f.length(), limit.toLong()).toInt()
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(b, off, n - off)
                if (r < 0) break
                off += r
            }
            return String(b, 0, off, Charsets.UTF_8)
        }
    }

    // ---------- المعالجة ----------
    private fun process(run: Run, item: Item) {
        val db = Db.get(appCtx)
        val hu = item.url.toHttpUrlOrNull()
        if (hu == null) {
            run.errors.incrementAndGet()
            db.markQueue(run.siteId, item.url, 2)
            return
        }
        val req = Request.Builder().url(hu)
            .header("User-Agent", ua)
            .header("Referer", run.startUrl)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                run.errors.incrementAndGet()
                db.markQueue(run.siteId, item.url, 2)
                return
            }
            val mime = resp.header("Content-Type") ?: ""
            val tmp = File(appCtx.cacheDir, "dl_" + System.nanoTime())
            val md = MessageDigest.getInstance("SHA-256")
            val head = ByteArrayOutputStream()
            var total = 0L
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        if (head.size() < 65536) head.write(buf, 0, minOf(n, 65536 - head.size()))
                        total += n
                        if (total > MAX_FILE) break
                    }
                }
            }
            register(run, item, tmp, head.toByteArray(), md.digest(), total, mime, item.url, null)
        }
        db.markQueue(run.siteId, item.url, 1)
    }

    private fun register(
        run: Run, item: Item, tmp: File, head: ByteArray, digest: ByteArray, size: Long,
        mime: String, url: String, nameOverride: String?
    ) {
        val db = Db.get(appCtx)
        val sha = digest.joinToString("") { String.format("%02x", it) }
        val dir = File(appCtx.filesDir, "sites/${run.siteId}/blobs")
        dir.mkdirs()
        val dest = File(dir, sha)
        if (dest.exists()) {
            tmp.delete()
        } else if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, true)
            tmp.delete()
        }

        val name = nameOverride ?: nameOf(url)
        val path = pathOf(url)
        val kind = Classifier.classify(head, name, mime, path)
        val ent = Classifier.entropy(head)
        var category = kind.category
        var format = kind.format

        if (category == "unknown" && head.size >= 256 && ent > 7.5) {
            category = "closed"
            format = "مشفّر أو مضغوط"
        }

        var hlsText: String? = null
        if (format == "HLS") {
            val t = readText(dest, 2_000_000)
            if (Regex("#EXT-X-KEY:[^\\n]*METHOD=(AES-128|SAMPLE-AES)").containsMatchIn(t)) {
                category = "closed"
                format = "HLS مشفّر"
            } else {
                hlsText = t
            }
        }
        if (format == "DASH") {
            val t = readText(dest, 2_000_000)
            if (t.contains("ContentProtection")) {
                category = "closed"
                format = "DASH محمي"
            }
        }

        val fid = db.insertFile(run.siteId, url, name, path, mime, size, sha, category, format, ent)

        if (format == "HTML") run.pages.incrementAndGet()

        val textual = (category == "text" || category == "trans" || category == "code") && format != "PDF"
        if (textual && size <= 2_000_000 && fid > 0) {
            val t = readText(dest, 2_000_000)
            db.saveText(fid, run.siteId, t)
            extractLinks(run, item, t, url, format)
        }

        if (hlsText != null) handleHls(run, item, url, hlsText)
    }

    // ---------- استخراج الروابط ----------
    private val reA = Regex("<a\\s[^>]*?href\\s*=\\s*[\"']([^\"'#][^\"']*)", RegexOption.IGNORE_CASE)
    private val reAttr = Regex("(?:src|data-src|data-lazy-src|data-original|poster|href|data-url)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val reSrcset = Regex("srcset\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val reUrlFn = Regex("url\\(\\s*[\"']?([^\"')\\s]+)")
    private val reAbs = Regex("https?://[^\\s\"'<>\\\\)\\]},;]+")
    private val reRel = Regex(
        "[\"'](/[A-Za-z0-9_\\-./%]+\\.(?:png|jpe?g|gif|webp|svg|ico|bmp|avif|mp4|webm|m3u8|mpd|ts|m4s|mp3|ogg|wav|glb|gltf|obj|stl|fbx|bin|woff2?|ttf|otf|json|js|css|wasm|ydd|yft|ytd|zip|rpf|txt|xml|vtt|srt))[\"']"
    )
    private val assetExt = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif", "mp4", "webm", "m3u8", "mpd", "ts", "m4s",
        "mp3", "ogg", "wav", "glb", "gltf", "obj", "stl", "fbx", "bin", "woff", "woff2", "ttf", "otf", "json", "js",
        "css", "wasm", "ydd", "yft", "ytd", "zip", "rpf", "txt", "xml", "vtt", "srt"
    )

    private fun isAsset(u: String): Boolean {
        val p = u.substringBefore('?').substringAfter("://").substringAfter('/', "")
        return Classifier.extOf(p) in assetExt
    }

    private fun extractLinks(run: Run, item: Item, text: String, base: String, format: String) {
        if (item.depth >= 6) return
        val pageLinks = HashSet<String>()
        if (format == "HTML") {
            for (m in reA.findAll(text)) {
                val u = resolve(base, m.groupValues[1])
                if (u != null) pageLinks.add(u)
            }
        }
        val strong = HashSet<String>()
        val weak = HashSet<String>()
        for (m in reAttr.findAll(text)) {
            val u = resolve(base, m.groupValues[1])
            if (u != null) strong.add(u)
        }
        for (m in reSrcset.findAll(text)) {
            for (part in m.groupValues[1].split(",")) {
                val first = part.trim().substringBefore(' ')
                val u = resolve(base, first)
                if (u != null) strong.add(u)
            }
        }
        for (m in reUrlFn.findAll(text)) {
            val u = resolve(base, m.groupValues[1])
            if (u != null) strong.add(u)
        }
        for (m in reAbs.findAll(text)) {
            val u = resolve(base, m.value)
            if (u != null) weak.add(u)
        }
        for (m in reRel.findAll(text)) {
            val u = resolve(base, m.groupValues[1])
            if (u != null) weak.add(u)
        }
        strong.removeAll(pageLinks)
        weak.removeAll(pageLinks)
        for (u in strong) enqueue(run, u, item.depth + 1)
        for (u in weak) if (isAsset(u)) enqueue(run, u, item.depth + 1)
        if (run.scope == 1 && item.depth < 4) {
            for (u in pageLinks) {
                if (run.pages.get() >= 400) break
                if (sameSite(run, u)) enqueue(run, u, item.depth + 1)
            }
        }
    }

    // ---------- HLS ----------
    private fun handleHls(run: Run, item: Item, url: String, t: String) {
        if (t.contains("#EXT-X-STREAM-INF")) {
            var best = -1L
            var bestUri: String? = null
            val lines = t.lines()
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    if (j < lines.size && bw >= best) {
                        best = bw
                        bestUri = lines[j].trim()
                    }
                }
            }
            val u = if (bestUri != null) resolve(url, bestUri) else null
            if (u != null) enqueue(run, u, item.depth + 1)
        } else if (t.contains("#EXTINF")) {
            assembleHls(run, item, url, t)
        }
    }

    private fun copyUrl(run: Run, url: String, out: OutputStream, md: MessageDigest, head: ByteArrayOutputStream): Long {
        val hu = url.toHttpUrlOrNull() ?: return 0L
        val req = Request.Builder().url(hu).header("User-Agent", ua).header("Referer", run.startUrl).build()
        var n = 0L
        client.newCall(req).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) return 0L
            body.byteStream().use { input ->
                val buf = ByteArray(65536)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    md.update(buf, 0, r)
                    if (head.size() < 65536) head.write(buf, 0, minOf(r, 65536 - head.size()))
                    n += r
                }
            }
        }
        return n
    }

    private fun assembleHls(run: Run, item: Item, playlistUrl: String, t: String) {
        var mapUrl: String? = null
        val segs = ArrayList<String>()
        for (raw in t.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXT-X-MAP")) {
                val m = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                if (m != null) mapUrl = resolve(playlistUrl, m)
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val u = resolve(playlistUrl, l)
                if (u != null) segs.add(u)
            }
        }
        if (segs.isEmpty() || segs.size > 6000) return

        val mu: String? = mapUrl
        val tmp = File(appCtx.cacheDir, "hls_" + System.nanoTime())
        val md = MessageDigest.getInstance("SHA-256")
        val head = ByteArrayOutputStream()
        var total = 0L
        try {
            FileOutputStream(tmp).use { out ->
                if (mu != null) total += copyUrl(run, mu, out, md, head)
                for (i in segs.indices) {
                    if (!run.running) throw CancellationException("paused")
                    run.note = "تجميع فيديو: " + (i + 1) + " من " + segs.size
                    total += copyUrl(run, segs[i], out, md, head)
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            run.note = ""
            throw e
        }
        run.note = ""
        val base = nameOf(playlistUrl).substringBeforeLast('.')
        val name = base + (if (mu != null) ".mp4" else ".ts")
        register(run, item, tmp, head.toByteArray(), md.digest(), total, "", "$playlistUrl#assembled", name)
    }
}
