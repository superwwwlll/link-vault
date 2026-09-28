@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.linkvault

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalClipboardManager
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
    val rows = notes.visible

    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("notes-list"),
        contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                RootHeading("笔记")
                SearchBox(notes.search, { notes.search(it) }, "搜索笔记正文")
                Spacer(Modifier.height(10.dp))
                NotesKeyRow(vm)
                // 锁着的条数在两种列表状态下都得说：搜完什么都看不到，和最开始就没有，
                // 是同一件事——用户会把"现在读不到"理解成"内容没了"。
                if (notes.hiddenByLock > 0) Text(
                    "另有 ${notes.hiddenByLock} 条私密笔记未参与搜索：内容锁着，解锁后才能读。",
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (rows.isEmpty()) {
            item(contentType = "empty") {
                EmptyState(
                    Glyph.Note,
                    when {
                        notes.total == 0 -> "还没有笔记"
                        else -> "没有匹配的笔记"
                    },
                    when {
                        notes.total == 0 -> "点右下角的 + 新建，或直接从剪贴板存一段文字。"
                        else -> ""
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
                            vm.toast("已复制 · 这段文字会留在系统剪贴板里，直到你复制或清空")
                        } ?: vm.fail("锁着的内容复制不出来，先解锁这条笔记")
                    },
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

/** 解锁状态与入口。没有主密码时只提供「设密码」。 */
@Composable
private fun NotesKeyRow(vm: VaultViewModel) {
    val notes = vm.notes
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (notes.hasMaster) {
            TextButton(onClick = { if (notes.unlocked) notes.lock() else notes.requireKey() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Glyph.Shield, null, Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text(if (notes.unlocked) "锁定" else "解锁", fontSize = 12.sp)
            }
        } else {
            TextButton(onClick = { notes.requireKey() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("设主密码", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NoteCard(row: NoteRow, onClick: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    // 「显示」是逐条的：列表常常在解锁期间一直开着，全局展开等于把全部口令摊在屏幕上。
    // key 带上 unlocked —— 切后台自动锁定再解锁后回到掩码，不继承上一次"我展开过"。
    var revealed by rememberSaveable(row.id, row.locked) { mutableStateOf(false) }
    val masked = row.secret && !row.locked && !row.unreadable && !revealed
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
        shadowElevation = 0.5.dp,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (row.secret) Glyph.Shield else Glyph.Note,
                contentDescription = if (row.secret) "私密笔记" else null,
                modifier = Modifier.size(15.dp).padding(top = 2.dp),
                tint = if (row.secret) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!masked && row.heading.isNotEmpty()) Text(
                    row.heading, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface
                )
                val preview = when {
                    row.unreadable -> "这条属于另一个主密码（可能来自别人的备份）。解锁原来那个主密码才能看和改。"
                    row.locked -> "已锁定 · 点按解锁"
                    masked -> "••••••••••••"
                    else -> row.summary
                }
                if (preview.isNotEmpty()) Text(
                    preview,
                    fontSize = 13.sp, lineHeight = 19.sp,
                    maxLines = if (row.locked || row.unreadable || masked) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                    color = if (row.locked || row.unreadable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (masked) TextButton(
                    onClick = { revealed = true },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    modifier = Modifier.align(Alignment.Start).height(24.dp)
                ) { Text("显示", fontSize = 12.sp) }
                Text(
                    buildString {
                        append(Stamp.date(row.updatedAt)).append(" 改")
                        // 锁着的时候字数本身就是没说出来的内容，显示 0 字更像出错。
                        if (!row.locked && !row.unreadable && !masked) append(" · ${row.text.length} 字")
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!row.locked && !row.unreadable) IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(Glyph.Copy, "复制全文", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 自动锁定后回到编辑入口：不给看正文，也不假装内容没了。 */
@Composable
internal fun LockedDraftPage(vm: VaultViewModel, onBack: () -> Unit) {
    val notes = vm.notes
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        PageToolbar(if ((notes.draft?.id ?: 0L) == 0L) "新建笔记" else "编辑笔记", onBack)
        EmptyState(
            Glyph.Shield,
            "这条草稿是私密的",
            "内容还在这里，一个字都没丢，也还没保存。解锁后接着写；返回会先问你一次，确认才丢弃。"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { notes.requireKey() }, enabled = !notes.busy, shape = RoundedCornerShape(12.dp)) {
                Icon(Glyph.Shield, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("解锁继续", fontSize = 13.sp)
            }
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) { Text("返回", fontSize = 13.sp) }
        }
    }
}

/** 编辑器：只有正文和一个「私密」开关，内容进不出数据库以外的地方。 */
@Composable
internal fun NotesEditorPage(vm: VaultViewModel, draft: NoteDraft, onBack: () -> Unit) {
    val notes = vm.notes
    val clipboard = LocalClipboardManager.current
    val row = if (draft.id > 0) notes.rows.firstOrNull { it.id == draft.id } else null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageToolbar(if (draft.id == 0L) "新建笔记" else "编辑笔记", onBack) {
            TextButton(
                onClick = {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    val next = (draft.text + pasted)
                    if (pasted.isBlank()) vm.fail("剪贴板里没有文字")
                    // 放不下就一个字都不贴：截断等于把用户的内容悄悄吃掉一半。
                    else if (next.length > NoteCrypto.MAX_TEXT) vm.fail("粘贴后超过 ${NoteCrypto.MAX_TEXT} 字上限，这条放不下")
                    else notes.change(draft.copy(text = next))
                },
                enabled = !notes.busy
            ) { Icon(Glyph.Copy, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("粘贴", fontSize = 12.sp) }
        }
        OutlinedTextField(
            value = draft.text,
            onValueChange = { notes.change(draft.copy(text = it)) },
            enabled = !notes.busy,
            placeholder = { Text("记下来：账号、地址、验证码、一段想法…", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 16,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
        Text(
            "${draft.text.length} / ${NoteCrypto.MAX_TEXT} 字",
            Modifier.fillMaxWidth(), fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NoteSecretSwitch(vm, draft)
        if (row != null) {
            var confirmDelete by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.Start)) {
                Icon(Glyph.Delete, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("删除这条笔记", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
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

@Composable
private fun NoteSecretSwitch(vm: VaultViewModel, draft: NoteDraft) {
    val notes = vm.notes
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("私密", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = draft.secret,
                    onCheckedChange = { notes.change(draft.copy(secret = it)) },
                    enabled = !notes.busy,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                )
            }
            Text(
                if (draft.secret) "正文加密后才落盘，列表与搜索只显示掩码；打开笔记页需要主密码。解锁期间本应用禁止截屏。"
                else "这段文字会以明文存在本机数据库里。账号、口令这类内容建议打开「私密」。",
                fontSize = 11.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    "主密码不记在任何地方，也不写进备份文件。忘了无法找回。",
                    fontSize = 12.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PasswordField("主密码", first, { first = it }, "password-first")
                if (setup) PasswordField("再输一次", second, { second = it }, "password-second")
                Text(
                    "至少 ${NoteCrypto.MIN_PASSWORD} 个字符。",
                    fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(200)) },
        label = { Text(label, fontSize = 13.sp) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp),
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
                    "全部私密笔记会用新密码重新加密一遍。笔记多的时候要等几秒，中途不写库。",
                    fontSize = 12.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
