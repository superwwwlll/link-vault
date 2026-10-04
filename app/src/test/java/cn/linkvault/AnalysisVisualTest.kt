package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalysisVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val realTransport = Analysis.transport
    @After fun restore() { Analysis.transport = realTransport }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File(System.getProperty("vault.screenshots"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun configureVerifySummarizeAndContinueConversation() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val calls = AtomicInteger()
        Analysis.transport = { _, _, _ ->
            val text = when (calls.incrementAndGet()) {
                1 -> "OK"
                2 -> "## 一句话概括\n用结构化笔记把信息变成可行动的知识。\n\n## 值得关注\n- 与你的工作方向相关：先选一个真实场景尝试。\n- 建议记录依据和不确定性，不把推断当事实。"
                else -> "## 下一步\n选一篇本周工作相关的文章，列出一个可以验证的行动。"
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", text)))).toString()
        }
        rule.runOnIdle { vm.tab(3); vm.theme("light"); vm.analysis.auto(false); vm.selectAiProvider(AiProviders.default); vm.clearAiKey() }
        rule.onNodeWithText("你的 API Key").performScrollTo()
        rule.onNode(hasSetTextAction() and hasText("你的 API Key")).performTextInput("fake-visual-key")
        rule.runOnIdle { check(vm.aiKey == "fake-visual-key") }
        rule.onNodeWithText("测试连接").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { vm.aiVerified && !vm.aiTesting } }
        rule.onNodeWithText("连接验证通过，可以使用总结和对话").performScrollTo().assertIsDisplayed()
        capture("analysis-01-provider-verified")
        rule.onNodeWithText("我的工作、兴趣与关注方向").performScrollTo()
        rule.onNode(hasSetTextAction() and hasText("我的工作、兴趣与关注方向")).performTextInput("关注工作中可行动的新方法")
        rule.onNodeWithText("提示词框架（可直接编辑）").performScrollTo().assertIsDisplayed()
        capture("analysis-02-framework")

        val db = VaultDb.get(rule.activity)
        val id = runBlocking { db.notes().insert(Note(text = "结构化阅读\n从文章中提取核心观点，并判断哪些内容与工作相关。")) }
        rule.waitUntil(10_000) { rule.runOnIdle { vm.notes.rows.any { it.id == id } } }
        rule.runOnIdle { vm.analysis.clear("n-$id"); vm.notes.open(vm.notes.rows.first { it.id == id }) }
        rule.onNodeWithText("分析这篇内容").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { vm.analysis.records["n-$id"] != null } }
        rule.onNodeWithText("用结构化笔记把信息变成可行动的知识。").performScrollTo().assertIsDisplayed()
        capture("analysis-03-summary")
        rule.onNodeWithText("围绕这篇内容继续追问").performScrollTo()
        rule.onNode(hasSetTextAction() and hasText("围绕这篇内容继续追问")).performTextInput("给我一个具体行动")
        rule.onNodeWithText("发送追问").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { vm.analysis.records["n-$id"]?.messages?.size == 3 } }
        rule.onNodeWithText("选一篇本周工作相关的文章，列出一个可以验证的行动。").performScrollTo().assertIsDisplayed()
        capture("analysis-04-conversation")
        rule.runOnIdle { check(calls.get() == 3); vm.notes.edit(null) }
        runBlocking { db.notes().delete(id) }
        rule.runOnIdle { vm.analysis.clear("n-$id") }
    }
    @Test fun bookmarkSuggestionsRequireExplicitConfirmationAndPreserveExistingTags() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val db = VaultDb.get(rule.activity)
        Analysis.transport = { _, _, body ->
            val system = JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content")
            val content = if (system.contains("JSON 对象"))
                """{"summary":"## 核心观点\n从阅读中提取可验证的行动。","title":"结构化阅读：让收藏成为行动线索","tags":["阅读方法","知识管理"]}""" else "OK"
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString()
        }
        rule.runOnIdle {
            vm.analysis.auto(false); vm.selectAiProvider(AiProviders.default); vm.aiKey("fake-visual-key"); vm.testAiConnection(); vm.theme("light")
        }
        rule.waitUntil(10_000) { rule.runOnIdle { vm.aiVerified } }
        val id = runBlocking { db.bookmarks().insert(Bookmark(url = "https://example.com/action", canonical = "https://example.com/action", title = "原收藏标题", tags = "已有标签")) }
        Snapshots.save(rule.activity, id, "从阅读中提取观点，按照工作方向筛选关注点，然后验证行动。")
        rule.waitUntil(10_000) { rule.runOnIdle { vm.items.any { it.id == id } } }
        rule.runOnIdle { vm.analysis.clear("b-$id"); vm.show(vm.items.first { it.id == id }) }
        rule.onNodeWithText("分析这篇内容").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { vm.analysis.records["b-$id"] != null && vm.analysis.states["b-$id"] == null } }
        rule.onNodeWithText("应用标题与标签").performScrollTo().assertIsDisplayed()
        check(runBlocking { db.bookmarks().byId(id)!!.title } == "原收藏标题")
        capture("analysis-05-title-tags-suggestions")
        rule.onNodeWithText("应用标题与标签").performClick()
        rule.onNodeWithText("确认应用").performClick()
        rule.waitUntil(10_000) { rule.runOnIdle { vm.items.first { it.id == id }.title == "结构化阅读：让收藏成为行动线索" } }
        check(runBlocking { db.bookmarks().byId(id)!!.tags } == "已有标签,阅读方法,知识管理")
        rule.onAllNodesWithText("结构化阅读：让收藏成为行动线索").onFirst().performScrollTo().assertIsDisplayed()
        capture("analysis-06-applied-title-tags")
        rule.runOnIdle { vm.closeDetail(); vm.analysis.clear("b-$id") }
        runBlocking { db.bookmarks().delete(id) }
        Snapshots.delete(rule.activity, id)
    }
}
