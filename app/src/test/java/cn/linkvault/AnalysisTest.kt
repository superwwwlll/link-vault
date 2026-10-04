package cn.linkvault

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnalysisTest {
    private lateinit var app: Application
    private lateinit var db: VaultDb
    private lateinit var scope: CoroutineScope
    private lateinit var ai: AnalysisController
    private val config = Translate.Config(AiProviders.default.endpoint, "fake-test-key", AiProviders.default.model)
    private val realTransport = Analysis.transport
    private var verified = true
    private val calls = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, VaultDb::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        ai = AnalysisController(app, db, scope, { config }, { verified }, { false })
        Analysis.transport = { _, _, _ -> calls.incrementAndGet(); response("## 核心观点\n有用的总结") }
    }
    @After fun teardown() {
        val job = scope.coroutineContext[Job]!!
        scope.cancel()
        runBlocking { withTimeout(10_000) { job.join() } }
        db.close(); Analysis.transport = realTransport; Dispatchers.resetMain()
    }
    private fun response(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", text)))).toString()
    private fun waitUntil(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!condition()) delay(5) } }
    private fun note(text: String = "研究方法与应用"): Note = runBlocking {
        val n = Note(text = text)
        n.copy(id = db.notes().insert(n)).also { ai.clear("n-${it.id}") }
    }

    @Test fun sourceIsBoundedAndHistoryKeepsOnlyCompleteRecentPairs() {
        val messages = listOf(AnalysisMessage("assistant", "总结")) + (1..10).flatMap { listOf(AnalysisMessage("user", "问$it"), AnalysisMessage("assistant", "答$it")) }
        val record = AnalysisRecord("hash", "旧框架", "模板", 30_000, messages)
        val body = JSONObject(Analysis.request(config, "系统", "字".repeat(30_000), record, "继续问"))
        val sent = body.getJSONArray("messages")
        assertTrue(sent.getJSONObject(1).getString("content").contains("提供前24000字"))
        assertFalse(sent.getJSONObject(1).getString("content").contains("字".repeat(24_001)))
        assertEquals(16, sent.length())
        assertEquals("问5", sent.getJSONObject(3).getString("content"))
        assertEquals("继续问", sent.getJSONObject(15).getString("content"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }
    @Test fun recordsRoundTripAndAreSeparatedByKind() {
        val record = AnalysisRecord("hash", "提示词", "研究分析", 100, listOf(AnalysisMessage("assistant", "总结\n换行")), 123)
        assertEquals(record, AnalysisStore.decode(AnalysisStore.encode(record)))
        AnalysisStore.save(app, "b-901", record)
        AnalysisStore.save(app, "n-901", record.copy(template = "笔记"))
        assertEquals("研究分析", AnalysisStore.read(app, "b-901")!!.template)
        AnalysisStore.delete(app, "b-901")
        assertNull(AnalysisStore.read(app, "b-901"))
        assertEquals("笔记", AnalysisStore.read(app, "n-901")!!.template)
        AnalysisStore.delete(app, "n-901")
    }
    @Test fun autoIsOffUntilEnabledAndUnchangedSavesDoNotBillAgain() {
        val n = note(); val key = "n-${n.id}"
        ai.afterNoteSave(n)
        assertEquals(0, calls.get())
        ai.auto(true); ai.afterNoteSave(n)
        waitUntil { ai.records[key] != null && ai.states[key] == null }
        assertEquals(1, calls.get())
        ai.afterNoteSave(n)
        waitUntil { ai.states[key] == null }
        assertEquals(1, calls.get())
    }
    @Test fun summaryAndFollowupPersistAndUseOriginalFramework() {
        val n = note(); val key = "n-${n.id}"
        ai.profile("关注实验方法"); ai.run(key)
        waitUntil { ai.records[key] != null && ai.states[key] == null }
        val original = ai.records[key]!!.system
        ai.profile("另一个方向")
        var sent = ""
        Analysis.transport = { _, _, body -> sent = body; response("下一步建议") }
        ai.run(key, "怎么应用？")
        waitUntil { ai.records[key]!!.messages.size == 3 }
        assertEquals(original, JSONObject(sent).getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("怎么应用？", ai.records[key]!!.messages[1].text)
        val reopened = AnalysisController(app, db, scope, { config }, { true }, { false })
        reopened.load(key)
        waitUntil { reopened.records[key] != null }
        assertEquals(ai.records[key], reopened.records[key])
    }
    @Test fun changedBodyRequiresFreshSummaryAndFailureKeepsOldConversation() {
        val n = note(); val key = "n-${n.id}"
        ai.run(key); waitUntil { ai.records[key] != null && ai.states[key] == null }
        val old = ai.records[key]
        runBlocking { db.notes().update(n.copy(text = "改过的新正文")) }
        ai.run(key, "追问")
        waitUntil { ai.states[key] == null }
        assertTrue(ai.errors[key]!!.contains("正文已变化"))
        assertEquals(1, calls.get()); assertEquals(old, ai.records[key])
        Analysis.transport = { _, _, _ -> error("服务不可用 fake-test-key") }
        ai.run(key); waitUntil { ai.states[key] == null }
        assertEquals(old, ai.records[key]); assertFalse(ai.errors[key]!!.contains("fake-test-key"))
    }
    @Test fun privateNotesAndUnverifiedConfigsNeverUpload() {
        val n = note(); val key = "n-${n.id}"
        verified = false; ai.run(key)
        waitUntil { ai.states[key] == null }
        assertEquals(0, calls.get())
        verified = true
        runBlocking { db.notes().update(n.copy(text = "", secret = true, cipher = "cipher")) }
        ai.run(key); waitUntil { ai.states[key] == null }
        assertEquals(0, calls.get()); assertTrue(ai.errors[key]!!.contains("私密"))
    }
    @Test fun makingNotePrivateWhileAnswerIsPendingDeletesAllAiRecords() {
        val n = note(); val key = "n-${n.id}"
        ai.run(key); waitUntil { ai.records[key] != null && ai.states[key] == null }
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        Analysis.transport = { _, _, _ -> started.countDown(); release.await(5, TimeUnit.SECONDS); response("不该落盘") }
        ai.run(key, "追问")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val secret = n.copy(text = "", cipher = "cipher", secret = true)
        runBlocking { db.notes().update(secret) }
        ai.afterNoteSave(secret); release.countDown()
        runBlocking { delay(100) }
        assertNull(ai.records[key]); assertNull(AnalysisStore.read(app, key))
    }
    @Test fun anotherSaveDuringAnalysisProcessesLatestBodyRatherThanDroppingIt() {
        val n = note(); val key = "n-${n.id}"
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        Analysis.transport = { _, _, body ->
            if (requests.incrementAndGet() == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            response(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
        }
        ai.auto(true); ai.afterNoteSave(n)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val latest = n.copy(text = "最新保存的正文")
        runBlocking { db.notes().update(latest) }
        ai.afterNoteSave(latest); release.countDown()
        waitUntil { ai.records[key]?.hash == Analysis.hash(latest.text) && ai.states[key] == null }
        assertTrue(ai.records[key]!!.messages.first().text.contains(latest.text))
        assertEquals(2, requests.get())
    }
    @Test fun disablingAutoCancelsQueuedRequestsWithoutUploadingThem() {
        val first = note("第一篇"); val second = note("第二篇")
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        Analysis.transport = { _, _, _ -> calls.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); response("总结") }
        ai.auto(true); ai.afterNoteSave(first)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        ai.afterNoteSave(second); ai.auto(false); release.countDown()
        runBlocking { delay(100) }
        assertEquals(1, calls.get())
        assertTrue(ai.states.isEmpty()); assertTrue(ai.records.isEmpty())
    }
    @Test fun missingBookmarkBodyDoesNotGuessFromUrl() {
        val id = runBlocking { db.bookmarks().insert(Bookmark(url = "https://example.com", canonical = "https://example.com")) }
        val key = "b-$id"; ai.clear(key); Snapshots.delete(app, id)
        ai.run(key); waitUntil { ai.states[key] == null }
        assertEquals(0, calls.get()); assertTrue(ai.errors[key]!!.contains("没有正文"))
    }
    @Test fun templatesAreEditablePersistentAndNeverAllDeleted() {
        ai.addTemplate(); ai.updateTemplate("我的模板", "按工作价值排序")
        val reopened = AnalysisController(app, db, scope, { config }, { true }, { false })
        assertEquals("我的模板", reopened.active.name)
        assertEquals("按工作价值排序", reopened.active.prompt)
        repeat(10) { reopened.removeTemplate() }
        assertEquals(1, reopened.templates.size)
    }
    @Test fun apiErrorsEmptyAndTruncatedOutputsAreNotSavedAsSummaries() {
        listOf("不是JSON", "{}", "{\"choices\":[]}", response(""), response("未完").replace("stop", "length")).forEach {
            assertTrue(runCatching { Analysis.reply(it) }.isFailure)
        }
        assertEquals("完整结果", Analysis.reply(response("完整结果")))
    }
    @Test fun presetsAndValidationFingerprintsAreSafe() {
        assertEquals("deepseek", AiProviders.default.id)
        AiProviders.presets.filter { it.id != "custom" }.forEach { assertTrue(it.endpoint.startsWith("https://")); assertTrue(it.endpoint.endsWith("/chat/completions")); assertTrue(it.console.startsWith("https://")) }
        assertNotEquals(AiProviders.fingerprint(config), AiProviders.fingerprint(config.copy(model = "other")))
        assertNotEquals(AiProviders.fingerprint(config), AiProviders.fingerprint(config.copy(apiKey = "other")))
        val custom = AiProviders.adapt(config.copy(endpoint = "https://example.com/chat/completions"), JSONObject())
        assertEquals(0, custom.length())
    }
    @Test fun bookmarkAnalysisSuggestsTitleAndTagsButOnlyConfirmationChangesBookmark() {
        val original = Bookmark(url = "https://example.com/study", canonical = "https://example.com/study", title = "原来的标题", notes = "我的备注", tags = "Research,收藏", pinned = true)
        val id = runBlocking { db.bookmarks().insert(original) }
        val key = "b-$id"; ai.clear(key); Snapshots.save(app, id, "关于结构化阅读和实践方法的正文")
        Analysis.transport = { _, _, body ->
            val system = JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("JSON 对象"))
            response("""{"summary":"## 核心观点\n值得尝试结构化阅读。","title":"结构化阅读：把知识变成行动","tags":["research","阅读方法","知识管理"]}""")
        }
        ai.run(key); waitUntil { ai.records[key] != null && ai.states[key] == null }
        val suggested = ai.records[key]!!
        assertEquals("结构化阅读：把知识变成行动", suggested.suggestedTitle)
        assertEquals(3, suggested.suggestedTags.size)
        assertFalse(suggested.messages.first().text.contains("\"summary\""))
        assertEquals("原来的标题", runBlocking { db.bookmarks().byId(id)!!.title })
        ai.applySuggestions(key); waitUntil { ai.states[key] == null }
        val updated = runBlocking { db.bookmarks().byId(id)!! }
        assertEquals(suggested.suggestedTitle, updated.title)
        assertEquals("Research,收藏,阅读方法,知识管理", updated.tags)
        assertEquals(original.notes, updated.notes); assertTrue(updated.pinned)
        assertEquals(original.createdAt, updated.createdAt); assertEquals(original.updatedAt, updated.updatedAt)
        Analysis.transport = { _, _, body ->
            assertFalse(JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content").contains("JSON 对象"))
            response("可以这样应用")
        }
        ai.run(key, "继续追问")
        waitUntil { ai.records[key]!!.messages.size == 3 || ai.errors[key] != null }
        assertNull(ai.errors[key]); assertEquals(3, ai.records[key]!!.messages.size)
        assertEquals(ai.records[key], AnalysisStore.read(app, key))
    }
    @Test fun changedBookmarkRejectsStaleTitleSuggestions() {
        val id = runBlocking { db.bookmarks().insert(Bookmark(url = "https://example.com", canonical = "https://example.com", title = "原标题")) }
        val key = "b-$id"; ai.clear(key); Snapshots.save(app, id, "原文")
        Analysis.transport = { _, _, _ -> response("""{"summary":"总结","title":"建议标题","tags":["主题"]}""") }
        ai.run(key); waitUntil { ai.records[key] != null && ai.states[key] == null }
        runBlocking { db.bookmarks().setTitle(id, "用户刚修改的标题") }
        ai.applySuggestions(key); waitUntil { ai.states[key] == null }
        assertTrue(ai.errors[key]!!.contains("内容已变化"))
        assertEquals("用户刚修改的标题", runBlocking { db.bookmarks().byId(id)!!.title })
        assertEquals("", runBlocking { db.bookmarks().byId(id)!!.tags })
    }
    @Test fun suggestionsHaveStrictBoundsAndOldRecordsRemainReadable() {
        val wrapped = "```json\n{\"summary\":\"总结\",\"title\":\"标题\",\"tags\":[\"研究\",\"研究\"]}\n```"
        assertEquals(listOf("研究"), Analysis.suggestions(wrapped).tags)
        listOf("总结而非JSON", "{}", """{"summary":"总结","title":"标题","tags":["a,b"]}""", """{"summary":"总结","title":"标题","tags":[]}""").forEach {
            assertTrue(runCatching { Analysis.suggestions(it) }.isFailure)
        }
        val record = AnalysisRecord("hash", "system", "模板", 100, listOf(AnalysisMessage("assistant", "总结")))
        val old = JSONObject(AnalysisStore.encode(record)).apply { remove("suggestedTitle"); remove("suggestedTags") }
        assertEquals(record, AnalysisStore.decode(old.toString()))
    }
}
