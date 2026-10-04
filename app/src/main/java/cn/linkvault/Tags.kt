@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.linkvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class TagSort(val label: String) {
    COUNT("数量最多"), NAME("名称 A–Z")
}

internal fun visibleTags(totals: List<Pair<String, Int>>, query: String, sort: TagSort): List<Pair<String, Int>> {
    val filtered = totals.filter { it.first.contains(query, ignoreCase = true) }
    val byName = compareBy<Pair<String, Int>> { Links.tagKey(it.first) }.thenBy { it.first }
    return filtered.sortedWith(if (sort == TagSort.COUNT) compareByDescending<Pair<String, Int>> { it.second }.then(byName) else byName)
}

internal fun validTagRename(from: String, to: String): Boolean {
    val target = to.trim()
    return target.isNotEmpty() && target.length <= 1000 && parseTags(target).singleOrNull() == target &&
        Links.tagKey(from) != Links.tagKey(target)
}

@Composable
internal fun TagsPage(vm: VaultViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(TagSort.COUNT) }
    var sortMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var mergeSource by remember { mutableStateOf<String?>(null) }
    var mergeDestination by remember { mutableStateOf<String?>(null) }
    var mergeQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    val totals = Links.tagTotals(vm.items)
    val visible = visibleTags(totals, query, sort)

    BackHandler(enabled = renameTarget != null || mergeSource != null) {
        if (!vm.busy) { renameTarget = null; mergeSource = null }
    }
    renameTarget?.let { tag ->
        val existing = totals.firstOrNull { Links.tagKey(it.first) == Links.tagKey(renameValue) && Links.tagKey(it.first) != Links.tagKey(tag) }?.first
        Column(Modifier.fillMaxSize()) {
            PageToolbar(if (existing == null) "重命名标签" else "合并到已有标签", { if (!vm.busy) renameTarget = null })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("原标签：$tag", fontWeight = FontWeight.Medium)
                Text(if (existing == null) "将更新所有关联的收藏标签。" else "将把「$tag」合并到「$existing」，原标签会移除，重复标签会去重；收藏本身不会删除。", fontSize = 13.sp)
                OutlinedTextField(renameValue, { renameValue = it.take(1000) }, label = { Text("新标签名") },
                    supportingText = { Text("不能包含逗号、分号或换行，且不能与原标签相同。") },
                    enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4)
                Button(onClick = { vm.renameTag(tag, existing ?: renameValue); renameTarget = null },
                    enabled = !vm.busy && validTagRename(tag, renameValue), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (existing == null) "重命名" else "确认合并")
                }
                TextButton(onClick = { renameTarget = null }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            }
        }
        return
    }
    mergeSource?.let { source ->
        val candidates = visibleTags(totals.filterNot { Links.tagKey(it.first) == Links.tagKey(source) }, mergeQuery, TagSort.NAME)
        val destination = mergeDestination?.takeIf { chosen -> totals.any { Links.tagKey(it.first) == Links.tagKey(chosen) } }
        Column(Modifier.fillMaxSize()) {
            PageToolbar("合并标签", { if (!vm.busy) mergeSource = null })
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("原标签：$source", fontWeight = FontWeight.Medium)
                    Text("选择要保留的已有标签。合并会更新所有关联收藏（含归档），移除原标签并去重，不会删除收藏。", fontSize = 13.sp)
                    OutlinedTextField(mergeQuery, { mergeQuery = it.take(1000) }, label = { Text("查找目标标签") }, singleLine = true,
                        enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
                }
                if (candidates.isEmpty()) item { Text("没有匹配的标签") }
                items(candidates, key = { Links.tagKey(it.first) }) { (target, count) ->
                    TextButton(onClick = { mergeDestination = target }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(target, Modifier.weight(1f), fontWeight = if (destination == target) FontWeight.Bold else FontWeight.Normal)
                        Spacer(Modifier.width(8.dp)); Text("$count 条")
                    }
                }
                item {
                    Text(if (destination == null) "尚未选择目标标签" else "将「$source」合并到「$destination」", fontWeight = FontWeight.Medium)
                    Button(onClick = { destination?.let { vm.renameTag(source, it); mergeSource = null } }, enabled = !vm.busy && destination != null,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("确认合并") }
                    TextButton(onClick = { mergeSource = null }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                }
            }
        }
        return
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!searchExpanded) Text("标签", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        SearchBox(query, { query = it.take(1000) }, "查找标签", Modifier.weight(1f), onExpandedChange = { searchExpanded = it }, initiallyExpanded = searchExpanded)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (query.isEmpty()) "${totals.size} 个标签" else "${visible.size} / ${totals.size} 个标签",
            Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            TextButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("排序：${sort.label}")
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                TagSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortMenu = false })
                }
            }
        }
    }

    if (visible.isEmpty()) {
        EmptyState(Glyph.Tag, if (totals.isEmpty()) "暂无标签" else "没有匹配的标签")
    } else LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("tags-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(visible, key = { Links.tagKey(it.first) }) { (tag, count) ->
            var menu by remember { mutableStateOf(false) }
            Surface(
                onClick = { vm.tag(tag) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        tag, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text("$count 条", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp), enabled = !vm.busy) {
                            Icon(Glyph.More, "标签操作：$tag", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("重命名") }, leadingIcon = { Icon(Glyph.Edit, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; renameValue = tag; renameTarget = tag }
                            )
                            DropdownMenuItem(
                                text = { Text("合并到…") }, enabled = totals.size > 1,
                                onClick = { menu = false; mergeDestination = null; mergeQuery = ""; mergeSource = tag }
                            )
                            DropdownMenuItem(
                                text = { Text("删除标签", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Glyph.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleteTarget = tag }
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { tag ->
        AlertDialog(
            onDismissRequest = { if (!vm.busy) deleteTarget = null },
            icon = { Icon(Glyph.Tag, null) },
            title = { Text("删除标签「$tag」？") },
            text = { Text("将从所有收藏中移除此标签，收藏本身不会被删除。", fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { vm.deleteTag(tag); deleteTarget = null }, enabled = !vm.busy) { Text("删除标签", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !vm.busy) { Text("取消") } }
        )
    }
}
