package cn.linkvault

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 便携备份格式。文件里的 ID 和去重键一律不信任。
 *
 * 格式版本刻意停留在 1：v2 新增的字段在读取时全部可选（缺失就用默认值），
 * 这样 1.0.0 / 1.1.0 导出的备份仍然能原样导入，不会因为升级而失效。
 */
object Backup {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_ITEMS = 10000
    const val FORMAT = "cn.linkvault.backup"
    const val VERSION = 1
    private const val MAX_TITLE = 200
    private const val MAX_NOTES = 8000
    private const val MAX_SUMMARY = 8000
    private const val MAX_SITE = 200
    private const val MAX_TAGS = 1000

    fun read(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size() + n <= MAX_BYTES) { "备份超过 10 MB 上限" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Bookmark> {
        require(bytes.size <= MAX_BYTES) { "备份超过 10 MB 上限" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

        val trimmed = text.trimStart()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.contains("<H1>Bookmarks</H1>", ignoreCase = true) ||
            trimmed.contains("<DT><A ", ignoreCase = true) ||
            trimmed.contains("<dt><a ", ignoreCase = true)) {
            return decodeHtml(text)
        }

        // Bound parser recursion before org.json sees untrusted data (including ignored fields).
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "备份结构嵌套过深" } }
                '}', ']' -> { depth--; require(depth >= 0) { "备份结构错误" } }
            }
        }
        require(depth == 0 && !quoted) { "备份结构不完整" }
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: error("不是有效的链藏 JSON 备份")
        require(tokener.nextClean() == '\u0000') { "备份末尾含无效内容" }
        require(root.opt("format") == FORMAT) { "不是链藏备份格式" }
        require(root.opt("version") is Int && root.getInt("version") == VERSION) { "不支持此备份版本" }
        val rows = root.opt("bookmarks") as? JSONArray ?: error("缺少收藏列表")
        require(rows.length() <= MAX_ITEMS) { "单次最多导入 10000 条收藏" }

        return (0 until rows.length()).map { index ->
            val row = rows.opt(index) as? JSONObject ?: error("第 ${index + 1} 条收藏格式错误")
            val url = required(row, "url", 16000).trim()
            val key = Links.canonical(url)
            val title = required(row, "title", MAX_TITLE)
            val notes = required(row, "notes", MAX_NOTES)
            val rawTags = row.opt("tags") as? JSONArray ?: error("标签必须是数组")
            require(rawTags.length() <= 500) { "标签数量过多" }
            val tags = (0 until rawTags.length()).map {
                val value = rawTags.opt(it)
                require(value is String && value.isNotBlank() && value.length <= MAX_TAGS && parseTags(value) == listOf(value)) { "标签格式错误" }
                value
            }.distinct().joinToString(",")
            require(tags.length <= MAX_TAGS) { "标签总长度超过上限" }
            val updatedAt = timestamp(row, "updatedAt") ?: error("收藏时间格式错误")
            Bookmark(
                url = url,
                canonical = key,
                title = title,
                notes = notes,
                tags = tags,
                createdAt = timestamp(row, "createdAt") ?: updatedAt,
                updatedAt = updatedAt,
                pinned = flag(row, "pinned"),
                archived = flag(row, "archived"),
                read = flag(row, "read"),
                summary = optional(row, "summary", MAX_SUMMARY),
                siteName = optional(row, "siteName", MAX_SITE),
                fetchedAt = timestamp(row, "fetchedAt") ?: 0L
            )
        }
    }

    /** 必需字段：缺失、类型不对或超长都拒绝整份文件，不静默截断。 */
    private fun required(row: JSONObject, key: String, max: Int): String {
        val value = row.opt(key)
        require(value is String && value.length <= max) { "字段 $key 类型或长度不正确" }
        return value
    }

    /** v2 新增的可选字段：老备份里没有就当默认值，保证向后兼容。 */
    private fun optional(row: JSONObject, key: String, max: Int): String {
        if (!row.has(key) || row.isNull(key)) return ""
        val value = row.opt(key)
        require(value is String && value.length <= max) { "字段 $key 类型或长度不正确" }
        return value
    }

    private fun flag(row: JSONObject, key: String): Boolean {
        if (!row.has(key) || row.isNull(key)) return false
        val value = row.opt(key)
        require(value is Boolean) { "字段 $key 必须是布尔值" }
        return value
    }

    private fun timestamp(row: JSONObject, key: String): Long? {
        if (!row.has(key) || row.isNull(key)) return null
        val value = row.opt(key)
        require((value is Int || value is Long) && (value as Number).toLong() >= 0) { "字段 $key 时间格式错误" }
        return (value as Number).toLong()
    }

    fun encode(items: List<Bookmark>): ByteArray {
        require(items.size <= MAX_ITEMS) { "单个备份最多 10000 条收藏" }
        val rows = JSONArray()
        items.forEach { item ->
            rows.put(
                JSONObject()
                    .put("url", item.url).put("title", item.title).put("notes", item.notes)
                    .put("tags", JSONArray(parseTags(item.tags)))
                    .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
                    .put("pinned", item.pinned).put("archived", item.archived).put("read", item.read)
                    .put("summary", item.summary).put("siteName", item.siteName).put("fetchedAt", item.fetchedAt)
            )
        }
        val bytes = JSONObject().put("format", FORMAT).put("version", VERSION).put("exportedAt", System.currentTimeMillis())
            .put("bookmarks", rows).toString(2).toByteArray(Charsets.UTF_8)
        // A backup must always be valid for our own importer; never silently truncate.
        decode(bytes)
        return bytes
    }

    fun decodeHtml(text: String): List<Bookmark> {
        val aRegex = Regex("""(?i)<a\s+([^>]+)>(.*?)</a>""")
        val hrefRegex = Regex("""(?i)href\s*=\s*["']([^"']+)["']""")
        val addDateRegex = Regex("""(?i)add_date\s*=\s*["']([0-9]+)["']""")
        val tagsRegex = Regex("""(?i)tags\s*=\s*["']([^"']*)["']""")

        val matches = aRegex.findAll(text).take(MAX_ITEMS).toList()
        require(matches.isNotEmpty()) { "未能从 HTML 书签中识别到有效链接" }

        val now = System.currentTimeMillis()
        val list = mutableListOf<Bookmark>()
        for (m in matches) {
            val attrs = m.groupValues[1]
            val rawTitle = m.groupValues[2]
            val href = hrefRegex.find(attrs)?.groupValues?.get(1)?.trim() ?: continue
            if (!href.startsWith("http://", ignoreCase = true) && !href.startsWith("https://", ignoreCase = true)) continue
            if (href.length > 16000) continue

            val dateVal = addDateRegex.find(attrs)?.groupValues?.get(1)?.toLongOrNull()
            val createdAt = when {
                dateVal == null || dateVal <= 0 -> now
                dateVal < 10000000000L -> dateVal * 1000L
                else -> dateVal
            }
            val rawTags = tagsRegex.find(attrs)?.groupValues?.get(1) ?: ""
            val tags = rawTags.split(",", ";").map { it.trim() }.filter { it.isNotBlank() && it.length <= 50 }.distinct().take(10).joinToString(",")
            val title = unescapeHtml(rawTitle).trim().take(MAX_TITLE)
            val canonical = Links.canonical(href)

            list.add(
                Bookmark(
                    url = href,
                    canonical = canonical,
                    title = title,
                    tags = tags,
                    createdAt = createdAt,
                    updatedAt = createdAt
                )
            )
        }
        require(list.isNotEmpty()) { "HTML 书签内未包含有效的 HTTP/HTTPS 链接" }
        return list
    }

    fun encodeHtml(items: List<Bookmark>): ByteArray {
        require(items.size <= MAX_ITEMS) { "单个备份最多 10000 条收藏" }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<!-- This is an automatically generated file. -->\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n")
        sb.append("<H1>Bookmarks</H1>\n")
        sb.append("<DL><p>\n")
        items.forEach { item ->
            val tagsAttr = if (item.tags.isNotBlank()) " TAGS=\"${escapeHtml(item.tags)}\"" else ""
            val addDateAttr = " ADD_DATE=\"${item.createdAt / 1000}\""
            val title = if (item.title.isNotBlank()) escapeHtml(item.title) else escapeHtml(item.url)
            sb.append("    <DT><A HREF=\"${escapeHtml(item.url)}\"$addDateAttr$tagsAttr>$title</A>\n")
            if (item.notes.isNotBlank() || item.summary.isNotBlank()) {
                val desc = listOf(item.notes, item.summary).filter { it.isNotBlank() }.joinToString(" — ")
                sb.append("    <DD>${escapeHtml(desc)}\n")
            }
        }
        sb.append("</DL><p>\n")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        decode(bytes)
        return bytes
    }

    fun encodeMarkdown(items: List<Bookmark>): ByteArray {
        require(items.size <= MAX_ITEMS) { "单个备份最多 10000 条收藏" }
        val sb = StringBuilder()
        val dateStr = Stamp.date(System.currentTimeMillis())
        sb.append("# 链藏书签合辑\n\n")
        sb.append("> 导出时间：$dateStr · 共 ${items.size} 条收藏\n\n")

        val byTag = mutableMapOf<String, MutableList<Bookmark>>()
        val untagged = mutableListOf<Bookmark>()
        items.forEach { item ->
            val tags = parseTags(item.tags)
            if (tags.isEmpty()) {
                untagged.add(item)
            } else {
                tags.forEach { t ->
                    byTag.getOrPut(t) { mutableListOf() }.add(item)
                }
            }
        }

        if (byTag.isNotEmpty()) {
            byTag.forEach { (tag, list) ->
                sb.append("## $tag\n\n")
                list.forEach { item ->
                    val title = item.title.ifBlank { Links.siteName(item.url).ifBlank { item.url } }
                    sb.append("- [${title.replace("[", "\\[").replace("]", "\\]")}](${item.url})\n")
                    if (item.notes.isNotBlank()) {
                        sb.append("  > ${item.notes.replace("\n", "\n  > ")}\n")
                    } else if (item.summary.isNotBlank()) {
                        sb.append("  > ${item.summary.replace("\n", "\n  > ")}\n")
                    }
                }
                sb.append("\n")
            }
        }

        if (untagged.isNotEmpty()) {
            sb.append("## 未分类\n\n")
            untagged.forEach { item ->
                val title = item.title.ifBlank { Links.siteName(item.url).ifBlank { item.url } }
                sb.append("- [${title.replace("[", "\\[").replace("]", "\\]")}](${item.url})\n")
                if (item.notes.isNotBlank()) {
                    sb.append("  > ${item.notes.replace("\n", "\n  > ")}\n")
                } else if (item.summary.isNotBlank()) {
                    sb.append("  > ${item.summary.replace("\n", "\n  > ")}\n")
                }
            }
            sb.append("\n")
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun unescapeHtml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    fun countNew(items: List<Bookmark>, existing: List<Bookmark>): Int =
        (items.map { it.canonical }.toSet() - existing.map { it.canonical }.toSet()).size

    suspend fun merge(db: VaultDb, items: List<Bookmark>): MergeResult = db.withTransaction {
        var added = 0
        items.forEach { item ->
            val safe = item.copy(id = 0, canonical = Links.canonical(item.url))
            if (db.bookmarks().byKey(safe.canonical) == null) { db.bookmarks().insert(safe); added++ }
        }
        MergeResult(added, items.size - added)
    }
}

data class MergeResult(val added: Int, val skipped: Int)
data class ImportPreview(val items: List<Bookmark>, val added: Int) { val skipped get() = items.size - added }
