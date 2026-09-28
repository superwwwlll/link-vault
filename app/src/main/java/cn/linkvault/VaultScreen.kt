@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.linkvault

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VaultScreen(vm: VaultViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (vm.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemDark
    }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var cancelConfirm by rememberSaveable { mutableStateOf(false) }
    var noteCancelConfirm by rememberSaveable { mutableStateOf(false) }
    var securePassword by rememberSaveable { mutableStateOf("") }
    val d = vm.draft
    val nd = vm.notes.draft
    val editing = d != null || nd != null
    val detail = vm.items.firstOrNull { it.id == vm.detailId }
    val collectionListState = rememberLazyListState()
    val fabShown = rememberFabShown(collectionListState)

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    val exportHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri -> uri?.let(vm::exportHtml) }
    val exportPortalHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri -> uri?.let(vm::exportPortalHtml) }
    val exportMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let(vm::exportMarkdown) }
    val secureExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.exportEncrypted(it, securePassword.toCharArray()) } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::prepareImport) }
    val secureImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.prepareEncryptedImport(it, securePassword.toCharArray()) } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setBackupFolder) }

    // 启动时静默查一次更新（受设置开关与 12 小时节流控制，失败不打扰）
    LaunchedEffect(Unit) { vm.checkUpdate(manual = false) }

    fun guarded(action: () -> Unit) {
        try { action() } catch (_: ActivityNotFoundException) { vm.fail("系统没有可用的文件选择器") }
    }
    fun launchExport() = guarded { export.launch("链藏备份-${Stamp.fileNameStamp()}.json") }
    fun launchExportHtml() = guarded { exportHtml.launch("链藏书签-${Stamp.fileNameStamp()}.html") }
    fun launchExportPortalHtml() = guarded { exportPortalHtml.launch("链藏导航页-${Stamp.fileNameStamp()}.html") }
    fun launchExportMarkdown() = guarded { exportMarkdown.launch("链藏知识库-${Stamp.fileNameStamp()}.md") }
    fun launchImport() = guarded { import.launch(arrayOf("application/json", "text/html", "text/plain", "application/octet-stream")) }
    fun launchSecureExport(password: String) { securePassword = password; guarded { secureExport.launch("链藏加密备份-${Stamp.fileNameStamp()}.lvault") } }
    fun launchSecureImport(password: String) { securePassword = password; guarded { secureImport.launch(arrayOf("application/octet-stream", "application/*", "*/*")) } }
    fun launchFolder() = guarded { if (vm.backupFolder.isEmpty()) folder.launch(null) else vm.clearBackupFolder() }

    fun openLink(item: Bookmark) {
        if (!Links.valid(item.url)) { vm.fail("链接无效，无法安全打开"); return }
        try {
            val uri = Uri.parse(item.url).normalizeScheme()
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setColorScheme(if (isDark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
                .setDefaultColorSchemeParams(
                    CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(if (isDark) 0xFF18181B.toInt() else 0xFFFAF8F5.toInt())
                        .build()
                )
                .build()
            customTabsIntent.launchUrl(context, uri)
        } catch (_: Exception) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: ActivityNotFoundException) { vm.fail("没有能打开此链接的应用，请安装浏览器") }
            catch (_: SecurityException) { vm.fail("系统阻止了链接打开") }
        }
    }
    fun share(item: Bookmark) {
        val title = Links.displayTitle(item.url, item.title)
        val body = if (item.title.isBlank()) item.url else "$title\n${item.url}"
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).putExtra(Intent.EXTRA_SUBJECT, title)
        try { context.startActivity(Intent.createChooser(intent, "分享链接")) }
        catch (_: ActivityNotFoundException) { vm.fail("没有可以分享的应用") }
    }
    fun copyLink(item: Bookmark) { clipboard.setText(AnnotatedString(item.url)); vm.toast("已复制链接") }
    fun copyMarkdown(item: Bookmark) {
        val title = Links.displayTitle(item.url, item.title)
        val text = if (item.notes.isNotBlank()) {
            "[$title](${item.url})\n\n> ${item.notes.replace("\n", "\n> ")}"
        } else {
            "[$title](${item.url})"
        }
        clipboard.setText(AnnotatedString(text))
        vm.toast("已复制 Markdown 链接")
    }

    BackHandler(enabled = editing || vm.detailId != null || vm.trashOpen || vm.tab != 0) {
        if (!vm.busy) {
            if (nd != null) noteCancelConfirm = true
            else if (d != null) cancelConfirm = true
            else if (vm.detailId != null) vm.closeDetail()
            else if (vm.trashOpen) vm.closeTrash()
            else vm.tab(0)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (nd != null) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    Button(onClick = vm.notes::save, enabled = !vm.notes.busy, shape = RoundedCornerShape(14.dp), modifier = Modifier
                        .navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().height(50.dp)) {
                        Icon(Glyph.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (vm.notes.busy) "保存中…" else if (nd.secret) "加密保存" else "保存笔记", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (d != null) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    Button(onClick = vm::save, enabled = !vm.busy, shape = RoundedCornerShape(14.dp), modifier = Modifier
                        .navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().height(50.dp)) {
                        Icon(Glyph.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (vm.busy) "保存中…" else "保存收藏", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (vm.detailId == null && !vm.trashOpen) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            listOf("收藏" to Glyph.Bookmark, "笔记" to Glyph.Note, "标签" to Glyph.Tag, "设置" to Glyph.Settings).forEachIndexed { index, (label, icon) ->
                                NavigationBarItem(selected = vm.tab == index, onClick = { vm.tab(index) }, icon = { Icon(icon, label, Modifier.size(22.dp)) }, label = { Text(label, fontSize = 12.sp) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!editing && vm.detailId == null && !vm.trashOpen && (vm.tab == 0 || vm.tab == 1)) AnimatedVisibility(
                visible = fabShown,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.86f),
                exit = fadeOut(tween(130)) + scaleOut(tween(130), targetScale = 0.86f)
            ) {
                FloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        // 笔记页的 + 建笔记，收藏页的 + 建收藏：同一个位置，两种意图。
                        if (vm.tab == 1) vm.notes.create() else vm.edit(Draft())
                    },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Glyph.Add, contentDescription = if (vm.tab == 1) "新建笔记" else "收藏链接", modifier = Modifier.size(22.dp))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.busy || vm.notes.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.pending != null) Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = vm::importPending, enabled = d == null && !vm.busy && vm.preview == null) { Text(if (d == null) "有一条待收分享 · 点击处理" else "新分享已暂存，当前草稿不受影响") }
            }
            vm.message?.let { text ->
                Banner(text, BannerTone.Info, action = { IconButton(onClick = vm::clearMessage, modifier = Modifier.size(32.dp)) { Icon(Glyph.Close, "关闭提示", Modifier.size(15.dp)) } })
            }
            if (d == null && vm.detailId == null && vm.tab == 0 && !vm.updateBannerDismissed) {
                vm.available?.let { info ->
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Glyph.Cloud, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp)) {
                                Text("发现新版本 ${info.versionName}", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            TextButton(onClick = { vm.downloadUpdate() }, enabled = !vm.updateDownloading) {
                                Text(if (vm.updateDownloading) "${(vm.updateProgress * 100).toInt()}%" else "更新", fontSize = 13.sp)
                            }
                            IconButton(onClick = vm::dismissUpdateBanner, modifier = Modifier.size(32.dp)) { Icon(Glyph.Close, "忽略这次更新", Modifier.size(15.dp)) }
                        }
                    }
                }
            }
            if (!editing && vm.detailId == null && (vm.tab == 0 || vm.tab == 1) && !vm.trashOpen) {
                val url = vm.clipboardCandidate
                val text = vm.clipboardText
                if (url != null || text != null) ClipboardSuggestion(
                    url = url,
                    text = text,
                    onSaveUrl = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.quickSaveClipboard()
                    },
                    onSaveNote = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.saveClipboardAsNote()
                    },
                    onDismiss = vm::dismissClipboard
                )
            }
            when {
                nd != null && vm.notes.draftLocked -> LockedDraftPage(vm, onBack = { noteCancelConfirm = true })
                nd != null -> NotesEditorPage(vm, nd, onBack = { noteCancelConfirm = true })
                d != null -> EditorPage(vm, d, onBack = { cancelConfirm = true })
                vm.detailId != null -> {
                    if (detail != null) DetailPage(vm, detail, onBack = vm::closeDetail, onEdit = { vm.open(detail) }, onOpen = { openLink(detail) }, onShare = { share(detail) }, onDelete = { deleteId = detail.id }, onCopyMarkdown = { copyMarkdown(detail) })
                    else { PageToolbar("收藏详情", vm::closeDetail); EmptyState(Glyph.Bookmark, if (vm.loading) "正在载入" else "这条收藏已不存在") }
                }
                vm.trashOpen -> TrashPage(vm, onBack = vm::closeTrash)
                vm.tab == 1 -> NotesPage(vm)
                vm.tab == 2 -> TagsPage(vm)
                vm.tab == 3 -> SettingsPage(vm, ::launchExport, ::launchExportHtml, ::launchExportPortalHtml, ::launchExportMarkdown, ::launchImport, ::launchFolder, ::launchSecureExport, ::launchSecureImport)
                else -> CollectionPage(vm, onShare = ::share, onDelete = { deleteId = it.id }, onCopy = ::copyLink, onCopyMarkdown = ::copyMarkdown, onOpen = ::openLink, listState = collectionListState)
            }
        }
    }

    vm.error?.let { text -> AlertDialog(onDismissRequest = vm::clearError, icon = { Icon(Glyph.Bookmark, null) }, title = { Text("链藏") }, text = { Text(text) }, confirmButton = { TextButton(onClick = vm::clearError) { Text("知道了") } }) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("移入回收站？") }, text = { Text("删除后 30 天内可以从回收站恢复。") }, confirmButton = { TextButton(onClick = { deleteId = null; vm.delete(id) }, enabled = !vm.busy) { Text("移入回收站", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("保留收藏") } }) }
    if (vm.notes.masterSetupRequired) MasterPasswordDialog(vm, setup = true, onDismiss = vm.notes::dismissKeyPrompt)
    else if (vm.notes.unlockRequired) MasterPasswordDialog(vm, setup = false, onDismiss = vm.notes::dismissKeyPrompt)
    if (noteCancelConfirm) AlertDialog(onDismissRequest = { noteCancelConfirm = false }, title = { Text("放弃这条笔记？") }, text = { Text("这条笔记还没保存，放弃之后内容会丢失。") },
        confirmButton = { TextButton(onClick = { noteCancelConfirm = false; vm.notes.edit(null) }) { Text("放弃编辑") } },
        dismissButton = { TextButton(onClick = { noteCancelConfirm = false }) { Text("继续编辑") } })
    if (cancelConfirm) AlertDialog(onDismissRequest = { cancelConfirm = false }, title = { Text("放弃本次编辑？") }, text = { Text("尚未保存的改动将会丢失。") }, confirmButton = { TextButton(onClick = { cancelConfirm = false; vm.cancel() }) { Text("放弃编辑") } }, dismissButton = { TextButton(onClick = { cancelConfirm = false }) { Text("继续编辑") } })
    vm.preview?.let { preview -> AlertDialog(onDismissRequest = { if (!vm.busy) vm.cancelImport() }, icon = { Icon(Glyph.Import, null) }, title = { Text("确认导入备份") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("共 ${preview.items.size} 条收藏")
                Text("预计新增 ${preview.added} 条 · 跳过 ${preview.skipped} 条重复", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                preview.items.take(3).forEach { Text("• ${Links.displayTitle(it.url, it.title)}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }, confirmButton = { Button(onClick = vm::confirmImport, enabled = !vm.busy) { Text(if (vm.busy) "导入中…" else "确认合并") } }, dismissButton = { TextButton(onClick = vm::cancelImport, enabled = !vm.busy) { Text("取消") } }) }
}

/**
 * 剪贴板提示条：链接走「收录」，纯文本走「存为笔记」。
 *
 * 只提示、不监听：Android 13 起读剪贴板会弹系统提示，主动进页面才查一次，
 * 免得后台监听把用户每一次复制都变成一次系统弹窗。
 * 纯文本按明文存，所以这里就把"明文存在本机"写在脸上，而不是等出事再解释。
 */
@Composable
private fun ClipboardSuggestion(
    url: String?,
    text: String?,
    onSaveUrl: () -> Unit,
    onSaveNote: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (url != null) Glyph.LinkChain else Glyph.Note, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(if (url != null) "检测到剪贴板链接" else "检测到剪贴板文字", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text((url ?: text.orEmpty()).take(80), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (url == null) Text("存下来是明文笔记 · 要加密就在笔记里打开「私密」", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = if (url != null) onSaveUrl else onSaveNote, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (url != null) "收录" else "存为笔记", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(Glyph.Close, "忽略", Modifier.size(14.dp))
            }
        }
    }
}

/**
 * 向下滚动时收起添加按钮。
 *
 * 它停在右下角，正好压住列表第三张卡片的来源与时间；列表越长，被挡住的那张越靠不上前。
 * 回到列表顶部时必须显示，否则首屏会没有添加入口。
 */
@Composable
private fun rememberFabShown(listState: LazyListState): Boolean {
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val down = index > lastIndex || (index == lastIndex && offset > lastOffset)
                val up = index < lastIndex || (index == lastIndex && offset < lastOffset)
                when {
                    index == 0 && offset == 0 -> shown = true
                    down -> shown = false
                    up -> shown = true
                }
                lastIndex = index
                lastOffset = offset
            }
    }
    return shown
}
