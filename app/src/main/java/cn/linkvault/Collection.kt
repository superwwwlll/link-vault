@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package cn.linkvault

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val scopeLabels = listOf("未归档", "未读", "已读", "归档")

/** 固定顶部工具栏；紧凑列表与舒适卡片共享筛选和多选行为。 */
@Composable
internal fun CollectionPage(
    vm: VaultViewModel,
    onShare: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onCopy: (Bookmark) -> Unit,
    onCopyMarkdown: (Bookmark) -> Unit = {},
    onOpen: ((Bookmark) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    onSelectionChange: (Boolean) -> Unit = {}
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
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val selectionCallback by rememberUpdatedState(onSelectionChange)
    LaunchedEffect(selectionMode) { selectionCallback(selectionMode) }
    DisposableEffect(Unit) { onDispose { selectionCallback(false) } }
    BackHandler(enabled = selectionMode || searchExpanded || vm.search.isNotEmpty() || filtersExpanded) {
        when {
            selectionMode -> selectedIds = emptySet()
            searchExpanded || vm.search.isNotEmpty() -> { vm.search(""); searchExpanded = false }
            else -> filtersExpanded = false
        }
    }
    LaunchedEffect(visible) { selectedIds = selectedIds.intersect(visible.map { it.id }.toSet()) }

    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                FlowRow(Modifier.padding(start = 18.dp, end = 8.dp), verticalArrangement = Arrangement.Center) {
                    Text("已选择 ${selectedIds.size} 条", Modifier.align(Alignment.CenterVertically), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.bulkMarkRead(selectedIds); selectedIds = emptySet() }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("标已读") }
                    TextButton(onClick = { vm.bulkArchive(selectedIds); selectedIds = emptySet() }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("归档") }
                    TextButton(onClick = { confirmBulkDelete = true }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(48.dp)) { Icon(Glyph.Close, "取消多选", Modifier.size(16.dp)) }
                }
            }
        }
            Column(Modifier.padding(horizontal = 16.dp).testTag("collection-toolbar")) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的收藏", Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onBackground)
                    IconButton(onClick = { if (searchExpanded || vm.search.isNotEmpty()) { vm.search(""); searchExpanded = false } else searchExpanded = true }, modifier = Modifier.size(48.dp).testTag("collection-search-toggle")) {
                        Icon(if (searchExpanded || vm.search.isNotEmpty()) Glyph.Close else Glyph.Search,
                            if (searchExpanded || vm.search.isNotEmpty()) "收起搜索" else "展开搜索", Modifier.size(20.dp))
                    }
                }
                if (searchExpanded || vm.search.isNotEmpty()) {
                    CollectionSearch(vm.search, vm::search, onClose = { vm.search(""); searchExpanded = false })
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    scopeLabels.forEachIndexed { index, label ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { vm.scope(index) }, modifier = Modifier.heightIn(min = 48.dp).testTag("collection-scope-$index").semantics { selected = vm.scope == index },
                            colors = ButtonDefaults.textButtonColors(contentColor = if (vm.scope == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(label, fontWeight = if (vm.scope == index) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                            Text(" ${counts[index]}", fontSize = 12.sp, maxLines = 1)
                        }
                        Box(Modifier.width(64.dp).height(3.dp).background(if (vm.scope == index) MaterialTheme.colorScheme.primary else Color.Transparent))
                        }
                    }
                }
                if (filtersExpanded && tagNames.isNotEmpty() && vm.items.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                                    .heightIn(min = 48.dp)
                                    .widthIn(min = 48.dp)
                                    .semantics { this.selected = selected }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 9.dp)) { Text(
                                    tag,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                ) }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val heading = when {
                        vm.filter.isNotBlank() -> "# ${vm.filter}"
                        vm.scope == 3 -> "归档"
                        else -> "最近收藏"
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${visible.size} 条", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("collection-result-count"))
                        Text(heading, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (tagNames.isNotEmpty()) IconButton(onClick = { filtersExpanded = !filtersExpanded }, modifier = Modifier.size(48.dp)) {
                        Icon(Glyph.Tag, if (filtersExpanded) "收起标签筛选" else "展开标签筛选", Modifier.size(20.dp),
                            tint = if (vm.filter.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CollectionMoreMenu(vm.sortOrder, vm::setSort, visible.isNotEmpty(), vm.busy,
                        onRandom = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.randomRead()
                        }, onSelectAll = { selectedIds = visible.map { it.id }.toSet() },
                        onClearFilter = if (vm.filter.isNotBlank()) ({ vm.filter("") }) else null)
                }
            }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("collection-list"),
            contentPadding = PaddingValues(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(if (vm.compactCollection) 0.dp else 10.dp)
        ) {
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
                        .padding(horizontal = if (vm.compactCollection) 0.dp else 24.dp)
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                )
            }
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
private fun CollectionSearch(value: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(value = value, onValueChange = { onChange(it.replace("\n", "").replace("\r", "")) },
        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        placeholder = { Text("搜索标题、链接、备注、标签", fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Glyph.Search, null, Modifier.size(18.dp)) },
        trailingIcon = { IconButton(onClick = { if (value.isNotEmpty()) onChange("") else onClose() }, modifier = Modifier.size(48.dp)) {
            Icon(Glyph.Close, if (value.isNotEmpty()) "清空搜索" else "关闭搜索", Modifier.size(16.dp))
        } }, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("collection-search"))
}

@Composable
private fun CollectionMoreMenu(sortOrder: Int, onSelectSort: (Int) -> Unit, hasResults: Boolean, busy: Boolean,
    onRandom: () -> Unit, onSelectAll: () -> Unit, onClearFilter: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf("最新添加", "最早添加", "标题 A-Z", "站点聚合")
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).testTag("collection-more")) {
            Icon(Glyph.More, "更多收藏操作与排序", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("随机翻一篇") }, leadingIcon = { Icon(Glyph.Shuffle, null) },
                enabled = hasResults && !busy, onClick = { expanded = false; onRandom() })
            DropdownMenuItem(text = { Text("多选当前结果") }, leadingIcon = { Icon(Glyph.Check, null) },
                enabled = hasResults && !busy, onClick = { expanded = false; onSelectAll() })
            if (onClearFilter != null) DropdownMenuItem(text = { Text("清除标签筛选") },
                onClick = { expanded = false; onClearFilter() })
            HorizontalDivider()
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

