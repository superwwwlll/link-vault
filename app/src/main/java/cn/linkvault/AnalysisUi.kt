@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.linkvault

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AiConnectionSettings(vm: VaultViewModel) {
    val uri = LocalUriHandler.current
    val provider = AiProviders.presets.firstOrNull { it.id == vm.aiProvider } ?: AiProviders.presets.last()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("AI 接口（翻译 / 总结 / 对话共用）", trailing = if (vm.aiVerified) "验证通过" else "待验证")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiProviders.presets.forEach { preset ->
                FilterChip(selected = provider.id == preset.id, onClick = { vm.selectAiProvider(preset) }, label = { Text(preset.name, fontSize = 12.sp) }, enabled = !vm.aiTesting)
            }
        }
        Text("选择服务商会填好地址与模型；跨服务商切换会清除旧密钥，避免误发。地址和模型都可修改。", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(vm.aiEndpoint, { vm.aiEndpoint(it.take(300)) }, label = { Text("API 地址（完整 chat/completions 地址）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !vm.aiTesting)
        OutlinedTextField(vm.aiModel, { vm.aiModel(it) }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !vm.aiTesting)
        OutlinedTextField(vm.aiKey, { vm.aiKey(it.take(400)) }, label = { Text("你的 API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !vm.aiTesting,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::testAiConnection, enabled = !vm.aiTesting && vm.aiKey.isNotBlank()) { Text(if (vm.aiTesting) "测试中…" else "测试连接") }
            TextButton(onClick = vm::clearAiKey, enabled = !vm.aiTesting) { Text("清除密钥") }
            if (provider.console.isNotEmpty()) TextButton(onClick = {
                runCatching { uri.openUri(provider.console) }.onFailure { vm.fail("没有可用浏览器") }
            }) { Text("官方开通 / 充值") }
        }
        if (vm.aiTestResult.isNotEmpty() && (vm.aiTesting || !vm.aiTestResult.startsWith("连接验证通过") || vm.aiVerified))
            Text(vm.aiTestResult, fontSize = 12.sp, color = if (vm.aiVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (provider.note.isNotEmpty()) Text(provider.note, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("测试会发送一条极短的请求，可能计费，不发送你的收藏。聊天会员、编码订阅通常不等于通用 API 额度。密钥仅存本机私有目录（明文），不进备份；只有你操作时才联网。", fontSize = 11.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AnalysisSettings(vm: VaultViewModel) {
    val ai = vm.analysis
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("AI 阅读助手")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("保存后自动分析", fontWeight = FontWeight.SemiBold)
                Text("独立于翻译开关，默认关闭", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ai.auto, onCheckedChange = { if (it) confirmEnable = true else ai.auto(false) })
        }
        Text("开启后，新保存的链接和普通笔记会发送到你配置的 AI；批量保存也会逐条分析并计费。私密笔记绝不发送。链接需要正文，首次抓取受「页面信息抓取」开关控制。不会自动扫描旧收藏。", fontSize = 11.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(ai.profile, ai::profile, label = { Text("我的工作、兴趣与关注方向") }, placeholder = { Text("例如：我做生命科学产品销售，关注新研究方法、客户需求和可行动的机会") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ai.templates.forEach { template -> FilterChip(selected = ai.active.id == template.id, onClick = { ai.select(template.id) }, label = { Text(template.name.ifBlank { "未命名" }) }) }
        }
        OutlinedTextField(ai.active.name, { ai.updateTemplate(it, ai.active.prompt) }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(ai.active.prompt, { ai.updateTemplate(ai.active.name, it) }, label = { Text("提示词框架（可直接编辑）") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = ai::addTemplate) { Text("新增模板") }
            TextButton(onClick = { confirmRemove = true }, enabled = ai.templates.size > 1) { Text("删除当前模板") }
        }
        Text("新分析使用当前模板；已有对话沿用生成总结时的框架。更换框架后可重新分析。总结与对话仅存本机，目前不进入备份，卸载后会丢失。应用进程退出会中断请求，可回到条目手动重试。", fontSize = 11.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false }, title = { Text("开启自动分析？") },
        text = { Text("保存后，正文、标题、备注、关注方向及提示词会发送到你填写的 AI 服务，可能产生费用。私密笔记不发送。请先测试连接并确认信任该接口。") },
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
    var question by rememberSaveable(key) { mutableStateOf("") }
    var showHistory by rememberSaveable(key) { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("AI 总结与对话", trailing = record?.template ?: ai.active.name)
        if (record == null && state == null) Text("保存后自动分析${if (ai.auto) "已开启" else "未开启"}，也可以点下面手动分析。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (record != null) {
            Text("AI 分析不等于事实核验，请结合原文判断。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.sourceChars > Analysis.MAX_SOURCE) Text("材料 ${record.sourceChars} 字，仅分析前 ${Analysis.MAX_SOURCE} 字；后续对话也只能参考这部分。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            SelectionContainer { SnapshotMarkdownViewer(record.messages.first().text, expanded = true, showImages = false) }
            if (key.startsWith("b-") && record.suggestedTitle.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("标题与标签建议", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(record.suggestedTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { record.suggestedTags.forEach { TagPill(it) } }
                        Text("确认后替换收藏标题，标签追加到现有标签；原文和备注不变。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { confirmApply = true }, enabled = state == null) { Text("应用标题与标签") }
                    }
                }
            }
            if (record.messages.size > 1) {
                TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "收起对话" else "展开对话（${(record.messages.size - 1) / 2}轮）") }
                if (showHistory) record.messages.drop(1).forEach { message ->
                    Surface(shape = RoundedCornerShape(12.dp), color = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (message.role == "user") "我" else "AI", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            SelectionContainer { SnapshotMarkdownViewer(message.text, expanded = true, showImages = false) }
                        }
                    }
                }
            }
            OutlinedTextField(question, { question = it.take(Analysis.MAX_QUESTION) }, label = { Text("围绕这篇内容继续追问") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, enabled = state == null)
            Text("每次追问会重新发送原文、总结及最近最多6轮对话（历史预算12,000字）；更早记录仍留在本机。", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { ai.run(key, question.trim()); showHistory = true }, enabled = state == null && question.isNotBlank() && vm.aiVerified) { Text("发送追问") }
            // 请求成功才清空输入；失败时问题原样保留，便于重试。
            LaunchedEffect(record.updatedAt) {
                if (record.messages.size > 1 && record.messages[record.messages.size - 2].text == question.trim()) question = ""
            }
        }
        if (state != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row { Text(state, Modifier.weight(1f), fontSize = 12.sp); TextButton(onClick = { ai.cancel(key) }) { Text("取消") } }
        }
        if (error != null) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        if (!vm.aiVerified) Text("先到设置填写自己的 API Key 并测试连接，验证通过后即可分析。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (record == null) ai.run(key) else confirmReplace = true }, enabled = state == null && vm.aiVerified) { Text(if (record == null) "分析这篇内容" else "按当前模板重新分析") }
            if (record != null) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(record.messages.joinToString("\n\n") { if (it.role == "user") "我：${it.text}" else it.text })); vm.toast("已复制总结与对话") }) { Text("复制") }
                TextButton(onClick = { confirmClear = true }) { Text("清除 AI 记录") }
            }
        }
    }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("重新分析？") }, text = { Text("使用当前关注方向与模板，会再次计费。成功后替换原总结并清空旧对话；失败则保留旧记录。") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; ai.run(key) }) { Text("重新分析") } }, dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除总结与对话？") }, text = { Text("只清除本机 AI 记录，不修改原文，也无法撤回已发给服务商的内容。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; ai.clear(key) }) { Text("清除") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    if (confirmApply && record != null) AlertDialog(onDismissRequest = { confirmApply = false }, title = { Text("应用 AI 建议？") },
        text = { Text("将收藏标题替换为「${record.suggestedTitle}」，并追加标签：${record.suggestedTags.joinToString("、")}。已有标签、原文和备注保留。") },
        confirmButton = { TextButton(onClick = { confirmApply = false; ai.applySuggestions(key) }) { Text("确认应用") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("取消") } })
}
