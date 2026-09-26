package cn.linkvault

import android.content.Context
import java.io.File

/**
 * 本地离线正文快照存储。
 * 保存在应用私有文件目录的 snapshots/ 文件夹中，以 bookmark.id 为文件名。
 * 纯本地存储、随备份清理、无需数据库迁移，安全且轻量。
 */
object Snapshots {
    private fun dir(context: Context): File = File(context.filesDir, "snapshots").apply {
        if (!exists()) mkdirs()
    }

    private fun file(context: Context, id: Long): File = File(dir(context), "$id.txt")

    fun get(context: Context, id: Long): String? {
        val f = file(context, id)
        return if (f.exists() && f.isFile) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun save(context: Context, id: Long, text: String) {
        val f = file(context, id)
        f.writeText(text, Charsets.UTF_8)
    }

    fun delete(context: Context, id: Long): Boolean {
        val f = file(context, id)
        return if (f.exists()) f.delete() else false
    }

    fun has(context: Context, id: Long): Boolean {
        val f = file(context, id)
        return f.exists() && f.length() > 0
    }
}
