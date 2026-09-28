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
 * v1 的读路径原样保留（`notes` / `vault` 缺失就是空），所以 1.0.0~1.4.0 导出的文件仍然能导入。
 * v2 只多这两项：**反向**不兼容 —— 老版本遇到 `version: 2` 会按"不支持此备份版本"拒绝，
 * 这是把私密笔记挡在明文备份之外的必要代价，宁可让用户先用新版导入，也不能悄悄漏掉内容。
 */
object Backup {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_ITEMS = 10000
    const val FORMAT = "cn.linkvault.backup"
    const val VERSION = 2
    /** 一键备份的文件名前缀。清理旧备份时只认这个前缀，文件夹里别人的文件一概不碰。 */
    const val BACKUP_PREFIX = "链藏备份-"
    private const val MAX_TITLE = 200
    private const val MAX_NOTES = 8000
    private const val MAX_SUMMARY = 8000
    private const val MAX_SITE = 200
    private const val MAX_IMAGE = 500
    private const val MAX_TAGS = 1000

    /**
     * 从文件夹里的文件名中挑出该删掉的那些：只认「链藏备份-*.json」，按文件名从新到旧排，
     * 留下前 keep 份。时间戳是 yyyyMMdd-HHmm，所以字典序就是时间序。
     */
    fun staleBackups(names: List<String>, keep: Int): List<String> =
        names.filter { it.startsWith(BACKUP_PREFIX) && it.endsWith(".json") }
            .sortedDescending()
            .drop(keep.coerceAtLeast(0))

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

    fun decode(bytes: ByteArray): List<Bookmark> = decodeContents(bytes).bookmarks

    /** 一份备份里的全部内容。v1 文件里 [Contents.notes] 是空列表、[Contents.master] 是 null。 */
    fun decodeContents(bytes: ByteArray): Contents {
        require(bytes.size <= MAX_BYTES) { "备份超过 10 MB 上限" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

        val trimmed = text.trimStart()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.contains("<H1>Bookmarks</H1>", ignoreCase = true) ||
            trimmed.contains("<DT><A ", ignoreCase = true) ||
            trimmed.contains("<dt><a ", ignoreCase = true)) {
            return Contents(decodeHtml(text), emptyList(), null)
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
        val version = root.opt("version")
        require(version is Int && version in 1..VERSION) { "不支持此备份版本" }
        // v1 文件里出现这两项只能是手工改的：读它会得到一份"看起来导入成功、其实少了内容"的结果。
        if (version == 1) {
            require(!root.has("notes") && !root.has("vault")) { "v1 备份里不应包含笔记或主密码" }
        }
        val rows = root.opt("bookmarks") as? JSONArray ?: error("缺少收藏列表")
        require(rows.length() <= MAX_ITEMS) { "单次最多导入 10000 条收藏" }

        val bookmarks = (0 until rows.length()).map { index ->
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
                image = optional(row, "image", MAX_IMAGE),
                fetchedAt = timestamp(row, "fetchedAt") ?: 0L
            )
        }
        val noteRows = root.opt("notes") as? JSONArray ?: JSONArray()
        require(noteRows.length() <= MAX_ITEMS) { "单次最多导入 10000 条笔记" }
        val notes = (0 until noteRows.length()).map { index ->
            note(index + 1, noteRows.opt(index) as? JSONObject ?: error("第 ${index + 1} 条笔记格式错误"))
        }
        return Contents(bookmarks, notes, master(root))
    }

    /**
     * 一条笔记。文件里的 `id` 一概不采信：导入永远是新增，不会盖掉本机已有那条。
     *
     * 私密笔记只带 `cipher`。这里**不校验密文能不能解开** —— 别人主密码封的内容本来就解不开，
     * 要求可解等于把用户要的东西当垃圾扔掉；真正读不出时由笔记页如实显示"属于另一个主密码"。
     */
    private fun note(label: Int, row: JSONObject): Note {
        val secret = flag(row, "secret")
        val text = optional(row, "text", NoteCrypto.MAX_TEXT)
        val cipher = optional(row, "cipher", NoteCrypto.MAX_CIPHER)
        fun bad(reason: String): Nothing = error("第 $label 条笔记 $reason")
        require(!secret || cipher.isNotEmpty()) { bad("缺少密文") }
        require(secret || text.isNotEmpty()) { bad("正文是空的") }
        require(!secret || text.isEmpty()) { bad("同时带明文正文，格式错误") }
        require(secret || cipher.isEmpty()) { bad("不是私密笔记却有密文") }
        if (cipher.isNotEmpty()) NoteCrypto.decode(cipher)
        val updatedAt = timestamp(row, "updatedAt") ?: bad("时间格式错误")
        return Note(
            text = text,
            cipher = cipher,
            secret = secret,
            createdAt = timestamp(row, "createdAt") ?: updatedAt,
            updatedAt = updatedAt
        )
    }

