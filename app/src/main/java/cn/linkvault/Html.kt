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
    private val imgTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val brackets = Regex("[\\[\\]]")

    /** 懒加载站点的真实地址常在这些属性里，src 往往是一张占位图，所以 src 排最后。 */
    private val IMAGE_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "src")

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
     * 本地正文快照提取与结构化 Markdown 转换（Reader Mode + Pangu Spacing）。
     * 1. 过滤脚本、样式、广告、弹窗与页脚导航噪音；
     * 2. 转换为规范 Markdown 语法：标题 (#, ##, ###)、引用 (>)、列表 (-)、粗体 (**)、图片 (![alt](url))、链接 ([text](url)) 与代码块 (```)；
     * 3. 盘古排版：中英文与数字间自动补充空格，段落排版优雅舒适。
     *
     * baseUrl 用于把 `<img src="/a.png">` 这类相对地址展开；留空时正文图片一律丢弃，
     * 因为原样写进快照就是一张必然加载失败的图。
     */
    fun extractArticle(html: String, baseUrl: String = ""): String {
        if (html.isBlank()) return ""
        val source = html.take(600_000)

        // 1. 剔除噪声标签块与 HTML 注释
        var s = source
            .replace(Regex("<(script|style|noscript|svg|iframe|header|footer|nav|aside|button|form)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

        // 2. 代码块转换：<pre><code>...</code></pre> -> ```\n...\n```
        s = s.replace(Regex("<pre\\b[^>]*>(?:<code\\b[^>]*>)?(.*?)(?:</code>)?</pre>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            val code = m.groupValues[1].replace(tags, "").trim()
            "\n\n```\n${decodeEntities(code)}\n```\n\n"
        }

        // 3. 标题层级转换
        s = s.replace(Regex("<h1\\b[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n# $1\n\n")
            .replace(Regex("<h2\\b[^>]*>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n## $1\n\n")
            .replace(Regex("<h3\\b[^>]*>(.*?)</h3>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n### $1\n\n")
            .replace(Regex("<h[4-6]\\b[^>]*>(.*?)</h[4-6]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n#### $1\n\n")

        // 4. 引用块转换：<blockquote>...</blockquote> -> > ...
        s = s.replace(Regex("<blockquote\\b[^>]*>(.*?)</blockquote>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            val content = m.groupValues[1].replace(tags, " ")
            val lines = decodeEntities(content).lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) "" else "\n\n" + lines.joinToString("\n") { "> $it" } + "\n\n"
        }

        // 5. 列表转换：<li>...</li> -> \n- ...\n
        s = s.replace(Regex("<li\\b[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n- $1\n")

        // 6. 行内强调与行内代码
        s = s.replace(Regex("<(strong|b)\\b[^>]*>(.*?)</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "**$2**")
            .replace(Regex("<(em|i)\\b[^>]*>(.*?)</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "*$2*")
            .replace(Regex("<code\\b[^>]*>(.*?)</code>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "`$1`")

        // 7. 图片转换：<img src="..."> -> ![alt](url)，必须排在超链接之前，
        //    这样 <a href><img></a> 会组合成 [![alt](图)](链接) 而不是丢掉图
        s = s.replace(imgTag) { m ->
            val tag = m.value
            val src = IMAGE_ATTRS.firstNotNullOfOrNull { name ->
                attribute(tag, name)?.let { Links.absolute(baseUrl, it) }.orEmpty().takeIf { it.isNotEmpty() }
            } ?: return@replace ""
            // 1x1 占位图是埋点，不是内容；它甚至常常就是正文里唯一的“图”
            val spacer = listOf("width", "height").any { attribute(tag, it) in setOf("1", "1px") }
            if (spacer) return@replace ""
            val alt = attribute(tag, "alt").orEmpty().replace(brackets, "").take(200)
            "\n\n![${alt}]($src)\n\n"
        }

        // 8. 超链接转换：<a href="...">text</a> -> [text](url)
        s = s.replace(Regex("""<a\b[^>]*href=["'](https?://[^"'\s]+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            val href = m.groupValues[1].trim()
            val text = m.groupValues[2].replace(tags, " ").trim()
            val cleanText = decodeEntities(text).replace(whitespace, " ").trim()
            if (cleanText.isBlank() || cleanText.equals(href, ignoreCase = true)) href
            else "[$cleanText]($href)"
        }

        // 9. 块级标签换行与普通断行
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(p|div|section|article|tr|hr|table|tbody)\\b[^>]*>"), "\n\n")

        // 10. 清除残留标签并解码 HTML 实体
        val stripped = s.replace(tags, " ")
        val decoded = decodeEntities(stripped)

        // 11. 分行整理、盘古排版（中英文数字混排加空格）与空行归一化
        val lines = mutableListOf<String>()
        var inCodeBlock = false
        for (rawLine in decoded.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                lines.add(line)
                continue
            }
            if (inCodeBlock) {
                lines.add(rawLine)
                continue
            }
            if (line.isBlank()) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                continue
            }
            // 规整行内多余空格
            val collapsed = line.replace(Regex("[ \\t]{2,}"), " ")
            val beautified = pangu(collapsed)
            lines.add(beautified)
        }

        val result = lines.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return result.take(35_000)
    }

    /**
     * 盘古之白（Pangu Spacing）：在中文与半角英文/数字间自动补充空格，优化排版体验。
     */
    fun pangu(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace(Regex("([\\u4e00-\\u9fa5])([a-zA-Z0-9])"), "$1 $2")
            .replace(Regex("([a-zA-Z0-9])([\\u4e00-\\u9fa5])"), "$1 $2")
    }
}
