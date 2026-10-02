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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.testTag
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
    onOpen: ((Bookmark) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState()
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
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionMode = selectedIds.isNotEmpty()
    var confirmBulkDelete by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(visible) { selectedIds = selectedIds.intersect(visible.map { it.id }.toSet()) }

    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("collection-list"),
        contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (selectionMode) item(contentType = "selection") {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选择 ${selectedIds.size} 条", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.bulkMarkRead(selectedIds); selectedIds = emptySet() }, enabled = !vm.busy) { Text("标已读") }
                    TextButton(onClick = { vm.bulkArchive(selectedIds); selectedIds = emptySet() }, enabled = !vm.busy) { Text("归档") }
                    TextButton(onClick = { confirmBulkDelete = true }, enabled = !vm.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(32.dp)) { Icon(Glyph.Close, "取消多选", Modifier.size(16.dp)) }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                RootHeading("我的收藏")
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val tabContent: @Composable (Modifier) -> Unit = { tabModifier ->
                        ScopeTabs(
                            items = listOf(0, 1, 2, 3),
                            selectedItem = vm.scope,
                            onSelect = { index -> vm.scope(index) },
                            label = { index -> scopeLabels[index] },
                            badge = { index -> if (counts[index] > 0 && index != 0) counts[index].toString() else null },
                            modifier = tabModifier
                        )
                    }
                    if (maxWidth >= 600.dp) {
                        val tabWidth = (maxWidth - 262.dp).coerceAtLeast(300.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                            tabContent(Modifier.width(tabWidth))
                            SearchBox(vm.search, vm::search, "搜索收藏", Modifier.width(250.dp))
                        }
                    } else {
                        Column {
                            tabContent(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            SearchBox(vm.search, vm::search, "搜索标题、链接、备注、标签")
                        }
                    }
                }

                if (tagNames.isNotEmpty() && vm.items.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tagNames.forEach { tag ->
                            val selected = vm.filter == tag
                            val shape = RoundedCornerShape(50)
                            Surface(
                                shape = shape,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = if (selected) null else BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .clip(shape)
                                    .clickable { vm.filter(if (selected) "" else tag) }
                            ) {
                                Text(
                                    tag,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    maxLines = 1
                                )
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
                    // 条数并进分区标题：单独占一行右对齐，白占一行高度却只有一句话
                    Text(
                        buildString {
                            append(heading)
                            if (vm.items.isNotEmpty()) append(" · ${visible.size} 条")
                        },
                        Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (visible.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.randomRead()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Glyph.Shuffle, null, Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("翻一篇", fontSize = 12.sp)
                        }
                        TextButton(onClick = { selectedIds = visible.map { it.id }.toSet() }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("多选", fontSize = 12.sp) }
                    }
                    SortMenuButton(sortOrder = vm.sortOrder, onSelectSort = vm::setSort)
                }
            }
        }

        if (vm.loading) item(contentType = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
        if (vm.readFailed) item(contentType = "retry") { TextButton(onClick = vm::reload, modifier = Modifier.padding(horizontal = 24.dp)) { Text("读取失败，点击重试") } }

        if (visible.isEmpty() && !vm.loading) {
            item(contentType = "empty") {
                EmptyState(
                    Glyph.Bookmark,
                    when {
                        vm.items.isEmpty() -> "暂无收藏"
                        vm.scope == 3 -> "归档为空"
                        vm.scope == 2 -> "暂无已读收藏"
                        vm.scope == 1 -> "已全部读完"
                        else -> "未找到匹配收藏"
                    },
                    if (vm.items.isEmpty()) "从分享菜单或右下角 +，为值得回看的链接留个位置。" else "试试其他筛选，或换个关键词。"
                )
            }
        } else {
            items(
                items = visible,
                key = { it.id },
                contentType = { "bookmark" }
            ) { item ->
                SwipeableBookmarkCard(
                    vm = vm,
                    item = item,
                    selected = item.id in selectedIds,
                    selectionMode = selectionMode,
                    onToggleSelection = { selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id },
                    onClick = { vm.show(item) },
                    onShare = { onShare(item) },
                    onDelete = { onDelete(item) },
                    onCopy = { onCopy(item) },
                    onCopyMarkdown = { onCopyMarkdown(item) },
                    onTag = vm::tag,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                )
            }
        }
    }

    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("移入回收站？") },
            text = { Text("已选择 ${selectedIds.size} 条收藏，删除后 30 天内可以恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmBulkDelete = false; vm.bulkDelete(selectedIds); selectedIds = emptySet() }, enabled = !vm.busy) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("取消") } }
        )
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

private val CardShape = RoundedCornerShape(16.dp)

@Composable
private fun SwipeableBookmarkCard(
    vm: VaultViewModel,
    item: Bookmark,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onTag: (String) -> Unit,
    modifier: Modifier = Modifier
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
            if (dismissState.targetValue != SwipeToDismissBoxValue.Settled || dismissState.progress > 0.05f) {
                val direction = dismissState.dismissDirection
                val isReadAction = direction == SwipeToDismissBoxValue.StartToEnd
                val (bgColor, tintColor) = if (isReadAction) {
                    MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
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
                        .clip(CardShape)
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
            }
        },
        modifier = modifier.fillMaxWidth()
    ) {
        BookmarkCard(
            vm = vm,
            item = item,
            selected = selected,
            selectionMode = selectionMode,
            onToggleSelection = onToggleSelection,
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
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onTag: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val titleColor = if (item.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val site = remember(item.url) { Links.siteName(item.url) }
    val displayTitle = remember(item.url, item.title) { Links.displayTitle(item.url, item.title) }
    val stamp = remember(item.createdAt) { Stamp.date(item.createdAt) }
    val tags = remember(item.tags) { parseTags(item.tags) }

    Box {
        Surface(
            shape = CardShape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.6.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().clip(CardShape).combinedClickable(
                onClick = if (selectionMode) onToggleSelection else onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (selectionMode) onToggleSelection() else menu = true
                }
            )
        ) {
            // 标题是卡片的第一视觉：来源色块从顶部横排挪到底部元信息行，
            // 正文因此占满宽度，两行标题不再被色块挤成三行。
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    displayTitle,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.summary.isNotBlank()) {
                    Text(
                        item.summary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.notes.isNotBlank()) {
                    NoteSnippetCard(text = item.notes, maxLines = 2)
                }
                if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    tags.take(4).forEach { tag -> TagPill(tag, onClick = { onTag(tag) }) }
                    if (tags.size > 4) TagPill("+${tags.size - 4}")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SourceMark(item.url, size = MarkSize.Tiny, muted = item.read)
                    if (site.length > 1) {
                        Text(site, Modifier.weight(1f), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (item.pinned) Icon(Glyph.Pin, "已置顶", Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stamp, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                }
            }
        }
        if (menu) {
            DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
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
}
