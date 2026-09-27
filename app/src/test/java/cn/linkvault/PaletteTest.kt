package cn.linkvault

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt
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

    /**
     * 哈希不重复 ≠ 看得出来不一样。
     *
     * 1.2.0 那版深色把明度压到 0.19/0.25、饱和度 0.32，32 个色块在哈希上互不重复，
     * 但两两平均 ΔE 只有 27.7、同一色相的深浅两档只差 7.5，扫过去是一片相同的灰。
     * 下面四条测的是「肉眼分得出」，四条按 1.2.0 的实现都会失败。
     */
    @Test fun everyChipStaysReadableAgainstItsOwnLetter() {
        for (dark in listOf(false, true)) for (deep in listOf(false, true)) for (hue in sourceHues) {
            val (background, foreground) = sourceColorFor(hue, deep, dark)
            val ratio = contrast(background, foreground)
            assertTrue("色相 $hue ${if (dark) "深色" else "浅色"}档 $deep 字/块对比只有 $ratio", ratio >= 4.0)
        }
    }

    @Test fun chipsAreVisibleAgainstTheCardTheySitOn() {
        for (hue in sourceHues) for (deep in listOf(false, true)) {
            val night = deltaE(sourceColorFor(hue, deep, dark = true).first, NightSourceBase)
            assertTrue("深色下色块与卡片底几乎重合（ΔE=$night）", night >= 12.0)
            val day = deltaE(sourceColorFor(hue, deep, dark = false).first, DaySourceBase)
            assertTrue("浅色下色块与卡片底几乎重合（ΔE=$day）", day >= 4.0)
        }
    }

    @Test fun distinctHuesActuallySpreadOut() {
        for (dark in listOf(false, true)) {
            val chips = sourceHues.flatMap { listOf(sourceColorFor(it, false, dark).first, sourceColorFor(it, true, dark).first) }
            var sum = 0.0
            var pairs = 0
            for (i in chips.indices) for (j in chips.indices) if (i < j) { sum += deltaE(chips[i], chips[j]); pairs++ }
            val mean = sum / pairs
            assertTrue("${if (dark) "深色" else "浅色"}下 32 个色块两两平均 ΔE 只有 $mean，列表会看成一片灰", mean >= if (dark) 30.0 else 20.0)
        }
    }

    @Test fun theDepthBitIsNotWasted() {
        // 深浅档如果同色相下分不出来，等于只用掉 16 色而不是 32 色
        for (dark in listOf(false, true)) for (hue in sourceHues) {
            val spread = deltaE(sourceColorFor(hue, false, dark).first, sourceColorFor(hue, true, dark).first)
            assertTrue("色相 $hue 的深浅两档只差 ΔE=$spread", spread >= if (dark) 8.0 else 10.0)
        }
    }
}

/**
 * CIE Lab 的 ΔE：人眼可辨距离，比 RGB 逐通道差值靠谱得多。
 *
 * 「两个颜色不一样」在 sRGB 空间里量不出来——明度相近、色相不同的两块灰，
 * 逐通道差值很大，肉眼却分不出。这里用 ΔE 才能把「看成一片灰」这种回归测出来。
 */
private fun Color.lab(): DoubleArray {
    fun lin(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    val r = lin(red); val g = lin(green); val b = lin(blue)
    fun pivot(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
    val x = pivot((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047)
    val y = pivot(0.2126 * r + 0.7152 * g + 0.0722 * b)
    val z = pivot((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
    return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
}

private fun deltaE(a: Color, b: Color): Double {
    val x = a.lab(); val y = b.lab()
    return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
}

/** WCAG 相对亮度对比度。 */
private fun contrast(a: Color, b: Color): Double {
    fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}
