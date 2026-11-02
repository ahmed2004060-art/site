package com.webo.siteviewer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class BranchesActivity : BaseActivity() {

    private var siteId = 0L
    private lateinit var adapter: RowAdapter<CatStat>
    private lateinit var distBar: LinearLayout
    private lateinit var summary: TextView
    private lateinit var btnResume: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        siteId = intent.getLongExtra("site", 0L)
        val site = Db.get(this).getSite(siteId)
        if (site == null) {
            finish()
            return
        }
        val body = setup("الفروع", site.host)

        val search = EditText(this)
        search.hint = "ابحث في كل الفروع"
        search.setSingleLine(true)
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setPadding(dp(14), dp(12), dp(14), dp(12))
        search.background = shape(Color.WHITE, 14, C.LINE, 1)
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = v.text.toString().trim()
                if (q.length < 2) {
                    Toast.makeText(this, "اكتب حرفين على الأقل", Toast.LENGTH_SHORT).show()
                } else {
                    val i = Intent(this, FilesActivity::class.java)
                    i.putExtra("site", siteId)
                    i.putExtra("q", q)
                    startActivity(i)
                }
                true
            } else {
                false
            }
        }
        body.addView(search, lp(MATCH, WRAP, 0f, 14, 12, 14, 0))

        summary = tv("", 14f, C.MUTED)
        body.addView(summary, lp(MATCH, WRAP, 0f, 16, 10, 16, 4))

        distBar = LinearLayout(this)
        distBar.orientation = LinearLayout.HORIZONTAL
        body.addView(distBar, lp(MATCH, dp(12), 0f, 14, 0, 14, 6))

        val list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        adapter = RowAdapter(emptyList<CatStat>()) { s -> catRow(s) }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val i = Intent(this, FilesActivity::class.java)
            i.putExtra("site", siteId)
            i.putExtra("cat", adapter.items[pos].key)
            startActivity(i)
        }
        body.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        btnResume = button("استئناف الالتقاط") {
            val i = Intent(this, CaptureActivity::class.java)
            i.putExtra("site", siteId)
            startActivity(i)
        }
        body.addView(btnResume, lp(MATCH, WRAP, 0f, 14, 6, 14, 12))
    }

    override fun onResume() {
        super.onResume()
        if (siteId == 0L) return
        val db = Db.get(this)
        val stats = db.catStats(siteId).sortedByDescending { it.count }
        adapter.items = stats
        adapter.notifyDataSetChanged()
        var total = 0
        var size = 0L
        for (s in stats) {
            total += s.count
            size += s.size
        }
        summary.text = if (total == 0) "لا توجد ملفات بعد" else "$total ملف  •  " + fmtSize(size)
        distBar.removeAllViews()
        for (s in stats) {
            val seg = View(this)
            seg.setBackgroundColor(Cats.of(s.key).fg)
            distBar.addView(seg, LinearLayout.LayoutParams(0, MATCH, s.count.toFloat()))
        }
        val q = db.queueCounts(siteId)
        btnResume.visibility = if (q.second > 0 || Engine.isRunning(siteId)) View.VISIBLE else View.GONE
    }

    private fun catRow(s: CatStat): View {
        val cat = Cats.of(s.key)
        val c = card()
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dot.background = shape(cat.fg, 8)
        r.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(cat.label, 18f, C.INK, true), LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(tv(fmtSize(s.size), 13f, C.MUTED), LinearLayout.LayoutParams(WRAP, WRAP))
        r.addView(col, lp(0, WRAP, 1f, 12, 0, 12, 0))
        val n = tv(s.count.toString(), 26f, cat.fg, true)
        n.textDirection = View.TEXT_DIRECTION_LTR
        r.addView(n, LinearLayout.LayoutParams(WRAP, WRAP))
        r.addView(tv("‹", 26f, C.LINE2), lp(WRAP, WRAP, 0f, 10, 0, 0, 0))
        c.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        return listRow(c)
    }
}
