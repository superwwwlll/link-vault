package cn.linkvault

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    Column(Modifier.padding(top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun quietFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    cursorColor = MaterialTheme.colorScheme.primary
)

@Composable
internal fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onExpandedChange: (Boolean) -> Unit = {}, initiallyExpanded: Boolean = value.isNotEmpty()) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val focus = remember { FocusRequester() }
    if (!expanded && value.isEmpty()) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { expanded = true; onExpandedChange(true) }, modifier = Modifier.size(48.dp)) { Icon(Glyph.Search, "展开搜索", Modifier.size(20.dp)) }
        }
        return
    }
    LaunchedEffect(expanded) { if (expanded) focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.replace("\n", "").replace("\r", "")) },
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingIcon = { Icon(Glyph.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            Row {
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(48.dp)) { Icon(Glyph.Close, "清空搜索", Modifier.size(16.dp)) }
                IconButton(onClick = { onChange(""); expanded = false; onExpandedChange(false) }, modifier = Modifier.size(48.dp)) { Icon(Glyph.Back, "关闭搜索", Modifier.size(16.dp)) }
            }
        },
        shape = RoundedCornerShape(10.dp),
        colors = quietFieldColors(),
        modifier = modifier.fillMaxWidth().focusRequester(focus)
    )
}

@Composable
internal fun TagPill(text: String, trailing: String = "", onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = if (onClick != null) Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(onClick = onClick) else Modifier
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 手记用一条细线隔开，不再套一层卡片。
 */
@Composable
internal fun NoteSnippetCard(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null
) {
    val noteColors = LocalNoteColors.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .width(2.dp)
                .height(16.dp)
                .background(noteColors.border, RoundedCornerShape(1.dp))
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = noteColors.onContainer,
            maxLines = maxLines,
            overflow = if (maxLines != Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip
        )
    }
}

/**
 * 等宽文字页签，保留 1.5.2 发布说明中的轻量切换交互。
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
    Surface(color = Color.Transparent, modifier = modifier) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                Surface(
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f).semantics { selected = isSelected; role = Role.Tab },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Text(label(item), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        badge?.invoke(item)?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.width(4.dp))
                            Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun <T> SegmentedPills(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    badge: ((T) -> String?)? = null
) {
    FlowRow(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val chosen = item == selectedItem
            Surface(
                onClick = { onSelect(item) },
                modifier = Modifier.semantics { selected = chosen; role = Role.Tab },
                shape = RoundedCornerShape(8.dp),
                color = if (chosen) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                contentColor = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label(item), style = MaterialTheme.typography.bodyMedium, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                    badge?.invoke(item)?.takeIf { it.isNotEmpty() }?.let { Text(" $it", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, subtitle: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
internal fun PageToolbar(title: String, onBack: () -> Unit, subtitle: String = "", action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Glyph.Back, "返回", tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
internal fun quietFillColors() = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedContainerColor = if (LocalVaultDark.current) Color(0xFF1A1A19) else Color(0xFFE9E8E4),
    unfocusedContainerColor = if (LocalVaultDark.current) Color(0xFF1A1A19) else Color(0xFFE9E8E4),
    disabledContainerColor = if (LocalVaultDark.current) Color(0xFF1A1A19) else Color(0xFFE9E8E4),
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent
)

@Composable
internal fun SectionLabel(text: String, trailing: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
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
            .size(size.box * LocalDensity.current.fontScale.coerceAtLeast(1f))
            .background(background, size.shape)
            .border(0.7.dp, if (muted) MaterialTheme.colorScheme.outlineVariant else sourceBorder(key, dark), size.shape),
        contentAlignment = Alignment.Center
    ) {
        val label = Links.siteName(url).take(1).uppercase(Locale.ROOT)
        Text(
            if (label.isEmpty()) "•" else label,
            color = foreground,
            fontSize = size.text,
            lineHeight = size.text * 1.2f,
            maxLines = 1,
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
            Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

internal enum class BannerTone { Info, Warn }
