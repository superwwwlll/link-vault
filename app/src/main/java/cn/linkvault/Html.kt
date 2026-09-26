package cn.linkvault

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 极简 HTML 片段工具：只为了从页面头部或剪贴板片段里取出标题与描述。
 *
 * 不做完整 DOM 解析、不执行脚本、不引入第三方依赖；全部是纯函数，便于单元测试。
 */
internal object Html {
    private val tags = Regex("<[^>]*>")
    private val whitespace = Regex("\\s+")
    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("<title\\b[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attrCache = ConcurrentHashMap<String, Regex>()

    /** 只扫描文件头部这么多字符，避免超大页面拖慢解析。 */
    const val SCAN_LIMIT = 200_000

    /** 单个取值的长度上限，防住畸形页面。 */
    const val VALUE_LIMIT = 800

    private fun attrPattern(name: String) = attrCache.getOrPut(name) {
        Regex("(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))", RegexOption.IGNORE_CASE)
    }

    /** 从单个标签文本里取属性值；三种引号写法都支持。 */
    fun attribute(tag: String, name: String): String? {
        val match = attrPattern(name).find(tag) ?: return null
        val value = match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }
        val decoded = collapse(decodeEntities(value))
        return decoded.ifBlank { null }
    }

    /** 按 keys 的优先级取第一个命中的 meta 内容（例如 og:description 优先于 description）。 */
    fun metaContent(html: String, keys: List<String>): String? {
        val source = html.take(SCAN_LIMIT)
        val found = HashMap<String, String>()
        for (match in metaTag.findAll(source)) {
            val tag = match.value
            val key = attribute(tag, "property") ?: attribute(tag, "name") ?: attribute(tag, "itemprop") ?: continue
            val content = attribute(tag, "content") ?: continue
            found.putIfAbsent(key.lowercase(Locale.ROOT), content)
        }
        keys.forEach { wanted -> found[wanted.lowercase(Locale.ROOT)]?.let { return it } }
        return null
    }

    fun titleTag(html: String): String? {
        val match = titleTag.find(html.take(SCAN_LIMIT)) ?: return null
        return collapse(decodeEntities(match.groupValues[1]))
    }

    /** 去掉标签取纯文本，用于 &lt;a&gt; 里的可见文字。 */
    fun text(fragment: String): String = collapse(decodeEntities(fragment.replace(tags, " ")))

    fun collapse(value: String): String = value.replace(whitespace, " ").trim().take(VALUE_LIMIT)

    fun decodeEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '&') { out.append(char); index++; continue }
            val end = value.indexOf(';', index + 1)
            if (end < 0 || end - index > 12) { out.append(char); index++; continue }
            val body = value.substring(index + 1, end)
            val replacement = when {
                body.startsWith("#x", true) -> body.drop(2).toIntOrNull(16)?.let(::fromCodePoint)
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::fromCodePoint)
                else -> named[body.lowercase(Locale.ROOT)]
            }
            if (replacement == null) { out.append(char); index++ } else { out.append(replacement); index = end + 1 }
        }
        return out.toString()
    }

    private fun fromCodePoint(value: Int): String? = try {
        if (value in 0x20..0x10FFFF) String(Character.toChars(value)) else null
    } catch (_: IllegalArgumentException) { null }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "laquo" to "«", "raquo" to "»",
        "times" to "×", "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "bull" to "•",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "shy" to "", "minus" to "−", "plusmn" to "±",
        "frac12" to "½", "sect" to "§", "para" to "¶", "prime" to "′", "rarr" to "→", "larr" to "←"
    )

    /**
     * 极简本地正文快照提取（Reader Mode）。
     * 移除脚本、样式、导航与页脚，提取可读段落，保留结构排版。
     */
    fun extractArticle(html: String): String {
        val source = html.take(500_000)
        val cleaned = source
            .replace(Regex("<(script|style|noscript|svg|iframe|header|footer|nav|aside)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        val withNewlines = cleaned
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(p|div|h[1-6]|li|blockquote|tr|section|article)\\b[^>]*>"), "\n")
        val stripped = withNewlines.replace(tags, " ")
        val decoded = decodeEntities(stripped)
        val lines = decoded.lines()
            .map { it.replace(whitespace, " ").trim() }
            .filter { it.isNotBlank() }
        return lines.joinToString("\n\n").take(30_000)
    }
}
