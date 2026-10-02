package cn.linkvault

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
    Column(Modifier.padding(top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onBackground)
            Text("LINK VAULT", fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun TagPill(text: String, trailing: String = "", onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
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
 * 手记使用浅中性底，与正文区分而不争夺标题的视觉优先级。
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
 * 等宽分段筛选：浅底承托选中胶囊，窄屏优先保留标签文字。
 */
@Composable
internal fun <T> ScopeTabs(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    badge: ((T) -> String?)? = null
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val background by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent, tween(160), label = "scopePill")
                Surface(
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f).semantics { selected = isSelected; role = Role.Tab },
                    shape = RoundedCornerShape(10.dp),
                    color = background,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Text(label(item), Modifier.weight(1f, fill = false), fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        badge?.invoke(item)?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.width(4.dp))
                            Text(it, fontSize = 10.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
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
                        MaterialTheme.colorScheme.surface
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
             Modifier.size(64.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
