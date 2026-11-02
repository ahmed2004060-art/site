package com.webo.siteviewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.FileInputStream

class ViewerActivity : BaseActivity() {

    private var fileRow: FileRow? = null
    private var blob: File? = null
    private var player: MediaPlayer? = null
    private lateinit var content: LinearLayout

    private val imgFormats = setOf("PNG", "JPEG", "GIF", "WEBP", "BMP", "ICO", "AVIF", "HEIC")
    private val vidFormats = setOf("MP4", "WebM/MKV", "TS", "MOV", "AVI", "FLV")
    private val audFormats = setOf("MP3", "AAC", "OGG", "WAV", "FLAC", "M4A", "MKA")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fid = intent.getLongExtra("file", 0L)
        val f = Db.get(this).getFile(fid)
        if (f == null) {
            finish()
            return
        }
        fileRow = f
        val b = File(filesDir, "sites/" + f.siteId + "/blobs/" + f.sha)
        blob = b

        val body = setup(f.name, f.format)
        val scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14), dp(12), dp(14), dp(20))
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        body.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val viewable = isViewable(f)
        val status = when {
            f.category == "closed" -> "مغلق"
            viewable -> "مفتوح"
            else -> "معروف بلا قارئ"
        }

        // ---- بطاقة المعلومات ----
        val info = card()
        infoRow(info, "الصيغة المكتشفة", f.format, false)
        infoRow(info, "الفرع", Cats.of(f.category).label, false)
        infoRow(info, "الحجم", fmtSize(f.size), true)
        infoRow(info, "الحالة", status, false)
        infoRow(info, "عشوائية المحتوى", String.format("%.2f / 8", f.entropy), true)
        infoRow(info, "البصمة", f.sha.take(16), true)
        infoRow(info, "المصدر", f.url, true)
        content.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- العارض ----
        content.addView(tv("العرض", 18f, C.INK, true), lp(MATCH, WRAP, 0f, 2, 16, 0, 8))
        if (f.category == "closed") {
            content.addView(note("الملف مغلق: مشفّر أو محمي. محفوظ كما هو ولا يمكن فتحه."), LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            buildViewer(f, b)
        }

        // ---- بداية الملف ----
        if (f.category != "text" && f.category != "trans" && f.category != "code") {
            content.addView(tv("بداية الملف", 18f, C.INK, true), lp(MATCH, WRAP, 0f, 2, 16, 0, 8))
            val hex = tv(hexDump(b, 256), 11f, Color.parseColor("#9BE7C4"))
            hex.typeface = Typeface.MONOSPACE
            hex.textDirection = View.TEXT_DIRECTION_LTR
            hex.gravity = Gravity.LEFT
            hex.setPadding(dp(12), dp(10), dp(12), dp(10))
            hex.background = shape(C.DARK, 12)
            content.addView(hex, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        content.addView(button("حفظ الأصل") { exportFile() }, lp(MATCH, WRAP, 0f, 0, 18, 0, 0))
    }

    private fun isViewable(f: FileRow): Boolean {
        if (f.category == "closed") return false
        return when (f.category) {
            "image" -> f.format in imgFormats || f.format == "SVG"
            "video" -> f.format in vidFormats
            "audio" -> f.format in audFormats
            "text", "trans", "code" -> f.format != "PDF"
            "font" -> f.format == "TTF" || f.format == "OTF"
            "model" -> f.format in MeshLoader.formats
            else -> false
        }
    }

    private fun infoRow(parent: LinearLayout, label: String, value: String, ltr: Boolean) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(0, dp(5), 0, dp(5))
        r.addView(tv(label, 12f, C.MUTED), LinearLayout.LayoutParams(MATCH, WRAP))
        val v = tv(value, 15f, C.INK, true)
        if (ltr) {
            v.textDirection = View.TEXT_DIRECTION_LTR
            v.gravity = Gravity.LEFT
        }
        v.setTextIsSelectable(true)
        r.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun note(text: String): View {
        val t = tv(text, 15f, Color.parseColor("#633806"))
        t.setPadding(dp(14), dp(12), dp(14), dp(12))
        t.background = shape(Color.parseColor("#FFF8E6"), 12, Color.parseColor("#E0C36A"), 1)
        return t
    }

    private fun buildViewer(f: FileRow, b: File) {
        if (!b.exists()) {
            content.addView(note("ملف الأصل غير موجود."), LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }
        when {
            f.category == "image" && f.format == "SVG" -> showText(b)
            f.category == "image" -> showImage(b)
            f.category == "video" && f.format in vidFormats -> showVideo(b)
            f.category == "audio" && f.format in audFormats -> showAudio(b)
            (f.category == "text" || f.category == "trans" || f.category == "code") && f.format != "PDF" -> showText(b)
            f.category == "font" && (f.format == "TTF" || f.format == "OTF") -> showFont(b)
            f.category == "model" && f.format in MeshLoader.formats -> showModel(b, f.format)
            else -> content.addView(
                note("الصيغة " + f.format + " معروفة ولا يوجد لها قارئ بعد. الملف محفوظ كما هو."),
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
        }
    }

    private fun showImage(b: File) {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(b.absolutePath, o)
        var sample = 1
        while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        val bmp = try {
            BitmapFactory.decodeFile(b.absolutePath, o2)
        } catch (e: Throwable) {
            null
        }
        if (bmp == null) {
            content.addView(note("تعذّر عرض هذه الصورة على هذا الجهاز."), LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setBackgroundColor(Color.parseColor("#EEF0F4"))
        content.addView(iv, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showVideo(b: File) {
        val vv = VideoView(this)
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoPath(b.absolutePath)
        content.addView(vv, LinearLayout.LayoutParams(MATCH, dp(260)))
    }

    private fun showAudio(b: File) {
        val mp = MediaPlayer()
        try {
            mp.setDataSource(b.absolutePath)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            content.addView(note("تعذّر تشغيل هذا الملف الصوتي."), LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }
        player = mp
        val btn = button("تشغيل / إيقاف") {
            if (mp.isPlaying) mp.pause() else mp.start()
        }
        content.addView(btn, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showText(b: File) {
        val limit = 200 * 1024
        val n = minOf(b.length(), limit.toLong()).toInt()
        val bytes = ByteArray(n)
        var off = 0
        FileInputStream(b).use { input ->
            while (off < n) {
                val r = input.read(bytes, off, n - off)
                if (r < 0) break
                off += r
            }
        }
        val t = tv(String(bytes, 0, off, Charsets.UTF_8), 12f, C.INK)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        t.setPadding(dp(12), dp(10), dp(12), dp(10))
        t.background = shape(Color.WHITE, 12, C.LINE, 1)
        content.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        if (b.length() > limit) {
            content.addView(tv("عُرض أول 200 KB فقط", 12f, C.MUTED), lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        }
    }

    private fun showFont(b: File) {
        val tf = try {
            Typeface.createFromFile(b)
        } catch (e: Throwable) {
            null
        }
        if (tf == null) {
            content.addView(note("تعذّر تحميل الخط."), LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }
        val t = tv("مرحبا ABC abc 0123456789\nThe quick brown fox", 24f, C.INK)
        t.typeface = tf
        t.setPadding(dp(12), dp(12), dp(12), dp(12))
        t.background = shape(Color.WHITE, 12, C.LINE, 1)
        content.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showModel(b: File, format: String) {
        val holder = FrameLayout(this)
        holder.setBackgroundColor(Color.parseColor("#EEF0F4"))
        val msg = tv("جارٍ تحميل المجسم…", 15f, C.MUTED)
        msg.gravity = Gravity.CENTER
        holder.addView(msg, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(holder, LinearLayout.LayoutParams(MATCH, dp(340)))
        Thread {
            val mesh = MeshLoader.load(b, format)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                holder.removeAllViews()
                if (mesh == null) {
                    msg.text = "تعذّر قراءة المجسم (قد يكون مضغوطًا أو بصيغة داخلية غير مدعومة)."
                    holder.addView(msg, FrameLayout.LayoutParams(MATCH, MATCH))
                } else {
                    holder.addView(ModelView(this, mesh), FrameLayout.LayoutParams(MATCH, MATCH))
                }
            }
        }.start()
    }

    private fun hexDump(b: File, n: Int): String {
        if (!b.exists()) return ""
        val buf = ByteArray(n)
        var len = 0
        FileInputStream(b).use { input ->
            while (len < n) {
                val r = input.read(buf, len, n - len)
                if (r < 0) break
                len += r
            }
        }
        val sb = StringBuilder()
        var i = 0
        while (i < len) {
            sb.append(String.format("%04X  ", i))
            val asc = StringBuilder()
            for (j in 0 until 16) {
                if (i + j < len) {
                    val v = buf[i + j].toInt() and 0xFF
                    sb.append(String.format("%02X ", v))
                    asc.append(if (v in 32..126) v.toChar() else '.')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(" ").append(asc).append("\n")
            i += 16
        }
        return sb.toString().trimEnd()
    }

    private fun exportFile() {
        val f = fileRow ?: return
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_TITLE, f.name)
        startActivityForResult(i, 77)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 77 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val b = blob ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(b).use { input -> input.copyTo(out) }
            }
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر الحفظ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            player?.release()
        } catch (e: Exception) {
        }
    }
}
