@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.linkvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EditorPage(vm: VaultViewModel, draft: Draft, onBack: () -> Unit) {
    val context = LocalContext.current
    PageToolbar(if (draft.id == 0L) "新建收藏" else "编辑收藏", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        vm.notice?.let { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) } }

        SectionLabel("原始链接")
        EditorField(draft.url, { vm.edit(draft.copy(url = it.take(16000))) }, "粘贴 HTTP / HTTPS 链接", !vm.busy, minLines = 2, maxLines = 5)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val shared = Capture.clipboard(context)
                    if (shared.text.isBlank() && shared.html.isBlank()) vm.fail("剪贴板里没有可用的文字")
                    else vm.paste(shared.text, shared.html)
                },
                enabled = !vm.busy, shape = RoundedCornerShape(12.dp)
            ) { Icon(Glyph.Copy, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text("粘贴并提取", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { vm.extractText(draft.url) }, enabled = !vm.busy) { Text("从输入中提取", style = MaterialTheme.typography.labelMedium) }
        }

        if (vm.detected.size > 1) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("这一段里还有 ${vm.detected.size - 1} 条链接", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = vm::saveAllDetected, enabled = !vm.busy, shape = RoundedCornerShape(12.dp)) {
                        Icon(Glyph.Add, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                        Text("把 ${vm.detected.size} 条全部收藏", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        SectionLabel("标题", trailing = if (draft.title.isBlank() && draft.url.isNotBlank()) Links.readable(draft.url).takeIf { it.isNotEmpty() }?.let { "自动：$it" }.orEmpty() else "")
        EditorField(draft.title, { vm.edit(draft.copy(title = it.take(200))) }, "留空则按链接路径自动生成", !vm.busy)
        SectionLabel("备注")
        EditorField(draft.notes, { vm.edit(draft.copy(notes = it.take(8000))) }, "写下灵感、重点，或收藏的理由…", !vm.busy, minLines = 4, maxLines = 9)
        SectionLabel("标签")
        TagInput(vm, draft)
    }
}

@Composable
private fun EditorField(value: String, onChange: (String) -> Unit, placeholder: String, enabled: Boolean, minLines: Int = 1, maxLines: Int = 3) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}
