@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.linkvault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AiConnectionSettings(vm: VaultViewModel) {
    val uri = LocalUriHandler.current
    val provider = AiProviders.presets.firstOrNull { it.id == vm.aiProvider } ?: AiProviders.presets.last()
    var showKey by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmClearKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("连接配置", trailing = if (vm.aiVerified) "验证通过" else "待验证")
        SettingsChoice("服务商", provider.name, AiProviders.presets.map { it.id to it.name }, !vm.aiTesting) { id ->
            showKey = false
            vm.selectAiProvider(AiProviders.presets.first { it.id == id })
        }
        TextField(vm.aiModel, { vm.aiModel(it) }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !vm.aiTesting, shape = RoundedCornerShape(10.dp), colors = quietFillColors())
        TextField(vm.aiKey, { vm.aiKey(it.take(400)) }, label = { Text("你的 API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !vm.aiTesting,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            shape = RoundedCornerShape(10.dp), colors = quietFillColors(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showKey) "隐藏" else "显示") } })
        TextButton(onClick = vm::testAiConnection, enabled = !vm.aiTesting && vm.aiKey.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (vm.aiTesting) "测试中…" else "测试连接", fontSize = 17.sp) }
        if (vm.aiTestResult.isNotEmpty() && (vm.aiTesting || !vm.aiTestResult.startsWith("连接验证通过") || vm.aiVerified))
            Text(vm.aiTestResult, style = MaterialTheme.typography.labelMedium, color = if (vm.aiVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text("可能计费。密钥只存本机。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (advanced) "收起高级配置" else "高级配置（API 地址）") }
        if (advanced) {
            TextField(vm.aiEndpoint, vm::aiEndpoint, label = { Text("API 地址（完整 chat/completions 地址）") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !vm.aiTesting, shape = RoundedCornerShape(10.dp), colors = quietFillColors())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { confirmClearKey = true }, enabled = !vm.aiTesting && vm.aiKey.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("清除密钥") }
            if (provider.console.isNotEmpty()) TextButton(onClick = {
                runCatching { uri.openUri(provider.console) }.onFailure { vm.fail("没有可用浏览器") }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("官方开通 / 充值") }
        }
    }
    if (confirmClearKey) AlertDialog(onDismissRequest = { confirmClearKey = false }, title = { Text("清除密钥？") },
        text = { Text("需重新测试。已有总结保留。") },
        confirmButton = { TextButton(onClick = { confirmClearKey = false; showKey = false; vm.clearAiKey() }, enabled = !vm.aiTesting) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmClearKey = false }) { Text("取消") } })
}

/** 全宽选择入口，避免服务商与模板在窄屏上堆成多行标签。 */
@Composable
private fun SettingsChoice(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { expanded = true },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("$label：$selected", Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
            Icon(Glyph.Next, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
internal fun AnalysisSettings(vm: VaultViewModel) {
    val ai = vm.analysis
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var editTemplate by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("AI 阅读助手")
        TextField(ai.profile, ai::profile, label = { Text("我的工作、兴趣与关注方向") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, shape = RoundedCornerShape(10.dp), colors = quietFillColors())
        SettingsChoice("分析模板", ai.active.name.ifBlank { "未命名" }, ai.templates.map { it.id to it.name.ifBlank { "未命名" } }, onSelect = ai::select)
        TextButton(onClick = { editTemplate = !editTemplate }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editTemplate) "收起模板编辑" else "编辑模板") }
        if (editTemplate) {
            TextField(ai.active.name, { ai.updateTemplate(it, ai.active.prompt) }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = quietFillColors())
            TextField(ai.active.prompt, { ai.updateTemplate(ai.active.name, it) }, label = { Text("提示词框架（可直接编辑）") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12, shape = RoundedCornerShape(10.dp), colors = quietFillColors())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ai::addTemplate, modifier = Modifier.heightIn(min = 48.dp)) { Text("新增模板") }
                TextButton(onClick = { confirmRemove = true }, enabled = ai.templates.size > 1, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除当前模板") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("保存后自动分析", fontWeight = FontWeight.SemiBold)
                Text(if (ai.auto) "已开启" else "未开启", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ai.auto, onCheckedChange = { if (it) confirmEnable = true else ai.auto(false) })
        }
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false }, title = { Text("开启自动分析？") },
        text = { Text("保存后会发送正文，可能计费。私密笔记不发送。") },
        confirmButton = { TextButton(onClick = { confirmEnable = false; ai.auto(true) }, enabled = vm.aiVerified) { Text(if (vm.aiVerified) "开启" else "请先测试连接") } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("删除这个模板？") },
        text = { Text("已保存的总结和对话不受影响。") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; ai.removeTemplate() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } })
}

/** 只给已保存的非私密条目展示，不会因打开界面自动发送内容。 */
@Composable
internal fun AnalysisPanel(vm: VaultViewModel, key: String) {
    val ai = vm.analysis
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(key) { ai.load(key) }
    val record = ai.records[key]
    val state = ai.states[key]
    val error = ai.errors[key]
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    if (!vm.aiVerified && record == null && state == null && error == null) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AI 总结与对话", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { vm.openSettings("AI 接口") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("去设置"); Icon(Glyph.Next, null, Modifier.size(16.dp))
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("AI 总结与对话", trailing = record?.template ?: ai.active.name)
        if (record != null) {
            if (record.sourceChars > Analysis.MAX_SOURCE) Text("材料 ${record.sourceChars} 字，仅分析前 ${Analysis.MAX_SOURCE} 字。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            SelectionContainer { SnapshotMarkdownViewer(record.messages.first().text, expanded = true, showImages = false) }
            if (key.startsWith("b-") && record.suggestedTitle.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("标题与标签建议", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(record.suggestedTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { record.suggestedTags.forEach { TagPill(it) } }
                        TextButton(onClick = { confirmApply = true }, enabled = state == null) { Text("应用标题与标签") }
                }
            }
            TextButton(onClick = { vm.openAnalysisConversation(key) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("继续对话", fontSize = 17.sp)
                if (record.messages.size > 1) Text(" · ${(record.messages.size - 1) / 2}轮", fontSize = 17.sp)
            }
        }
        if (state != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row { Text(state, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); TextButton(onClick = { ai.cancel(key) }) { Text("取消") } }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        if (!vm.aiVerified) TextButton(onClick = { vm.openSettings("AI 接口") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("去设置") }
        Column {
            TextButton(onClick = { if (record == null) ai.run(key) else confirmReplace = true }, enabled = state == null && vm.aiVerified, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (record == null) "分析这篇内容" else "按当前模板重新分析", fontSize = 17.sp)
            }
            if (record != null) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(record.messages.joinToString("\n\n") { if (it.role == "user") "我：${it.text}" else it.text })); vm.toast("已复制总结与对话") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制", fontSize = 17.sp) }
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除 AI 记录", fontSize = 17.sp) }
            }
        }
    }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("重新分析？") }, text = { Text("会再次计费，并替换原总结。") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; ai.run(key) }) { Text("重新分析") } }, dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除总结与对话？") }, text = { Text("只删本机记录，不改原文。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; ai.clear(key); vm.clearConversationDraft(key) }) { Text("清除") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    if (confirmApply && record != null) AlertDialog(onDismissRequest = { confirmApply = false }, title = { Text("应用 AI 建议？") },
        text = { Text("标题改为「${record.suggestedTitle}」，追加标签：${record.suggestedTags.joinToString("、")}。原文和备注保留。") },
        confirmButton = { TextButton(onClick = { confirmApply = false; ai.applySuggestions(key) }) { Text("确认应用") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("取消") } })
}
