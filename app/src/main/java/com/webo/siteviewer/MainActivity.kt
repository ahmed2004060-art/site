package com.webo.siteviewer

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : BaseActivity() {

    private lateinit var adapter: RowAdapter<SiteRow>
    private lateinit var input: EditText
    private lateinit var btnWhole: TextView
    private lateinit var btnPage: TextView
    private var whole = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = setup("مستعرض المواقع")

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.setPadding(dp(16), dp(18), dp(16), dp(8))
        body.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val title = tv("أدخل رابط الموقع أو الصفحة", 20f, C.INK, true)
        title.gravity = Gravity.CENTER
        top.addView(title, lp(MATCH, WRAP, 0f, 0, 0, 0, 12))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = shape(Color.WHITE, 14, C.BLUE, 2)
        row.setPadding(dp(8), dp(4), dp(8), dp(4))
        top.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        input = EditText(this)
        input.hint = "https://"
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.textDirection = View.TEXT_DIRECTION_LTR
        input.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        input.setBackgroundColor(Color.TRANSPARENT)
        input.textSize = 17f
        row.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))

        val paste = tv("لصق", 16f, C.INK, true)
        paste.setPadding(dp(14), dp(10), dp(14), dp(10))
        paste.background = shape(C.FIELD, 10)
        paste.setOnClickListener { pasteLink() }
        row.addView(paste, LinearLayout.LayoutParams(WRAP, WRAP))

        val scopeRow = LinearLayout(this)
        scopeRow.orientation = LinearLayout.HORIZONTAL
        top.addView(scopeRow, lp(MATCH, WRAP, 0f, 0, 12, 0, 0))
        btnWhole = tv("الموقع كله", 16f, Color.WHITE, true)
        btnPage = tv("هذه الصفحة فقط", 16f, C.MUTED, false)
        for (b in listOf(btnWhole, btnPage)) {
            b.gravity = Gravity.CENTER
            b.setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        btnWhole.setOnClickListener { setScope(true) }
        btnPage.setOnClickListener { setScope(false) }
        scopeRow.addView(btnWhole, lp(0, WRAP, 1f, 0, 0, 5, 0))
        scopeRow.addView(btnPage, lp(0, WRAP, 1f, 5, 0, 0, 0))
        setScope(true)

        top.addView(button("ابدأ") { startCapture() }, lp(MATCH, WRAP, 0f, 0, 14, 0, 0))

        val lbl = tv("المواقع السابقة", 18f, C.INK, true)
        top.addView(lbl, lp(MATCH, WRAP, 0f, 0, 20, 0, 0))

        val list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        adapter = RowAdapter(emptyList<SiteRow>()) { s -> siteRow(s) }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val s = adapter.items[pos]
            val i = Intent(this, BranchesActivity::class.java)
            i.putExtra("site", s.id)
            startActivity(i)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(adapter.items[pos])
            true
        }
        body.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    override fun onResume() {
        super.onResume()
        adapter.items = Db.get(this).listSites()
        adapter.notifyDataSetChanged()
    }

    private fun setScope(w: Boolean) {
        whole = w
        btnWhole.background = if (w) shape(C.BLUE, 12) else shape(Color.WHITE, 12, C.LINE, 1)
        btnPage.background = if (!w) shape(C.BLUE, 12) else shape(Color.WHITE, 12, C.LINE, 1)
        btnWhole.setTextColor(if (w) Color.WHITE else C.MUTED)
        btnPage.setTextColor(if (!w) Color.WHITE else C.MUTED)
    }

    private fun pasteLink() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
            input.setText(t.trim())
            input.setSelection(input.text.length)
        } else {
            Toast.makeText(this, "لا يوجد شيء منسوخ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCapture() {
        var u = input.text.toString().trim()
        if (u.isEmpty()) {
            Toast.makeText(this, "أدخل الرابط أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        if (!u.contains("://")) u = "https://$u"
        val hu = u.toHttpUrlOrNull()
        if (hu == null) {
            Toast.makeText(this, "الرابط غير صحيح", Toast.LENGTH_SHORT).show()
            return
        }
        val id = Db.get(this).createSite(hu.host, u, if (whole) 1 else 0)
        val i = Intent(this, CaptureActivity::class.java)
        i.putExtra("site", id)
        startActivity(i)
    }

    private fun confirmDelete(s: SiteRow) {
        AlertDialog.Builder(this)
            .setMessage("حذف " + s.host + " وكل ملفاته؟")
            .setPositiveButton("حذف") { _, _ ->
                Engine.pause(s.id)
                Db.get(this).deleteSite(s.id)
                java.io.File(filesDir, "sites/" + s.id).deleteRecursively()
                onResume()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun siteRow(s: SiteRow): View {
        val c = card()
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val name = tv(s.host, 19f, C.INK, true)
        name.textDirection = View.TEXT_DIRECTION_LTR
        top.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
        val running = Engine.isRunning(s.id)
        val st = when {
            running -> pill("جارٍ", C.BLUE, C.BLUE_BG)
            s.pending > 0 -> pill("متوقف", C.AMBER, C.AMBER_BG)
            else -> pill("مكتمل", C.GREEN, C.GREEN_BG)
        }
        top.addView(st, LinearLayout.LayoutParams(WRAP, WRAP))
        c.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(tv(s.files.toString() + " ملف", 14f, C.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        return listRow(c)
    }
}
