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

    /**
     * 译文单独一个文件。
     *
     * 不写回 `<id>.txt`：原文快照是本机抓来的、可反复重抓的产物，译文是花过钱的调用结果，
     * 两者混在一个文件里就没法判断哪一半是原文，删译文也会连原文一起丢。
     */
    private fun translationFile(context: Context, id: Long): File = File(dir(context), "$id.zh.txt")

    fun get(context: Context, id: Long): String? {
        val f = file(context, id)
        return if (f.exists() && f.isFile) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun getTranslation(context: Context, id: Long): String? {
        val f = translationFile(context, id)
        return if (f.exists() && f.isFile) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun save(context: Context, id: Long, text: String) {
        val f = file(context, id)
        f.writeText(text, Charsets.UTF_8)
    }

    fun saveTranslation(context: Context, id: Long, text: String) {
        translationFile(context, id).writeText(text, Charsets.UTF_8)
    }

    /** 删正文快照时译文一起删：译文离开原文就没有意义，留着只会慢慢堆积。 */
    fun delete(context: Context, id: Long): Boolean {
        val f = file(context, id)
        val gone = if (f.exists()) f.delete() else false
        translationFile(context, id).let { if (it.exists()) it.delete() }
        return gone
    }

    fun has(context: Context, id: Long): Boolean {
        val f = file(context, id)
        return f.exists() && f.length() > 0
    }
}
