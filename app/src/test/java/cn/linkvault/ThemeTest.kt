package cn.linkvault

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

/** 主操作、正文、次要文字在两种主题下都应达到普通文字的 WCAG AA 对比度。 */
class ThemeTest {
    @Test fun textAndActionsStayReadable() {
        for (scheme in listOf(DayColors, NightColors)) {
            val pairs = listOf(
                scheme.primary to scheme.onPrimary,
                scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.background to scheme.onBackground,
                scheme.surface to scheme.onSurface,
                scheme.surface to scheme.onSurfaceVariant,
                scheme.surfaceVariant to scheme.onSurfaceVariant
            )
            pairs.forEach { (background, text) ->
                assertEquals(1f, background.alpha, 0f)
                assertTrue("文字对比度不足：$background / $text", contrast(background, text) >= 4.5)
            }
        }
    }

    @Test fun sourcePaletteUsesTheActualCardSurface() {
        assertEquals(DayColors.surface, DaySourceBase)
        assertEquals(NightColors.surface, NightSourceBase)
    }

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun linear(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
        }
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
