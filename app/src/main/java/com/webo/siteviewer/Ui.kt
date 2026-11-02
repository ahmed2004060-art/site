package com.webo.siteviewer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val BLUE = Color.parseColor("#2F6FDD")
    val DARK = Color.parseColor("#1F2430")
    val BG = Color.parseColor("#F5F6F8")
    val INK = Color.parseColor("#1F2430")
    val MUTED = Color.parseColor("#6B7280")
    val MUTED2 = Color.parseColor("#AAB2C0")
    val LINE = Color.parseColor("#E5E7EB")
    val LINE2 = Color.parseColor("#9AA1AD")
    val GREEN = Color.parseColor("#1D9E75")
    val GREEN_BG = Color.parseColor("#E3F5ED")
    val AMBER = Color.parseColor("#B7791F")
    val AMBER_BG = Color.parseColor("#FDF3DC")
    val RED = Color.parseColor("#C0392B")
    val RED_BG = Color.parseColor("#FCEBEB")
    val BLUE_BG = Color.parseColor("#E8F0FC")
    val FIELD = Color.parseColor("#F1F1EE")
}

class Cat(val key: String, val label: String, val fg: Int, val bg: Int)

object Cats {
    private fun p(s: String) = Color.parseColor(s)
    val all = listOf(
        Cat("text", "نصوص", p("#5F5E5A"), p("#EFEEE9")),
        Cat("trans", "ترجمات", p("#7C4DCC"), p("#F1EAFB")),
        Cat("image", "صور", p("#2F6FDD"), p("#E8F0FC")),
        Cat("video", "فيديوهات", p("#D85A30"), p("#FDEEE8")),
        Cat("audio", "صوتيات", p("#B7791F"), p("#FDF3DC")),
        Cat("model", "مجسمات", p("#1D9E75"), p("#E3F5ED")),
        Cat("font", "خطوط", p("#5F5E5A"), p("#EFEEE9")),
        Cat("code", "أكواد", p("#5F5E5A"), p("#EFEEE9")),
        Cat("archive", "أرشيفات", p("#6B7280"), p("#ECEEF1")),
        Cat("unknown", "غير معروف", p("#6B7280"), p("#ECEEF1")),
        Cat("closed", "مغلق", p("#C0392B"), p("#FCEBEB"))
    )

    fun of(key: String): Cat {
        for (c in all) if (c.key == key) return c
        return all[9]
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.shape(fill: Int, radius: Int = 12, stroke: Int = 0, strokeW: Int = 1): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(fill)
    g.cornerRadius = dp(radius).toFloat()
    if (stroke != 0) g.setStroke(dp(strokeW).coerceAtLeast(1), stroke)
    return g
}

fun Context.lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(dp(l), dp(t), dp(r), dp(b))
    return p
}

fun Context.tv(text: String, size: Float = 16f, color: Int = C.INK, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = size
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    return t
}

fun Context.pill(text: String, fg: Int, bg: Int): TextView {
    val t = tv(text, 12f, fg, true)
    t.setPadding(dp(10), dp(4), dp(10), dp(4))
    t.background = shape(bg, 14)
    t.gravity = Gravity.CENTER
    return t
}

fun Context.button(text: String, primary: Boolean = true, onClick: () -> Unit): TextView {
    val t = tv(text, 17f, if (primary) Color.WHITE else C.INK, true)
    t.gravity = Gravity.CENTER
    t.setPadding(dp(12), dp(14), dp(12), dp(14))
    t.background = if (primary) shape(C.BLUE, 14) else shape(Color.WHITE, 14, C.LINE2, 1)
    t.setOnClickListener { onClick() }
    return t
}

fun Context.card(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    l.setPadding(dp(14), dp(12), dp(14), dp(12))
    l.background = shape(Color.WHITE, 14, C.LINE, 1)
    return l
}

fun Context.listRow(inner: View): View {
    val w = LinearLayout(this)
    w.setPadding(dp(14), dp(5), dp(14), dp(5))
    w.addView(inner, LinearLayout.LayoutParams(MATCH, WRAP))
    return w
}

fun fmtSize(n: Long): String {
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

class RowAdapter<T : Any>(var items: List<T>, val build: (T) -> View) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = build(items[position])
}

abstract class BaseActivity : Activity() {
    fun setup(title: String, sub: String? = null): LinearLayout {
        window.statusBarColor = C.DARK
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setBackgroundColor(C.DARK)
        head.setPadding(dp(16), dp(14), dp(16), dp(14))
        val t = tv(title, 20f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        head.addView(t, lp(MATCH, WRAP))
        if (sub != null) {
            val s = tv(sub, 13f, C.MUTED2)
            s.gravity = Gravity.CENTER
            head.addView(s, lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
        }
        root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        return body
    }
}
