@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.linkvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 笔记列表。
 *
 * 锁住的私密笔记**必须留在列表里**，只是内容一个字符都不读出来：把"现在读不到"做成"看不见"，
 * 用户就会以为内容没了。
 */
@Composable
internal fun NotesPage(vm: VaultViewModel, listState: LazyListState = rememberLazyListState()) {
    val notes = vm.notes
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val rows = notes.visible

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("笔记", Modifier.weight(1f).padding(start = 28.dp, top = 8.dp, bottom = 4.dp), fontFamily = ReadingSerif, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) notes.search("") }, modifier = Modifier.size(48.dp)) {
                Icon(if (searchExpanded || notes.search.isNotEmpty()) Glyph.Close else Glyph.Search,
                    if (searchExpanded || notes.search.isNotEmpty()) "收起搜索" else "展开搜索", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); notes.create() }, modifier = Modifier.size(48.dp)) {
                Icon(Glyph.Add, "新建笔记", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (searchExpanded || notes.search.isNotEmpty()) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                SearchBox(notes.search, { notes.search(it) }, "搜索笔记正文", initiallyExpanded = true)
            }
        }
        NotesKeyRow(vm)
        if (notes.hiddenByLock > 0) Text(
            "另有 ${notes.hiddenByLock} 条未参与搜索",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).testTag("notes-list"),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (rows.isEmpty()) {
                item(contentType = "empty") {
                    EmptyState(
                        Glyph.Note,
                        when {
                            notes.total == 0 -> "还没有笔记"
                            else -> "没有匹配的笔记"
                        }
                    )
                }
            } else {
                items(items = rows, key = { it.id }, contentType = { "note" }) { row ->
                    NoteCard(
                        row = row,
                        onClick = { notes.open(row) },
                        onCopy = {
                            notes.plainText(row.id)?.let {
                                clipboard.setText(AnnotatedString(it))
                                vm.toast("已复制")
                            } ?: vm.fail("先解锁再复制")
                        }
                    )
                }
            }
        }
    }
}