/** 首条 assistant 消息才是总结；追问答案不能冒充摘要。 */
internal fun collectionAiExcerpt(summary: String): String = summary.lineSequence()
    .map { it.trim() }
    .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") && !it.startsWith("![") }
    .map { it.replace(Regex("^[-*>\\s]+|^\\d+[.)、]\\s*"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace("**", "").replace("`", "") }
    .filter { it.isNotBlank() && it.trimEnd('：', ':') !in listOf("一句话概括", "内容概要", "核心观点", "总结") }
    .firstOrNull().orEmpty().take(160)

internal fun collectionAiStatus(item: Bookmark, record: AnalysisRecord?, state: String?, error: String?): String = when {
    !state.isNullOrBlank() -> "AI · $state"
    !error.isNullOrBlank() -> "AI · 失败，打开详情重试"
    record == null -> "AI · 未分析"
    (record.suggestedTitle.isNotBlank() && record.suggestedTitle != item.title) ||
        record.suggestedTags.any { suggestion -> parseTags(item.tags).none { Links.tagKey(it) == Links.tagKey(suggestion) } } -> "AI · 标题 / 标签建议待确认"
    else -> "AI · 已总结"
}

/** 只展示首条 AI 总结；原始标题、摘要和追问内容都不被改写。 */
internal fun collectionSummary(item: Bookmark, record: AnalysisRecord?): String {
    val excerpt = collectionAiExcerpt(record?.messages?.firstOrNull { it.role == "assistant" }?.text.orEmpty())
    return if (excerpt.isNotBlank()) "AI 摘要 · $excerpt" else item.summary
}

/** 预留隐藏计数的实际宽度，任何字号下都只排一行。 */
internal fun collectionVisibleTagCount(widths: List<Int>, available: Int, gap: Int, overflowWidth: (Int) -> Int): Int {
    var used = 0
    var count = 0
    for (width in widths) {
        val next = used + (if (count > 0) gap else 0) + width
        val hidden = widths.size - count - 1
        val total = next + if (hidden > 0) gap + overflowWidth(hidden) else 0
        if (total > available) break
        used = next
        count++
    }
    return count
}

@Composable
private fun CollectionCardTags(tags: List<String>, onTag: (String) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("collection-card-tags")) {
        val padding = with(density) { 18.dp.roundToPx() }
        val minimum = with(density) { 48.dp.roundToPx() }
        val gap = with(density) { 6.dp.roundToPx() }
        fun width(text: String) = (measurer.measure(text, style, maxLines = 1).size.width + padding).coerceAtLeast(minimum)
        val widths = tags.map { width(it) }
        val count = collectionVisibleTagCount(widths, constraints.maxWidth, gap) { width("+$it") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            tags.take(count).forEachIndexed { index, tag ->
                CollectionTagButton(tag, Modifier.width(with(density) { widths[index].toDp() })) { onTag(tag) }
            }
            if (count < tags.size) CollectionTagButton("+${tags.size - count}",
                Modifier.widthIn(min = 48.dp).semantics { contentDescription = "查看全部 ${tags.size} 个标签" }) { showAll = true }
        }
    }
    if (showAll) AlertDialog(onDismissRequest = { showAll = false }, title = { Text("全部标签（${tags.size}）") },
        text = {
            FlowRow(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    TextButton(onClick = { showAll = false; onTag(tag) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(tag, fontSize = 12.sp)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showAll = false }) { Text("关闭") } })
}

@Composable
private fun CollectionTagButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 12.sp,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

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
        enableDismissFromStartToEnd = !selectionMode && !vm.busy,
        enableDismissFromEndToStart = !selectionMode && !vm.busy,
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
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(menu) {
        if (!menu) { pendingAction?.invoke(); pendingAction = null }
    }
    val compact = vm.compactCollection
    val titleColor = if (item.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val site = remember(item.url) { Links.siteName(item.url) }
    val displayTitle = remember(item.url, item.title) { Links.displayTitle(item.url, item.title) }
    val stamp = remember(item.createdAt) { Stamp.date(item.createdAt) }
    val tags = remember(item.tags) { parseTags(item.tags) }
    val aiKey = "b-${item.id}"
    LaunchedEffect(aiKey) { vm.analysis.load(aiKey) }
    val record = vm.analysis.records[aiKey]
    val aiStatus = collectionAiStatus(item, record, vm.analysis.states[aiKey], vm.analysis.errors[aiKey])
    val summary = remember(item.summary, record) { collectionSummary(item, record) }

    Box {
        Surface(
            shape = if (compact) RoundedCornerShape(0.dp) else CardShape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (compact) null else BorderStroke(0.6.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().testTag("collection-bookmark-${item.id}").semantics { this.selected = selected }.clip(if (compact) RoundedCornerShape(0.dp) else CardShape).combinedClickable(
                onClick = if (selectionMode) onToggleSelection else onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleSelection()
                }
            )
        ) {
            // 紧凑模式来源与标题同排；舒适模式保留两行标题、备注和底部来源。
            Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 14.dp, vertical = if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectionMode) Icon(if (selected) Glyph.Check else Glyph.Bookmark, if (selected) "已选择" else "未选择", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    else if (compact) SourceMark(item.url, size = MarkSize.Tiny, muted = item.read)
                    Text(
                        displayTitle,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!selectionMode) IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("collection-item-menu-${item.id}")) {
                        Icon(Glyph.More, "收藏操作", Modifier.size(18.dp))
                    }
                }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!compact && item.notes.isNotBlank()) {
                    NoteSnippetCard(text = item.notes, maxLines = 2)
                }
                if (tags.isNotEmpty()) CollectionCardTags(tags) { if (selectionMode) onToggleSelection() else onTag(it) }
                if (record != null || vm.analysis.auto || vm.analysis.states[aiKey] != null || vm.analysis.errors[aiKey] != null) Surface(
                    shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) { Text(aiStatus, fontSize = 11.sp,
                    color = if (vm.analysis.errors[aiKey] != null && vm.analysis.states[aiKey] == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).testTag("collection-ai-status-${item.id}")) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (!compact) SourceMark(item.url, size = MarkSize.Tiny, muted = item.read)
                    Text(site, Modifier.weight(1f), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.pinned) Icon(Glyph.Pin, "已置顶", Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stamp, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                }
            }
        }
        if (compact) HorizontalDivider(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        if (menu) {
            ModalBottomSheet(onDismissRequest = { menu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("collection-item-actions")) {
                    Text(displayTitle, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    fun dismissThen(action: () -> Unit) { pendingAction = action; menu = false }
                    CollectionAction("分享", Glyph.Share) { dismissThen(onShare) }
                    CollectionAction("复制链接", Glyph.Copy) { dismissThen(onCopy) }
                    CollectionAction("复制为 Markdown", Glyph.Markdown) { dismissThen(onCopyMarkdown) }
                    CollectionAction("编辑", Glyph.Edit) { dismissThen { vm.open(item) } }
                    CollectionAction("编辑标签", Glyph.Tag) { dismissThen { vm.open(item) } }
                    CollectionAction(if (item.pinned) "取消置顶" else "置顶", Glyph.Pin) { dismissThen { vm.togglePin(item) } }
                    CollectionAction(if (item.read) "标为未读" else "标为已读", Glyph.Check) { dismissThen { vm.toggleRead(item) } }
                    CollectionAction(if (item.archived) "移出归档" else "归档", Glyph.Archive) { dismissThen { vm.toggleArchived(item) } }
                    CollectionAction("删除", Glyph.Delete, destructive = true) { dismissThen(onDelete) }
                }
            }
        }
    }
}

@Composable
private fun CollectionAction(label: String, glyph: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(glyph, null, Modifier.size(20.dp), tint = color)
        Text(label, color = color)
    }
}
