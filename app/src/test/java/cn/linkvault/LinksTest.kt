package cn.linkvault
import org.junit.Assert.*
import org.junit.Test

class LinksTest {
    private val example = "https://x.com/Astronaut_1216/status/2097610542127223251?s=20"
    @Test fun sample() { assertEquals("https://x.com/Astronaut_1216/status/2097610542127223251", Links.canonical(example)) }
    @Test fun twitterAliases() { assertEquals(Links.canonical(example), Links.canonical("http://mobile.twitter.com/Astronaut_1216/status/2097610542127223251?t=abcd&s=20#ref")) }
    @Test fun preserveOtherParameters() { assertEquals("https://x.com/a/status/123?lang=zh", Links.canonical("https://x.com/a/status/123?s=20&lang=zh&t=abc")) }
    @Test fun ordinaryQueryPreserved() { assertEquals("https://example.com/p?s=20&t=abc#top", Links.canonical("https://EXAMPLE.com:443/p?s=20&t=abc#top")) }
    @Test fun multipleLinksAndPunctuation() { assertEquals(listOf(example, "https://example.com/a"), Links.extract("看看（$example）。还有 https://example.com/a！")) }
    @Test fun balancedParentheses() { assertEquals(listOf("https://en.wikipedia.org/wiki/Test_(assessment)"), Links.extract("(https://en.wikipedia.org/wiki/Test_(assessment))")) }
    @Test fun unsafeUrlsRejected() { listOf("javascript:alert(1)", "file:///etc/passwd", "intent://abc", "https://", "https://user:pass@example.com", "https://example.com:99999", "https://example.com/a\nb", "https://example.com/%0d%0aX").forEach { assertFalse(it, Links.valid(it)) } }
    @Test fun fragmentsRemainDistinct() { assertNotEquals(Links.canonical("https://example.com/#/page1"), Links.canonical("https://example.com/#/page2")) }
    @Test fun mixedCaseSchemeAccepted() { assertTrue(Links.valid("Https://example.com")) }
    @Test fun httpAccepted() { assertTrue(Links.valid("http://example.com/a?b=1")) }
    @Test fun spoofedXNotNormalized() { assertEquals("https://x.com.evil.example/a?s=20", Links.canonical("https://x.com.evil.example/a?s=20")) }
    @Test fun tagsAndSearch() {
        assertEquals(listOf("技术", "稍后读"), parseTags("技术， 稍后读,技术;;"))
        val b = Bookmark(url = example, canonical = Links.canonical(example), title = "Compose", notes = "状态恢复", tags = "技术,安卓")
        assertTrue(matches(b, "compose", "安卓")); assertTrue(matches(b, "状态", "")); assertFalse(matches(b, "", "安"))
    }

    // ---------------------------------------------------------- 新增：去重键扩展

    @Test fun trackingParametersAreDroppedOnEverySite() {
        assertEquals("https://example.com/a?keep=1", Links.canonical("https://example.com/a?utm_source=x&keep=1&fbclid=y&spm=z"))
        assertEquals(Links.canonical("https://example.com/a"), Links.canonical("https://example.com/a?utm_campaign=news&igshid=abc"))
    }

    @Test fun sameArticleFromTwoSourcesCollapsesToTheSameKey() {
        assertEquals(
            Links.canonical("https://example.com/post?id=9&utm_source=wechat&share_session_id=abc"),
            Links.canonical("https://example.com/post?id=9&utm_source=twitter")
        )
        assertEquals("https://example.com/post?id=9", Links.canonical("https://example.com/post?id=9&utm_source=wechat&share_session_id=abc"))
    }

    @Test fun meaningfulParametersSurvive() {
        assertEquals("https://example.com/s?q=compose&page=2", Links.canonical("https://example.com/s?q=compose&utm_medium=email&page=2"))
    }

    // ---------------------------------------------------------- 新增：可读标题

    @Test fun readableTitleFromPath() {
        assertEquals("Room Migration", Links.readable("https://developer.android.com/docs/room-migration"))
        assertEquals("Compose", Links.readable("https://developer.android.com/compose"))
        assertEquals("Getting Started", Links.readable("https://example.com/guide/getting_started.html"))
    }

