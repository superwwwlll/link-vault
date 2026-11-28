@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.linkvault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EditorPage(vm: VaultViewModel, draft: Draft, onBack: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val adding = draft.id == 0L
    var detailsExpanded by rememberSaveable(draft.id) {
        mutableStateOf(!adding || draft.notes.isNotBlank() || draft.tags.isNotBlank())
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()) {
        PageToolbar(if (adding) "新建收藏" else "编辑收藏", onBack) {
            Button(
                onClick = { focus.clearFocus(); vm.save() },
                enabled = !vm.busy && draft.url.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(end = 12.dp).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(if (vm.busy) "保存中…" else "保存收藏", maxLines = 1)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditorCaption("链接")
            EditorField(draft.url, vm::updateDraftUrl, "输入或粘贴链接", !vm.busy, maxLines = 3, tag = "editor-url")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val shared = Capture.clipboard(context)
                        if (shared.text.isBlank() && shared.html.isBlank()) vm.fail("剪贴板里没有可用的文字")
                        else vm.paste(shared.text, shared.html)
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("粘贴并提取") }
                if (draft.url.isNotBlank() && vm.detected.isEmpty() && !Links.valid(draft.url.trim())) {
                    TextButton(onClick = { vm.extractText(draft.url) }, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("从输入中提取")
                    }
                }
            }
            if (vm.detected.size > 1) {
                vm.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = vm::saveAllDetected, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("把 ${vm.detected.size} 条全部收藏")
                }
            }
            if (adding) {
                if (!detailsExpanded && draft.title.isNotBlank()) {
                    Text(draft.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = {
                        focus.clearFocus()
                        detailsExpanded = !detailsExpanded
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) { Text(if (detailsExpanded) "收起标题、备注和标签" else "补充标题、备注和标签") }
            }
            if (detailsExpanded) {
                EditorCaption("标题")
                val autoTitle = Links.readable(draft.url).takeIf { it.isNotBlank() } ?: "选填"
                EditorField(draft.title, { vm.edit(draft.copy(title = it.take(200))) }, autoTitle, !vm.busy, tag = "editor-title")
                Spacer(Modifier.height(4.dp))
                EditorCaption("备注")
                EditorField(draft.notes, { vm.edit(draft.copy(notes = it.take(8000))) }, "选填", !vm.busy, minLines = 2, maxLines = 8, tag = "editor-notes")
                Spacer(Modifier.height(4.dp))
                EditorCaption("标签")
                TagInput(vm, draft)
            }
        }
    }
}

@Composable
private fun EditorCaption(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    tag: String,
    minLines: Int = 1,
    maxLines: Int = 3
) {
    TextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth().testTag(tag),
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 26.sp),
        shape = RoundedCornerShape(12.dp),
        colors = quietFillColors()
    )
}