/** 解锁状态与入口。没有主密码时只提供「设密码」。 */
@Composable
private fun NotesKeyRow(vm: VaultViewModel) {
    val notes = vm.notes
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildString {
                append("${notes.total} 条")
                if (notes.secretTotal > 0) append(" · 私密 ${notes.secretTotal} 条")
                append(when {
                    !notes.hasMaster -> " · 还没设主密码"
                    notes.unlocked -> " · 已解锁"
                    else -> " · 未解锁"
                })
            },
            Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (notes.hasMaster) {
            TextButton(onClick = { if (notes.unlocked) notes.lock() else notes.requireKey() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (notes.unlocked) "锁定" else "解锁", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
            }
        } else {
            TextButton(onClick = { notes.requireKey() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("设主密码", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun NoteCard(row: NoteRow, onClick: () -> Unit, onCopy: () -> Unit) {
    // 「显示」是逐条的：列表常常在解锁期间一直开着，全局展开等于把全部口令摊在屏幕上。
    // key 带上 unlocked —— 切后台自动锁定再解锁后回到掩码，不继承上一次"我展开过"。
    var revealed by rememberSaveable(row.id, row.locked) { mutableStateOf(false) }
    val masked = row.secret && !row.locked && !row.unreadable && !revealed
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (!masked && row.heading.isNotEmpty()) Text(
                    row.heading, fontFamily = ReadingSerif, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface
                )
                val preview = when {
                    row.unreadable -> "另一个主密码"
                    row.locked -> "已锁定 · 点按解锁"
                    masked -> "••••••••••••"
                    else -> row.summary
                }
                if (preview.isNotEmpty()) Text(
                    preview,
                    fontSize = 15.sp, lineHeight = 21.sp,
                    maxLines = if (row.locked || row.unreadable || masked) 2 else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (row.locked || row.unreadable || masked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (masked) TextButton(
                    onClick = { revealed = true },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp).widthIn(min = 48.dp)
                ) {
                    Box(Modifier.widthIn(min = ButtonDefaults.MinWidth), contentAlignment = Alignment.CenterStart) {
                        Text("显示", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    buildString {
                        append(if (row.secret) "私密 · " else "")
                        append(Stamp.date(row.updatedAt)).append(" 改")
                        // 锁着的时候字数本身就是没说出来的内容，显示 0 字更像出错。
                        if (!row.locked && !row.unreadable && !masked) append(" · ${row.text.length} 字")
                    },
                    fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!row.locked && !row.unreadable) IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                Icon(Glyph.Copy, "复制全文", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 自动锁定后回到编辑入口：不给看正文，也不假装内容没了。 */
@Composable
internal fun LockedDraftPage(vm: VaultViewModel, onBack: () -> Unit) {
    val notes = vm.notes
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageToolbar(if ((notes.draft?.id ?: 0L) == 0L) "新建笔记" else "编辑笔记", onBack)
        EmptyState(Glyph.Shield, "这条草稿是私密的")
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { notes.requireKey() }, enabled = !notes.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("解锁继续", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
            }
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("返回列表", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
            }
        }
    }
}

/** 编辑器：只有正文和一个「私密」开关，内容进不出数据库以外的地方。 */
@Composable
internal fun NotesEditorPage(vm: VaultViewModel, draft: NoteDraft, onBack: () -> Unit) {
    val notes = vm.notes
    val clipboard = LocalClipboardManager.current
    val row = if (draft.id > 0) notes.rows.firstOrNull { it.id == draft.id } else null
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageToolbar(if (draft.id == 0L) "新建笔记" else "编辑笔记", onBack) {
            TextButton(
                onClick = {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    val next = (draft.text + pasted)
                    if (pasted.isBlank()) vm.fail("剪贴板里没有文字")
                    else if (next.length > NoteCrypto.MAX_TEXT) vm.fail("粘贴后超过 ${NoteCrypto.MAX_TEXT} 字上限，这条放不下")
                    else notes.change(draft.copy(text = next))
                },
                enabled = !notes.busy,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("粘贴", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp) }
            TextButton(onClick = notes::save, enabled = !notes.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (notes.busy) "保存中…" else if (draft.secret) "加密保存" else "保存笔记", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        TextField(
            value = draft.text,
            onValueChange = { notes.change(draft.copy(text = it)) },
            enabled = !notes.busy,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 16,
            shape = RoundedCornerShape(10.dp),
            colors = quietFillColors()
        )
        Text(
            "${draft.text.length} / ${NoteCrypto.MAX_TEXT} 字",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NoteSecretSwitch(vm, draft)
        if (!draft.secret && row?.secret != true) {
            var aiExpanded by rememberSaveable(draft.id) { mutableStateOf(false) }
            val key = "n-${draft.id}"
            LaunchedEffect(key) { if (row != null) vm.analysis.load(key) }
            val record = vm.analysis.records[key]
            val status = when {
                row == null || draft.text != row.text -> "先保存再分析"
                vm.analysis.states[key] != null -> "正在分析…"
                record != null -> "已有总结 · ${(record.messages.size - 1) / 2} 轮对话"
                !vm.aiVerified -> "配置 AI 后可用"
                else -> "未分析"
            }
            Surface(onClick = { aiExpanded = !aiExpanded }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 总结与对话", style = MaterialTheme.typography.bodyMedium)
                        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (aiExpanded) Glyph.Close else Glyph.Next, if (aiExpanded) "收起 AI" else "展开 AI", Modifier.size(20.dp))
                }
            }
            if (aiExpanded && row != null && draft.text == row.text) AnalysisPanel(vm, key)
        }
        if (row != null) {
            var confirmDelete by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp)) {
                Icon(Glyph.Delete, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("删除这条笔记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (confirmDelete) AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("彻底删除这条笔记？") },
                text = { Text("笔记不进回收站，删掉就找不回来了。") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; notes.edit(null); notes.delete(draft.id) }, enabled = !notes.busy) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
            )
        }
        }
    }
}

@Composable
private fun NoteSecretSwitch(vm: VaultViewModel, draft: NoteDraft) {
    val notes = vm.notes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("私密", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = draft.secret,
                    onCheckedChange = { notes.change(draft.copy(secret = it)) },
                    enabled = !notes.busy,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                )
            }
            if (draft.secret) Text(
                "忘记主密码无法找回。",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 设主密码 / 解锁弹窗。两种模式只差标题和字段数，共用一个表单。 */
@Composable
internal fun MasterPasswordDialog(vm: VaultViewModel, setup: Boolean, onDismiss: () -> Unit) {
    val notes = vm.notes
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Glyph.Shield, null) },
        title = { Text(if (setup) "设置主密码" else "输入主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (setup) Text(
                    "忘记无法找回。",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PasswordField("主密码", first, { first = it }, "password-first")
                if (setup) PasswordField("再输一次", second, { second = it }, "password-second")
                Text(
                    "至少 ${NoteCrypto.MIN_PASSWORD} 个字符。",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (setup) notes.setupMaster(first, second) else notes.unlock(first) },
                enabled = !notes.busy && first.isNotEmpty() && (!setup || second.isNotEmpty())
            ) { Text(if (notes.busy) "处理中…" else if (setup) "设定" else "解锁") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !notes.busy) { Text("取消") } }
    )
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    TextField(
        value = value,
        onValueChange = { onChange(it.take(200)) },
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(10.dp),
        colors = quietFillColors(),
        // testTag 给测试用例定位用：三格口令同在一个弹窗里，只靠 label 分不出第二格和第三格。
        modifier = Modifier.testTag(tag)
    )
}

/** 改密码：三格表单。任何一条私密笔记解不开都会整体放弃，不会只改一半。 */
@Composable
internal fun ChangeMasterDialog(vm: VaultViewModel, onDismiss: () -> Unit) {
    val notes = vm.notes
    var old by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Glyph.Shield, null) },
        title = { Text("修改主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "解不开则整次放弃。",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PasswordField("旧主密码", old, { old = it }, "password-old")
                PasswordField("新主密码", fresh, { fresh = it }, "password-new")
                PasswordField("再输一次新密码", confirm, { confirm = it }, "password-confirm")
            }
        },
        confirmButton = {
            TextButton(onClick = { notes.changeMaster(old, fresh, confirm) }, enabled = !notes.busy) {
                Text(if (notes.busy) "重新加密中…" else "修改")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !notes.busy) { Text("取消") } }
    )
}
