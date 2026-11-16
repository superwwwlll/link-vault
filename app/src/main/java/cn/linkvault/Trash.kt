package cn.linkvault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TrashPage(vm: VaultViewModel, onBack: () -> Unit) {
    var deleteForever by remember { mutableStateOf<Bookmark?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageToolbar("回收站", onBack)
        if (vm.trashItems.isEmpty()) {
            EmptyState(Glyph.Delete, "回收站为空")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Text(
                        "删除后保留 30 天，之后自动清理",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(vm.trashItems, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp)) {
                            Text(Links.displayTitle(item.url, item.title), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.restoreTrash(item) }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("恢复", fontSize = 17.sp)
                                }
                                TextButton(onClick = { deleteForever = item }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("永久删除", color = MaterialTheme.colorScheme.error, fontSize = 17.sp)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