    @Test fun readableTitleKnowsCommonShapes() {
        assertEquals("a/b", Links.readable("https://github.com/a/b"))
        assertEquals("@someone", Links.readable("https://x.com/someone/status/1"))
        assertEquals("YouTube 视频", Links.readable("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun opaqueIdentifiersAreNotTreatedAsTitles() {
        assertEquals("", Links.readable("https://mp.weixin.qq.com/s/AbCdEfGhIjKl"))
        assertEquals("", Links.readable("https://example.com/status/2097610542127223251"))
        assertEquals("", Links.readable("https://example.com/"))
    }

    @Test fun displayTitleNeverFallsBackToTheRawUrl() {
        assertEquals("我的标题", Links.displayTitle("https://example.com/design", "我的标题"))
        assertEquals("Design", Links.displayTitle("https://example.com/design", ""))
        assertEquals("微信公众号", Links.displayTitle("https://mp.weixin.qq.com/s/AbCdEfGhIjKl", ""))
    }

    @Test fun siteNameUsesBrandNamesAndWalksUpSubdomains() {
        assertEquals("GitHub", Links.siteName("https://github.com/a/b"))
        assertEquals("GitHub Gist", Links.siteName("https://gist.github.com/a"))
        assertEquals("GitHub", Links.siteName("https://blog.github.com/a"))
        assertEquals("哔哩哔哩", Links.siteName("https://bilibili.com/x"))
        assertEquals("example.com", Links.siteName("https://www.example.com/x"))
        assertEquals("链接", Links.siteName("not a url"))
    }

    // ---------------------------------------------------------- 新增：系统已递过来的标题

    @Test fun sharedSubjectIsUsedButUrlsAndJunkAreRejected() {
        assertEquals("值得慢慢看的宇宙", Links.titleFromShared("值得慢慢看的宇宙"))
        assertEquals("标题", Links.titleFromShared("标题 https://example.com/a"))
        assertEquals("", Links.titleFromShared("https://example.com/a"))
        assertEquals("", Links.titleFromShared("   "))
        assertEquals("", Links.titleFromShared("12345"))
        assertEquals("", Links.titleFromShared("x".repeat(500)))
        assertEquals("", Links.titleFromShared(null))
    }

    @Test fun clipboardHtmlYieldsTheLinkTitle() {
        val html = """<meta charset='utf-8'><a href="https://example.com/x">很棒的页面</a>"""
        assertEquals("很棒的页面", Links.titleFromHtml(html, "https://example.com/x"))
    }

    @Test fun clipboardHtmlMatchesAfterTrackingParamsAreStripped() {
        val html = """<a href="https://example.com/x?utm_source=wechat&amp;id=3">跟着编码走</a>"""
        assertEquals("跟着编码走", Links.titleFromHtml(html, "https://example.com/x?id=3"))
    }

    @Test fun clipboardHtmlForAnotherLinkIsIgnored() {
        val html = """<a href="https://other.example/y">别人的标题</a>"""
        assertEquals("", Links.titleFromHtml(html, "https://example.com/x"))
        assertEquals("", Links.titleFromHtml(null, "https://example.com/x"))
        assertEquals("", Links.titleFromHtml("<a href=\"https://example.com/x\">", "https://example.com/x"))
    }

    // ---------------------------------------------------------- 新增：标签归一化

    @Test fun tagKeysIgnoreCaseAndWhitespace() {
        assertEquals(listOf("Design"), parseTags("Design, design , DESIGN"))
        assertEquals(Links.tagKey(" 设计 "), Links.tagKey("设计"))
    }

    @Test fun tagTotalsMergeCaseVariantsAndCountPerItem() {
        val items = listOf(
            Bookmark(url = "https://a.example/1", canonical = "https://a.example/1", tags = "设计,灵感"),
            Bookmark(url = "https://a.example/2", canonical = "https://a.example/2", tags = "设计"),
            Bookmark(url = "https://a.example/3", canonical = "https://a.example/3", tags = "design")
        )
        val totals = Links.tagTotals(items).toMap()
        assertEquals(3, totals.size)
        assertEquals(1, totals["灵感"])
        assertEquals(2, totals["设计"])
        assertEquals(1, totals["design"])
    }

    @Test fun tagFilteringIsCaseInsensitive() {
        val item = Bookmark(url = "https://a.example/1", canonical = "https://a.example/1", tags = "Design")
        assertTrue(matches(item, "", "design"))
        assertTrue(matches(item, "", "DESIGN"))
        assertFalse(matches(item, "", "其他"))
    }

    @Test fun searchCoversSummaryAndSiteName() {
        val item = Bookmark(url = "https://example.com/a", canonical = "https://example.com/a", summary = "讲状态管理的文章", siteName = "示例站")
        assertTrue(matches(item, "状态管理", ""))
        assertTrue(matches(item, "示例站", ""))
    }
}
