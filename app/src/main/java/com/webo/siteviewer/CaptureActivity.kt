package com.webo.siteviewer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

class CaptureActivity : BaseActivity() {

    private var siteId = 0L
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var vFiles: TextView
    private lateinit var vSpeed: TextView
    private lateinit var vSize: TextView
    private lateinit var vErr: TextView
    private lateinit var vNote: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnPause: TextView
    private lateinit var adapter: RowAdapter<FileRow>

    private var lastCount = 0
    private var lastTime = 0L

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        siteId = intent.getLongExtra("site", 0L)
        val db = Db.get(this)
        val site = db.getSite(siteId)
        if (site == null) {
            finish()
            return
        }
        val body = setup(site.host, "الالتقاط")

        // ---- المتصفح ----
        web = WebView(this)
        val st = web.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        Engine.ua = st.userAgentString
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && request.method == "GET") {
                    Engine.observe(siteId, request.url.toString())
                }
                return null
            }
        }
        body.addView(web, LinearLayout.LayoutParams(MATCH, dp(240)))

        // ---- العدادات ----
        val stats = LinearLayout(this)
        stats.orientation = LinearLayout.HORIZONTAL
        stats.setPadding(dp(12), dp(10), dp(12), 0)
        vFiles = statCard(stats, "ملف", C.INK)
        vSpeed = statCard(stats, "ملف/ث", C.BLUE)
        vSize = statCard(stats, "الحجم", C.INK)
        vErr = statCard(stats, "أخطاء", C.GREEN)
        body.addView(stats, LinearLayout.LayoutParams(MATCH, WRAP))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        body.addView(bar, lp(MATCH, WRAP, 0f, 14, 8, 14, 0))

        vNote = tv("", 13f, C.MUTED)
        vNote.gravity = Gravity.CENTER
        body.addView(vNote, lp(MATCH, WRAP, 0f, 14, 0, 14, 4))

        // ---- آخر الملفات ----
        val list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        adapter = RowAdapter(emptyList<FileRow>()) { f -> fileRow(f) }
        list.adapter = adapter
        body.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---- الأزرار ----
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.setPadding(dp(12), dp(8), dp(12), dp(12))
        btnPause = button("إيقاف مؤقت", false) { togglePause() }
        btns.addView(btnPause, lp(0, WRAP, 1f, 0, 0, 5, 0))
        btns.addView(button("الفروع") { openBranches() }, lp(0, WRAP, 1f, 5, 0, 0, 0))
        body.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP))

        Engine.start(siteId)
        web.loadUrl(site.url)
    }

    private fun statCard(parent: LinearLayout, label: String, color: Int): TextView {
        val c = card()
        c.gravity = Gravity.CENTER
        c.setPadding(dp(4), dp(10), dp(4), dp(10))
        val v = tv("0", 18f, color, true)
        v.gravity = Gravity.CENTER
        v.textDirection = View.TEXT_DIRECTION_LTR
        c.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
        val l = tv(label, 12f, C.MUTED)
        l.gravity = Gravity.CENTER
        c.addView(l, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.addView(c, lp(0, WRAP, 1f, 3, 0, 3, 0))
        return v
    }

    private fun fileRow(f: FileRow): View {
        val c = card()
        val cat = Cats.of(f.category)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val n = tv(f.name, 15f, C.INK, true)
        n.maxLines = 1
        n.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        n.textDirection = View.TEXT_DIRECTION_LTR
        top.addView(n, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(pill(cat.label, cat.fg, cat.bg), lp(WRAP, WRAP, 0f, 8, 0, 0, 0))
        c.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(tv(f.format + "  •  " + fmtSize(f.size), 12f, C.MUTED), lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
        return listRow(c)
    }

    private fun refresh() {
        val db = Db.get(this)
        val fs = db.fileStats(siteId)
        val q = db.queueCounts(siteId)
        val now = System.currentTimeMillis()
        if (lastTime != 0L && now > lastTime) {
            val sp = (fs.first - lastCount) * 1000.0 / (now - lastTime)
            vSpeed.text = String.format("%.1f", sp)
        }
        lastCount = fs.first
        lastTime = now
        vFiles.text = fs.first.toString()
        vSize.text = fmtSize(fs.second)
        vErr.text = q.third.toString()
        vErr.setTextColor(if (q.third > 0) C.RED else C.GREEN)
        val total = q.first + q.second + q.third
        bar.progress = if (total == 0) 0 else ((q.first + q.third) * 100L / total).toInt()
        vNote.text = Engine.note(siteId)
        adapter.items = db.recent(siteId, 40)
        adapter.notifyDataSetChanged()
        if (Engine.isRunning(siteId)) {
            btnPause.text = "إيقاف مؤقت"
        } else if (q.second > 0) {
            btnPause.text = "استئناف"
        } else {
            btnPause.text = "اكتمل"
        }
    }

    private fun togglePause() {
        if (Engine.isRunning(siteId)) {
            Engine.pause(siteId)
        } else {
            Engine.start(siteId)
        }
        refresh()
    }

    private fun openBranches() {
        val i = Intent(this, BranchesActivity::class.java)
        i.putExtra("site", siteId)
        startActivity(i)
    }

    override fun onResume() {
        super.onResume()
        if (this::web.isInitialized) {
            handler.post(tick)
            web.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::web.isInitialized) web.destroy()
    }
}
