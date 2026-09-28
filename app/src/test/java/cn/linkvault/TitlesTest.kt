package cn.linkvault

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TitlesTest {
    private val zhihu = "https://www.zhihu.com/question/488000000"
    private val bili = "https://www.bilibili.com/video/BV1y441177Xk"

    @Test fun collapsesNewlinesAndOddSpacesSoCardsStopBreakingMidSentence() {
        assertEquals("Kotlin 协程的结构化并发", Titles.collapse("Kotlin 协程\n\n的结构化并发 "))
        assertEquals("A B C", Titles.collapse("A\u00A0B\u3000C\u200B"))
        // 真实网页里最常见的形状：og:title 换行 + 尾部站点名
        assertEquals("Kotlin 协程的结构化并发", Titles.cleanse(zhihu, "Kotlin 协程\n的结构化并发 - 知乎"))
    }

    @Test fun stripsOnlyTailsThatAreTheSiteNameOrAnEmptyWord() {
        assertEquals("结构化并发", Titles.cleanse(zhihu, "结构化并发 | 知乎"))
        assertEquals("结构化并发", Titles.cleanse(zhihu, "结构化并发【知乎】"))
        assertEquals("结构化并发", Titles.cleanse(bili, "结构化并发_哔哩哔哩_bilibili"))
        assertEquals("结构化并发", Titles.cleanse(zhihu, "结构化并发 - 知乎 - 首页"))
        // 裸连字符不算分隔符：Well-Known 里的它不该把标题切短
        assertEquals("Well-Known 中间件", Titles.cleanse("https://example.com/a", "Well-Known 中间件"))
        // 认不出来的短尾巴留着：那更可能是副标题，不是站点名
        assertEquals("结构化并发 - 进阶篇", Titles.cleanse(zhihu, "结构化并发 - 进阶篇"))
    }

    @Test fun treatsPlaceholderTitlesAsNoTitleAtAll() {
        listOf("首页", "HOME", "No Title", "Untitled", "未命名", "  ", "404", "——————", "12345", "知乎", "Zhihu", "zhihu.com").forEach {
            assertTrue("$it 该被判成空标题", Titles.meaningless(zhihu, it))
        }
        // 站点名只在它确实是这条链接的站点时才算空标题
        assertTrue(Titles.meaningless(bili, "哔哩哔哩"))
        assertFalse(Titles.meaningless(zhihu, "哔哩哔哩"))
        assertEquals("", Titles.cleanse(zhihu, "首页"))
        assertEquals("", Titles.cleanse(bili, "哔哩哔哩"))
        assertFalse(Titles.meaningless(zhihu, " Kotlin 协程到底解决什么问题"))
    }

    @Test fun cleaningIsIdempotentAndLengthCapped() {
        val long = "标题" + "字".repeat(300)
        val once = Titles.cleanse(zhihu, long)
        assertEquals(Titles.LIMIT, once.length)
        assertEquals(once, Titles.cleanse(zhihu, once))
    }

    @Test fun namesFromArticlePreferHeadingThenFirstSentence() {
        assertEquals("为什么需要结构化并发", Titles.fromArticle("# 为什么需要结构化并发\n\n正文…"))
        // 没有一级标题时取第一段的第一句，并在 48 字处收口
        val derived = Titles.fromArticle("引言段落。\n\n" + "协程把并发的生命周期交给一个作用域来管，".repeat(6))
        assertTrue(derived.isNotEmpty())
        assertTrue(derived.startsWith("协程把并发的生命周期"))
        assertTrue(derived.length <= 49)
        // 图片行、引用、列表、代码块都不配当标题
        assertEquals("", Titles.fromArticle("![图](https://example.com/a.png)\n\n> 引用\n\n- 列表项"))
    }

    @Test fun resolveKeepsWhatTheUserWroteAndOnlyReplacesEmptyTitles() {
        assertEquals("我自己起的名字", Titles.resolve(zhihu, "我自己起的名字", "首页 - 知乎"))
        assertEquals("页面真正的标题", Titles.resolve(zhihu, "首页", "页面真正的标题 - 知乎"))
        assertEquals("正文里的一级标题", Titles.resolve(zhihu, "", "", "", "# 正文里的一级标题\n\n内容"))
        // 三处都没有可用信息时返回空串，让显示层退回路径推导或站点名
        assertEquals("", Titles.resolve(zhihu, "", "", "", null))
        assertEquals("结构化并发", Links.displayTitle(zhihu, "结构化并发\n\n - 知乎"))
        assertEquals("Design", Links.displayTitle("https://example.com/design", "首页"))
    }
}
