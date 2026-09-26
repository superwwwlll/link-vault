package cn.linkvault

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 来源标识的配色是列表可扫读的基础。
 *
 * 之前所有来源共用一个 primaryContainer，等于完全没有信息；这里把「同源同色、异源尽量异色」
 * 当成契约测出来。调色板有限而域名无限，撞色不可能彻底消除，所以对常见来源要求互不相同，
 * 对大样本只要求达到可接受的分散度——一旦退回「所有来源一个颜色」，两条都会立刻失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaletteTest {
    /** 中文语境下最可能出现在列表里的来源。 */
    private val commonHosts = listOf(
        "github.com", "x.com", "bilibili.com", "mp.weixin.qq.com",
        "youtube.com", "zhihu.com", "developer.android.com", "sspai.com"
    )

    private val broadSample = listOf(
        "github.com", "x.com", "bilibili.com", "mp.weixin.qq.com", "youtube.com", "zhihu.com",
        "developer.android.com", "sspai.com", "juejin.cn", "notion.so", "medium.com",
        "stackoverflow.com", "douban.com", "36kr.com", "figma.com", "reddit.com",
        "news.ycombinator.com", "arxiv.org", "openai.com", "huggingface.co", "gitee.com",
        "cnblogs.com", "segmentfault.com", "oschina.net", "weibo.com", "xiaohongshu.com",
        "jike.city", "kotlinlang.org", "jetbrains.com", "developer.mozilla.org",
        "developer.apple.com", "wikipedia.org", "nytimes.com", "bbc.com", "economist.com",
        "apple.com", "microsoft.com", "taobao.com", "jd.com", "linkedin.com"
    )

    @Test fun sameHostAlwaysGetsTheSameColour() {
        assertEquals(sourcePalette("github.com", dark = false), sourcePalette("github.com", dark = false))
        assertEquals(sourcePalette("github.com", dark = true), sourcePalette("github.com", dark = true))
    }

    @Test fun commonSourcesAreAllDistinct() {
        val light = commonHosts.map { sourcePalette(it, dark = false).first }
        assertEquals("常见来源不该撞色：$light", commonHosts.size, light.distinct().size)
        val dark = commonHosts.map { sourcePalette(it, dark = true).first }
        assertEquals(commonHosts.size, dark.distinct().size)
    }

    @Test fun aBroadSampleStaysMostlyDistinct() {
        val distinct = broadSample.map { sourcePalette(it, dark = false).first }.distinct().size
        assertTrue("40 个真实域名只落到 $distinct 种配色，分散度过低", distinct >= 20)
        val distinctHues = broadSample.map { sourcePalette(it, dark = true).first }.distinct().size
        assertTrue("深色下只有 $distinctHues 种配色", distinctHues >= 20)
    }

    @Test fun lightAndDarkUseDifferentColoursButBothStayReadable() {
        val light = sourcePalette("github.com", dark = false)
        val dark = sourcePalette("github.com", dark = true)
        assertNotEquals(light, dark)
        assertNotEquals("浅色下背景与前景必须能分辨", light.first, light.second)
        assertNotEquals("深色下背景与前景必须能分辨", dark.first, dark.second)
    }

    @Test fun colourIsStableForAStableKey() {
        // 域名字符串不变，取色就不能变，否则用户下次打开列表会对不上记忆
        val first = sourcePalette("example.com", dark = false)
        val second = sourcePalette("example.com", dark = false)
        assertEquals(first.first.value, second.first.value)
        assertEquals(first.second.value, second.second.value)
        // 相差一个字符的域名应该被位混合打散，而不是落到相邻下标
        assertNotEquals(sourcePalette("a.example.com", false), sourcePalette("b.example.com", false))
    }
}
