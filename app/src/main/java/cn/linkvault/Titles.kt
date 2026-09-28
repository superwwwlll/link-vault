package cn.linkvault

/**
 * 标题的离线整理。
 *
 * 全是纯字符串处理，一个请求都不发。抓来的标题和系统分享递来的标题常常带换行符、尾巴上粘着站点名，
 * 或者干脆就是「首页」「Untitled」这类空壳；这里统一收拾干净。
 * 收拾完仍是空壳时返回空串，由调用方决定退回哪条路（正文第一句 → 路径推导 → 站点名）。
 */
internal object Titles {
    const val LIMIT = 200

    /** 换行、制表、不间断空格、全角空格、零宽字符——网页标题里这几样都实际出现过。 */
    private val spaces = Regex("[\\s\\u00A0\\u2000-\\u200B\\u2028\\u2029\\u3000\\uFEFF]+")
    private val control = Regex("[\\u0000-\\u001F\\u007F]")

    /** 中文标题里的换行该接回句子，不该留一个西文空格；西文与中文混排时两侧的空格照原样保留。 */
    private val cjkGap = Regex(
        "(?<=[\\u2E80-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]) " +
            "(?=[\\u2E80-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF])"
    )

    /**
     * 站点名常见的分隔写法。刻意不含裸连字符：
     * "Well-Known" 这类词里也有它，按裸 "-" 切会把正常标题切短。
     */
    private val tails = listOf(" | ", " \uFF5C ", " |", " - ", " \u2013 ", " \u2014 ", " \u00B7 ", " \u2022 ", " _ ", "_", " \u00BB ", " \u300B ")

    /** 尾部括号里粘站点名：「标题【知乎】」「标题（CSDN）」。 */
    private val bracketTail = Regex("\\s*[\\[\\u3010(\\uFF08\\u3008][^\\]\\u3011)\\uFF09\\u3009]{1,40}[\\]\\u3011)\\uFF09\\u3009]$")

    /** 这些标题等于没写。比较前一律 normalize（小写、去空白）。 */
    private val EMPTY_TITLES = setOf(
        "首页", "主页", "欢迎", "未命名", "无标题", "无标题文档", "新标签页", "新标签", "正文", "文章",
        "详情", "新闻", "原文", "登录", "注册", "加载中", "页面不存在", "出错了", "错误", "示例域名",
        "home", "homepage", "welcome", "untitled", "no title", "new page", "loading", "article", "post",
        "news", "document", "example domain", "sign in", "log in", "login", "error", "404", "404 not found",
        "page not found", "not found", "index"
    )

    /** 比较用的形式：集合里的词也一律 normalize，否则带空格的「no title」永远对不上。 */
    private val EMPTY = EMPTY_TITLES.map { normalize(it) }.toSet()

    fun collapse(value: String): String =
        value.replace(control, " ").replace(spaces, " ").replace(cjkGap, "").trim().take(LIMIT)

    /** 用来比较的形式：小写、去空白，站点名和标题才能对上。 */
    private fun normalize(value: String): String =
        value.lowercase().replace(spaces, "")

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty().substringAfter("www.") }.getOrDefault("")

    /** 域名主干："bilibili.com" → "bilibili"。站点装饰里出现的多半是它，不是中文名。 */
    private fun hostStemOf(url: String): String = hostOf(url).substringBefore(".").trimEnd('-')

    private fun knownOf(url: String, siteName: String): List<String> =
        listOf(siteName, Links.siteName(url), hostOf(url), hostStemOf(url)).map { normalize(it) }

    /**
     * 剥掉尾部粘着的站点名与空壳词，最多剥三层（"标题 - 站点 - 首页" 这种叠了两层的也要能拆开）。
     * 只剥「和站点名对得上」或「本来就是空壳词」的那一段，不猜别的短词。
     */
    fun stripTails(url: String, title: String, siteName: String = ""): String {
        var value = title
        val known = knownOf(url, siteName).filter { it.isNotEmpty() }
        var steps = 0
        while (steps++ < 3) {
            val before = value
            // 先看括号层
            bracketTail.find(value)?.let { match ->
                val inner = normalize(match.value)
                if (known.any { it.isNotEmpty() && inner.contains(it) }) value = value.removeSuffix(match.value).trim()
            }
            if (value != before) continue
            val cut = tails.mapNotNull { mark -> value.lastIndexOf(mark).takeIf { it > 0 } }
                .maxOrNull() ?: break
            val tail = value.substring(cut).let { t -> tails.fold(t) { s, mark -> s.removePrefix(mark) }.trim() }
            val normalized = normalize(tail)
            val droppable = tail.isNotBlank() && normalized.length <= 40 &&
                (known.any { it == normalized } || normalized.isEmpty() || EMPTY.contains(normalized))
            if (!droppable) break
            value = value.substring(0, cut).trim()
        }
        return value.trim()
    }

    /** 这个标题值不值得留：空壳词、纯符号数字、只有站点名/域名都算不值得。 */
    fun meaningless(url: String, title: String): Boolean {
        val value = collapse(title)
        if (value.isBlank()) return true
        val normalized = normalize(value)
        if (normalized.length < 2) return true
        if (EMPTY.contains(normalized)) return true
        if (value.none { it.isLetterOrDigit() }) return true
        // 纯数字通常是评论数、播放量或页面编号，不是标题
        if (value.all { it.isDigit() }) return true
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return true
        return knownOf(url, "").any { it.isNotEmpty() && it == normalized }
    }

    /** 清洗并判定：留不下有效信息时返回空串。 */
    fun cleanse(url: String, title: String, siteName: String = ""): String {
        val value = collapse(stripTails(url, title, siteName))
        return if (meaningless(url, value)) "" else value
    }

    /**
     * 从本地正文快照里取一个能认出来的标题：先找 `# 一级标题`，再退到第一段的第一句。
     * 只读已经在磁盘上的文字，不联网。
     */
    fun fromArticle(markdown: String): String {
        if (markdown.isBlank()) return ""
        val heading = markdown.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim().orEmpty()
        if (heading.length >= 4) return collapse(heading).take(80)

        val skip = listOf("#", ">", "-", "*", "!", "|", "```")
        val paragraph = markdown.lineSequence()
            .map { it.trim() }
            .firstOrNull { line -> line.isNotEmpty() && line.length >= 12 && skip.none { line.startsWith(it) } }
            ?: return ""
        val first = paragraph.split(Regex("[\\u3002\\uFF01\\uFF1F\\uFF1B;\uFF1A]")).firstOrNull { normalize(it).length >= 8 }.orEmpty().trim()
        if (first.length < 8) return ""
        return if (first.length > 48) collapse(first.take(48)) + "\u2026" else collapse(first)
    }

    /**
     * 决定入库标题：自己写过的原样保留（只清洗排版），
     * 空壳或没有时才依次取抓取到的页面标题、正文里取出来的名字。
     */
    fun resolve(url: String, stored: String, fetched: String, siteName: String = "", article: String? = null): String {
        val mine = cleanse(url, stored, siteName)
        if (mine.isNotEmpty()) return mine
        val page = cleanse(url, fetched, siteName)
        if (page.isNotEmpty()) return page
        if (article != null) {
            val derived = cleanse(url, fromArticle(article), siteName)
            if (derived.isNotEmpty()) return derived
        }
        return ""
    }
}
