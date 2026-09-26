package cn.linkvault

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

object Links {
    private val pattern = Regex("https?://[^\\s<>\"'，。；！？【】「」]+", RegexOption.IGNORE_CASE)
    private val htmlHref = Regex(
        "<a\\b[^>]*\\bhref\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s>]+))",
        RegexOption.IGNORE_CASE
    )

    fun extract(text: String): List<String> = extractPlain(text)

    /**
     * Extract URLs from the text first, then fall back to anchor hrefs in HTML.
     * Some share targets provide only EXTRA_HTML_TEXT (no EXTRA_TEXT); ignoring
     * that payload makes an otherwise valid shared link look empty.
     */
    fun extract(text: String, html: String): List<String> {
        val plain = extractPlain(text)
        if (plain.isNotEmpty() || html.isBlank()) return plain
        return htmlHref.findAll(html.take(Html.SCAN_LIMIT)).mapNotNull { match ->
            val raw = match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }
            val href = Html.decodeEntities(raw)
            extractPlain(href).firstOrNull()
        }.distinct().toList()
    }

    private fun extractPlain(text: String): List<String> = pattern.findAll(text).map { match ->
        var value = match.value.trimEnd('.', ',', ';', '!', '?', '。', '，', '）', '】')
        while (value.endsWith(")") && value.count { it == ')' } > value.count { it == '(' }) value = value.dropLast(1)
        value
    }.filter { valid(it) }.distinct().toList()

    fun valid(value: String): Boolean = try {
        val uri = URI(value)
        val host = uri.host
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65535) &&
            value.none { it.isWhitespace() || it.isISOControl() || it == '\\' } &&
            !value.contains(Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE))
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- 去重键

    /** 只在 X/Twitter 去掉的参数。普通站点的 s / t 有真实含义，不能跟着一起删。 */
    private val xOnly = setOf("s", "t")

    /**
     * 业界通用的跟踪参数：任何站点上都丢弃。
     * 不做这一步，同一篇文章从微信、X、浏览器分别分享进来就会各存一条。
     */
    private val tracking = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "utm_name", "utm_reader", "utm_brand", "utm_social", "utm_social-type",
        "fbclid", "gclid", "gbraid", "wbraid", "msclkid", "dclid", "twclid", "igshid", "ttclid", "yclid",
        "mc_cid", "mc_eid", "_hsenc", "_hsmi", "ref_src", "ref_url",
        "spm", "scm", "share_source", "share_medium", "share_plat", "share_session_id", "share_tag", "share_token",
        "wxshare", "hmsr", "hmpl", "hmcu", "hmkw", "hmci"
    )

    private fun keepQuery(key: String, x: Boolean): Boolean {
        val k = key.lowercase(Locale.ROOT)
        return !(k in tracking || (x && k in xOnly))
    }

    fun canonical(value: String): String {
        require(valid(value)) { "请输入有效的 HTTP/HTTPS 链接（不支持用户名密码或控制字符）" }
        val u = URI(value)
        var host = u.host.lowercase(Locale.ROOT)
        val x = host in setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")
        if (x) host = "x.com"
        val scheme = if (x) "https" else u.scheme.lowercase(Locale.ROOT)
        val port = if (u.port == -1 || u.port == 80 && u.scheme.equals("http", true) || u.port == 443 && u.scheme.equals("https", true)) "" else ":${u.port}"
        val path = u.rawPath.ifEmpty { "/" }
        val query = u.rawQuery?.split("&")?.filter { it.isNotEmpty() }?.filter { keepQuery(it.substringBefore("="), x) }?.joinToString("&")
        return "$scheme://$host$port$path" + (if (query.isNullOrEmpty()) "" else "?$query") +
            (if (!x && u.rawFragment != null) "#${u.rawFragment}" else "")
    }

    // ---------------------------------------------------------------- 展示用名称

    fun host(value: String): String =
        runCatching { URI(value).host }.getOrNull()?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()

    /** 常见站点用中文/品牌名展示，卡片第一行才像人话。 */
    private val sites = mapOf(
        "x.com" to "X", "github.com" to "GitHub", "gist.github.com" to "GitHub Gist",
        "gitee.com" to "Gitee", "gitlab.com" to "GitLab",
        "mp.weixin.qq.com" to "微信公众号", "weixin.qq.com" to "微信", "weread.qq.com" to "微信读书",
        "bilibili.com" to "哔哩哔哩", "zhihu.com" to "知乎", "juejin.cn" to "掘金", "csdn.net" to "CSDN",
        "cnblogs.com" to "博客园", "segmentfault.com" to "SegmentFault", "oschina.net" to "开源中国",
        "sspai.com" to "少数派", "36kr.com" to "36氪", "douban.com" to "豆瓣", "weibo.com" to "微博",
        "xiaohongshu.com" to "小红书", "xhslink.com" to "小红书", "jike.city" to "即刻",
        "youtube.com" to "YouTube", "youtu.be" to "YouTube", "vimeo.com" to "Vimeo",
        "notion.so" to "Notion", "notion.site" to "Notion", "figma.com" to "Figma",
        "medium.com" to "Medium", "substack.com" to "Substack", "reddit.com" to "Reddit",
        "news.ycombinator.com" to "Hacker News", "stackoverflow.com" to "Stack Overflow",
        "developer.android.com" to "Android 开发者", "developer.apple.com" to "Apple 开发者",
        "kotlinlang.org" to "Kotlin", "jetbrains.com" to "JetBrains", "developer.mozilla.org" to "MDN",
        "arxiv.org" to "arXiv", "nature.com" to "Nature", "openai.com" to "OpenAI",
        "huggingface.co" to "Hugging Face", "apple.com" to "Apple", "microsoft.com" to "Microsoft",
        "wikipedia.org" to "维基百科", "linkedin.com" to "LinkedIn", "dribbble.com" to "Dribbble",
        "behance.net" to "Behance", "pinterest.com" to "Pinterest", "spotify.com" to "Spotify",
        "nytimes.com" to "纽约时报", "bbc.com" to "BBC", "economist.com" to "经济学人",
        "taobao.com" to "淘宝", "jd.com" to "京东", "cloud.tencent.com" to "腾讯云"
    )

    /** 依次向上找域名后缀，blog.github.com 也能命中 GitHub。 */
    fun siteName(value: String): String {
        val host = host(value)
        if (host.isEmpty()) return "链接"
        var probe = host
        while (probe.contains('.')) {
            sites[probe]?.let { return it }
            probe = probe.substringAfter('.', "")
        }
        return host
    }

    // ---------------------------------------------------------------- 从 URL 推导标题

    private val pathNoise = setOf(
        "index", "index.html", "index.htm", "index.php", "index.asp", "index.aspx", "default", "default.html",
        "en", "zh", "zh-cn", "zh-hans", "zh-hant", "cn", "us", "docs", "doc", "document",
        "article", "articles", "post", "posts", "p", "page", "pages", "status", "statuses",
        "watch", "view", "main", "home", "blog", "news", "s", "v", "w", "wiki", "abs", "pdf",
        "html", "htm", "amp", "story", "stories", "detail", "details", "share", "redirect"
    )

    private val fileExtensions = listOf(".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".shtml", ".cgi", ".md", ".txt", ".json", ".pdf")

    /** 看起来很像是随机 ID 而不是人写的词。 */
    private fun looksOpaque(segment: String): Boolean {
        if (segment.isEmpty()) return true
        if (segment.all { it.isDigit() }) return true
        if (segment.length < 10) return false
        if (segment.contains('-') || segment.contains('_')) return false
        val digits = segment.any { it.isDigit() }
        val letters = segment.any { it.isLetter() }
        // AbCdEf123 这类短码：既有数字又有字母，还没有任何分隔符
        if (digits && letters) return true
        // AbCdEfGhIjKl 这类 base64 风格 token：大小写混排且没有分隔符
        if (segment.any { it.isUpperCase() } && segment.any { it.isLowerCase() }) return true
        return segment.length >= 20
    }

    private fun prettify(segment: String): String {
        var value = segment.substringBefore('?').substringBefore('#')
        val lower = value.lowercase(Locale.ROOT)
        for (ext in fileExtensions) {
            if (lower.endsWith(ext)) { value = value.dropLast(ext.length); break }
        }
        value = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
        value = value.replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        if (value.isEmpty()) return ""
        if (value.length > 60) value = value.take(60).substringBeforeLast(' ').ifBlank { value.take(60) }
        return value.split(' ').joinToString(" ") { word ->
            when {
                word.isEmpty() -> word
                word[0].code < 128 -> word[0].uppercaseChar() + word.drop(1)
                else -> word
            }
        }
    }

    /** 路径本身常常已经说明这是什么，比直接甩出整条 URL 有用得多。 */
    fun readable(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return ""
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return ""
        val segments = (uri.rawPath ?: "").split('/').map { it.trim() }.filter { it.isNotEmpty() }
        when {
            host == "x.com" && segments.isNotEmpty() -> return "@" + segments[0]
            host.endsWith("github.com") && segments.size >= 2 -> return segments[0] + "/" + segments[1]
            host == "gitee.com" && segments.size >= 2 -> return segments[0] + "/" + segments[1]
            host.endsWith("bilibili.com") && segments.size >= 2 && segments[0] == "video" -> return "视频 " + segments[1]
            host == "youtube.com" || host == "youtu.be" -> return "YouTube 视频"
            host.endsWith("zhihu.com") && segments.firstOrNull() == "question" && segments.size >= 2 -> return "知乎 " + segments[1]
        }
        val candidate = segments.asReversed().firstOrNull { segment ->
            val clean = segment.substringBefore('?').substringBefore('#')
            clean.isNotEmpty() && clean.lowercase(Locale.ROOT) !in pathNoise && !looksOpaque(clean)
        } ?: return ""
        return prettify(candidate)
    }

    /** 标题为空时依次退回：路径推导 → 站点名。永远不把整条 URL 当作标题显示。 */
    fun displayTitle(url: String, title: String): String {
        val given = title.trim()
        if (given.isNotEmpty()) return given
        val derived = readable(url)
        if (derived.isNotEmpty()) return derived
        return siteName(url)
    }

    // ---------------------------------------------------------------- 从系统分享 / 剪贴板拿到标题

    private val urlToken = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    /**
     * 浏览器和系统分享常常已经把页面标题放在 EXTRA_SUBJECT 里递过来了。
     * 明显不是标题的内容（URL、纯符号）一律丢弃。
     */
    fun titleFromShared(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 400) return ""
        var text = urlToken.replace(raw, " ").replace(Regex("\\s+"), " ").trim().trim('"', '\'', '“', '”', '«', '»', '-', '—', '|')
        if (text.isEmpty() || text.length > 200) text = text.take(200).trim()
        if (text.isEmpty()) return ""
        if (valid(text)) return ""
        if (text.none { it.isLetter() }) return ""
        return text
    }

    /** 剪贴板里复制超链接时通常同时带一份 HTML，标题就藏在里面。完全离线。 */
    fun titleFromHtml(html: String?, url: String): String {
        val source = html?.take(Html.SCAN_LIMIT).orEmpty()
        if (source.isEmpty() || source.indexOf("<a") < 0) return ""
        val target = runCatching { canonical(url) }.getOrNull() ?: return ""
        val open = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE)
        var from = 0
        while (true) {
            val head = open.find(source, from) ?: return ""
            val href = Html.attribute(head.value, "href")
            if (href == null) { from = head.range.last + 1; continue }
            val close = source.indexOf("</a", head.range.last + 1)
            if (close < 0) return ""
            val text = Html.text(source.substring(head.range.last + 1, close))
            val same = runCatching { canonical(Html.decodeEntities(href)) }.getOrNull() == target
            if (same && text.isNotEmpty()) return text.take(200)
            from = close + 3
        }
    }

    // ---------------------------------------------------------------- 标签

    /** 标签归一键：大小写与首尾空白不影响归属，"设计" 和 "设计 " 是同一个标签。 */
    fun tagKey(tag: String): String = tag.trim().lowercase(Locale.ROOT)

    /** 按归一键聚合标签，展示名取首次出现的写法。 */
    fun tagTotals(items: List<Bookmark>): List<Pair<String, Int>> {
        val names = LinkedHashMap<String, String>()
        val counts = LinkedHashMap<String, Int>()
        items.forEach { item ->
            parseTags(item.tags).forEach { tag ->
                val key = tagKey(tag)
                names.putIfAbsent(key, tag)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.keys.sorted().map { names[it].orEmpty() to (counts[it] ?: 0) }
    }
}

/** 标签输入归一：中英文逗号、分号、换行都算分隔符，并按归一键去重。 */
fun parseTags(value: String): List<String> {
    val seen = LinkedHashSet<String>()
    return value.split(',', '，', ';', '；', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && seen.add(Links.tagKey(it)) }
}

fun matches(item: Bookmark, search: String, tag: String): Boolean {
    val wanted = Links.tagKey(tag)
    val tagOk = tag.isEmpty() || parseTags(item.tags).any { Links.tagKey(it) == wanted }
    if (!tagOk) return false
    if (search.isBlank()) return true
    val needle = search.trim()
    return listOf(item.title, item.url, item.notes, item.tags, item.summary, item.siteName)
        .any { it.contains(needle, ignoreCase = true) }
}
