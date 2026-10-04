package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TelegramIntegrationUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val realTransport = Analysis.transport
    @After fun restoreTransport() { Analysis.transport = realTransport }

    private fun seed(fontScale: Float = 1f): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            val created = System.currentTimeMillis()
            repeat(15) { index ->
                val url = "https://example.com/ui/$index"
                dao.insert(Bookmark(url = url, canonical = url, title = "T3 Code：统一管理多个编程 Agent 的工作台 $index",
                    summary = "比较多 Agent 管理与远程接管的适用场景，不把产品宣传当作验证结论。",
                    tags = "AI 编程 Agent,多 Agent 管理,开发工具,远程接管,工具评测", createdAt = created - (15 - index) * 1000L))
            }
        }
        rule.runOnIdle {
            vm.notes.edit(null); vm.closeDetail(); vm.tab(0); vm.scope(0); vm.filter(""); vm.search(""); vm.setSort(0)
            vm.compactCollection(true); vm.analysis.auto(false); vm.fetchEnabled(false); vm.theme("light"); vm.reload(); vm.clearMessage()
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { VaultTheme(false) { VaultScreen(vm) } }
            }
        }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.size == 15 && !vm.loading } }
        return vm
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots"), "$name.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    @Test fun realScreenHidesFabDuringSelectionAndProvidesUndo() {
        val vm = seed()
        capture("ui-162-collection-compact")
        val first = rule.runOnIdle { vm.visible().first() }
        rule.onNodeWithTag("collection-bookmark-${first.id}").performTouchInput { longClick() }
        rule.onNodeWithText("已选择 1 条").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("收藏链接").assertCountEquals(0)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("收藏链接").assertIsDisplayed()
        rule.runOnIdle { vm.delete(first.id) }
        rule.waitUntil(10_000) { rule.runOnIdle { !vm.busy && vm.deletionUndo != null } }
        rule.onNodeWithText("撤销").assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { !vm.busy && vm.items.any { it.id == first.id } } }
        rule.runOnIdle { vm.clearMessage(); vm.compactCollection(false) }
        capture("ui-162-collection-comfortable")
        rule.onNodeWithText("设置").performClick()
        capture("ui-162-settings-home")
    }

    @Test fun largeTextConversationKeepsComposerOutsideHistoryAndBackRestoresDetail() {
        val vm = seed(1.5f)
        capture("ui-162-collection-large-text")
        val item = rule.runOnIdle { vm.visible().first() }
        val key = "b-${item.id}"
        val history = listOf(AnalysisMessage("assistant", "# 原总结\n保留文档式阅读。")) + (1..20).flatMap {
            listOf(AnalysisMessage("user", "问题 $it"), AnalysisMessage("assistant", "## 回答 $it\n${"完整历史可以阅读。".repeat(6)}"))
        }
        rule.runOnIdle {
            vm.show(item)
            vm.analysis.records[key] = AnalysisRecord("hash", "system", "模板", 100, history)
            vm.openAnalysisConversation(key)
        }
        rule.onNodeWithText("AI 对话").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("围绕这篇内容继续追问")).assertIsDisplayed()
        rule.onAllNodesWithContentDescription("收藏链接").assertCountEquals(0)
        rule.onNodeWithTag("analysis-conversation-history").performScrollToIndex(1)
        rule.onNode(hasSetTextAction() and hasText("围绕这篇内容继续追问")).assertIsDisplayed().performTextInput("保留尚未发送的问题")
        capture("ui-162-conversation-large-text")
        rule.runOnIdle { vm.receive(Shared("https://example.com/incoming", "外部分享")) }
        rule.onNodeWithText("AI 对话").assertIsDisplayed()
        rule.runOnIdle { check(vm.draft == null); check(vm.pending != null) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("收藏详情").assertIsDisplayed()
        rule.runOnIdle { check(vm.detailId == item.id); check(vm.analysisConversationKey == null) }
        rule.runOnIdle { vm.openAnalysisConversation(key) }
        rule.onNode(hasSetTextAction() and hasText("围绕这篇内容继续追问")).assertTextContains("保留尚未发送的问题")
    }

    @Test fun failedRequestSurvivesBackAndReentryThenOnlySuccessfulRetryClearsDraft() {
        val vm = seed()
        fun response(text: String) = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
            .put("finish_reason", "stop").put("message", org.json.JSONObject().put("content", text)))).toString()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Analysis.transport = { _, _, _ -> calls.incrementAndGet(); response("OK") }
        rule.runOnIdle { vm.selectAiProvider(AiProviders.default); vm.aiKey("fake-chat-test-key"); vm.testAiConnection() }
        rule.waitUntil(10_000) { rule.runOnIdle { vm.aiVerified && !vm.aiTesting } }
        val text = "公开笔记正文：讨论结构化阅读的方法。"
        val id = runBlocking { VaultDb.get(rule.activity).notes().insert(Note(text = text)) }
        val key = "n-$id"
        rule.runOnIdle {
            vm.analysis.clear(key)
            vm.analysis.records[key] = AnalysisRecord(Analysis.hash(text), "系统框架", "模板", text.length, listOf(AnalysisMessage("assistant", "总结")))
            vm.openAnalysisConversation(key)
        }
        Analysis.transport = { _, _, _ -> calls.incrementAndGet(); error("模拟请求失败") }
        rule.onNode(hasSetTextAction() and hasText("围绕这篇内容继续追问")).performTextInput("失败后保留的问题")
        rule.onNodeWithText("发送追问").performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { vm.analysis.errors[key] != null && vm.analysis.states[key] == null } }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { vm.openAnalysisConversation(key) }
        rule.onNode(hasSetTextAction() and hasText("围绕这篇内容继续追问")).assertTextContains("失败后保留的问题")
        Analysis.transport = { _, _, _ -> calls.incrementAndGet(); response("重试后成功回答") }
        rule.onNodeWithText("发送追问").performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { vm.analysis.records[key]?.messages?.size == 3 && vm.conversationDraft(key).question.isEmpty() } }
        rule.runOnIdle { check(calls.get() == 3); check(vm.analysis.errors[key] == null); vm.closeAnalysisConversation(); vm.analysis.clear(key) }
        runBlocking { VaultDb.get(rule.activity).notes().delete(id) }
    }
}
