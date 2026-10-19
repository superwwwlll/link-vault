package cn.linkvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 只读取本机记录；进入、展开总结、滚动历史都不会触发请求。 */
@Composable
internal fun AnalysisConversationPage(vm: VaultViewModel, key: String, onBack: () -> Unit) {
    val ai = vm.analysis
    LaunchedEffect(key) { ai.load(key) }
    val record = ai.records[key]
    val state = ai.states[key]
    val error = ai.errors[key]
    val id = key.substringAfter('-').toLongOrNull()
    // 不读取私密笔记标题，也不把展示信息添加到请求中。
    val title = if (key.startsWith("b-")) vm.items.firstOrNull { it.id == id }?.title
        else vm.notes.rows.firstOrNull { it.id == id && !it.secret }?.heading
    val draft = vm.conversationDraft(key)
    val question = draft.question
    var summaryExpanded by rememberSaveable(key) { mutableStateOf(false) }
    val pendingQuestion = draft.pendingQuestion
    val baselineSize = draft.baselineSize
    val messages = record?.messages.orEmpty()
    val history = messages.drop(1)
    val listState = rememberLazyListState()

    LaunchedEffect(key, messages, state, error) {
        val sent = pendingQuestion
        if (sent != null && analysisReplyWasSaved(messages, baselineSize, sent)) {
            vm.finishConversationQuestion(key, succeeded = true)
        } else if (sent != null && state == null) {
            // 失败/取消不自动重发，不清除草稿，也不重复插入历史。
            vm.finishConversationQuestion(key, succeeded = false)
        }
    }
    LaunchedEffect(key, history.size, pendingQuestion) {
        // 定位最新答案的开头，而非长答案底部；旧消息仍可向上滚动访问。
        if (pendingQuestion != null && state != null) listState.animateScrollToItem(history.size + 1)
        else if (history.isNotEmpty()) listState.animateScrollToItem(history.size)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(tonalElevation = 2.dp) {
            PageToolbar("AI 对话", onBack, subtitle = title?.takeIf { it.isNotBlank() } ?: "这篇内容")
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("analysis-conversation-history"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { summaryExpanded = !summaryExpanded }, enabled = record != null,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (summaryExpanded) "收起原总结" else "查看原总结") }
                    if (summaryExpanded && record != null) {
                        Text("原总结 · ${record.template}", fontWeight = FontWeight.SemiBold)
                        SelectionContainer { SnapshotMarkdownViewer(messages.firstOrNull()?.text.orEmpty(), expanded = true, showImages = false) }
                        if (record.sourceChars > Analysis.MAX_SOURCE) Text("材料 ${record.sourceChars} 字，仅分析前 ${Analysis.MAX_SOURCE} 字；对话也只能参考这部分。",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Text("AI 回答不等于事实核验，请结合原文判断。发送原文、总结及最近最多 6 轮；全部历史仅存本机。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (record == null) Text("尚无可用总结，请返回原内容先完成分析。", style = MaterialTheme.typography.bodyMedium)
                    else if (history.isEmpty()) Text("围绕这篇内容继续追问", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(history, key = { index, _ -> "message-$index" }) { _, message -> AnalysisConversationMessage(message) }
            if (pendingQuestion != null && state != null) item(key = "pending") {
                AnalysisConversationMessage(AnalysisMessage("user", pendingQuestion!!), pending = true)
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.testTag("analysis-conversation-composer")) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { ai.cancel(key); vm.finishConversationQuestion(key, succeeded = false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止生成") }
                    }
                }
                if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!vm.aiVerified) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 接口未验证", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    TextButton(onClick = { vm.openSettings("AI 接口") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("去设置") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(question, { vm.conversationQuestion(key, it) }, placeholder = { Text("围绕这篇内容继续追问", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).testTag("analysis-conversation-input"), minLines = 1, maxLines = 4, enabled = state == null)
                    FilledIconButton(onClick = {
                        val sent = question.trim()
                        vm.beginConversationQuestion(key, sent, messages.size)
                        ai.run(key, sent)
                    }, enabled = record != null && state == null && question.isNotBlank() && vm.aiVerified,
                        modifier = Modifier.size(48.dp)) { Icon(Glyph.Next, "发送追问", Modifier.size(24.dp)) }
                }
            }
        }
    }
}

/** 按本次发送前的消息边界判断，旧历史里同一句问题不能误清草稿。 */
internal fun analysisReplyWasSaved(messages: List<AnalysisMessage>, baselineSize: Int, question: String): Boolean =
    baselineSize >= 1 && messages.size == baselineSize + 2 &&
        messages[baselineSize] == AnalysisMessage("user", question) &&
        messages[baselineSize + 1].role == "assistant" && messages[baselineSize + 1].text.isNotBlank()

@Composable
private fun AnalysisConversationMessage(message: AnalysisMessage, pending: Boolean = false) {
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (user) if (pending) "我 · 等待回答" else "我" else "AI", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (user) {
            Surface(shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                SelectionContainer { Text(message.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge, lineHeight = 23.sp) }
            }
        } else {
            // 长回答采用文章宽度，不挤进狭窄气泡；Markdown 与详情总结使用同一渲染器。
            SelectionContainer { SnapshotMarkdownViewer(message.text, expanded = true, showImages = false) }
        }
    }
}
