package cn.linkvault

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun RootHeading(title: String, subtitle: String = "") {
    Column(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Text("LINK VAULT", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.0.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
        if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.replace("\n", "").replace("\r", "")) },
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        placeholder = {
            Text(
                placeholder,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        },
        leadingIcon = { Icon(Glyph.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Glyph.Close, "清空搜索", Modifier.size(16.dp)) } },
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            focusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun TagPill(text: String, trailing: String = "", onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(trailing, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 手记便签卡片：ChunUI panel 沉降质感。
 */
@Composable
internal fun NoteSnippetCard(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null
) {
    val noteColors = LocalNoteColors.current
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = noteColors.container,
        border = BorderStroke(0.6.dp, noteColors.border),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Glyph.Quote,
                contentDescription = "手记",
                modifier = Modifier.size(13.dp).padding(top = 2.dp),
                tint = noteColors.icon
            )
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
                color = noteColors.onContainer,
                fontWeight = FontWeight.Normal,
                maxLines = maxLines,
                overflow = if (maxLines != Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip
            )
        }
    }
}

/**
 * ChunUI CCSegmentedControl 风格凹槽分段控制。
 */
@Composable
internal fun <T> UnderlineTabs(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    badge: ((T) -> String?)? = null
) {
    val count = items.size.coerceAtLeast(1)
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    val progress by animateFloatAsState(selectedIndex.toFloat(), tween(220), label = "tabIndicator")
    val indicatorColor = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Box {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                items.forEach { item ->
                    val isSelected = item == selectedItem
                    Tab(
                        selected = isSelected,
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Text(label(item), fontSize = 15.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                badge?.invoke(item)?.takeIf { it.isNotEmpty() }?.let {
                                    Spacer(Modifier.width(4.dp))
                                    Text(it, fontSize = 11.sp)
                                }
                            }
                        }
                    )
                }
            }
            // 指示条按页签等宽插值滑动；以前只有一条整宽分隔线，选中项只靠加粗区分，扫一眼看不出可点
            Canvas(Modifier.matchParentSize()) {
                val cell = size.width / count
                val barWidth = cell * 0.52f
                val left = cell * (progress + 0.5f) - barWidth / 2f
                val height = 2.dp.toPx()
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(height / 2f)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), thickness = 1.dp)
    }
}

@Composable
internal fun <T> SegmentedPills(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    badge: ((T) -> String?)? = null
) {
    val isDark = LocalVaultDark.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = item == selectedItem
                val shape = RoundedCornerShape(12.dp)
                val pillColor by animateColorAsState(
                    if (selected) {
                        if (isDark) Color(0xFF27272A) else MaterialTheme.colorScheme.surface
                    } else Color.Transparent,
                    animationSpec = tween(160),
                    label = "segmentedPill"
                )

                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(pillColor)
                        .clickable { onSelect(item) }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = label(item),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val b = badge?.invoke(item)
                        if (!b.isNullOrEmpty()) {
                            Text(
                                text = b,
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, subtitle: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(76.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle.isNotBlank()) {
            Text(subtitle, fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
internal fun PageToolbar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Glyph.Back, "返回", tint = MaterialTheme.colorScheme.onSurface) }
        Text(title, Modifier.weight(1f).padding(start = 4.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        action?.invoke()
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

enum class MarkSize(val box: Dp, val text: TextUnit, val shape: RoundedCornerShape) {
    Tiny(20.dp, 10.sp, RoundedCornerShape(7.dp)),
    Small(28.dp, 13.sp, RoundedCornerShape(9.dp)),
    Large(46.dp, 20.sp, RoundedCornerShape(15.dp))
}

/**
 * 来源标识：同一个域名永远同一个颜色，带精致柔和描边。
 */
@Composable
internal fun SourceMark(url: String, size: MarkSize = MarkSize.Small, muted: Boolean = false) {
    val key = Links.host(url).ifEmpty { url }
    val dark = LocalVaultDark.current
    val (background, foreground) = if (muted) MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else sourcePalette(key, dark)
    Box(
        Modifier
            .size(size.box)
            .background(background, size.shape)
            .border(0.7.dp, if (muted) MaterialTheme.colorScheme.outlineVariant else sourceBorder(key, dark), size.shape),
        contentAlignment = Alignment.Center
    ) {
        val label = Links.siteName(url).take(1).uppercase(Locale.ROOT)
        Text(
            if (label.isEmpty()) "•" else label,
            color = foreground,
            fontSize = size.text,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun Banner(text: String, tone: BannerTone, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val container = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.primaryContainer
        BannerTone.Warn -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = container, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

internal enum class BannerTone { Info, Warn }