    /** `vault` 缺失就是"这份备份里没有主密码"，导入时保持本机现状。 */
    private fun master(root: JSONObject): VaultMaster? {
        val row = root.opt("vault") as? JSONObject ?: return null
        val salt = required(row, "salt", 64)
        val verifier = required(row, "verifier", NoteCrypto.MAX_CIPHER)
        // 先确认这两串真是 Base64：错的话用户会在解锁时以为自己的密码坏了，而不是这份备份有问题。
        require(NoteCrypto.decode(salt).size == NoteCrypto.SALT_BYTES) { "主密码 salt 格式错误" }
        require(NoteCrypto.decode(verifier).isNotEmpty()) { "主密码校验数据格式错误" }
        return VaultMaster(salt = salt, verifier = verifier, createdAt = timestamp(row, "createdAt") ?: System.currentTimeMillis())
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

    /**
     * 写出一份备份。[notes] 与 [master] 只有 `.lvault` 加密通道会传。
     *
     * 私密笔记进明文文件由下面那道 require 挡住，而不是靠调用方记得过滤 ——
     * 明文备份是要发给别的设备的，漏一次就是把口令交出去。
     */
    fun encode(items: List<Bookmark>, notes: List<Note> = emptyList(), master: VaultMaster? = null): ByteArray {
        require(items.size <= MAX_ITEMS) { "单个备份最多 10000 条收藏" }
        require(notes.size <= MAX_ITEMS) { "单个备份最多 10000 条笔记" }
        if (master == null) require(notes.none { it.secret }) { "私密笔记不能进明文备份" }
        val rows = JSONArray()
        items.forEach { item ->
            rows.put(
                JSONObject()
                    .put("url", item.url).put("title", item.title).put("notes", item.notes)
                    .put("tags", JSONArray(parseTags(item.tags)))
                    .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
                    .put("pinned", item.pinned).put("archived", item.archived).put("read", item.read)
                    .put("summary", item.summary).put("siteName", item.siteName).put("image", item.image)
                    .put("fetchedAt", item.fetchedAt)
            )
        }
        val root = JSONObject().put("format", FORMAT).put("version", VERSION).put("exportedAt", System.currentTimeMillis())
            .put("bookmarks", rows)
        if (notes.isNotEmpty()) {
            val noteRows = JSONArray()
            notes.forEach { note ->
                noteRows.put(
                    JSONObject()
                        .put("text", note.text).put("cipher", note.cipher).put("secret", note.secret)
                        .put("createdAt", note.createdAt).put("updatedAt", note.updatedAt)
                )
            }
            root.put("notes", noteRows)
        }
        // 没设过主密码就别往文件里写一个空的 vault 对象：导入端会把它当成"要替换主密码"。
        master?.let {
            root.put("vault", JSONObject().put("salt", it.salt).put("verifier", it.verifier).put("createdAt", it.createdAt))
        }
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        // A backup must always be valid for our own importer; never silently truncate.
        decodeContents(bytes)
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

    /**
     * 导出为单文件离线交互式导航网页（HTML Portal）。
     * 内置现代化卡片布局、暗色模式适配、原生即时搜索输入框与标签筛选交互，无需任何外链与服务器。
     */
    fun encodePortalHtml(items: List<Bookmark>): ByteArray {
        require(items.size <= MAX_ITEMS) { "单个备份最多 10000 条收藏" }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
        sb.append("<meta charset=\"UTF-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        sb.append("<title>链藏 · 个人导航书签</title>\n")
        sb.append("<style>\n")
        sb.append("""
            :root {
                --bg: #F8F7F4;
                --surface: #FFFFFF;
                --text: #191B1F;
                --sub: #646A73;
                --primary: #2F4DA8;
                --primary-bg: #EAEFFC;
                --border: #E8E6E1;
                --tag-bg: #EFEFE9;
                --card-shadow: 0 1px 3px rgba(0,0,0,0.04), 0 4px 12px rgba(0,0,0,0.02);
            }
            @media (prefers-color-scheme: dark) {
                :root {
                    --bg: #141518;
                    --surface: #1E2024;
                    --text: #EDEFEA;
                    --sub: #9AA0A6;
                    --primary: #5C82FF;
                    --primary-bg: #1B2544;
                    --border: #2E3138;
                    --tag-bg: #282A30;
                    --card-shadow: 0 1px 4px rgba(0,0,0,0.2);
                }
            }
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                background: var(--bg);
                color: var(--text);
                line-height: 1.5;
                padding: 32px 20px 60px;
                min-height: 100vh;
            }
            .container { max-width: 1080px; margin: 0 auto; }
            header { margin-bottom: 24px; text-align: center; }
            .logo { font-size: 26px; font-weight: 700; letter-spacing: -0.5px; }
            .sub { font-size: 13px; color: var(--sub); margin-top: 4px; }
            .search-box {
                margin: 20px auto 16px;
                max-width: 540px;
                position: relative;
            }
            .search-box input {
                width: 100%;
                padding: 12px 18px;
                font-size: 14.5px;
                border: 1px solid var(--border);
                border-radius: 12px;
                background: var(--surface);
                color: var(--text);
                outline: none;
                transition: border-color 0.15s ease, box-shadow 0.15s ease;
            }
            .search-box input:focus {
                border-color: var(--primary);
                box-shadow: 0 0 0 3px var(--primary-bg);
            }
            .tags-bar {
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                justify-content: center;
                margin-bottom: 24px;
            }
            .tag-btn {
                background: var(--surface);
                border: 1px solid var(--border);
                color: var(--sub);
                padding: 5px 12px;
                border-radius: 20px;
                font-size: 12.5px;
                cursor: pointer;
                transition: all 0.15s ease;
            }
            .tag-btn:hover { border-color: var(--primary); color: var(--primary); }
            .tag-btn.active {
                background: var(--primary-bg);
                border-color: var(--primary);
                color: var(--primary);
                font-weight: 600;
            }
            .stats { text-align: center; font-size: 12px; color: var(--sub); margin-bottom: 20px; }
            .grid {
                display: grid;
                grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
                gap: 16px;
            }
            .card {
                background: var(--surface);
                border: 1px solid var(--border);
                border-radius: 14px;
                padding: 16px;
                box-shadow: var(--card-shadow);
                display: flex;
                flex-direction: column;
                justify-content: space-between;
                gap: 12px;
                transition: transform 0.15s ease, border-color 0.15s ease;
            }
            .card:hover {
                transform: translateY(-2px);
                border-color: var(--primary);
            }
            .card-header {
                display: flex;
                align-items: flex-start;
                gap: 10px;
            }
            .site-badge {
                width: 28px;
                height: 28px;
                border-radius: 8px;
                background: var(--primary-bg);
                color: var(--primary);
                font-size: 13px;
                font-weight: 700;
                display: flex;
                align-items: center;
                justify-content: center;
                flex-shrink: 0;
            }
            .title-area { flex: 1; min-width: 0; }
            .title-link {
                font-size: 15px;
                font-weight: 600;
                color: var(--text);
                text-decoration: none;
                word-break: break-word;
                line-height: 1.4;
            }
            .title-link:hover { color: var(--primary); text-decoration: underline; }
            .site-host {
                font-size: 11.5px;
                color: var(--sub);
                margin-top: 2px;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            .desc {
                font-size: 13px;
                color: var(--sub);
                line-height: 1.45;
                word-break: break-word;
                display: -webkit-box;
                -webkit-line-clamp: 2;
                -webkit-box-orient: vertical;
                overflow: hidden;
            }
            .notes {
                font-size: 12.5px;
                background: var(--tag-bg);
                border-radius: 8px;
                padding: 8px 10px;
                color: var(--text);
                word-break: break-word;
            }
            .card-footer {
                display: flex;
                align-items: center;
                justify-content: space-between;
                margin-top: auto;
                padding-top: 6px;
                border-top: 1px solid var(--border);
            }
            .card-tags { display: flex; flex-wrap: wrap; gap: 4px; }
            .card-tag {
                font-size: 11px;
                padding: 2px 7px;
                border-radius: 6px;
                background: var(--tag-bg);
                color: var(--sub);
            }
            .copy-btn {
                background: none;
                border: none;
                color: var(--sub);
                font-size: 12px;
                cursor: pointer;
                padding: 4px 6px;
                border-radius: 6px;
            }
            .copy-btn:hover { color: var(--primary); background: var(--primary-bg); }
        """.trimIndent())
        sb.append("\n</style>\n</head>\n<body>\n")
        sb.append("<div class=\"container\">\n")
        sb.append("  <header>\n")
        sb.append("    <div class=\"logo\">链藏 · 导航书签</div>\n")
        sb.append("    <div class=\"sub\">离线个人知识主页 · 纯本地生成</div>\n")
        sb.append("    <div class=\"search-box\"><input id=\"search\" type=\"search\" placeholder=\"输入关键词实时检索标题、链接、标签、手记…\" autocomplete=\"off\" /></div>\n")

        val allTags = items.flatMap { parseTags(it.tags) }.groupingBy { it }.eachCount()
        sb.append("    <div class=\"tags-bar\" id=\"tagsBar\">\n")
        sb.append("      <button class=\"tag-btn active\" data-tag=\"\">全部 (${items.size})</button>\n")
        allTags.toList().sortedByDescending { it.second }.forEach { (tag, count) ->
            sb.append("      <button class=\"tag-btn\" data-tag=\"${escapeHtml(tag)}\">${escapeHtml(tag)} ($count)</button>\n")
        }
        sb.append("    </div>\n")
        sb.append("    <div class=\"stats\" id=\"stats\">共 ${items.size} 条收藏</div>\n")
        sb.append("  </header>\n")

        sb.append("  <div class=\"grid\" id=\"grid\">\n")
        items.forEach { item ->
            val host = runCatching { java.net.URI(item.url).host.orEmpty() }.getOrDefault("")
            val initial = host.filter { it.isLetter() }.firstOrNull()?.uppercaseChar()?.toString() ?: "L"
            val title = if (item.title.isNotBlank()) item.title else item.url
            val tagsList = parseTags(item.tags)
            val searchData = escapeHtml((item.title + " " + item.url + " " + item.tags + " " + item.notes + " " + item.summary).lowercase())

            sb.append("    <div class=\"card\" data-search=\"$searchData\" data-tags=\"${escapeHtml(item.tags.lowercase())}\">\n")
            sb.append("      <div class=\"card-header\">\n")
            sb.append("        <div class=\"site-badge\">${escapeHtml(initial)}</div>\n")
            sb.append("        <div class=\"title-area\">\n")
            sb.append("          <a class=\"title-link\" href=\"${escapeHtml(item.url)}\" target=\"_blank\" rel=\"noopener noreferrer\">${escapeHtml(title)}</a>\n")
            sb.append("          <div class=\"site-host\">${escapeHtml(host.ifBlank { item.url })}</div>\n")
            sb.append("        </div>\n")
            sb.append("      </div>\n")
            if (item.summary.isNotBlank()) {
                sb.append("      <div class=\"desc\">${escapeHtml(item.summary)}</div>\n")
            }
            if (item.notes.isNotBlank()) {
                sb.append("      <div class=\"notes\">💭 ${escapeHtml(item.notes)}</div>\n")
            }
            sb.append("      <div class=\"card-footer\">\n")
            sb.append("        <div class=\"card-tags\">\n")
            tagsList.forEach { tag ->
                sb.append("          <span class=\"card-tag\">#${escapeHtml(tag)}</span>\n")
            }
            sb.append("        </div>\n")
            sb.append("        <button class=\"copy-btn\" data-url=\"${escapeHtml(item.url)}\">复制</button>\n")
            sb.append("      </div>\n")
            sb.append("    </div>\n")
        }
        sb.append("  </div>\n")
        sb.append("</div>\n")

        sb.append("""
            <script>
            let currentTag = '';
            const searchInput = document.getElementById('search');
            const stats = document.getElementById('stats');
            const cards = Array.from(document.querySelectorAll('.card'));
            const tagBtns = Array.from(document.querySelectorAll('.tag-btn'));
            const total = cards.length;

            function filter() {
                const query = (searchInput.value || '').trim().toLowerCase();
                let visible = 0;
                cards.forEach(card => {
                    const searchData = card.getAttribute('data-search') || '';
                    const tags = card.getAttribute('data-tags') || '';
                    const matchQuery = !query || searchData.includes(query);
                    const matchTag = !currentTag || tags.split(',').map(s=>s.trim()).includes(currentTag.toLowerCase());
                    if (matchQuery && matchTag) {
                        card.style.display = '';
                        visible++;
                    } else {
                        card.style.display = 'none';
                    }
                });
                if (visible === total) {
                    stats.textContent = '共 ' + total + ' 条收藏';
                } else {
                    stats.textContent = '显示 ' + visible + ' / 共 ' + total + ' 条';
                }
            }

            searchInput.addEventListener('input', filter);

            tagBtns.forEach(btn => {
                btn.addEventListener('click', () => {
                    tagBtns.forEach(b => b.classList.remove('active'));
                    btn.classList.add('active');
                    currentTag = btn.getAttribute('data-tag') || '';
                    filter();
                });
            });

            // 链接从按钮的 data-url 属性取，不拼进 JS 源码：
            // HTML 属性里的转义在交给脚本执行前会被还原，拼进源码等于给链接一个越狱机会。
            document.querySelectorAll('.copy-btn').forEach(btn => {
                btn.addEventListener('click', () => copyUrl(btn.getAttribute('data-url') || '', btn));
            });

            function copyUrl(url, btn) {
                if (navigator.clipboard) {
                    navigator.clipboard.writeText(url).then(() => {
                        const orig = btn.textContent;
                        btn.textContent = '已复制';
                        setTimeout(() => btn.textContent = orig, 1500);
                    });
                }
            }
            </script>
        """.trimIndent())
        sb.append("\n</body>\n</html>\n")

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

    /**
     * 一次事务写完收藏和笔记。
     *
     * 分成两个事务会留下"收藏进来了、笔记没进来"这种中间态，而界面那句"失败已整体回滚"
     * 就成了假话 —— 导入是用户最不该自己去猜结果的操作。
     */
    suspend fun mergeAll(db: VaultDb, items: List<Bookmark>, notes: List<Note>, master: VaultMaster?): MergeOutcome =
        db.withTransaction {
            val bookmarks = mergeBookmarks(db, items)
            val noteRows = mergeNoteRows(db, notes, master)
            MergeOutcome(bookmarks, noteRows)
        }

    data class MergeOutcome(val bookmarks: MergeResult, val notes: NotesMergeResult)

    suspend fun merge(db: VaultDb, items: List<Bookmark>): MergeResult = db.withTransaction { mergeBookmarks(db, items) }

    private suspend fun mergeBookmarks(db: VaultDb, items: List<Bookmark>): MergeResult {
        var added = 0
        items.forEach { item ->
            val safe = item.copy(id = 0, canonical = Links.canonical(item.url))
            if (db.bookmarks().byKey(safe.canonical) == null) { db.bookmarks().insert(safe); added++ }
        }
        return MergeResult(added, items.size - added)
    }

    /**
     * 导入笔记：永远是新增，绝不覆盖本机已有那条。
     *
     * 明文按正文原文去重；私密的比对密文 —— iv 每次不同，同一句口令两次导入会得到两段不同
     * 密文，所以重复导入会多出几条。这比"猜哪条是重复"诚实，界面上报的条数也就是实际新增条数。
     */
    suspend fun mergeNotes(db: VaultDb, notes: List<Note>, master: VaultMaster?): NotesMergeResult =
        db.withTransaction { mergeNoteRows(db, notes, master) }

    private suspend fun mergeNoteRows(db: VaultDb, notes: List<Note>, master: VaultMaster?): NotesMergeResult {
        val existing = db.notes().all()
        val plain = existing.filter { !it.secret }.mapTo(HashSet()) { it.text }
        val sealed = existing.filter { it.secret }.mapTo(HashSet()) { it.cipher }
        var added = 0
        notes.forEach { note ->
            // add() 返回 true 表示"这句之前没见过"，也就是可以写进去。
            val fresh = if (note.secret) sealed.add(note.cipher) else plain.add(note.text)
            if (fresh) {
                db.notes().insert(note.copy(id = 0))
                added++
            }
        }
        // 本机已经有主密码时保持本机那个：换掉它等于让现有全部私密笔记当场变"解不开"。
        val adopted = master != null && db.vault().master() == null &&
            db.vault().insert(master.copy(id = VaultMaster.ROW_ID)) > 0L
        return NotesMergeResult(added, notes.size - added, adopted)
    }
}

/** 一份备份文件里的全部内容。 */
data class Contents(val bookmarks: List<Bookmark>, val notes: List<Note>, val master: VaultMaster?)

data class MergeResult(val added: Int, val skipped: Int)
data class NotesMergeResult(val added: Int, val skipped: Int, val masterAdopted: Boolean)
data class ImportPreview(
    val items: List<Bookmark>,
    val added: Int,
    val notes: List<Note> = emptyList(),
    /** 这份备份自带的主密码记录；本机没有时导入时启用它。 */
    val master: VaultMaster? = null
) {
    val skipped get() = items.size - added
    /** 明文备份里永远不会有私密笔记，所以这个数只在加密导入时大于 0。 */
    val secretNotes get() = notes.count { it.secret }
}
