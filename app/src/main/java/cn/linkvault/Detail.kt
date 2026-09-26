@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package cn.linkvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DetailPage(
    vm: VaultViewModel,
    item: Bookmark,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCopyMarkdown: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val fetching = vm.fetchingId == item.id
    val noteColors = LocalNoteColors.current

    PageToolbar("收藏详情", onBack) {
        IconButton(onClick = onEdit, enabled = !vm.busy) {
            Icon(Glyph.Edit, "编辑收藏", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !vm.busy) {
                Icon(Glyph.More, "更多操作", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("分享链接") },
                    leadingIcon = { Icon(Glyph.Share, null, Modifier.size(18.dp)) },
                    onClick = { menu = false; onShare() }
                )
                DropdownMenuItem(
                    text = { Text("复制原链接") },
                    leadingIcon = { Icon(Glyph.Copy, null, Modifier.size(18.dp)) },
                    onClick = { menu = false; clipboard.setText(AnnotatedString(item.url)); vm.toast("已复制原链接") }
                )
                DropdownMenuItem(
                    text = { Text("复制为 Markdown") },
                    leadingIcon = { Icon(Glyph.Markdown, null, Modifier.size(18.dp)) },
                    onClick = { menu = false; onCopyMarkdown() }
                )
                DropdownMenuItem(
                    text = { Text("删除收藏", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Glyph.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 头部：来源徽标 + 站点名与时间
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceMark(item.url)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val site = Links.siteName(item.url)
                if (site.length > 1) Text(site, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                val stamp = buildString {
                    append("收藏于 ").append(Stamp.date(item.createdAt))
                    if (item.updatedAt / 60_000L > item.createdAt / 60_000L) append(" · 更新于 ").append(Stamp.date(item.updatedAt))
                }
                Text(stamp, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 大标题（人文排版）
        SelectionContainer {
            Text(
                Links.displayTitle(item.url, item.title),
                fontSize = 24.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // 状态微胶囊栏（已读、置顶、归档）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DetailStatusPill(
                label = if (item.read) "已读" else "标为已读",
                selected = item.read,
                icon = Glyph.Check,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.toggleRead(item)
                },
                enabled = !vm.busy
            )
            DetailStatusPill(
                label = if (item.pinned) "已置顶" else "置顶",
                selected = item.pinned,
                icon = Glyph.Pin,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.togglePin(item)
                },
                enabled = !vm.busy
            )
            DetailStatusPill(
                label = if (item.archived) "已归档" else "归档",
                selected = item.archived,
                icon = Glyph.Archive,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.toggleArchived(item)
                },
                enabled = !vm.busy
            )
        }

        // URL 交互胶囊：紧凑且兼顾查看、复制与外部跳转
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Glyph.LinkChain, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    item.url,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(item.url)); vm.toast("已复制原链接") },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Glyph.Copy, "复制原链接", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = onCopyMarkdown,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Glyph.Markdown, "复制 Markdown", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("打开", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(3.dp))
                    Icon(Glyph.External, null, Modifier.size(11.dp))
                }
            }
        }

        // 我的手记：重点突出人文便签质感
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("我的备注")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = noteColors.container,
                border = BorderStroke(0.6.dp, noteColors.border),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.Note, null, Modifier.size(14.dp), tint = noteColors.icon)
                        Spacer(Modifier.width(6.dp))
                        Text("思考与备忘", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = noteColors.icon)
                        Spacer(Modifier.weight(1f))
                        Icon(Glyph.Edit, null, Modifier.size(13.dp), tint = noteColors.icon.copy(alpha = 0.6f))
                    }
                    SelectionContainer {
                        Text(
                            item.notes.ifBlank { "暂无备注" },
                            fontSize = 14.5.sp,
                            lineHeight = 22.sp,
                            color = if (item.notes.isEmpty()) noteColors.onContainer.copy(alpha = 0.6f) else noteColors.onContainer
                        )
                    }
                }
            }
        }

        // 页面描述
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("页面描述", trailing = if (item.fetchedAt > 0) "抓取于 ${Stamp.date(item.fetchedAt)}" else "")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.summary.isNotBlank()) {
                        SelectionContainer {
                            Text(item.summary, fontSize = 13.5.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        Text("暂无页面描述", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    if (vm.fetchEnabled && item.url.startsWith("https://", true)) {
                        OutlinedButton(
                            onClick = { vm.fetch(item) },
                            enabled = !vm.busy,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            if (fetching) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.8.dp)
                            else Icon(Glyph.Search, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (fetching) "抓取中…" else if (item.fetchedAt > 0) "重新抓取" else "抓取页面信息", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 标签栏
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("标签")
            if (item.tags.isEmpty()) {
                Text("尚未添加标签", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    parseTags(item.tags).forEach { tag -> TagPill(tag, onClick = { vm.tag(tag) }) }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        TextButton(
            onClick = onDelete,
            enabled = !vm.busy,
            modifier = Modifier.align(Alignment.Start)
        ) {
            Icon(Glyph.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text("删除这条收藏", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DetailStatusPill(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            0.6.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                icon,
                null,
                Modifier.size(14.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
