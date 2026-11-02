package com.webo.siteviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FloatBuf {
    var a = FloatArray(1024)
    var n = 0
    fun add(x: Float) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = x
    }

    fun toArray(): FloatArray = a.copyOf(n)
}

class IntBuf {
    var a = IntArray(1024)
    var n = 0
    fun add(x: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = x
    }

    fun toArray(): IntArray = a.copyOf(n)
}

class Mesh(val v: FloatArray, val idx: IntArray)

object MeshLoader {
    val formats = setOf("GLB", "glTF", "OBJ", "STL")

    fun load(f: File, format: String): Mesh? {
        if (f.length() > 80L * 1024 * 1024) return null
        return try {
            when (format) {
                "GLB" -> fromGlb(f.readBytes())
                "glTF" -> fromGltfText(f.readText())
                "OBJ" -> fromObj(f)
                "STL" -> fromStl(f.readBytes())
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun fromGlb(bytes: ByteArray): Mesh? {
        if (bytes.size < 20) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x46546C67) return null
        var off = 12
        var json: String? = null
        var bin: ByteBuffer? = null
        while (off + 8 <= bytes.size) {
            val len = bb.getInt(off)
            val type = bb.getInt(off + 4)
            val start = off + 8
            if (len < 0 || start + len > bytes.size) break
            if (type == 0x4E4F534A) {
                json = String(bytes, start, len, Charsets.UTF_8)
            } else if (type == 0x004E4942 && bin == null) {
                bin = ByteBuffer.wrap(bytes, start, len).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            off = start + len
        }
        val j = json ?: return null
        val list = ArrayList<ByteBuffer>()
        if (bin != null) list.add(bin)
        return fromGltf(j, list)
    }

    private fun fromGltfText(text: String): Mesh? {
        val root = JSONObject(text)
        val bufs = root.optJSONArray("buffers") ?: return null
        val list = ArrayList<ByteBuffer>()
        for (i in 0 until bufs.length()) {
            val uri = bufs.getJSONObject(i).optString("uri", "")
            if (!uri.startsWith("data:")) return null
            val data = Base64.decode(uri.substringAfter(','), Base64.DEFAULT)
            list.add(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))
        }
        return fromGltf(text, list)
    }

    private fun fromGltf(js: String, buffers: List<ByteBuffer>): Mesh? {
        val root = JSONObject(js)
        val accessors = root.optJSONArray("accessors") ?: return null
        val views = root.optJSONArray("bufferViews") ?: return null
        val meshes = root.optJSONArray("meshes") ?: return null
        val pos = FloatBuf()
        val idx = IntBuf()
        var vcount = 0

        for (mi in 0 until meshes.length()) {
            val prims = meshes.getJSONObject(mi).optJSONArray("primitives") ?: continue
            for (pi in 0 until prims.length()) {
                val prim = prims.getJSONObject(pi)
                if (prim.optInt("mode", 4) != 4) continue
                val ext = prim.optJSONObject("extensions")
                if (ext != null && ext.has("KHR_draco_mesh_compression")) continue
                val attrs = prim.optJSONObject("attributes") ?: continue
                if (!attrs.has("POSITION")) continue

                val pa = accessors.getJSONObject(attrs.getInt("POSITION"))
                if (pa.optInt("componentType", 5126) != 5126 || !pa.has("bufferView")) continue
                val pv = views.getJSONObject(pa.getInt("bufferView"))
                val pbuf = buffers.getOrNull(pv.optInt("buffer", 0)) ?: continue
                val pcount = pa.getInt("count")
                val pbase = pv.optInt("byteOffset", 0) + pa.optInt("byteOffset", 0)
                var pstride = pv.optInt("byteStride", 0)
                if (pstride == 0) pstride = 12
                for (i in 0 until pcount) {
                    val p = pbase + i * pstride
                    pos.add(pbuf.getFloat(p))
                    pos.add(pbuf.getFloat(p + 4))
                    pos.add(pbuf.getFloat(p + 8))
                }

                if (prim.has("indices")) {
                    val ia = accessors.getJSONObject(prim.getInt("indices"))
                    if (!ia.has("bufferView")) {
                        vcount += pcount
                        continue
                    }
                    val iv = views.getJSONObject(ia.getInt("bufferView"))
                    val ibuf = buffers.getOrNull(iv.optInt("buffer", 0))
                    if (ibuf == null) {
                        vcount += pcount
                        continue
                    }
                    val icount = ia.getInt("count")
                    val ibase = iv.optInt("byteOffset", 0) + ia.optInt("byteOffset", 0)
                    val ct = ia.getInt("componentType")
                    val csize = if (ct == 5121) 1 else if (ct == 5123) 2 else 4
                    var istride = iv.optInt("byteStride", 0)
                    if (istride == 0) istride = csize
                    for (i in 0 until icount) {
                        val p = ibase + i * istride
                        val v = when (ct) {
                            5121 -> ibuf.get(p).toInt() and 0xFF
                            5123 -> ibuf.getShort(p).toInt() and 0xFFFF
                            else -> ibuf.getInt(p)
                        }
                        idx.add(vcount + v)
                    }
                } else {
                    for (i in 0 until pcount) idx.add(vcount + i)
                }
                vcount += pcount
            }
        }
        if (pos.n == 0 || idx.n < 3) return null
        return Mesh(pos.toArray(), idx.toArray())
    }

    private fun fromObj(f: File): Mesh? {
        val pos = FloatBuf()
        val idx = IntBuf()
        var vc = 0
        BufferedReader(FileReader(f)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith("v ")) {
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size >= 4) {
                        pos.add(p[1].toFloat())
                        pos.add(p[2].toFloat())
                        pos.add(p[3].toFloat())
                        vc++
                    }
                } else if (line.startsWith("f ")) {
                    val p = line.trim().split(Regex("\\s+"))
                    val ids = ArrayList<Int>()
                    for (k in 1 until p.size) {
                        val a = p[k].substringBefore('/').toIntOrNull() ?: continue
                        ids.add(if (a < 0) vc + a else a - 1)
                    }
                    for (k in 1 until ids.size - 1) {
                        idx.add(ids[0])
                        idx.add(ids[k])
                        idx.add(ids[k + 1])
                    }
                }
            }
        }
        if (pos.n == 0 || idx.n < 3) return null
        return Mesh(pos.toArray(), idx.toArray())
    }

    private fun fromStl(bytes: ByteArray): Mesh? {
        val pos = FloatBuf()
        val idx = IntBuf()
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val ascii = head.trimStart().startsWith("solid") && head.contains("facet")
        if (ascii) {
            val text = String(bytes, Charsets.ISO_8859_1)
            var vc = 0
            for (line in text.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("vertex")) {
                    val p = t.split(Regex("\\s+"))
                    if (p.size >= 4) {
                        pos.add(p[1].toFloat())
                        pos.add(p[2].toFloat())
                        pos.add(p[3].toFloat())
                        idx.add(vc++)
                    }
                }
            }
        } else {
            if (bytes.size < 84) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.getInt(80)
            if (count < 0 || 84L + count.toLong() * 50L > bytes.size.toLong()) return null
            var vc = 0
            for (t in 0 until count) {
                val base = 84 + t * 50 + 12
                for (k in 0 until 3) {
                    val p = base + k * 12
                    pos.add(bb.getFloat(p))
                    pos.add(bb.getFloat(p + 4))
                    pos.add(bb.getFloat(p + 8))
                    idx.add(vc++)
                }
            }
        }
        if (pos.n == 0 || idx.n < 3) return null
        return Mesh(pos.toArray(), idx.toArray())
    }
}

