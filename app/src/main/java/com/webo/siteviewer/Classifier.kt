package com.webo.siteviewer

import kotlin.math.ln
import kotlin.math.min

class Kind(val category: String, val format: String)

object Classifier {

    private fun b(h: ByteArray, i: Int): Int = if (i >= 0 && i < h.size) (h[i].toInt() and 0xFF) else -1

    private fun has(h: ByteArray, off: Int, s: String): Boolean {
        if (off + s.length > h.size) return false
        for (i in s.indices) {
            if ((h[off + i].toInt() and 0xFF) != s[i].code) return false
        }
        return true
    }

    fun entropy(h: ByteArray): Double {
        if (h.isEmpty()) return 0.0
        val cnt = IntArray(256)
        for (x in h) cnt[x.toInt() and 0xFF]++
        var e = 0.0
        val n = h.size.toDouble()
        for (c in cnt) {
            if (c > 0) {
                val p = c / n
                e -= p * (ln(p) / ln(2.0))
            }
        }
        return e
    }

    fun extOf(name: String): String {
        val n = name.substringBefore('?')
        val i = n.lastIndexOf('.')
        if (i < 0 || i == n.length - 1) return ""
        val e = n.substring(i + 1).lowercase()
        return if (e.length > 8) "" else e
    }

    private val transExt = setOf("json", "xml", "properties", "po", "pot", "xliff", "xlf", "arb", "yaml", "yml", "strings")
    private val transWords = listOf("lang", "locale", "i18n", "l10n", "translation", "messages", "strings", "intl")
    private val langName = Regex("^[a-z]{2,3}([-_][a-zA-Z]{2,4})?\\.(json|xml|properties|po|yml|yaml|arb)$")
    private val gtaModel = setOf("ydd", "yft", "ytd", "ydr", "ybn", "ymap", "ytyp", "ycd", "ynv")

    private fun isTrans(path: String, name: String, ext: String): Boolean {
        if (ext !in transExt) return false
        val p = path.lowercase()
        for (w in transWords) if (p.contains(w)) return true
        return langName.matches(name.lowercase())
    }

