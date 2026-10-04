package cn.linkvault

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramSettingsSummaryTest {
    @Test fun themeSummaryMatchesTheFallbackSelection() {
        assertEquals("浅色", settingsThemeLabel("light"))
        assertEquals("深色", settingsThemeLabel("dark"))
        assertEquals("跟随系统", settingsThemeLabel("system"))
        assertEquals("跟随系统", settingsThemeLabel("unknown"))
    }

    @Test fun verificationSummaryNeverConfusesHavingAKeyWithBeingVerified() {
        assertEquals("未设密钥", settingsAiStatus(false, false, false))
        assertEquals("待验证", settingsAiStatus(false, false, true))
        assertEquals("已验证", settingsAiStatus(false, true, true))
        assertEquals("验证中…", settingsAiStatus(true, true, true))
    }

    @Test fun translationSummarySeparatesSwitchAndConfiguration() {
        assertEquals("未开启", settingsTranslationStatus(false, false))
        assertEquals("未开启", settingsTranslationStatus(false, true))
        assertEquals("已开启 · 待配置", settingsTranslationStatus(true, false))
        assertEquals("已开启", settingsTranslationStatus(true, true))
    }

    @Test fun blueActionsRemainReadableOnBothThemes() {
        for (scheme in listOf(DayColors, NightColors)) {
            assertTrue(scheme.primary.blue > scheme.primary.red)
            assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.5)
            assertTrue(contrast(scheme.primary, scheme.surface) >= 4.5)
            assertTrue(contrast(scheme.primaryContainer, scheme.onPrimaryContainer) >= 4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(value: Float): Double = if (value <= 0.04045f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        }
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
