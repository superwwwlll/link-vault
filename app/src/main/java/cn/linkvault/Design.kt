package cn.linkvault

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object Glyph {
    private fun line(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(path).toNodes(), fill = null,
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
    ).build()
    val Bookmark = line("Bookmark", "M6 4Q6 3 7 3H17Q18 3 18 4V21L12 17L6 21Z")
    val Tag = line("Tag", "M3 3H12L21 12L12 21L3 12Z M7 7H7.01")
    val Settings = line("Settings", "M4 6H20 M4 12H20 M4 18H20 M8 3V9 M16 9V15 M10 15V21")
    val Search = line("Search", "M20 20L16 16 M18 10A8 8 0 1 1 2 10A8 8 0 1 1 18 10")
    val Back = line("Back", "M15 5L8 12L15 19")
    val Next = line("Next", "M9 5L16 12L9 19")
    val Add = line("Add", "M12 5V19 M5 12H19")
    val Close = line("Close", "M6 6L18 18 M18 6L6 18")
    val External = line("External", "M14 3H21V10 M21 3L10 14 M10 4H5Q3 4 3 6V19Q3 21 5 21H18Q20 21 20 19V14")
    val Edit = line("Edit", "M4 16L3 21L8 20L21 7L17 3Z M14 6L18 10")
    val Delete = line("Delete", "M3 6H21 M9 6V3H15V6 M5 6L6 21H18L19 6 M10 10V17 M14 10V17")
    val Export = line("Export", "M12 16V3 M7 8L12 3L17 8 M4 15V21H20V15")
    val Import = line("Import", "M12 3V16 M7 11L12 16L17 11 M4 17V21H20V17")
    val Check = line("Check", "M5 12L10 17L20 7")
    val Shield = line("Shield", "M12 2L21 6V12Q21 19 12 22Q3 19 3 12V6Z M8 12L11 15L17 9")
    val Copy = line("Copy", "M8 8H21V21H8Z M16 4V2H2V16H4")
    val Pin = line("Pin", "M12 22V14 M8 14H16L14 10V3H10V10L8 14Z")
    val Archive = line("Archive", "M3 4H21V9H3Z M5 9V21H19V9 M10 13H14")
    val Dot = line("Dot", "M12 12H12.01")
    val Moon = line("Moon", "M20 14A8 8 0 1 1 10 4A7 7 0 0 0 20 14")
    val Cloud = line("Cloud", "M7 18A4 4 0 0 1 7 10A5 5 0 0 1 17 11A3.5 3.5 0 0 1 16.5 18Z")
    val Share = line("Share", "M12 16V3 M8 7L12 3L16 7 M5 13V21H19V13")
    val More = line("More", "M12 6H12.01 M12 12H12.01 M12 18H12.01")
    val Note = line("Note", "M15.5 3.5L20.5 8.5L7 22H2V17L15.5 3.5Z M13.5 5.5L18.5 10.5")
    val Quote = line("Quote", "M4 14C4 9 7 6 11 5V8C9 8 7 9.5 7 12H11V19H4V14Z M13 14C13 9 16 6 20 5V8C18 8 16 9.5 16 12H20V19H13V14Z")
    val LinkChain = line("LinkChain", "M10 13A5 5 0 0 0 17 13L20 10A5 5 0 0 0 13 3L11.5 4.5 M14 11A5 5 0 0 0 7 11L4 14A5 5 0 0 0 11 21L12.5 19.5")
    val Sort = line("Sort", "M3 6H15 M3 12H11 M3 18H7 M18 8V18 M15 15L18 18L21 15")
    val Markdown = line("Markdown", "M3 5H21V19H3Z M6 15V9L9 12L12 9V15 M15 12H18 M16.5 10.5V14.5")
    val Shuffle = line("Shuffle", "M16 3H21V8 M4 20L21 3 M21 16V21H16 M15 15L21 21 M4 4L9 9")
    val Globe = line("Globe", "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2 M2 12H22 M12 2A15 15 0 0 1 12 22 M12 2A15 15 0 0 0 12 22")
    val Book = line("Book", "M4 19.5A2.5 2.5 0 0 1 6.5 17H20 M4 4.5A2.5 2.5 0 0 1 6.5 2H20V22H6.5A2.5 2.5 0 0 1 4 19.5V4.5Z")
}

