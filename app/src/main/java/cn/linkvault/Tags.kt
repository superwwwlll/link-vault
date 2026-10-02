@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package cn.linkvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TagsPage(vm: VaultViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    val totals = Links.tagTotals(vm.items)
    Box(Modifier.padding(horizontal = 24.dp)) { RootHeading("标签") }
    SearchBox(query, { query = it.take(1000) }, "查找标签", Modifier.padding(horizontal = 24.dp))
    val visible = totals.filter { it.first.contains(query, true) }
    val collectionSize = vm.items.count { !it.archived }

    if (visible.isEmpty()) {
        EmptyState(Glyph.Tag, if (totals.isEmpty()) "暂无标签" else "没有匹配的标签")
    } else LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(visible, key = { it.first }) { (tag, count) ->
            var menu by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(16.dp)
            Surface(
                onClick = { vm.tag(tag) },
                color = MaterialTheme.colorScheme.surface,
                shape = shape,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 0.dp
            ) {
                Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Glyph.Tag, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Glyph.More, "标签操作：$tag", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Glyph.Edit, null, Modifier.size(18.dp)) }, onClick = { menu = false; renameValue = tag; renameTarget = tag })
                                DropdownMenuItem(text = { Text("删除标签", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Glyph.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleteTarget = tag })
                            }
                        }
                    }
                    Text(tag, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$count 条收藏", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    renameTarget?.let { tag ->
        AlertDialog(
            onDismissRequest = { if (!vm.busy) renameTarget = null },
            icon = { Icon(Glyph.Edit, null) },
            title = { Text("重命名标签") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("将更新所有关联的收藏标签。", fontSize = 13.sp)
                    OutlinedTextField(renameValue, { renameValue = it.take(1000) }, label = { Text("新标签名") }, singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
            },
            confirmButton = { Button(onClick = { vm.renameTag(tag, renameValue); renameTarget = null }, enabled = !vm.busy && renameValue.isNotBlank()) { Text("重命名") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }, enabled = !vm.busy) { Text("取消") } }
        )
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
