package cn.linkvault

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Draft(val id: Long = 0, val url: String = "", val title: String = "", val notes: String = "", val tags: String = "")

class VaultViewModel(private val app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val db = VaultDb.get(app)
    private val dao = db.bookmarks()
    private val prefs = app.getSharedPreferences("appearance", 0)
    private val settings = app.getSharedPreferences("settings", 0)
    private val draftStore = app.getSharedPreferences("draft", 0)

    /**
     * 真实安装的版本。刻意不写死字符串：否则升级之后界面上还显示旧版本号，
     * 而这正是判断「更新到底装上没有」的唯一依据。
     */
    private val installed: Pair<String, Long> = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") val legacy = info.versionCode
            legacy.toLong()
        }
        info.versionName.orEmpty() to code
    }.getOrDefault("" to 0L)

    val installedVersionCode: Long get() = installed.second
    val installedVersionName: String get() = installed.first
    val versionLabel: String =
        if (installed.second > 0L) "链藏 ${installed.first}（build ${installed.second}）" else "链藏"

    // ------------------------------------------------------------ 界面状态

    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system"); private set
    var tab by mutableStateOf(saved.get<Int>("tab") ?: 0); private set
    var detailId by mutableStateOf(saved.get<Long>("detail")); private set
    var preview by mutableStateOf<ImportPreview?>(null); private set
    private val previewFile get() = File(getApplication<Application>().cacheDir, "pending-import.json")
    private val securePreviewFile get() = File(getApplication<Application>().cacheDir, "pending-import.lvault")

    /** 列表范围：0 全部 / 1 未读 / 2 已读 / 3 归档。 */
    var scope by mutableStateOf(saved.get<Int>("scope") ?: 0); private set
    /** 排序方式：0 默认（置顶+最新添加） / 1 最早添加 / 2 标题 A-Z / 3 站点聚合 */
    var sortOrder by mutableStateOf(saved.get<Int>("sort") ?: prefs.getInt("sort", 0)); private set

    fun theme(value: String) { if (value in listOf("system", "light", "dark")) { theme = value; prefs.edit().putString("theme", value).apply() } }
    fun tab(value: Int) { tab = value; saved["tab"] = value; clearMessage() }
    fun scope(value: Int) { if (value in 0..3) { scope = value; saved["scope"] = value; clearMessage() } }
    fun setSort(value: Int) { if (value in 0..3) { sortOrder = value; saved["sort"] = value; prefs.edit().putInt("sort", value).apply() } }
    fun show(item: Bookmark) { detailId = item.id; saved["detail"] = item.id; loadSnapshot(item.id); clearMessage() }
    fun closeDetail() { detailId = null; saved["detail"] = null; currentSnapshot = null; currentTranslation = null; clearMessage() }
    fun tag(value: String) { scope(0); filter(value); search(""); closeDetail(); tab(0) }

    // ------------------------------------------------------------ 离线正文快照

    var currentSnapshot by mutableStateOf<String?>(null); private set
    var fetchingSnapshot by mutableStateOf(false); private set

    /**
     * 详情页的译文与进行状态。刻意与 [currentSnapshot] 同级声明：
     * init 里就会读它们，放在后面会拿到尚未初始化的委托属性。
     */
    var currentTranslation by mutableStateOf<String?>(null); private set
    var translating by mutableStateOf(false); private set
    var translateStep by mutableStateOf(0 to 0); private set

    private fun loadSnapshot(id: Long) {
        currentTranslation = null
        translating = false
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { Snapshots.get(app, id) to Snapshots.getTranslation(app, id) }
            // 只认当前这条详情：快速连点两条时，先读完的那条不该把正文塞给后打开的那条。
            if (detailId == id) {
                currentSnapshot = text.first
                currentTranslation = text.second
            }
        }
    }

    /** 抓正文并落盘。网络与文件写入都留在 IO 线程上。 */
    private suspend fun fetchAndSaveSnapshot(item: Bookmark): String = withContext(Dispatchers.IO) {
        val text = Net.fetchArticle(item.url)
        Snapshots.save(app, item.id, text)
        text
    }

    /**
     * 保存之后自动补齐：先页面标题与描述，再离线正文。
     *
     * 全程静默。成功时内容自己就出现在详情页里，失败时详情页那两个手动按钮就是重试入口，
     * 两种情况都不该用一句提示打断用户。抓取开关没打开时一条请求都不发。
     */
    private fun autoComplete(item: Bookmark) {
        if (!fetchEnabled || !item.url.startsWith("https://", true)) return
        viewModelScope.launch {
            fetchingId = item.id
            val head = runCatching { withContext(Dispatchers.IO) { Net.fetchHead(item.url) } }.getOrNull()
            fetchingId = null
            var applied = ""
            if (head != null && !head.isEmpty) {
                applied = Titles.resolve(item.url, item.title, head.title, head.siteName)
                dao.applyFetch(item.id, head.description.take(8000), head.siteName.take(200), applied,
                    head.image.ifBlank { item.image }, System.currentTimeMillis())
            }
            fetchingSnapshot = true
            val text = runCatching { fetchAndSaveSnapshot(item) }.getOrNull()
            fetchingSnapshot = false
            if (text != null && detailId == item.id) currentSnapshot = text
            // 页面标题本身就是「首页」这类空壳时，退到正文里取名字。只读刚落盘的快照，不再联网。
            if (text != null && Titles.meaningless(item.url, applied.ifBlank { item.title })) {
                val derived = Titles.fromArticle(text)
                if (derived.isNotEmpty()) dao.setTitle(item.id, derived.take(Titles.LIMIT))
            }
        }
    }

    fun captureSnapshot(item: Bookmark) {
        if (busy || fetchingSnapshot) return
        if (!fetchEnabled) {
            toast("请先在设置中开启「页面信息抓取」")
            return
        }
        if (!item.url.startsWith("https://", true)) {
            toast("只能抓取 https 链接")
            return
        }
        fetchingSnapshot = true
        viewModelScope.launch {
            try {
                val text = fetchAndSaveSnapshot(item)
                if (detailId == item.id) currentSnapshot = text
                toast("已提取 Markdown 正文快照 (${text.length} 字)")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                toast("正文抓取失败: ${e.message ?: "未知错误"}")
            } finally {
                fetchingSnapshot = false
            }
        }
    }

    fun removeSnapshot(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Snapshots.delete(app, id) }
            if (detailId == id) { currentSnapshot = null; currentTranslation = null }
            toast("已删除正文快照")
        }
    }

    // ------------------------------------------------------------ 标题整理（完全离线）

    /**
     * 用本机已有的信息把标题重取一遍：剥掉换行与尾部站点名，空壳标题改用正文快照里的
     * `# 一级标题` 或第一句话。不发任何请求 —— 升级安装时顺手联网是不可接受的。
     */
    fun tidyTitles() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val rows = dao.all()
                val plans = withContext(Dispatchers.IO) {
                    rows.mapNotNull { item ->
                        val next = Titles.resolve(item.url, item.title, "", item.siteName, Snapshots.get(app, item.id))
                        if (next.isEmpty() || next == item.title) null else item.id to next
                    }
                }
                if (plans.isEmpty()) { toast("没有需要整理的标题"); return@launch }
                db.withTransaction { plans.forEach { (id, title) -> check(dao.setTitle(id, title) == 1) { "收藏已不存在" } } }
                toast("已按内容整理 ${plans.size} 条标题，原文与笔记未改动")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("整理标题失败，改动已整体回滚：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    // ------------------------------------------------------------ AI 翻译（用户自备接口）

    var aiEnabled by mutableStateOf(settings.getBoolean("aiOn", false)); private set
    var aiEndpoint by mutableStateOf(settings.getString("aiEndpoint", Translate.DEFAULT_ENDPOINT).orEmpty()); private set
    var aiModel by mutableStateOf(settings.getString("aiModel", Translate.DEFAULT_MODEL).orEmpty()); private set

    /**
     * 密钥。存在应用私有目录的 SharedPreferences 里，是明文：
     * 这台设备没有 Android Keystore 之外的更轻方案能既不引依赖又不落明文，
     * 所以边界写成「不 root 就拿不到」，并在设置页如实告诉用户。
     */
    var aiKey by mutableStateOf(settings.getString("aiKey", "").orEmpty()); private set

    val canTranslate: Boolean get() = aiEnabled && Translate.Config(aiEndpoint, aiKey, aiModel).ready

    fun aiEnabled(value: Boolean) { aiEnabled = value; settings.edit().putBoolean("aiOn", value).apply() }
    fun aiEndpoint(value: String) { aiEndpoint = value.trim(); settings.edit().putString("aiEndpoint", aiEndpoint).apply() }
    fun aiModel(value: String) { aiModel = value.trim().take(100); settings.edit().putString("aiModel", aiModel).apply() }
    fun aiKey(value: String) { aiKey = value.trim(); settings.edit().putString("aiKey", aiKey).apply() }
    fun clearAiKey() { aiKey = ""; settings.edit().remove("aiKey").apply(); toast("已清除接口密钥") }

    /** 翻译当前详情页的正文快照。结果落盘一份，重进页面不再重复花钱。 */
    fun translate(item: Bookmark) {
        if (translating || busy) return
        val source = currentSnapshot
        if (source.isNullOrBlank()) { fail("这条还没有正文快照，先提取正文再翻译"); return }
        if (!aiEnabled) { fail("先在「设置 → AI 翻译」打开开关"); return }
        val config = Translate.Config(aiEndpoint, aiKey, aiModel)
        if (!config.ready) { fail("接口地址必须是 https，并且要填密钥"); return }
        translating = true
        translateStep = 0 to 0
        viewModelScope.launch {
            try {
                val text = Translate.translate(config, source) { done, total -> translateStep = done to total }
                if (detailId == item.id) currentTranslation = text
                withContext(Dispatchers.IO) { Snapshots.saveTranslation(app, item.id, text) }
                toast("已译成简体中文 · ${text.length} 字")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("翻译失败：${e.localizedMessage ?: "未知错误"}") }
            finally { translating = false }
        }
    }

    // ------------------------------------------------------------ 收藏数据

    var items by mutableStateOf(emptyList<Bookmark>()); private set
    var trashItems by mutableStateOf(emptyList<Bookmark>()); private set
    var trashOpen by mutableStateOf(saved.get<Boolean>("trashOpen") ?: false); private set
    private var readJob: Job? = null
    private var trashJob: Job? = null
    var readFailed by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    /** 成功、提示类信息走这里（轻量提示），和需要打断的 error 分开。 */
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var search by mutableStateOf(saved.get<String>("search") ?: ""); private set
    var filter by mutableStateOf(saved.get<String>("filter") ?: ""); private set
    var pending by mutableStateOf(saved.get<String>("pending")); private set
    var pendingTitle by mutableStateOf(saved.get<String>("pendingTitle")); private set
    /** 最近一次粘贴/分享里检测到的全部链接，用于「全部收藏」。 */
    var detected by mutableStateOf(saved.get<ArrayList<String>>("detected")?.toList() ?: emptyList()); private set
    var fetchingId by mutableStateOf<Long?>(null); private set
    var clipboardCandidate by mutableStateOf<String?>(null); private set
    private var lastDismissedClipboard: String? = null

    var draft by mutableStateOf(restoreDraft()); private set

    init {
        reload()
        reloadTrash()
        detailId?.let { loadSnapshot(it) }
        if (saved.get<Boolean>("importPreview") == true) restoreImport()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // 正文快照是散在磁盘上的文件：行被清掉之前先把对应文件一起清掉，否则会永久残留。
                val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
                val gone = dao.trashedIdsBefore(cutoff)
                dao.purgeTrash(cutoff)
                gone.forEach { Snapshots.delete(app, it) }
            }
        }
    }

    fun reload() {
        readJob?.cancel()
        loading = true
        readFailed = false
        readJob = viewModelScope.launch {
            dao.observe().catch { if (it is CancellationException) throw it; loading = false; readFailed = true; fail("读取失败，请重试：${it.localizedMessage}") }
                .collect { items = it; loading = false }
        }
    }

    private fun reloadTrash() {
        trashJob?.cancel()
        trashJob = viewModelScope.launch {
            dao.observeTrash().catch { if (it is CancellationException) throw it; fail("读取回收站失败：${it.localizedMessage}") }
                .collect { trashItems = it }
        }
    }

    fun openTrash() { trashOpen = true; saved["trashOpen"] = true }
    fun closeTrash() { trashOpen = false; saved["trashOpen"] = false }

    fun fail(text: String) { error = text.take(600) }
    fun clearError() { error = null }
    fun toast(text: String) { message = text.take(600) }
    fun clearMessage() { message = null }

    fun search(value: String) { search = value.take(500); saved["search"] = search }
    fun filter(value: String) { filter = value; saved["filter"] = value }

    /** 当前范围内、匹配搜索与标签的收藏。 */
    fun visible(): List<Bookmark> {
        val filtered = items.filter { item ->
            when (scope) {
                1 -> !item.archived && !item.read
                2 -> !item.archived && item.read
                3 -> item.archived
                else -> !item.archived
            }
        }.filter { matches(it, search, filter) }

        return when (sortOrder) {
            1 -> filtered.sortedWith(compareBy<Bookmark> { !it.pinned }.thenBy { it.createdAt }.thenBy { it.id })
            2 -> filtered.sortedWith(compareBy<Bookmark> { !it.pinned }.thenBy { it.title.lowercase(Locale.ROOT) }.thenByDescending { it.createdAt })
            3 -> filtered.sortedWith(compareBy<Bookmark> { !it.pinned }.thenBy { Links.siteName(it.url).lowercase(Locale.ROOT) }.thenByDescending { it.createdAt })
            else -> filtered
        }
    }

    // ------------------------------------------------------------ 通用动作

    private fun act(success: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block(); toast(success) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("操作失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun togglePin(item: Bookmark) = act(if (item.pinned) "已取消置顶" else "已置顶") {
        check(dao.pin(item.id, !item.pinned) == 1) { "收藏已不存在" }
    }

    fun toggleRead(item: Bookmark) = act(if (item.read) "已标为未读" else "已标为已读") {
        check(dao.markRead(item.id, !item.read) == 1) { "收藏已不存在" }
    }

    fun toggleArchived(item: Bookmark) = act(if (item.archived) "已移出归档" else "已归档") {
        check(dao.archive(item.id, !item.archived) == 1) { "收藏已不存在" }
        if (!item.archived && detailId == item.id) closeDetail()
    }

    fun bulkMarkRead(ids: Set<Long>) = bulkAct(ids, "已标记为已读") { dao.markReadBulk(it, true) }
    fun bulkArchive(ids: Set<Long>) = bulkAct(ids, "已归档") { dao.archiveBulk(it, true) }

    private fun bulkAct(ids: Set<Long>, success: String, block: suspend (List<Long>) -> Int) {
        if (ids.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            try { block(ids.toList()); toast(success) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("批量操作失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    // ------------------------------------------------------------ 回收站

    fun delete(id: Long) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                check(dao.softDelete(id, System.currentTimeMillis()) == 1) { "收藏已不存在" }
                if (detailId == id) closeDetail()
                toast("已移入回收站，可在 30 天内恢复")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("删除失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun bulkDelete(ids: Set<Long>) {
        if (ids.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                db.withTransaction { ids.forEach { dao.softDelete(it, now) } }
                toast("已移入回收站，可在 30 天内恢复")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("批量删除失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun restoreTrash(item: Bookmark) = act("已恢复收藏") {
        check(dao.restore(item.id) == 1) { "这条收藏已不存在" }
    }

    fun deleteForever(item: Bookmark) = act("已永久删除") {
        check(dao.deleteForever(item.id) == 1) { "这条收藏已不存在" }
        withContext(Dispatchers.IO) { Snapshots.delete(app, item.id) }
        if (detailId == item.id) currentSnapshot = null
    }

    // ------------------------------------------------------------ 标签管理

    fun renameTag(from: String, to: String) {
        val target = to.trim()
        if (target.isEmpty()) { fail("标签名不能为空"); return }
        if (target.length > 1000) { fail("标签名过长"); return }
        if (parseTags(target).size != 1) { fail("标签名不能包含逗号、分号或换行"); return }
        if (Links.tagKey(target) == Links.tagKey(from)) { fail("新旧标签名相同"); return }
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val merged = db.withTransaction {
                    var moved = 0
                    dao.all().forEach { item ->
                        val tags = parseTags(item.tags)
                        if (tags.none { Links.tagKey(it) == Links.tagKey(from) }) return@forEach
                        val next = tags.filterNot { Links.tagKey(it) == Links.tagKey(from) }.toMutableList()
                        if (next.none { Links.tagKey(it) == Links.tagKey(target) }) next.add(target)
                        val value = next.joinToString(",")
                        require(value.length <= 1000) { "「${item.title.ifBlank { item.url }}」的标签会超出长度上限" }
                        dao.setTags(item.id, value)
                        moved++
                    }
                    moved
                }
                if (filter.isNotEmpty() && Links.tagKey(filter) == Links.tagKey(from)) filter(target)
                toast(if (merged == 0) "没有收藏在用这个标签" else "已把 $merged 条收藏的标签改为「$target」")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("重命名失败，改动已回滚：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun deleteTag(tag: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val changed = db.withTransaction {
                    var hit = 0
                    dao.all().forEach { item ->
                        val tags = parseTags(item.tags)
                        if (tags.none { Links.tagKey(it) == Links.tagKey(tag) }) return@forEach
                        dao.setTags(item.id, tags.filterNot { Links.tagKey(it) == Links.tagKey(tag) }.joinToString(","))
                        hit++
                    }
                    hit
                }
                if (filter.isNotEmpty() && Links.tagKey(filter) == Links.tagKey(tag)) filter("")
                toast(if (changed == 0) "没有收藏在用这个标签" else "已从 $changed 条收藏上移除「$tag」")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("删除标签失败，改动已回滚：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    // ------------------------------------------------------------ 草稿

    fun edit(value: Draft?) {
        draft = value
        saved["draft"] = value?.let { d -> Bundle().apply {
            putLong("id", d.id); putString("url", d.url); putString("title", d.title); putString("notes", d.notes); putString("tags", d.tags)
        } }
        draftStore.edit().apply {
            if (value == null) clear() else {
                putBoolean("active", true).putLong("id", value.id).putString("url", value.url)
                putString("title", value.title).putString("notes", value.notes).putString("tags", value.tags)
                putString("process", PROCESS_TOKEN)
            }
        }.apply()
    }

    /**
     * 草稿来源优先级：本次会话保存的状态 → 上次进程留下的落盘草稿。
     *
     * 落盘的草稿只在**进程重启**后才恢复（比对进程标识），这样同进程内新建的 ViewModel
     * 不会被旧草稿污染；同时强制停止或划掉任务之后草稿依然还在。
     */
    private fun restoreDraft(): Draft? {
        saved.get<Bundle>("draft")?.let {
            return Draft(it.getLong("id"), it.getString("url", ""), it.getString("title", ""), it.getString("notes", ""), it.getString("tags", ""))
        }
        if (!draftStore.getBoolean("active", false)) return null
        if (draftStore.getString("process", "") == PROCESS_TOKEN) return null
        return Draft(
            draftStore.getLong("id", 0),
            draftStore.getString("url", "").orEmpty(),
            draftStore.getString("title", "").orEmpty(),
            draftStore.getString("notes", "").orEmpty(),
            draftStore.getString("tags", "").orEmpty()
        )
    }

    fun open(item: Bookmark) { notice = null; edit(Draft(item.id, item.url, item.title, item.notes, item.tags)) }

    fun extractText(text: String, html: String = "", suggested: String = "") {
        val urls = Links.extract(text.take(16000), html)
        if (urls.isEmpty()) { fail("没有找到有效的 HTTP/HTTPS 链接"); return }
        notice = if (urls.size > 1) "检测到 ${urls.size} 个链接，本次仅取第一个；其余链接请分别收藏。" else null
        detected = urls
        saved["detected"] = ArrayList(urls)
        val old = draft ?: Draft()
        val fromHtml = if (html.isBlank()) "" else Links.titleFromHtml(html, urls.first())
        val title = old.title.ifBlank { suggested.ifBlank { fromHtml } }
        edit(old.copy(url = urls.first(), title = title.take(200)))
    }

    fun paste(text: String, html: String) = extractText(text, html)

    fun receive(text: String) = receive(Shared(text))

    fun receive(shared: Shared) {
        if (shared.text.isBlank() && shared.html.isBlank()) { fail("分享中没有可识别的链接文字"); return }
        if (draft != null || busy || preview != null) {
            pending = shared.text.take(16000); saved["pending"] = pending
            pendingTitle = shared.title.take(200); saved["pendingTitle"] = pendingTitle
            fail("收到新分享，当前草稿未覆盖。完成或取消编辑后，可点击“处理待收分享”。多次分享只保留最新一条待收内容。")
        } else {
            edit(Draft())
            extractText(shared.text, shared.html, shared.title)
        }
    }

    fun importPending() {
        if (draft != null || busy) return
        val text = pending ?: return
        val title = pendingTitle.orEmpty()
        pending = null; saved["pending"] = null
        pendingTitle = null; saved["pendingTitle"] = null
        receive(Shared(text, title))
    }

    fun cancel() { if (!busy) { edit(null); notice = null; detected = emptyList(); saved["detected"] = null } }

    fun checkClipboard(context: Context) {
        if (draft != null || detailId != null || busy || trashOpen) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        if (!cm.hasPrimaryClip()) return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val urls = Links.extract(text.take(4000))
        if (urls.isEmpty()) return
        val candidate = urls.first()
        if (candidate == lastDismissedClipboard) return
        val key = runCatching { Links.canonical(candidate) }.getOrNull() ?: return
        if (items.any { it.canonical == key || it.url == candidate }) return
        if (trashItems.any { it.canonical == key || it.url == candidate }) return
        clipboardCandidate = candidate
    }

    fun dismissClipboard() {
        lastDismissedClipboard = clipboardCandidate
        clipboardCandidate = null
    }

    fun quickSaveClipboard() {
        val url = clipboardCandidate ?: return
        dismissClipboard()
        if (busy) return
        val key = try { Links.canonical(url) } catch (_: Exception) { return }
        busy = true
        viewModelScope.launch {
            try {
                if (dao.byKey(key) != null) {
                    toast("此链接已存在于收藏中")
                    return@launch
                }
                val now = System.currentTimeMillis()
                val readableTitle = Links.readable(url).take(200)
                val item = Bookmark(url = url, canonical = key, title = readableTitle, createdAt = now, updatedAt = now)
                val id = dao.insert(item)
                toast("已收录剪贴板链接")
                if (fetchEnabled && url.startsWith("https://", true)) {
                    val head = runCatching { withContext(Dispatchers.IO) { Net.fetchHead(url) } }.getOrNull()
                    if (head != null && !head.isEmpty) {
                        val title = Titles.cleanse(url, head.title, head.siteName).ifBlank { readableTitle }
                        dao.applyFetch(id, head.description.take(8000), head.siteName.take(200), title, head.image, System.currentTimeMillis())
                    }
                    runCatching { fetchAndSaveSnapshot(item.copy(id = id)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("收录失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun randomRead() {
        if (items.isEmpty()) {
            toast("暂无收藏可供温故")
            return
        }
        val pool = when (scope) {
            1 -> items.filter { !it.archived && !it.read }
            2 -> items.filter { !it.archived && it.read }
            3 -> items.filter { it.archived }
            else -> {
                val unread = items.filter { !it.archived && !it.read }
                if (unread.isNotEmpty()) unread else items.filter { !it.archived }
            }
        }
        val scoped = if (filter.isNotBlank()) pool.filter { matches(it, "", filter) } else pool
        val target = scoped.randomOrNull() ?: items.filter { !it.archived }.randomOrNull() ?: items.randomOrNull()
        if (target != null) {
            show(target)
            toast("为你翻出：${Links.displayTitle(target.url, target.title)}")
        } else {
            toast("暂无符合条件的收藏可供温故")
        }
    }

    // ------------------------------------------------------------ 保存

    fun save() {
        val d = draft ?: return
        if (busy) return
        val url = d.url.trim()
        val key = try { Links.canonical(url) } catch (e: IllegalArgumentException) { fail(e.message ?: "链接无效"); return }
        busy = true
        viewModelScope.launch {
            try {
                val existing = if (d.id == 0L) null else dao.byId(d.id)
                if (d.id != 0L && existing == null) { fail("原收藏已不存在，草稿已保留"); return@launch }
                val duplicate = dao.byKey(key)
                if (duplicate != null && duplicate.id != d.id) {
                    fail("此链接已收藏：${Links.displayTitle(duplicate.url, duplicate.title)}。请返回列表编辑已有收藏。")
                } else {
                    val now = System.currentTimeMillis()
                    val tags = parseTags(d.tags).joinToString(",")
                    // 手打的标题也折叠空白：正文粘贴进来常带换行，卡片会在句子中间断成两截。
                    val title = Titles.collapse(d.title).take(Titles.LIMIT)
                    val item = existing?.copy(
                        url = url, canonical = key, title = title, notes = d.notes.trim(), tags = tags, updatedAt = now
                    ) ?: Bookmark(
                        url = url, canonical = key, title = title, notes = d.notes.trim(), tags = tags,
                        createdAt = now, updatedAt = now
                    )
                    val id = if (d.id == 0L) dao.insert(item) else { check(dao.update(item) == 1) { "原收藏已不存在" }; d.id }
                    val written = item.copy(id = id)
                    show(written); tab(0)
                    // 只补没抓过的。已经抓到过信息的条目即便改了网址也不自动重抓，
                    // 想在编辑后刷新还有详情页那个「重新抓取」。
                    if (written.fetchedAt == 0L) autoComplete(written)
                    edit(null); notice = null; detected = emptyList(); saved["detected"] = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("保存失败，草稿已保留，请重试：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    /** 一次把这次粘贴/分享里的所有链接都收下来。 */
    fun saveAllDetected() {
        val urls = detected
        if (urls.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            try {
                var added = 0
                var skipped = 0
                db.withTransaction {
                    val now = System.currentTimeMillis()
                    urls.forEach { raw ->
                        val key = runCatching { Links.canonical(raw) }.getOrNull() ?: return@forEach
                        if (dao.byKey(key) != null) { skipped++; return@forEach }
                        dao.insert(Bookmark(url = raw, canonical = key, title = Links.readable(raw).take(200), createdAt = now, updatedAt = now))
                        added++
                    }
                }
                edit(null); notice = null; detected = emptyList(); saved["detected"] = null; tab(0)
                toast("已收藏 $added 条，跳过 $skipped 条重复链接")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("批量收藏失败，已整体回滚：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    // ------------------------------------------------------------ 可选联网抓取

    var fetchEnabled by mutableStateOf(settings.getBoolean("fetch", false)); private set
    fun fetchEnabled(value: Boolean) { fetchEnabled = value; settings.edit().putBoolean("fetch", value).apply() }

    fun fetch(item: Bookmark) {
        if (busy) return
        if (!fetchEnabled) { fail("页面信息抓取未开启。请先到「设置 → 联网抓取」打开开关。"); return }
        if (!item.url.startsWith("https://", true)) { fail("只抓取 https 链接，这一条是明文 http，为安全起见已跳过"); return }
        busy = true
        fetchingId = item.id
        viewModelScope.launch {
            try {
                val head = withContext(Dispatchers.IO) { Net.fetchHead(item.url) }
                val title = Titles.resolve(item.url, item.title, head.title, head.siteName)
                check(dao.applyFetch(item.id, head.description.take(8000), head.siteName.take(200), title, head.image.ifBlank { item.image }, System.currentTimeMillis()) == 1) { "收藏已不存在" }
                toast(if (head.isEmpty) "页面里没有找到标题或描述" else "已抓取页面标题与描述")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("抓取失败：${e.localizedMessage}") }
            finally { busy = false; fetchingId = null }
        }
    }

    // ------------------------------------------------------------ 备份

    var lastBackupAt by mutableStateOf(settings.getLong("lastBackup", 0L)); private set
    var backupFolder by mutableStateOf(settings.getString("backupFolder", "").orEmpty()); private set

    private fun markBackedUp() {
        lastBackupAt = System.currentTimeMillis()
        settings.edit().putLong("lastBackup", lastBackupAt).apply()
    }

    fun setBackupFolder(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            fail("系统没有授予长期访问权限，请重新选择文件夹。${e.localizedMessage ?: ""}")
            return
        }
        backupFolder = uri.toString()
        settings.edit().putString("backupFolder", backupFolder).apply()
        toast("已设置备份文件夹，之后可以一键备份")
    }

    fun clearBackupFolder() { backupFolder = ""; settings.edit().remove("backupFolder").apply(); toast("已取消备份文件夹") }

    /** 一键备份到用户选定的文件夹，不用每次再走一遍文件选择器。 */
    fun backupNow() {
        val folder = backupFolder
        if (folder.isEmpty()) { fail("还没有设置备份文件夹"); return }
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val name = "${Backup.BACKUP_PREFIX}${Stamp.fileNameStamp()}.json"
                val count = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val rows = dao.all()
                    val target = DocumentsContract.createDocument(resolver, Uri.parse(folder), "application/json", name)
                        ?: error("无法在所选文件夹中创建文件")
                    resolver.openOutputStream(target, "wt")?.use { it.write(Backup.encode(rows)); it.flush() } ?: error("无法写入文件")
                    pruneBackups(folder)
                    rows.size
                }
                markBackedUp()
                toast("已备份 $count 条到所选文件夹")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("备份到文件夹失败：${e.localizedMessage}。可改用「导出收藏」手动选择位置。") }
            finally { busy = false }
        }
    }

    /**
     * 备份文件夹里只留最近 BACKUP_KEEP 份链藏自己的备份。
     *
     * 没有 queryChildDocuments / findDocument 这两个 API，只能自己查子文件列表再删。
     * 删除失败不影响这次备份已经成功这件事，所以逐个吞掉。
     */
    private fun pruneBackups(folder: String) {
        val root = Uri.parse(folder)
        val resolver = getApplication<Application>().contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        val ids = HashMap<String, String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                ids[name] = id
            }
        }
        Backup.staleBackups(ids.keys.toList(), BACKUP_KEEP).forEach { name ->
            val id = ids[name] ?: return@forEach
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(root, id)) }
        }
    }

    /** 预览用的两份临时备份文件（明文 / 加密）用完即清，不在 cache 里留底。 */
    private fun clearPreviewFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            previewFile.delete()
            securePreviewFile.delete()
        }
    }

    private fun restoreImport() {
        busy = true
        viewModelScope.launch {
            try {
                preview = withContext(Dispatchers.IO) {
                    val rows = previewFile.inputStream().use { Backup.decode(Backup.read(it)) }
                    ImportPreview(rows, Backup.countNew(rows, dao.allIncludingDeleted()))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                saved["importPreview"] = false
                clearPreviewFiles()
                fail("导入预览已失效，请重新选择备份文件")
            }
            finally { busy = false }
        }
    }

    fun prepareImport(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择系统文件选择器中的文件" }
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { Backup.read(it) }
                        ?: error("无法读取文件")
                    val rows = Backup.decode(bytes)
                    previewFile.writeBytes(bytes)
                    securePreviewFile.delete()
                    ImportPreview(rows, Backup.countNew(rows, dao.allIncludingDeleted()))
                }
                saved["importPreview"] = true
                preview = result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("备份读取失败，没有导入任何数据：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun cancelImport() {
        if (busy) return
        preview = null; saved["importPreview"] = false
        clearPreviewFiles()
    }

    fun confirmImport() {
        val data = preview ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val result = Backup.merge(db, data.items)
                preview = null; saved["importPreview"] = false
                withContext(Dispatchers.IO) {
                    previewFile.delete()
                    securePreviewFile.delete()
                }
                toast("导入完成：新增 ${result.added} 条，跳过 ${result.skipped} 条重复收藏。原有数据未被覆盖。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("导入失败，事务已回滚，没有部分写入：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun exportEncrypted(uri: Uri, password: CharArray) {
        if (busy) { password.fill('\u0000'); fail("有操作进行中，请稍后重新导出"); return }
        busy = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择系统文件选择器中的文件" }
                    val rows = dao.all()
                    val bytes = SecureBackup.encrypt(rows, password)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                        ?: error("无法写入文件")
                    rows.size
                }
                markBackedUp()
                toast("已导出 $count 条加密备份")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("加密导出失败：${e.localizedMessage}") }
            finally { password.fill('\u0000'); busy = false }
        }
    }

    fun prepareEncryptedImport(uri: Uri, password: CharArray) {
        if (busy) { password.fill('\u0000'); return }
        busy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择系统文件选择器中的文件" }
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use(SecureBackup::read)
                        ?: error("无法读取文件")
                    val rows = SecureBackup.decrypt(bytes, password)
                    securePreviewFile.writeBytes(bytes)
                    ImportPreview(rows, Backup.countNew(rows, dao.allIncludingDeleted()))
                }
                preview = result
                toast("加密备份已解锁，请确认导入")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("加密备份读取失败，没有导入任何数据：${e.localizedMessage}") }
            finally { password.fill('\u0000'); busy = false }
        }
    }

    fun export(uri: Uri) {
        if (busy) { fail("有操作进行中，请稍后重新导出"); return }
        busy = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择系统文件选择器中的文件" }
                    val rows = dao.all()
                    val bytes = Backup.encode(rows)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                        ?: error("无法写入文件")
                    rows.size
                }
                markBackedUp()
                toast("已导出 $count 条收藏。备份为未加密 JSON，请妥善保管。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("导出失败：${e.localizedMessage}。目标文件可能不完整，请重新导出。") }
            finally { busy = false }
        }
    }

    fun exportHtml(uri: Uri) {
        if (busy) { fail("有操作进行中，请稍后重新导出"); return }
        busy = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择系统文件选择器中的文件" }
                    val rows = dao.all()
                    val bytes = Backup.encodeHtml(rows)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                        ?: error("无法写入文件")
                    rows.size
                }
                markBackedUp()
                toast("已导出 $count 条 HTML 书签，支持导入 Chrome/Safari/Edge。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("导出 HTML 失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun exportMarkdown(uri: Uri) {
        if (busy) { fail("有操作进行中，请稍后重新导出"); return }
        busy = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择系统文件选择器中的文件" }
                    val rows = dao.all()
                    val bytes = Backup.encodeMarkdown(rows)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                        ?: error("无法写入文件")
                    rows.size
                }
                markBackedUp()
                toast("已导出 $count 条 Markdown 知识合辑，支持导入 Obsidian/Notion。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("导出 Markdown 失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun exportPortalHtml(uri: Uri) {
        if (busy) { fail("有操作进行中，请稍后重新导出"); return }
        busy = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择系统文件选择器中的文件" }
                    val rows = dao.all()
                    val bytes = Backup.encodePortalHtml(rows)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                        ?: error("无法写入文件")
                    rows.size
                }
                markBackedUp()
                toast("已导出独立导航网页（$count 条），内置即时检索与标签，可直接在浏览器中打开。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("导出导航网页失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    // ------------------------------------------------------------ 应用内更新

    var updateChecking by mutableStateOf(false); private set
    var updateDownloading by mutableStateOf(false); private set
    var updateProgress by mutableStateOf(0f); private set
    var updateBytes by mutableStateOf(0L); private set
    var updateTotal by mutableStateOf(0L); private set
    var updateSpeed by mutableStateOf(0L); private set
    var available by mutableStateOf<Updater.Info?>(null); private set
    var downloaded by mutableStateOf<File?>(null); private set
    var updateError by mutableStateOf<String?>(null); private set
    var needInstallPermission by mutableStateOf(false); private set
    var autoCheckUpdates by mutableStateOf(settings.getBoolean("autoCheck", true)); private set
    /** 用户主动忽略了首页的更新提示；只在本次运行内有效，下次启动会重新提醒。 */
    var updateBannerDismissed by mutableStateOf(false); private set

    fun dismissUpdateBanner() { updateBannerDismissed = true }

    private val updateFile get() = File(getApplication<Application>().cacheDir, "updates/lian-cang.apk")

    fun autoCheckUpdates(value: Boolean) { autoCheckUpdates = value; settings.edit().putBoolean("autoCheck", value).apply() }
    fun clearUpdateError() { updateError = null }

    /** 已经下载好、就等着交给系统安装器的文件。 */
    fun downloadedFile(): File? = downloaded?.takeIf { it.isFile && it.length() > 0L }

    /**
     * 检查更新。
     *
     * [manual]=false 是启动时的静默检查：受开关与 12 小时节流控制，失败不打扰用户。
     * [manual]=true 是用户主动点击：失败与「已是最新」都会明确反馈。
     */
    fun checkUpdate(manual: Boolean) {
        if (updateChecking) return
        if (!manual) {
            if (!autoCheckUpdates) return
            if (System.currentTimeMillis() - settings.getLong("lastCheck", 0L) < 12 * 3600_000L) return
        }
        // 先记时间再发请求：这样同一进程内反复新建 ViewModel 也不会反复联网
        settings.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        updateChecking = true
        if (manual) updateError = null
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.fetchManifest() }
                if (Updater.isNewer(info, installedVersionCode)) {
                    available = info
                    if (manual) message = "发现新版本 ${info.versionName}"
                } else {
                    available = null
                    if (manual) message = "已经是最新版本（$versionLabel）"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (manual) updateError = "检查更新失败：${e.localizedMessage ?: "网络不可用"}"
            } finally { updateChecking = false }
        }
    }

    /**
     * 下载新版并（在已授权的情况下）直接拉起系统安装器。
     * 下载完会核对 SHA-256，不匹配就丢弃，不会把来路不明的东西递给安装器。
     */
    fun downloadUpdate() {
        val info = available ?: return
        if (updateDownloading) return
        updateDownloading = true
        updateProgress = 0f
        updateBytes = 0L
        updateTotal = if (info.size > 0L) info.size else 0L
        updateSpeed = 0L
        updateError = null
        needInstallPermission = false
        val started = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                Log.i(TAG, "开始下载 ${info.versionName}")
                val file = withContext(Dispatchers.IO) {
                    Updater.download(updateFile, info.sha256) { done, total ->
                        val elapsed = System.currentTimeMillis() - started
                        updateBytes = done
                        if (total > 0L) {
                            updateTotal = total
                            updateProgress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                        if (elapsed >= 1000L) updateSpeed = done * 1000L / elapsed
                    }
                }
                check(file.isFile && file.length() > 0L) { "下载下来的文件是空的" }
                downloaded = file
                Log.i(TAG, "下载完成 ${file.length()} 字节")
                launchInstaller(file, info.versionName)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { updateError = "下载失败：${Updater.describe(e)}" }
            finally { updateDownloading = false }
        }
    }

    /**
     * 逐个尝试安装意图（ACTION_INSTALL_PACKAGE → ACTION_VIEW）。
     *
     * 刻意不用 queryIntentActivities 预判「有没有安装器」：Android 11 起的包可见性限制
     * 会让它误报为空，反而把能用的路径拦掉。这里直接试，失败了再说。
     */
    private fun launchInstaller(file: File, versionName: String) {
        val context = getApplication<Application>()
        if (!Updater.canInstall(context)) {
            needInstallPermission = true
            message = "已下载 $versionName，还需要允许「安装未知应用」"
            Log.i(TAG, "缺少安装未知应用权限")
            return
        }
        val intents = runCatching { Updater.installIntents(context, file) }.getOrNull()
        if (intents == null) {
            updateError = "无法准备安装（文件访问被拒绝），可以改用下面的「用浏览器下载」。"
            return
        }
        var last: Throwable? = null
        for (intent in intents) {
            try {
                context.startActivity(intent)
                Log.i(TAG, "已发出安装意图 ${intent.action}")
                message = "已下载 $versionName，已请求系统安装"
                return
            } catch (e: Exception) {
                last = e
                Log.i(TAG, "安装意图失败 ${intent.action}", e)
            }
        }
        needInstallPermission = true
        updateError = "系统没有响应安装请求（${last?.javaClass?.simpleName ?: "无可用安装器"}）。" +
            "部分国产系统会额外限制应用自行安装软件 —— 可以点下面的「用浏览器下载」。"
    }

    /** 手动再试一次安装。 */
    fun installDownloaded() {
        val file = downloadedFile() ?: return
        launchInstaller(file, available?.versionName ?: "")
    }

    /** 系统现在到底允不允许本应用安装软件 —— 直接摆在界面上，省得猜。 */
    fun canInstallNow(): Boolean = runCatching { Updater.canInstall(getApplication()) }.getOrDefault(false)

    /** 兜底：交给系统浏览器下载。用户已经证明这条路能走通。 */
    fun openDownloadInBrowser() {
        val context = getApplication<Application>()
        runCatching { context.startActivity(Updater.downloadPageIntent()) }
            .onFailure { updateError = "无法打开浏览器：${Updater.describe(it)}" }
    }

    fun openInstallPermission() {
        val context = getApplication<Application>()
        runCatching { context.startActivity(Updater.installPermissionIntent(context)) }
            .onFailure { updateError = "无法打开系统设置，请手动进入「安装未知应用」" }
    }

    companion object {
        private const val TAG = "LinkVault.Update"
        private const val TRASH_RETENTION_MS = 30L * 86_400_000L
        /** 一键备份文件夹里保留的份数。再手动导出的文件不在这个范围内。 */
        private const val BACKUP_KEEP = 12

        /** 进程标识：只有真正重启过进程，落盘的草稿才会被捡回来。 */
        private val PROCESS_TOKEN: String = UUID.randomUUID().toString()
    }
}
