package cn.linkvault

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class HtmlTest {
    @Test fun namedAndNumericEntitiesDecode() {
        assertEquals("A & B < C > D \" E '", Html.decodeEntities("A &amp; B &lt; C &gt; D &quot; E &#39;"))
        assertEquals("中文·破折—", Html.decodeEntities("中文&middot;破折&mdash;"))
        assertEquals("星 & 星", Html.decodeEntities("星 &#x26; 星"))
        // 不认识的实体与孤立的 & 原样保留，不能把用户内容吃掉
        assertEquals("a &unknown; b & c", Html.decodeEntities("a &unknown; b & c"))
        assertEquals("没有实体", Html.decodeEntities("没有实体"))
    }

    @Test fun attributesAreReadInEveryQuoteStyle() {
        assertEquals("https://example.com/a", Html.attribute("""<a href="https://example.com/a">""", "href"))
        assertEquals("https://example.com/a", Html.attribute("<a href='https://example.com/a'>", "href"))
        assertEquals("https://example.com/a", Html.attribute("<a href=https://example.com/a>", "href"))
        assertEquals("标题", Html.attribute("""<meta content="标题" property="og:title">""", "content"))
        assertNull(Html.attribute("""<a href="https://example.com/a">""", "title"))
        assertNull(Html.attribute("""<a href="">""", "href"))
    }

    @Test fun metaContentPrefersEarlierKeysAndSkipsIrrelevantTags() {
        val html = """<head><meta name="description" content="普通描述"><meta property="og:description" content="OG 描述"></head>"""
        assertEquals("OG 描述", Html.metaContent(html, listOf("og:description", "description")))
        assertEquals("普通描述", Html.metaContent(html, listOf("description")))
        assertNull(Html.metaContent("<head><meta name=\"author\" content=\"某人\"></head>", listOf("description")))
    }

    @Test fun titleTagIsCollapsedAndDecoded() {
        assertEquals("标题 & 副标题", Html.titleTag("<title>\n  标题 &amp; 副标题\n</title>"))
        assertNull(Html.titleTag("<head></head>"))
    }

    @Test fun textStripsNestedTags() {
        assertEquals("加粗 的 标题", Html.text("加粗<b>的</b>标题"))
    }

    // ------------------------------------------------------ 抓取结果的纯解析

    @Test fun parseHeadPrefersOpenGraphThenTitleTag() {
        val html = """
            <html><head>
              <title>页面标题</title>
              <meta property="og:title" content="OG 标题">
              <meta property="og:description" content="OG 描述">
              <meta property="og:site_name" content="示例站">
            </head></html>
        """.trimIndent()
        val head = Net.parseHead(html)
        assertEquals("OG 标题", head.title)
        assertEquals("OG 描述", head.description)
        assertEquals("示例站", head.siteName)
        assertFalse(head.isEmpty)
    }

    @Test fun parseHeadFallsBackToTitleAndDescription() {
        val html = """<head><title>只有标题</title><meta name="description" content="普通描述"></head>"""
        val head = Net.parseHead(html)
        assertEquals("只有标题", head.title)
        assertEquals("普通描述", head.description)
        assertEquals("", head.siteName)
    }

    @Test fun parseHeadOnAnEmptyPageIsEmpty() {
        val head = Net.parseHead("<html><body>没有头部</body></html>")
        assertTrue(head.isEmpty)
    }

    @Test fun fetcherRefusesAnythingButHttpsBeforeTouchingTheNetwork() {
        listOf("http://example.com", "ftp://example.com", "not a url").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { Net.fetchHead(url) }
            assertThrows(IllegalArgumentException::class.java) { Net.fetchArticle(url) }
        }
    }

    @Test fun extractArticleExtractsParagraphsAndStripsNoise() {
        val html = """
            <html>
              <head><style>.ad { display:none; }</style></head>
              <body>
                <header><nav><a href="/">首页</a></nav></header>
                <h1>文章主标题</h1>
                <p>这是第一段内容，包含 <b>加粗</b> 与 &ldquo;引语&rdquo;。</p>
                <script>console.log("广告脚本");</script>
                <div class="content">
                  <p>这是第二段正文。</p>
                </div>
                <footer>版权所有 &copy; 2026</footer>
              </body>
            </html>
        """.trimIndent()
        val text = Html.extractArticle(html)
        assertTrue(text.contains("文章主标题"))
        assertTrue(text.contains("这是第一段内容，包含 加粗 与 “引语”。"))
        assertTrue(text.contains("这是第二段正文。"))
        assertFalse(text.contains("广告脚本"))
        assertFalse(text.contains("版权所有"))
        assertFalse(text.contains("首页"))
    }

    @Test fun extractArticleHandlesEmptyAndScriptOnlyHtml() {
        assertEquals("", Html.extractArticle("<script>alert(1)</script>"))
        assertEquals("", Html.extractArticle(""))
    }
}

class StampTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int): Long {
        val c = Calendar.getInstance()
        c.clear(); c.set(year, month - 1, day, hour, 0, 0)
        return c.timeInMillis
    }

    @Test fun todayAndYesterdayAreNamedAndYearOnlyAppearsAcrossYears() {
        val now = at(2026, 9, 26, 20)
        assertEquals("今天", Stamp.date(at(2026, 9, 26, 8), now))
        assertEquals("昨天", Stamp.date(at(2026, 9, 25, 8), now))
        assertEquals("8月1日", Stamp.date(at(2026, 8, 1, 8), now))
        assertEquals("2025年12月31日", Stamp.date(at(2025, 12, 31, 8), now))
    }

    @Test fun backupFreshness() {
        val now = at(2026, 9, 26, 12)
        assertEquals("从未备份", Stamp.ago(0L, now))
        assertEquals("就在刚刚", Stamp.ago(now, now))
        assertEquals("3 天前", Stamp.ago(now - 3L * 86_400_000L, now))
        assertEquals("2 个月前", Stamp.ago(now - 60L * 86_400_000L, now))
        assertTrue(Stamp.isStale(0L, now))
        assertFalse(Stamp.isStale(now, now))
        assertTrue(Stamp.isStale(now - (Stamp.STALE_DAYS + 1) * 86_400_000L, now))
    }
}
