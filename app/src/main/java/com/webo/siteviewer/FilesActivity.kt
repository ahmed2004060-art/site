package com.webo.siteviewer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class FilesActivity : BaseActivity() {

    private var siteId = 0L
    private var cat: String? = null
    private var query: String? = null
    private var fmt: String? = null
    private lateinit var adapter: RowAdapter<FileRow>
    private lateinit var chips: LinearLayout
    private lateinit var countView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        siteId = intent.getLongExtra("site", 0L)
        cat = intent.getStringExtra("cat")
        query = intent.getStringExtra("q")
        val title = if (query != null) "نتائج البحث" else Cats.of(cat ?: "unknown").label
        val body = setup(title, if (query != null) query else null)

        if (query == null && cat != null) {
            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            chips = LinearLayout(this)
            chips.orientation = LinearLayout.HORIZONTAL
            chips.setPadding(dp(12), dp(10), dp(12), dp(4))
            hs.addView(chips)
            body.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        countView = tv("", 14f, C.MUTED)
        body.addView(countView, lp(MATCH, WRAP, 0f, 16, 6, 16, 4))

        val list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        adapter = RowAdapter(emptyList<FileRow>()) { f -> fileRow(f) }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val i = Intent(this, ViewerActivity::class.java)
            i.putExtra("file", adapter.items[pos].id)
            startActivity(i)
        }
        body.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        buildChips()
        load()
    }

    private fun buildChips() {
        val c = cat
        if (query != null || c == null) return
        chips.removeAllViews()
        val stats = Db.get(this).fmtStats(siteId, c)
        addChip("الكل", null, stats.sumOf { it.count })
        for (s in stats) addChip(s.format, s.format, s.count)
    }

    private fun addChip(label: String, value: String?, count: Int) {
        val active = (fmt == value)
        val t = tv("$label  $count", 14f, if (active) Color.WHITE else C.MUTED, true)
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        t.background = if (active) shape(C.BLUE, 20) else shape(Color.WHITE, 20, C.LINE, 1)
        t.setOnClickListener {
            fmt = value
            buildChips()
            load()
        }
        chips.addView(t, lp(WRAP, WRAP, 0f, 0, 0, 8, 0))
    }

    private fun load() {
        val db = Db.get(this)
        val q = query
        val list = if (q != null) db.search(siteId, q) else db.listFiles(siteId, cat, fmt)
        adapter.items = list
        adapter.notifyDataSetChanged()
        countView.text = list.size.toString() + " ملف"
    }

    private fun fileRow(f: FileRow): View {
        val c = card()
        val k = Cats.of(f.category)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val n = tv(f.name, 16f, C.INK, true)
        n.maxLines = 1
        n.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        n.textDirection = View.TEXT_DIRECTION_LTR
        top.addView(n, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(pill(f.format, k.fg, k.bg), lp(WRAP, WRAP, 0f, 8, 0, 0, 0))
        c.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(tv(fmtSize(f.size), 13f, C.MUTED), lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
        if (f.snippet != null) {
            val sn = tv(f.snippet.replace("\n", " "), 14f, C.INK)
            sn.maxLines = 2
            sn.setPadding(0, dp(6), 0, 0)
            c.addView(sn, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return listRow(c)
    }
}