private val DayColors = lightColorScheme(
    primary = Color(0xFF2F4DA8), onPrimary = Color.White,
    primaryContainer = Color(0x182F4DA8), onPrimaryContainer = Color(0xFF29438F),
    background = Color(0xFFE9E8E3), onBackground = Color(0xFF182033),
    surface = Color(0xFFF8F8F5), onSurface = Color(0xFF182033),
    surfaceVariant = Color(0xFFF0EFEA), onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFFD8D7D0), outlineVariant = Color(0xFFE1E0D9)
)
private val NightColors = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color.White,
    primaryContainer = Color(0x280A84FF), onPrimaryContainer = Color(0xFF70B8FF),
    background = Color(0xFF09090B), onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF18181B), onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF27272A), onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF3F3F46), outlineVariant = Color(0xFF27272A)
)

data class NoteThemeColors(
    val container: Color,
    val onContainer: Color,
    val border: Color,
    val icon: Color
)

private val DayNoteColors = NoteThemeColors(
    container = Color(0xFFEDE6DA),
    onContainer = Color(0xFF292524),
    border = Color(0xFFDDD4C5),
    icon = Color(0xFF857A6C)
)

private val NightNoteColors = NoteThemeColors(
    container = Color(0xFF1F1F23),
    onContainer = Color(0xFFFAFAFA),
    border = Color(0xFF2E2E33),
    icon = Color(0xFFA1A1AA)
)

val LocalNoteColors = staticCompositionLocalOf { DayNoteColors }

/** 供少量需要知道明暗的绘制逻辑使用（例如来源标识配色）。 */
val LocalVaultDark = staticCompositionLocalOf { false }

/**
 * 来源标识配色：同一个域名永远同一个颜色，不同域名尽量拉开色相。
 *
 * 以前所有来源方块都用同一个 primaryContainer，字母标识等于只是把首字母重复了一遍，
 * 整个列表扫下来没有任何可用于快速定位的视觉线索。
 *
 * 两点实现说明：
 * - String.hashCode 对短字符串的低位分布很差，直接取模会让相差一个字符的域名频繁撞色；
 *   先用 Murmur3 风格的位混合把高位搅进低位，实测 40 个常见域名可从 20 种分散到 24 种配色。
 * - 调色板是有限的，域名是无限的，所以**撞色不可能完全消除**；这里的目标是「不再千篇一律、
 *   且同一来源永远可辨认」，而不是「全球唯一」。色相 × 明度两档共 32 种组合。
 */
private val sourceHues = floatArrayOf(
    232f, 205f, 268f, 158f, 28f, 348f, 190f, 45f,
    300f, 120f, 15f, 250f, 175f, 85f, 320f, 62f
)

private fun spread(key: String): Int {
    var h = key.hashCode()
    h = h xor (h ushr 16)
    h *= 0x7feb352d
    h = h xor (h ushr 15)
    h *= 0x846ca68b.toInt()
    h = h xor (h ushr 16)
    return h
}

fun sourcePalette(key: String, dark: Boolean): Pair<Color, Color> {
    val hash = spread(key)
    val hue = sourceHues[(hash and 0x7fffffff) % sourceHues.size]
    val deep = (hash ushr 16) and 1 == 1
    return if (dark) Color.hsl(hue, 0.32f, if (deep) 0.19f else 0.25f) to Color.hsl(hue, 0.62f, 0.76f)
    else Color.hsl(hue, 0.62f, if (deep) 0.90f else 0.95f) to Color.hsl(hue, 0.55f, 0.36f)
}

@Composable
fun VaultTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalVaultDark provides dark,
        LocalNoteColors provides if (dark) NightNoteColors else DayNoteColors
    ) {
        MaterialTheme(colorScheme = if (dark) NightColors else DayColors, content = content)
    }
}