    fun classify(h: ByteArray, name: String, mime: String, path: String): Kind {
        val ext = extOf(name)
        val m = mime.lowercase().substringBefore(';').trim()
        val n = h.size

        // ----- صور -----
        if (b(h, 0) == 0x89 && has(h, 1, "PNG")) return Kind("image", "PNG")
        if (b(h, 0) == 0xFF && b(h, 1) == 0xD8 && b(h, 2) == 0xFF) return Kind("image", "JPEG")
        if (has(h, 0, "GIF8")) return Kind("image", "GIF")
        if (has(h, 0, "RIFF") && has(h, 8, "WEBP")) return Kind("image", "WEBP")
        if (has(h, 0, "RIFF") && has(h, 8, "WAVE")) return Kind("audio", "WAV")
        if (has(h, 0, "RIFF") && has(h, 8, "AVI ")) return Kind("video", "AVI")
        if (b(h, 0) == 0 && b(h, 1) == 0 && b(h, 2) == 1 && b(h, 3) == 0 && ext == "ico") return Kind("image", "ICO")
        if (has(h, 0, "BM") && ext == "bmp") return Kind("image", "BMP")

        // ----- حاويات ISO (mp4 وما شابه) -----
        if (has(h, 4, "ftyp") && n >= 12) {
            val brand = String(h, 8, 4, Charsets.ISO_8859_1)
            return when {
                brand == "avif" || brand == "avis" -> Kind("image", "AVIF")
                brand == "heic" || brand == "heix" || brand == "mif1" || brand == "msf1" -> Kind("image", "HEIC")
                brand == "M4A " -> Kind("audio", "M4A")
                brand == "qt  " -> Kind("video", "MOV")
                else -> Kind("video", "MP4")
            }
        }
        if (has(h, 4, "moof") || has(h, 4, "styp") || has(h, 4, "sidx")) return Kind("video", "fMP4")
        if (b(h, 0) == 0x1A && b(h, 1) == 0x45 && b(h, 2) == 0xDF && b(h, 3) == 0xA3) {
            return if (ext == "mka") Kind("audio", "MKA") else Kind("video", "WebM/MKV")
        }
        if (b(h, 0) == 0x47 && n >= 377 && b(h, 188) == 0x47 && b(h, 376) == 0x47) return Kind("video", "TS")
        if (has(h, 0, "FLV")) return Kind("video", "FLV")

        // ----- صوتيات -----
        if (has(h, 0, "OggS")) return Kind(if (ext == "ogv") "video" else "audio", "OGG")
        if (has(h, 0, "fLaC")) return Kind("audio", "FLAC")
        if (has(h, 0, "ID3")) return Kind("audio", "MP3")
        if (b(h, 0) == 0xFF && (b(h, 1) and 0xE0) == 0xE0 && (ext == "mp3" || ext == "aac" || ext == "m4a" || m.startsWith("audio/"))) {
            return Kind("audio", if (ext == "aac") "AAC" else "MP3")
        }

        // ----- مجسمات -----
        if (has(h, 0, "glTF")) return Kind("model", "GLB")
        if (has(h, 0, "Kaydara FBX")) return Kind("model", "FBX")
        if (has(h, 0, "RSC7")) {
            return Kind("model", if (ext in gtaModel) ext.uppercase() else "RSC7")
        }

        // ----- أرشيفات -----
        if (has(h, 0, "RPF")) return Kind("archive", "RPF")
        if (b(h, 0) == 0x50 && b(h, 1) == 0x4B && (b(h, 2) == 3 || b(h, 2) == 5)) return Kind("archive", "ZIP")
        if (b(h, 0) == 0x1F && b(h, 1) == 0x8B) return Kind("archive", "GZIP")
        if (b(h, 0) == 0x37 && b(h, 1) == 0x7A && b(h, 2) == 0xBC && b(h, 3) == 0xAF) return Kind("archive", "7Z")
        if (has(h, 0, "Rar!")) return Kind("archive", "RAR")
        if (has(h, 0, "BZh")) return Kind("archive", "BZIP2")
        if (b(h, 0) == 0xFD && has(h, 1, "7zXZ")) return Kind("archive", "XZ")

        // ----- خطوط -----
        if (has(h, 0, "wOFF")) return Kind("font", "WOFF")
        if (has(h, 0, "wOF2")) return Kind("font", "WOFF2")
        if (has(h, 0, "OTTO")) return Kind("font", "OTF")
        if (has(h, 0, "true") || has(h, 0, "ttcf")) return Kind("font", "TTF")
        if (b(h, 0) == 0 && b(h, 1) == 1 && b(h, 2) == 0 && b(h, 3) == 0 && (ext == "ttf" || ext == "otf" || m.contains("font"))) {
            return Kind("font", if (ext == "otf") "OTF" else "TTF")
        }

        // ----- أخرى -----
        if (has(h, 0, "%PDF")) return Kind("text", "PDF")
        if (b(h, 0) == 0x00 && has(h, 1, "asm")) return Kind("code", "WASM")
        if (b(h, 0) == 0x7F && has(h, 1, "ELF")) return Kind("unknown", "ELF")

        // ----- نصوص -----
        val sample = min(n, 2048)
        var zero = 0
        var ctrl = 0
        for (i in 0 until sample) {
            val v = b(h, i)
            if (v == 0) zero++ else if (v < 9 || (v in 14..31)) ctrl++
        }
        val isText = sample > 0 && zero == 0 && ctrl * 100 <= sample * 2
        if (isText) {
            val s = String(h, 0, sample, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val low = s.take(400).lowercase()
            if (low.startsWith("#extm3u")) return Kind("video", "HLS")
            if (low.startsWith("webvtt")) return Kind("trans", "VTT")
            if (low.contains("<mpd")) return Kind("video", "DASH")
            if (low.startsWith("<svg") || (low.startsWith("<?xml") && low.contains("<svg"))) return Kind("image", "SVG")
            if (ext == "obj") return Kind("model", "OBJ")
            if (ext == "stl" || (low.startsWith("solid") && low.contains("facet"))) return Kind("model", "STL")
            if (ext == "gltf") return Kind("model", "glTF")
            if (ext == "dae") return Kind("model", "DAE")
            if (ext == "ply") return Kind("model", "PLY")
            if (low.startsWith("<!doctype html") || low.startsWith("<html") || ext == "html" || ext == "htm" || m == "text/html") {
                return Kind("text", "HTML")
            }
            if (ext == "srt") return Kind("trans", "SRT")
            if (isTrans(path, name, ext)) return Kind("trans", ext.uppercase())
            if (ext == "css" || m == "text/css") return Kind("text", "CSS")
            if (ext == "js" || ext == "mjs" || ext == "cjs" || m.contains("javascript")) return Kind("code", "JS")
            if (ext == "json" || m.contains("json") || low.startsWith("{") || low.startsWith("[")) return Kind("text", "JSON")
            if (ext == "xml" || m.contains("xml") || low.startsWith("<?xml")) return Kind("text", "XML")
            if (ext.isNotEmpty()) return Kind("text", ext.uppercase())
            return Kind("text", "TXT")
        }

        return Kind("unknown", if (ext.isEmpty()) "غير معروف" else ext.uppercase())
    }
}
