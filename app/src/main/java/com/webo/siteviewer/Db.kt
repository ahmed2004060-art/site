package com.webo.siteviewer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SiteRow(
    val id: Long, val host: String, val url: String, val scope: Int,
    val created: Long, val done: Boolean, val files: Int, val pending: Int
)

class FileRow(
    val id: Long, val siteId: Long, val url: String, val name: String, val mime: String,
    val size: Long, val sha: String, val category: String, val format: String,
    val entropy: Double, val snippet: String?
)

class CatStat(val key: String, val count: Int, val size: Long)
class FmtStat(val format: String, val count: Int)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "webo.db", null, 1) {

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db {
            val i = inst
            if (i != null) return i
            synchronized(Db::class.java) {
                val j = inst
                if (j != null) return j
                val n = Db(ctx)
                inst = n
                return n
            }
        }

        private const val FILE_COLS = "id,site_id,url,name,mime,size,sha,category,format,entropy"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sites(id INTEGER PRIMARY KEY AUTOINCREMENT, host TEXT, start_url TEXT, scope INTEGER, created INTEGER, done INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE files(id INTEGER PRIMARY KEY AUTOINCREMENT, site_id INTEGER, url TEXT, name TEXT, path TEXT, mime TEXT, size INTEGER, sha TEXT, category TEXT, format TEXT, entropy REAL, created INTEGER, UNIQUE(site_id,url))")
        db.execSQL("CREATE INDEX files_cat ON files(site_id,category)")
        db.execSQL("CREATE TABLE queue(site_id INTEGER, url TEXT, depth INTEGER, status INTEGER, UNIQUE(site_id,url))")
        db.execSQL("CREATE INDEX queue_st ON queue(site_id,status)")
        db.execSQL("CREATE TABLE texts(file_id INTEGER PRIMARY KEY, site_id INTEGER, content TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- المواقع ----------
    fun createSite(host: String, url: String, scope: Int): Long {
        val cv = ContentValues()
        cv.put("host", host)
        cv.put("start_url", url)
        cv.put("scope", scope)
        cv.put("created", System.currentTimeMillis())
        return writableDatabase.insert("sites", null, cv)
    }

    fun listSites(): List<SiteRow> {
        val out = ArrayList<SiteRow>()
        val c = readableDatabase.rawQuery(
            "SELECT s.id,s.host,s.start_url,s.scope,s.created,s.done," +
                "(SELECT count(*) FROM files f WHERE f.site_id=s.id)," +
                "(SELECT count(*) FROM queue q WHERE q.site_id=s.id AND q.status=0) " +
                "FROM sites s ORDER BY s.id DESC", null
        )
        c.use {
            while (it.moveToNext()) {
                out.add(SiteRow(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getInt(3), it.getLong(4), it.getInt(5) == 1, it.getInt(6), it.getInt(7)))
            }
        }
        return out
    }

    fun getSite(id: Long): SiteRow? {
        for (s in listSites()) if (s.id == id) return s
        return null
    }

    fun setDone(id: Long, done: Boolean) {
        writableDatabase.execSQL("UPDATE sites SET done=? WHERE id=?", arrayOf<Any>(if (done) 1 else 0, id))
    }

    fun deleteSite(id: Long) {
        val db = writableDatabase
        val a = arrayOf<Any>(id)
        db.execSQL("DELETE FROM texts WHERE site_id=?", a)
        db.execSQL("DELETE FROM files WHERE site_id=?", a)
        db.execSQL("DELETE FROM queue WHERE site_id=?", a)
        db.execSQL("DELETE FROM sites WHERE id=?", a)
    }

    // ---------- الطابور ----------
    fun enqueue(siteId: Long, url: String, depth: Int) {
        val cv = ContentValues()
        cv.put("site_id", siteId)
        cv.put("url", url)
        cv.put("depth", depth)
        cv.put("status", 0)
        writableDatabase.insertWithOnConflict("queue", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun markQueue(siteId: Long, url: String, status: Int) {
        writableDatabase.execSQL("UPDATE queue SET status=? WHERE site_id=? AND url=?", arrayOf<Any>(status, siteId, url))
    }

    fun pendingQueue(siteId: Long): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val c = readableDatabase.rawQuery("SELECT url,depth FROM queue WHERE site_id=? AND status=0", arrayOf(siteId.toString()))
        c.use { while (it.moveToNext()) out.add(Pair(it.getString(0) ?: "", it.getInt(1))) }
        return out
    }

    fun knownUrls(siteId: Long): List<String> {
        val out = ArrayList<String>()
        val a = arrayOf(siteId.toString())
        var c = readableDatabase.rawQuery("SELECT url FROM queue WHERE site_id=?", a)
        c.use { while (it.moveToNext()) out.add(it.getString(0) ?: "") }
        c = readableDatabase.rawQuery("SELECT url FROM files WHERE site_id=?", a)
        c.use { while (it.moveToNext()) out.add(it.getString(0) ?: "") }
        return out
    }

    // done, pending, errors
    fun queueCounts(siteId: Long): Triple<Int, Int, Int> {
        var done = 0
        var pending = 0
        var err = 0
        val c = readableDatabase.rawQuery("SELECT status,count(*) FROM queue WHERE site_id=? GROUP BY status", arrayOf(siteId.toString()))
        c.use {
            while (it.moveToNext()) {
                when (it.getInt(0)) {
                    0 -> pending = it.getInt(1)
                    1 -> done = it.getInt(1)
                    else -> err = it.getInt(1)
                }
            }
        }
        return Triple(done, pending, err)
    }

    // ---------- الملفات ----------
    fun insertFile(
        siteId: Long, url: String, name: String, path: String, mime: String,
        size: Long, sha: String, category: String, format: String, entropy: Double
    ): Long {
        val cv = ContentValues()
        cv.put("site_id", siteId)
        cv.put("url", url)
        cv.put("name", name)
        cv.put("path", path)
        cv.put("mime", mime)
        cv.put("size", size)
        cv.put("sha", sha)
        cv.put("category", category)
        cv.put("format", format)
        cv.put("entropy", entropy)
        cv.put("created", System.currentTimeMillis())
        return writableDatabase.insertWithOnConflict("files", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun saveText(fileId: Long, siteId: Long, content: String) {
        val cv = ContentValues()
        cv.put("file_id", fileId)
        cv.put("site_id", siteId)
        cv.put("content", content)
        writableDatabase.insertWithOnConflict("texts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun fileStats(siteId: Long): Pair<Int, Long> {
        val c = readableDatabase.rawQuery("SELECT count(*),coalesce(sum(size),0) FROM files WHERE site_id=?", arrayOf(siteId.toString()))
        c.use {
            if (it.moveToFirst()) return Pair(it.getInt(0), it.getLong(1))
        }
        return Pair(0, 0L)
    }

    fun catStats(siteId: Long): List<CatStat> {
        val out = ArrayList<CatStat>()
        val c = readableDatabase.rawQuery("SELECT category,count(*),coalesce(sum(size),0) FROM files WHERE site_id=? GROUP BY category", arrayOf(siteId.toString()))
        c.use { while (it.moveToNext()) out.add(CatStat(it.getString(0) ?: "unknown", it.getInt(1), it.getLong(2))) }
        return out
    }

    fun fmtStats(siteId: Long, cat: String): List<FmtStat> {
        val out = ArrayList<FmtStat>()
        val c = readableDatabase.rawQuery("SELECT format,count(*) FROM files WHERE site_id=? AND category=? GROUP BY format ORDER BY 2 DESC", arrayOf(siteId.toString(), cat))
        c.use { while (it.moveToNext()) out.add(FmtStat(it.getString(0) ?: "", it.getInt(1))) }
        return out
    }

    private fun readFiles(c: android.database.Cursor, withSnippet: Boolean): List<FileRow> {
        val out = ArrayList<FileRow>()
        c.use {
            while (it.moveToNext()) {
                out.add(
                    FileRow(
                        it.getLong(0), it.getLong(1), it.getString(2) ?: "", it.getString(3) ?: "", it.getString(4) ?: "",
                        it.getLong(5), it.getString(6) ?: "", it.getString(7) ?: "unknown", it.getString(8) ?: "", it.getDouble(9),
                        if (withSnippet) it.getString(10) else null
                    )
                )
            }
        }
        return out
    }

    fun listFiles(siteId: Long, cat: String?, fmt: String?): List<FileRow> {
        val sb = StringBuilder("SELECT $FILE_COLS FROM files WHERE site_id=?")
        val args = ArrayList<String>()
        args.add(siteId.toString())
        if (cat != null) {
            sb.append(" AND category=?")
            args.add(cat)
        }
        if (fmt != null) {
            sb.append(" AND format=?")
            args.add(fmt)
        }
        sb.append(" ORDER BY name LIMIT 5000")
        return readFiles(readableDatabase.rawQuery(sb.toString(), args.toTypedArray()), false)
    }

    fun recent(siteId: Long, n: Int): List<FileRow> {
        return readFiles(
            readableDatabase.rawQuery("SELECT $FILE_COLS FROM files WHERE site_id=? ORDER BY id DESC LIMIT $n", arrayOf(siteId.toString())),
            false
        )
    }

    fun getFile(id: Long): FileRow? {
        val l = readFiles(readableDatabase.rawQuery("SELECT $FILE_COLS FROM files WHERE id=?", arrayOf(id.toString())), false)
        return if (l.isEmpty()) null else l[0]
    }

    fun search(siteId: Long, q: String): List<FileRow> {
        val like = "%$q%"
        val sql = "SELECT f.id,f.site_id,f.url,f.name,f.mime,f.size,f.sha,f.category,f.format,f.entropy," +
            "CASE WHEN t.content IS NOT NULL AND instr(t.content,?)>0 THEN substr(t.content, max(instr(t.content,?)-30,1), 110) ELSE NULL END " +
            "FROM files f LEFT JOIN texts t ON t.file_id=f.id " +
            "WHERE f.site_id=? AND (f.name LIKE ? OR f.url LIKE ? OR t.content LIKE ?) ORDER BY f.name LIMIT 300"
        val c = readableDatabase.rawQuery(sql, arrayOf(q, q, siteId.toString(), like, like, like))
        return readFiles(c, true)
    }
}
