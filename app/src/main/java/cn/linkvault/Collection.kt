@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package cn.linkvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val scopeLabels = listOf("全部", "未读", "已读", "归档")

/**
 * 收藏列表。
 *
 * 整个顶部（标题、搜索、筛选、计数）都放在 LazyColumn 里，所以**往下滚时它会整块让开**，
 * 屏幕全给卡片；滚回去就回来了。这是把「一屏只能看 2.3 张卡」改成「一屏看 4 张」的关键。
 */
@Composable
internal fun CollectionPage(
    vm: VaultViewModel,
    onShare: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onCopy: (Bookmark) -> Unit,
    onCopyMarkdown: (Bookmark) -> Unit = {},
    onOpen: ((Bookmark) -> Unit)? = null
) {
    // 筛选与聚合都只在输入真的变了时才算，避免每次重组都全量遍历一遍收藏
    val visible = remember(vm.items, vm.search, vm.filter, vm.scope, vm.sortOrder) { vm.visible() }
    val tagTotals = remember(vm.items) { Links.tagTotals(vm.items) }
    val tagNames = remember(tagTotals, vm.filter) {
        (tagTotals.map { it.first } + listOf(vm.filter).filter { it.isNotEmpty() && tagTotals.none { t -> t.first == it } }).distinct()
    }
    val counts = remember(vm.items) {
        listOf(
            vm.items.count { !it.archived },
            vm.items.count { !it.archived && !it.read },
            vm.items.count { !it.archived && it.read },
            vm.items.count { it.archived }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                RootHeading("我的收藏")
                SearchBox(vm.search, vm::search, "搜索标题、链接、备注、标签")

                if (vm.items.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SegmentedPills(
                            items = listOf(0, 1, 2, 3),
                            selectedItem = vm.scope,
                            onSelect = { index -> vm.scope(if (vm.scope == index && index != 0) 0 else index) },
                            label = { index -> scopeLabels[index] },
                            badge = { index -> if (counts[index] > 0 && index != 0) counts[index].toString() else null }
                        )
                        Spacer(Modifier.weight(1f))
                        Text("${visible.size} 条", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tagNames.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tagNames.forEach { tag ->
                                val selected = vm.filter == tag
                                val shape = RoundedCornerShape(8.dp)
                                Surface(
                                    shape = shape,
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        0.6.dp,
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .clip(shape)
                                        .clickable { vm.filter(if (selected) "" else tag) }
                                ) {
                                    Text(
                                        tag,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val heading = when {
                        vm.filter.isNotBlank() -> "# ${vm.filter}"
                        vm.scope == 3 -> "归档"
                        else -> "最近收藏"
                    }
                    Text(heading, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SortMenuButton(sortOrder = vm.sortOrder, onSelectSort = vm::setSort)
                }
            }
        }

        if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
        if (vm.readFailed) item { TextButton(onClick = vm::reload, modifier = Modifier.padding(horizontal = 24.dp)) { Text("读取失败，点击重试") } }

        if (visible.isEmpty() && !vm.loading) {
            item {
                EmptyState(
                    Glyph.Bookmark,
                    when {
                        vm.items.isEmpty() -> "暂无收藏"
                        vm.scope == 3 -> "归档为空"
                        vm.scope == 2 -> "暂无已读收藏"
                        vm.scope == 1 -> "已全部读完"
                        else -> "未找到匹配收藏"
                    }
                )
            }
        } else {
            items(visible, key = { it.id }) { item ->
                Box(Modifier.padding(horizontal = 24.dp).animateItem()) {
                    SwipeableBookmarkCard(
                        vm = vm,
                        item = item,
                        onClick = { vm.show(item) },
                        onShare = { onShare(item) },
                        onDelete = { onDelete(item) },
                        onCopy = { onCopy(item) },
                        onCopyMarkdown = { onCopyMarkdown(item) },
                        onTag = vm::tag
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenuButton(sortOrder: Int, onSelectSort: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf("最新添加", "最早添加", "标题 A-Z", "站点聚合")
    val currentLabel = labels.getOrElse(sortOrder) { "最新添加" }
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.clickable { expanded = true }
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Glyph.Sort, "排序", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currentLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (sortOrder == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (sortOrder == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = if (sortOrder == index) {
                        { Icon(Glyph.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelectSort(index)
                    }
                )
            }
        }
    }
}

@Composable
private fun SwipeableBookmarkCard(
    vm: VaultViewModel,
    item: Bookmark,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onTag: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isDark = LocalVaultDark.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.toggleRead(item)
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.toggleArchived(item)
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val shape = RoundedCornerShape(18.dp)
            val isReadAction = direction == SwipeToDismissBoxValue.StartToEnd
            val (bgColor, tintColor) = if (isReadAction) {
                if (isDark) Color(0xFF1E3A8A) to Color(0xFF60A5FA)
                else Color(0xFFEFF6FF) to Color(0xFF007AFF)
            } else {
                if (isDark) Color(0xFF27272A) to Color(0xFFD4D4D8)
                else Color(0xFFF4F4F5) to Color(0xFF52525B)
            }
            val alignment = if (isReadAction) Alignment.CenterStart else Alignment.CenterEnd
            val icon = if (isReadAction) {
                if (item.read) Glyph.Bookmark else Glyph.Check
            } else {
                Glyph.Archive
            }
            val label = if (isReadAction) {
                if (item.read) "标为未读" else "标为已读"
            } else {
                if (item.archived) "移出归档" else "归档"
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isReadAction) {
                        Icon(icon, null, Modifier.size(18.dp), tint = tintColor)
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tintColor)
                    } else {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tintColor)
                        Icon(icon, null, Modifier.size(18.dp), tint = tintColor)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        BookmarkCard(
            vm = vm,
            item = item,
            onClick = onClick,
            onShare = onShare,
            onDelete = onDelete,
            onCopy = onCopy,
            onCopyMarkdown = onCopyMarkdown,
            onTag = onTag
        )
    }
}

@Composable
private fun BookmarkCard(
    vm: VaultViewModel,
    item: Bookmark,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onTag: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val titleColor = if (item.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val site = Links.siteName(item.url)
    Box {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 0.5.dp,
            modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                }
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceMark(item.url, small = true, muted = item.read)
                    if (site.length > 1) {
                        Text(site, Modifier.weight(1f).padding(start = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (item.pinned) {
                        Icon(Glyph.Pin, "已置顶", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(Stamp.date(item.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
                Text(
                    Links.displayTitle(item.url, item.title),
                    fontSize = 16.5.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.summary.isNotBlank()) {
                    Text(
                        item.summary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.notes.isNotBlank()) {
                    NoteSnippetCard(text = item.notes, maxLines = 2)
                }
                val tags = parseTags(item.tags)
                if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(4).forEach { tag -> TagPill(tag, onClick = { onTag(tag) }) }
                    if (tags.size > 4) TagPill("+${tags.size - 4}")
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (item.pinned) "取消置顶" else "置顶") }, leadingIcon = { Icon(Glyph.Pin, null, Modifier.size(18.dp)) }, onClick = { menu = false; vm.togglePin(item) })
            DropdownMenuItem(text = { Text(if (item.read) "标为未读" else "标为已读") }, leadingIcon = { Icon(Glyph.Check, null, Modifier.size(18.dp)) }, onClick = { menu = false; vm.toggleRead(item) })
            DropdownMenuItem(text = { Text(if (item.archived) "移出归档" else "归档") }, leadingIcon = { Icon(Glyph.Archive, null, Modifier.size(18.dp)) }, onClick = { menu = false; vm.toggleArchived(item) })
            DropdownMenuItem(text = { Text("复制链接") }, leadingIcon = { Icon(Glyph.Copy, null, Modifier.size(18.dp)) }, onClick = { menu = false; onCopy() })
            DropdownMenuItem(text = { Text("复制为 Markdown") }, leadingIcon = { Icon(Glyph.Markdown, null, Modifier.size(18.dp)) }, onClick = { menu = false; onCopyMarkdown() })
            DropdownMenuItem(text = { Text("分享") }, leadingIcon = { Icon(Glyph.Share, null, Modifier.size(18.dp)) }, onClick = { menu = false; onShare() })
            DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Glyph.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
        }
    }
}
