package cn.linkvault

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class AnalysisController(
    private val context: Context,
    private val db: VaultDb,
    private val scope: CoroutineScope,
    private val config: () -> Translate.Config,
    private val verified: () -> Boolean,
    private val fetchEnabled: () -> Boolean,
    private val onClear: (String) -> Unit = {},
    private val readRecord: (Context, String) -> AnalysisRecord? = AnalysisStore::read
) {
    private val prefs = context.getSharedPreferences("settings", 0)
    var auto by mutableStateOf(prefs.getBoolean("analysisAuto", false)); private set
    var profile by mutableStateOf(prefs.getString("analysisProfile", "").orEmpty()); private set
    var templates by mutableStateOf(loadTemplates()); private set
    var selected by mutableStateOf(prefs.getString("analysisTemplate", "quick").orEmpty()); private set
    val active: AnalysisTemplate get() = templates.firstOrNull { it.id == selected } ?: templates.first()
    val records = mutableStateMapOf<String, AnalysisRecord>()
    val states = mutableStateMapOf<String, String>()
    val errors = mutableStateMapOf<String, String>()
    private val jobs = mutableMapOf<String, Job>()
    private val automatic = mutableSetOf<String>()
    private val loaded = mutableSetOf<String>()
    private val loading = mutableMapOf<String, Job>()
    private val versions = mutableMapOf<String, Long>()
    private val queue = Semaphore(1)

    fun auto(value: Boolean) {
        auto = value; prefs.edit().putBoolean("analysisAuto", value).apply()
        if (!value) automatic.toList().forEach { cancel(it) }
    }
    fun profile(value: String) { profile = value.take(4_000); prefs.edit().putString("analysisProfile", profile).apply() }
    fun select(id: String) { selected = id; prefs.edit().putString("analysisTemplate", id).apply() }
    fun updateTemplate(name: String, prompt: String) {
        templates = templates.map { if (it.id == active.id) it.copy(name = name.take(40), prompt = prompt.take(6_000)) else it }
        saveTemplates()
    }
    fun addTemplate() {
        val next = AnalysisTemplate(UUID.randomUUID().toString(), "自定义框架", Analysis.defaults.first().prompt)
        templates = templates + next; saveTemplates(); select(next.id)
    }
    fun removeTemplate() {
        if (templates.size <= 1) return
        templates = templates.filterNot { it.id == active.id }; saveTemplates(); select(templates.first().id)
    }
    private fun loadTemplates(): List<AnalysisTemplate> = runCatching {
        val array = JSONArray(prefs.getString("analysisTemplates", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { j -> AnalysisTemplate(j.getString("id"), j.getString("name"), j.getString("prompt")) } }
    }.getOrDefault(emptyList()).ifEmpty { Analysis.defaults }
    private fun saveTemplates() {
        prefs.edit().putString("analysisTemplates", JSONArray().apply {
            templates.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)) }
        }.toString()).apply()
    }

    fun load(key: String) {
        if (key in loaded || key in loading || key in records) return
        val version = versions[key] ?: 0L
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val record = withContext(Dispatchers.IO) { readRecord(context, key) }
                // 请求可以先发布新记录；迟到的读盘永远不能覆盖它。
                if ((versions[key] ?: 0L) == version && key !in records) {
                    if (record != null) records[key] = record
                    loaded.add(key)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if ((versions[key] ?: 0L) == version && key !in records) errors[key] = "本机 AI 记录读取失败，可重新分析；原文未改动"
            }
            finally { if (loading[key] === coroutineContext[Job]) loading.remove(key) }
        }
        loading[key] = job
        job.start()
    }

    fun afterBookmarkSave(id: Long) { if (auto) reschedule("b-$id") }
    fun afterNoteSave(note: Note) {
        val key = "n-${note.id}"
        if (note.secret) clear(key) else if (auto) reschedule(key)
    }
    private fun reschedule(key: String) {
        if (jobs.containsKey(key)) cancel(key)
        run(key, automaticRequest = true)
    }
    fun cancel(key: String) = cancelRequest(key, restoreRecord = true)
    private fun cancelRequest(key: String, restoreRecord: Boolean) {
        versions[key] = (versions[key] ?: 0L) + 1
        jobs.remove(key)?.cancel(); automatic.remove(key); states.remove(key)
        loading.remove(key)?.cancel()
        if (restoreRecord && key !in records) { loaded.remove(key); load(key) }
    }
    fun clear(key: String) {
        onClear(key)
        cancelRequest(key, restoreRecord = false); records.remove(key); loaded.remove(key); errors.remove(key)
        // 小文件删除同步完成，避免立即重新分析与异步删除互相覆盖。
        AnalysisStore.delete(context, key)
    }

    /** 每次请求前重新读库，私密笔记没有任何可上传路径。 */
    private suspend fun source(key: String, allowFetch: Boolean): String {
        val id = key.substringAfter('-').toLong()
        if (key.startsWith("n-")) {
            val note = db.notes().byId(id) ?: error("笔记已不存在")
            require(!note.secret) { "私密笔记不参与 AI 分析或对话" }
            return note.text
        }
        val item = db.bookmarks().byId(id)?.takeIf { it.deletedAt == 0L } ?: error("收藏已删除")
        var body = withContext(Dispatchers.IO) { Snapshots.get(context, id) }
        if (body.isNullOrBlank() && allowFetch) {
            require(fetchEnabled()) { "没有正文：请开启「页面信息抓取」，或把文章正文粘贴为普通笔记" }
            require(item.url.startsWith("https://", true)) { "只能抓取 https 正文；请把正文粘贴为普通笔记" }
            states[key] = "正在提取正文…"
            body = withContext(Dispatchers.IO) { Net.fetchArticle(item.url) }
            val current = db.bookmarks().byId(id)
            require(current != null && current.deletedAt == 0L && current.url == item.url) { "收藏已变化，本次抓取未保存" }
            // 文件写入与主线程的编辑/删除不交错。
            Snapshots.save(context, id, body.orEmpty())
        }
        require(!body.isNullOrBlank()) { "没有可分析的正文，请先提取正文或粘贴为普通笔记" }
        return bookmarkSource(item, body.orEmpty())
    }

    private fun bookmarkSource(item: Bookmark, body: String): String =
        "标题：${item.title}\n链接：${item.url}\n我的备注：${item.notes}\n\n$body"

    /** 只由用户确认调用，不发网络请求；标签与现有标签合并。 */
    fun applySuggestions(key: String) {
        if (!key.startsWith("b-") || jobs.containsKey(key) || states.containsKey(key)) return
        val record = records[key] ?: return
        if (record.suggestedTitle.isBlank()) return
        val id = key.substringAfter('-').toLong()
        val version = (versions[key] ?: 0L) + 1
        versions[key] = version
        states[key] = "正在应用标题与标签…"; errors.remove(key)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                queue.withPermit {
                    val body = withContext(Dispatchers.IO) { Snapshots.get(context, id) } ?: error("原文已变化，请重新分析后再应用")
                    val updated = db.withTransaction {
                        val current = db.bookmarks().byId(id)?.takeIf { it.deletedAt == 0L } ?: error("收藏已删除")
                        require(Analysis.hash(bookmarkSource(current, body)) == record.hash) { "收藏内容已变化，请重新分析后再应用建议" }
                        val tags = (parseTags(current.tags) + record.suggestedTags).distinctBy(Links::tagKey).joinToString(",")
                        check(db.bookmarks().setTitle(id, record.suggestedTitle) == 1 && db.bookmarks().setTags(id, tags) == 1) { "收藏已不存在" }
                        current.copy(title = record.suggestedTitle, tags = tags)
                    }
                    val next = record.copy(hash = Analysis.hash(bookmarkSource(updated, body)), updatedAt = System.currentTimeMillis())
                    AnalysisStore.save(context, key, next)
                    records[key] = next
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (versions[key] == version) errors[key] = e.localizedMessage ?: "应用标题与标签失败，请重试" }
            finally { if (versions[key] == version) { states.remove(key); jobs.remove(key) } }
        }
        jobs[key] = job
        job.start()
    }

    fun run(key: String, question: String? = null, automaticRequest: Boolean = false) {
        if (jobs.containsKey(key)) return
        if (question != null && (question.isBlank() || question.length > Analysis.MAX_QUESTION)) return
        val selectedTemplate = active
        val system = Analysis.system(profile, selectedTemplate.prompt)
        val suggest = question == null && key.startsWith("b-")
        val connection = config()
        val version = (versions[key] ?: 0L) + 1
        versions[key] = version
        errors.remove(key); states[key] = "等待分析…"
        if (automaticRequest) automatic.add(key)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                queue.withPermit {
                    require(verified() && AiProviders.fingerprint(connection) == AiProviders.fingerprint(config())) { "请先在设置里测试当前 AI 连接，验证通过后才能分析或对话" }
                    require(connection.ready && connection.model.isNotBlank()) { "请先在设置里填好 AI 的 https 接口、模型和密钥" }
                    require(selectedTemplate.prompt.isNotBlank()) { "提示词框架不能为空" }
                    val old = records[key] ?: withContext(Dispatchers.IO) {
                        if (question == null) runCatching { readRecord(context, key) }.getOrNull()
                        else readRecord(context, key)
                    }
                    // 先恢复落盘的旧总结；新请求失败或取消也不会使旧记录消失。
                    if (old != null) { records[key] = old; loaded.add(key) }
                    val text = source(key, allowFetch = true)
                    val hash = Analysis.hash(text)
                    if (automaticRequest && old?.hash == hash) { records[key] = old; return@withPermit }
                    if (question != null) require(old != null && old.hash == hash) { "正文已变化或还没有总结，请先重新分析再继续对话" }
                    states[key] = if (question == null) "正在分析…" else "正在回答…"
                    val requestSystem = if (question == null) system + (if (suggest) Analysis.SUGGESTIONS else "") else old!!.system
                    val answer = Analysis.answer(connection, requestSystem, text, if (question == null) null else old, question)
                    val suggestions = if (suggest) Analysis.suggestions(answer) else null
                    // 网络返回后再次验证，防止删除/加密/改正文期间旧结果落盘。
                    require(Analysis.hash(source(key, allowFetch = false)) == hash) { "内容已变化，本次结果未保存，请重新分析" }
                    val record = if (question == null) AnalysisRecord(hash, system, selectedTemplate.name, text.length,
                        listOf(AnalysisMessage("assistant", suggestions?.summary ?: answer)),
                        suggestedTitle = suggestions?.title.orEmpty(), suggestedTags = suggestions?.tags.orEmpty())
                    else old!!.copy(messages = old.messages + AnalysisMessage("user", question) + AnalysisMessage("assistant", answer), updatedAt = System.currentTimeMillis())
                    // 小 JSON 在主线程原子落盘，取消/转私密不会与 IO 写入交错使明文记录复活。
                    AnalysisStore.save(context, key, record)
                    records[key] = record; loaded.add(key); errors.remove(key)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (versions[key] == version) errors[key] = (e.localizedMessage ?: "AI 分析失败，请重试")
                    .replace(connection.apiKey.takeIf { it.isNotBlank() } ?: "\u0000", "[密钥]")
            }
            finally {
                if (versions[key] == version) {
                    states.remove(key); jobs.remove(key); automatic.remove(key)
                    if (key !in records) {
                        loading.remove(key)?.cancel(); loaded.remove(key); load(key)
                    }
                }
            }
        }
        jobs[key] = job
        job.start()
    }
}
