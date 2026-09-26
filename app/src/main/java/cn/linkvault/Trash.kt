package cn.linkvault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TrashPage(vm: VaultViewModel, onBack: () -> Unit) {
    var deleteForever by remember { mutableStateOf<Bookmark?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageToolbar("回收站", onBack)
        if (vm.trashItems.isEmpty()) {
            EmptyState(Glyph.Delete, "回收站为空")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("删除后保留 30 天，之后自动清理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(vm.trashItems, key = { it.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Links.displayTitle(item.url, item.title), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.restoreTrash(item) }, enabled = !vm.busy, shape = RoundedCornerShape(11.dp)) {
                                    Icon(Glyph.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("恢复")
                                }
                                TextButton(onClick = { deleteForever = item }, enabled = !vm.busy) {
                                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteForever?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteForever = null },
            title = { Text("永久删除这条收藏？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = { deleteForever = null; vm.deleteForever(item) }, enabled = !vm.busy) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteForever = null }) { Text("取消") } }
        )
    }
}