class ModelView(ctx: Context, private val mesh: Mesh) : View(ctx) {
    private var yaw = 0.7f
    private var pitch = 0.35f
    private var zoom = 1f
    private var cx = 0f
    private var cy = 0f
    private var cz = 0f
    private var radius = 1f
    private val paint = Paint()
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.2f, 8f)
            invalidate()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val v = mesh.v
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < v.size) {
            val x = v[i]
            val y = v[i + 1]
            val z = v[i + 2]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
            i += 3
        }
        cx = (minX + maxX) / 2f
        cy = (minY + maxY) / 2f
        cz = (minZ + maxZ) / 2f
        val ext = maxOf(maxX - minX, maxOf(maxY - minY, maxZ - minZ))
        radius = if (ext <= 0f) 1f else ext / 2f * 1.15f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1) {
                        yaw += (e.x - lastX) * 0.01f
                        pitch = (pitch + (e.y - lastY) * 0.01f).coerceIn(-1.5f, 1.5f)
                        invalidate()
                    }
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                    lastX = e.x
                    lastY = e.y
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#EEF0F4"))
        val v = mesh.v
        val ix = mesh.idx
        val nv = v.size / 3
        if (nv == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val s = (minOf(w, h) * 0.45f / radius) * zoom
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cyw = cos(yaw)
        val syw = sin(yaw)

        val rx = FloatArray(nv)
        val ry = FloatArray(nv)
        val rz = FloatArray(nv)
        for (i in 0 until nv) {
            val x = v[i * 3] - cx
            val y = v[i * 3 + 1] - cy
            val z = v[i * 3 + 2] - cz
            val x1 = x * cyw + z * syw
            val z1 = -x * syw + z * cyw
            rx[i] = x1
            ry[i] = y * cp - z1 * sp
            rz[i] = y * sp + z1 * cp
        }

        val tri = ix.size / 3
        val step = maxOf(1, tri / 20000)
        val used = (tri + step - 1) / step
        val keys = LongArray(used)
        var cnt = 0
        var t = 0
        while (t < tri) {
            val a = ix[t * 3]
            val b = ix[t * 3 + 1]
            val d = ix[t * 3 + 2]
            if (a in 0 until nv && b in 0 until nv && d in 0 until nv) {
                val depth = (rz[a] + rz[b] + rz[d]) / 3f
                val bits = java.lang.Float.floatToIntBits(depth)
                val sortable = if (bits >= 0) bits else bits xor 0x7FFFFFFF
                keys[cnt++] = (sortable.toLong() shl 32) or (t.toLong() and 0xFFFFFFFFL)
            }
            t += step
        }
        java.util.Arrays.sort(keys, 0, cnt)

        val verts = FloatArray(cnt * 6)
        val colors = IntArray(cnt * 3)
        val lx = 0.3f
        val ly = 0.5f
        val lz = 0.8f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (k in 0 until cnt) {
            val tt = (keys[k] and 0xFFFFFFFFL).toInt()
            val a = ix[tt * 3]
            val b = ix[tt * 3 + 1]
            val d = ix[tt * 3 + 2]
            val ux = rx[b] - rx[a]
            val uy = ry[b] - ry[a]
            val uz = rz[b] - rz[a]
            val vx = rx[d] - rx[a]
            val vy = ry[d] - ry[a]
            val vz = rz[d] - rz[a]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl > 0f) {
                nx /= nl
                ny /= nl
                nz /= nl
            }
            val inten = 0.3f + 0.7f * abs((nx * lx + ny * ly + nz * lz) / ll)
            val r = (30 + 70 * inten).toInt().coerceIn(0, 255)
            val g = (40 + 110 * inten).toInt().coerceIn(0, 255)
            val bl = (40 + 190 * inten).toInt().coerceIn(0, 255)
            val col = Color.rgb(r, g, bl)
            verts[k * 6] = w / 2f + rx[a] * s
            verts[k * 6 + 1] = h / 2f - ry[a] * s
            verts[k * 6 + 2] = w / 2f + rx[b] * s
            verts[k * 6 + 3] = h / 2f - ry[b] * s
            verts[k * 6 + 4] = w / 2f + rx[d] * s
            verts[k * 6 + 5] = h / 2f - ry[d] * s
            colors[k * 3] = col
            colors[k * 3 + 1] = col
            colors[k * 3 + 2] = col
        }

        var t0 = 0
        while (t0 < cnt) {
            val n = minOf(5000, cnt - t0)
            c.drawVertices(Canvas.VertexMode.TRIANGLES, n * 6, verts, t0 * 6, null, 0, colors, t0 * 3, null, 0, 0, paint)
            t0 += n
        }
    }
}
