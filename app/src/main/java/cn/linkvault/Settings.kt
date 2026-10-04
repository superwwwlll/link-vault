@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package cn.linkvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsPage(
    vm: VaultViewModel,
    onExport: () -> Unit,
    onExportHtml: () -> Unit = {},
    onExportPortalHtml: () -> Unit = {},
    onExportMarkdown: () -> Unit = {},
    onImport: () -> Unit,
    onPickFolder: () -> Unit,
    onSecureExport: (String) -> Unit = {},
    onSecureImport: (String) -> Unit = {}
) {
    var secureAction by rememberSaveable { mutableStateOf<String?>(null) }
    var securePassword by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf("") }
    var returnPage by rememberSaveable { mutableStateOf("") }
    var changeMaster by rememberSaveable { mutableStateOf(false) }
    val goBack: () -> Unit = { page = returnPage; returnPage = "" }
    BackHandler(enabled = page.isNotEmpty() && secureAction == null && !changeMaster) { goBack() }

    Column(Modifier.fillMaxSize()) {
        if (page.isEmpty()) {
            Box(Modifier.padding(horizontal = 24.dp)) { RootHeading("设置") }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = goBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                Text(page, Modifier.weight(1f).padding(8.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        key(page) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {

            if (page.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Glyph.Shield, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("收藏与笔记存于本机", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text("无云同步；使用 AI 会发送内容给服务商，可能计费。请勿发送敏感内容。", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    SettingRow(Glyph.Check, "外观", "主题与显示", true) { page = "外观" }
                    SettingRow(Glyph.Cloud, "抓取", "页面信息与离线标题整理", true) { page = "抓取" }
                    SettingRow(Glyph.LinkChain, "AI 接口", "服务商、模型、密钥与连接测试", true) { page = "AI 接口" }
                    SettingRow(Glyph.Note, "阅读助手", "关注方向、模板与自动分析", true) { page = "阅读助手" }
                    SettingRow(Glyph.Translate, "翻译", "共用 AI 接口，按需翻译正文", true) { page = "翻译" }
                    SettingRow(Glyph.Archive, "备份与更新", "导入导出、私密笔记与应用更新", true) { page = "备份与更新" }
                }
            }
            }

            if (page == "外观") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("外观")
                val themeOptions = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
                SegmentedPills(
                    items = themeOptions,
                    selectedItem = themeOptions.firstOrNull { it.first == vm.theme } ?: themeOptions[0],
                    onSelect = { vm.theme(it.first) },
                    label = { it.second }
                )
            }
            }

            if (page == "备份与更新") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("应用更新", trailing = if (vm.updateChecking) "检查中…" else "")
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)), shadowElevation = 0.5.dp) {
                    Column {
                        SettingRow(Glyph.Cloud, "检查更新", "当前 ${vm.installedVersionName}（build ${vm.installedVersionCode}）", !vm.busy && !vm.updateChecking, { vm.checkUpdate(true) })
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SwitchRow(Glyph.Check, "启动时自动检查", "每次打开至多查一次", vm.autoCheckUpdates, true) { vm.autoCheckUpdates(it) }
                    }
                }

                val ready = vm.downloadedFile()
                val info = vm.available
                val allowed = vm.canInstallNow()
                when {
                    ready != null -> Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("新版本已下载并通过校验", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${info?.versionName ?: ""} · ${size(ready.length())}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.installDownloaded() }, shape = RoundedCornerShape(13.dp)) { Text("立即安装") }
                                OutlinedButton(onClick = { vm.openDownloadInBrowser() }, shape = RoundedCornerShape(13.dp)) { Text("用浏览器安装", fontSize = 12.sp) }
                            }
                            if (!allowed) {
                                OutlinedButton(onClick = { vm.openInstallPermission() }, shape = RoundedCornerShape(12.dp)) { Text("开启应用安装权限", fontSize = 12.sp) }
                            }
                        }
                    }
                    info != null -> Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("发现新版本 ${info.versionName}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${size(info.size)} · ${info.releasedAt}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (vm.updateDownloading) {
                                LinearProgressIndicator(progress = { vm.updateProgress }, modifier = Modifier.fillMaxWidth())
                                Text(downloadLabel(vm), fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { vm.downloadUpdate() }, shape = RoundedCornerShape(13.dp)) { Text("下载并安装") }
                                    OutlinedButton(onClick = { vm.openDownloadInBrowser() }, shape = RoundedCornerShape(13.dp)) { Text("用浏览器", fontSize = 12.sp) }
                                }
                                if (!allowed) {
                                    OutlinedButton(onClick = { vm.openInstallPermission() }, shape = RoundedCornerShape(12.dp)) { Text("开启应用安装权限", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }

                vm.updateError?.let { text ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.clearUpdateError() }) { Text("关闭", fontSize = 12.sp) }
                        }
                    }
                }
            }
            }

            if (page == "抓取") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("联网抓取", trailing = if (vm.fetchEnabled) "已开启" else "未开启")
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)), shadowElevation = 0.5.dp) {
                    SwitchRow(Glyph.Cloud, "页面信息抓取", "保存链接后自动补齐标题、封面与正文，详情页可手动重抓", vm.fetchEnabled, !vm.busy) { vm.fetchEnabled(it) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("标题整理")
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)), shadowElevation = 0.5.dp) {
                    Column {
                        SettingRow(Glyph.Sort, "按内容整理标题", "离线：去掉换行与尾部站点名，空壳标题改用正文里的标题或首句", !vm.busy, vm::tidyTitles)
                    }
                }
                Text("只读本机已有的正文快照，不发任何请求；改坏的标题可以在编辑页改回去。",
                    fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }

            if (page == "AI 接口") AiConnectionSettings(vm)
            if (page == "阅读助手") AnalysisSettings(vm)

            if (page == "翻译") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("AI 翻译", trailing = when {
                    !vm.aiEnabled -> "未开启"
                    vm.canTranslate -> "已配置"
                    else -> "还缺密钥"
                })
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)), shadowElevation = 0.5.dp) {
                    Column {
                        SwitchRow(Glyph.Translate, "启用翻译", "详情页把离线正文快照译成简体中文；快照要「联网抓取」才会产生", vm.aiEnabled, !vm.busy) { vm.aiEnabled(it) }
                    }
                }
                Text("与阅读助手共用「AI 接口」。翻译会发送正文到你配置的服务商，可能计费；敏感内容请勿翻译。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { returnPage = "翻译"; page = "AI 接口" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("配置 AI 接口") }
                var details by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (details) "收起翻译说明" else "详细隐私与用量说明") }
                // 翻译与阅读助手共用用户配置的第三方接口。
                if (details) Text(
                    "翻译会把整段正文原样发给你填的那个地址，费用记在你的账号上：一篇最多 24,000 字，" +
                        "按段发送，最多 20 段，每段等 60 秒，长文可能要等几分钟。密钥存在本机应用私有目录（明文，" +
                        "未 root 的设备上其他应用读不到），不导入备份、不同步、不参与更新检查；" +
                        "接口地址跨域跳转时一律停止，避免密钥被送到别的域名。不用时请关掉开关。",
                    fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }

            if (page == "备份与更新") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("私密笔记", trailing = if (vm.notes.hasMaster) "" else "未设主密码")
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)), shadowElevation = 0.5.dp) {
                    Column {
                        SettingRow(
                            Glyph.Shield,
                            if (vm.notes.hasMaster) "修改主密码" else "设置主密码",
                            if (vm.notes.hasMaster) "全部私密笔记会用新密码重新加密一遍；有一条解不开就整体放弃，不改一半"
                            else "私密笔记要先有主密码才能保存。它不记在任何地方，忘了无法找回",
                            !vm.notes.busy
                        ) { if (vm.notes.hasMaster) changeMaster = true else vm.notes.requireKey() }
                        if (vm.notes.hasMaster) {
                            HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            SettingRow(
                                Glyph.Note, "${vm.notes.secretTotal} 条私密 / 共 ${vm.notes.total} 条",
                                if (vm.notes.unlocked) "已解锁 · 退到后台会自动锁上" else "未解锁 · 列表只显示掩码",
                                true
                            ) { vm.tab(1) }
                        }
                    }
                }
                if (changeMaster) ChangeMasterDialog(vm, onDismiss = { changeMaster = false })
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("本地备份")
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)), shadowElevation = 0.5.dp) {
                    Column {
                        SettingRow(Glyph.Export, "导出收藏", "将 ${vm.items.size} 条收藏保存为 JSON", !vm.busy && !vm.readFailed && !vm.loading, onExport)
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.LinkChain, "导出 HTML 书签", "通用格式 · 兼容各大浏览器", !vm.busy && !vm.readFailed && !vm.loading, onExportHtml)
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.Globe, "导出独立导航网页", "单文件精美网页 · 内置即时搜索与标签", !vm.busy && !vm.readFailed && !vm.loading, onExportPortalHtml)
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.Markdown, "导出 Markdown 合辑", "知识库清单 · 兼容 Obsidian/Notion", !vm.busy && !vm.readFailed && !vm.loading, onExportMarkdown)
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.Import, "导入备份", "支持 JSON 与浏览器 HTML 书签", !vm.busy, onImport)
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.Shield, "加密导出", "密码保护的本地备份（至少 8 位）", !vm.busy) { secureAction = "export" }
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.Shield, "导入加密备份", "不会上传密码或备份内容", !vm.busy) { secureAction = "import" }
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.Archive, "备份文件夹", if (vm.backupFolder.isEmpty()) "设置自动备份文件夹" else "已设置 · 点击更换或取消", !vm.busy, onPickFolder)
                        if (vm.backupFolder.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            SettingRow(Glyph.Check, "立即备份到文件夹", "保存到已设文件夹", !vm.busy, vm::backupNow)
                        }
                        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        SettingRow(Glyph.Delete, "回收站", "删除后 30 天内可恢复", !vm.busy, vm::openTrash)
                    }
                }
                if (vm.lastBackupAt > 0L) {
                    Text("上次备份：${Stamp.ago(vm.lastBackupAt)}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("关于链藏")
                Text(vm.versionLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }

    secureAction?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!vm.busy) { secureAction = null; securePassword = "" } },
            icon = { Icon(Glyph.Shield, null) },
            title = { Text(if (action == "export") "设置加密备份密码" else "输入加密备份密码") },
            text = {
                OutlinedTextField(
                    value = securePassword,
                    onValueChange = { securePassword = it.take(128) },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !vm.busy,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = !vm.busy && securePassword.length >= 8,
                    onClick = {
                        val password = securePassword
                        secureAction = null
                        securePassword = ""
                        if (action == "export") onSecureExport(password) else onSecureImport(password)
                    }
                ) { Text(if (action == "export") "选择保存位置" else "选择备份文件") }
            },
            dismissButton = { TextButton(onClick = { secureAction = null; securePassword = "" }, enabled = !vm.busy) { Text("取消") } }
        )
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 下载进度显示真实字节与速度。只显示百分比时，网络慢会让进度长时间停在 0%，看起来像卡死。 */
private fun downloadLabel(vm: VaultViewModel): String {
    val parts = mutableListOf<String>()
    parts += if (vm.updateTotal > 0L) "已下载 ${size(vm.updateBytes)} / ${size(vm.updateTotal)}"
    else "已下载 ${size(vm.updateBytes)}"
    if (vm.updateSpeed > 0L) parts += "${vm.updateSpeed / 1024} KB/s"
    parts += "${(vm.updateProgress * 100).toInt()}%"
    return parts.joinToString(" · ")
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(32.dp).background(
                if (enabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Glyph.Next, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(32.dp).background(
                if (checked) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
