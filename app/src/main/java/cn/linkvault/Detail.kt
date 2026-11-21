@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package cn.linkvault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
    var showCover by rememberSaveable(item.id) { mutableStateOf(false) }
    val fetching = vm.fetchingId == item.id
    // 抓取和看图共用同一个联网开关：默认关闭时，一个请求都不该发出去
    val canFetch = vm.fetchEnabled && item.url.startsWith("https://", true)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    PageToolbar("", onBack) {
        TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("打开原文", fontFamily = ReadingSerif, color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
        }
        IconButton(onClick = onEdit, enabled = !vm.busy, modifier = Modifier.size(48.dp)) {
            Icon(Glyph.Edit, "编辑收藏", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !vm.busy, modifier = Modifier.size(48.dp)) {
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
                    text = { Text(if (item.read) "标为未读" else "标为已读") },
                    leadingIcon = { Icon(Glyph.Check, null, Modifier.size(18.dp)) },
                    onClick = {
                        menu = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.toggleRead(item)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (item.pinned) "取消置顶" else "置顶") },
                    leadingIcon = { Icon(Glyph.Pin, null, Modifier.size(18.dp)) },
                    onClick = {
                        menu = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.togglePin(item)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (item.archived) "移出归档" else "归档") },
                    leadingIcon = { Icon(Glyph.Archive, null, Modifier.size(18.dp)) },
                    onClick = {
                        menu = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.toggleArchived(item)
                    }
                )
            }
        }
    }

    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectionContainer {
            Text(
                Links.displayTitle(item.url, item.title),
                fontFamily = ReadingSerif,
                fontSize = 26.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        val source = Links.listSource(item.url, item.title)
        val stamp = buildString {
            if (source.isNotEmpty()) append(source).append("  ·  ")
            append(Stamp.date(item.createdAt))
            if (item.updatedAt / 60_000L > item.createdAt / 60_000L) append("  ·  更新 ").append(Stamp.date(item.updatedAt))
        }
        Text(stamp, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        val snapshot = vm.currentSnapshot
        val translation = vm.currentTranslation
        val fetchingSnapshot = vm.fetchingSnapshot
        var expandedReader by remember { mutableStateOf(false) }
        var showTranslated by rememberSaveable { mutableStateOf(false) }
        if (snapshot != null) {
            val readerText = if (showTranslated && translation != null) translation else snapshot
            if (translation != null) {
                QuietChoice(
                    items = listOf("原文", "译文"),
                    selected = if (showTranslated) "译文" else "原文",
                    onSelect = { showTranslated = it == "译文" }
                )
            }
            SelectionContainer {
                SnapshotMarkdownViewer(
                    markdown = readerText,
                    expanded = expandedReader,
                    showImages = expandedReader && canFetch && !(showTranslated && translation != null)
                )
            }
            Text(
                buildString {
                    append(if (showTranslated && translation != null) "正文译文" else "离线正文快照")
                    append("  ·  ")
                    append(when {
                        showTranslated && translation != null -> "译文 ${translation.length} 字"
                        else -> "已缓存 Markdown · ${snapshot.length} 字"
                    })
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot.length > 200) {
                DetailActionRow(if (expandedReader) "收起阅读模式" else "展开全文阅读") { expandedReader = !expandedReader }
            }
            DetailActionRow("复制正文") {
                clipboard.setText(AnnotatedString(snapshot))
                vm.toast("已复制 Markdown 正文")
            }
            val translateLabel = when {
                vm.translating -> {
                    val (done, total) = vm.translateStep
                    if (total > 0) "翻译中 $done/$total 段" else "翻译中…"
                }
                translation == null -> "译成中文"
                else -> "看译文"
            }
            DetailActionRow(translateLabel, busy = vm.translating, enabled = !vm.busy && !vm.translating) {
                if (translation == null) vm.translate(item) else showTranslated = true
            }
            if (canFetch) {
                DetailActionRow(if (fetchingSnapshot) "抓取中…" else "更新快照", busy = fetchingSnapshot, enabled = !vm.busy && !fetchingSnapshot) {
                    vm.captureSnapshot(item)
                }
            }
            DetailActionRow("删除快照", destructive = true) { vm.removeSnapshot(item.id) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.url, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { clipboard.setText(AnnotatedString(item.url)); vm.toast("已复制原链接") }, modifier = Modifier.size(48.dp)) {
                Icon(Glyph.Copy, "复制原链接", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (item.notes.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("我的一句", fontFamily = ReadingSerif, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                SelectionContainer {
                    Text(
                        item.notes,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onEdit),
                        fontFamily = ReadingSerif,
                        fontSize = 17.sp,
                        lineHeight = 32.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            DetailActionRow("添加备注", onClick = onEdit)
        }

        if (item.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                parseTags(item.tags).forEach { tag ->
                    TagChip(tag, onClick = { vm.tag(tag) }, maxLines = Int.MAX_VALUE)
                }
            }
        }

        val cover = rememberLoadedImage(item.image, enabled = showCover && canFetch)
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = "封面图",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio((cover.width.toFloat() / cover.height).coerceIn(0.8f, 2.2f))
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        }

        if (item.summary.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (item.fetchedAt > 0) "页面描述 · 抓取于 ${Stamp.date(item.fetchedAt)}" else "页面描述",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(item.summary, fontFamily = ReadingSerif, fontSize = 17.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                if (canFetch) DetailActionRow(if (fetching) "抓取中…" else if (item.fetchedAt > 0) "重新抓取" else "抓取页面信息", busy = fetching) {
                    showCover = true
                    vm.fetch(item)
                }
            }
        } else if (canFetch) {
            DetailActionRow(if (fetching) "抓取中…" else "抓取页面信息", busy = fetching) {
                showCover = true
                vm.fetch(item)
            }
        }
        if (snapshot == null && canFetch) {
            DetailActionRow(if (fetchingSnapshot) "正文提取与重排中…" else "提取正文快照", busy = fetchingSnapshot) { vm.captureSnapshot(item) }
        }

        AnalysisPanel(vm, "b-${item.id}")

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        DetailActionRow("删除这条收藏", destructive = true, enabled = !vm.busy, onClick = onDelete)
    }
    }
}

@Composable
private fun DetailActionRow(
    text: String,
    busy: Boolean = false,
    enabled: Boolean = !busy,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp)
        Text(
            text,
            fontSize = 17.sp,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun QuietChoice(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { label ->
            val chosen = label == selected
            Column(
                Modifier
                    .semantics { this.selected = chosen }
                    .clickable { onSelect(label) }
                    .padding(end = 20.dp)
                    .heightIn(min = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val line = if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent
                Text(
                    label,
                    Modifier.drawBehind {
                        val stroke = 2.dp.toPx()
                        val y = size.height - stroke / 2f
                        drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                    },
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** 快照里独立成行的图片：`![alt](https://…)`。混在句子里的图片链接仍按原文显示。 */
private val snapshotImageLine = Regex("^!\\[(.*?)]\\((https?://[^)\\s]+)\\)$")

@Composable
internal fun SnapshotMarkdownViewer(
    markdown: String,
    expanded: Boolean,
    showImages: Boolean
) {
    if (!expanded) {
        // 折叠态：显示简要预览（限制行数）。图片语法在这几行里只会是一串源码，直接去掉；
        // 图本身在展开态才画，折叠态没有位置容纳它。
        Text(
            text = markdown.lineSequence().filterNot { snapshotImageLine.matches(it.trim()) }.joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    } else {
        // 展开态：结构化 Markdown 人文排版（大标题、小标题、引用块、代码块、列表项）
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lines = markdown.lines()
            var inCode = false
            val codeLines = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("```")) {
                    if (inCode) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = codeLines.joinToString("\n"),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        codeLines.clear()
                        inCode = false
                    } else {
                        inCode = true
                    }
                    continue
                }
                if (inCode) {
                    codeLines.add(line)
                    continue
                }
                if (trimmed.isBlank()) {
                    Spacer(Modifier.height(3.dp))
                    continue
                }
                // 图片行单独处理：不画进正文，也不留一行 markdown 源码
                val imageLine = snapshotImageLine.matchEntire(trimmed)
                if (imageLine != null) {
                    if (showImages) {
                        val loaded = rememberLoadedImage(imageLine.groupValues[2])
                        if (loaded != null) {
                            Image(
                                bitmap = loaded,
                                contentDescription = imageLine.groupValues[1].ifBlank { "正文图片" },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio((loaded.width.toFloat() / loaded.height).coerceIn(0.5f, 3f))
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    continue
                }

                when {
                    trimmed.startsWith("# ") -> {
                        Text(
                            text = trimmed.removePrefix("# ").trim(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    trimmed.startsWith("## ") -> {
                        Text(
                            text = trimmed.removePrefix("## ").trim(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    trimmed.startsWith("### ") || trimmed.startsWith("#### ") -> {
                        val header = trimmed.replace(Regex("^#{3,4}\\s*"), "")
                        Text(
                            text = header,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    trimmed.startsWith("> ") -> {
                        Row(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = trimmed.removePrefix("> ").trim(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "•",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = trimmed.drop(2).trim(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = trimmed,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
