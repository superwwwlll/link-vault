@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.linkvault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EditorPage(vm: VaultViewModel, draft: Draft, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageToolbar(if (draft.id == 0L) "新建收藏" else "编辑收藏", onBack) {
            TextButton(onClick = vm::save, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (vm.busy) "保存中…" else "保存收藏", fontFamily = ReadingSerif, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 28.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (draft.id == 0L) {
                Text("把这一篇留在书架上", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, fontSize = 28.sp, lineHeight = 38.sp)
            }
            vm.notice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            EditorCaption("链接")
            EditorField(draft.url, { vm.edit(draft.copy(url = it.take(16000))) }, "粘贴 HTTP / HTTPS 链接", !vm.busy, minLines = 2, maxLines = 5)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val shared = Capture.clipboard(context)
                        if (shared.text.isBlank() && shared.html.isBlank()) vm.fail("剪贴板里没有可用的文字")
                        else vm.paste(shared.text, shared.html)
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("粘贴并提取", color = MaterialTheme.colorScheme.primary) }
                TextButton(onClick = { vm.extractText(draft.url) }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("从输入中提取", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (vm.detected.size > 1) {
                Text("这一段里还有 ${vm.detected.size - 1} 条链接", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = vm::saveAllDetected, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("把 ${vm.detected.size} 条全部收藏", color = MaterialTheme.colorScheme.primary)
                }
            }
            EditorCaption("标题", trailing = if (draft.title.isBlank() && draft.url.isNotBlank()) Links.readable(draft.url).takeIf { it.isNotEmpty() }?.let { "自动：$it" }.orEmpty() else "")
            EditorField(draft.title, { vm.edit(draft.copy(title = it.take(200))) }, "留空则按链接路径自动生成", !vm.busy)
            EditorCaption("一句")
            EditorField(draft.notes, { vm.edit(draft.copy(notes = it.take(8000))) }, "写下灵感、重点，或收藏的理由", !vm.busy, minLines = 4, maxLines = 9)
            EditorCaption("标签")
            TagInput(vm, draft)
        }
    }
}

@Composable
private fun EditorCaption(text: String, trailing: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontFamily = ReadingSerif, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun EditorField(value: String, onChange: (String) -> Unit, placeholder: String, enabled: Boolean, minLines: Int = 1, maxLines: Int = 3) {
    TextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 26.sp),
        shape = RoundedCornerShape(0.dp),
        colors = readingLineColors()
    )
}
