package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 补拍此前没覆盖、但界面本身已经能画出来的状态。不发网络，不关 FLAG_SECURE。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatchupVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val originalFetcher = Images.fetcher
    @After fun restoreFetcher() { Images.fetcher = originalFetcher }

    private fun show(vm: VaultViewModel, scale: Float = 1f, dark: Boolean = false) {
        rule.runOnIdle {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    VaultTheme(dark) { VaultScreen(vm) }
                }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/catchup-v2"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 72, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(0x1F, 0x55, 0x78))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test fun snapshotCoverSearchAndMultipleLinks() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val cover = "https://example.com/catchup-cover.png"
        val bytes = pngBytes()
        Images.fetcher = { bytes }
        val id = runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            dao.insert(Bookmark(
                url = "https://sspai.com/post/reading",
                canonical = "https://sspai.com/post/reading",
                title = "把一篇长文留在本地",
                notes = "先看结构，再决定要不要翻译。",
                summary = "页面描述留在快照上面，确认详情不是只有标题。",
                image = cover,
                tags = "阅读"
            ))
        }
        val body = "第一段把读过的话留下来。\n\n" + "后面还有足够长的正文，用来确认展开全文、复制和删除快照都还在。".repeat(8)
        Snapshots.save(rule.activity, id, body)
        Snapshots.saveTranslation(rule.activity, id, "这是译文。原文还在，切换之后才显示这一段。")
        rule.runOnIdle { vm.fetchEnabled(true); vm.reload(); vm.clearMessage() }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.any { it.id == id } } }
        rule.runOnIdle { vm.show(vm.items.first { it.id == id }); vm.clearMessage() }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.currentSnapshot != null && vm.currentTranslation != null } }
        show(vm)
        rule.onNode(hasText("第一段把读过的话留下来", substring = true)).assertExists()
        capture("gap-body")
        rule.onNodeWithText("展开全文阅读").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("原文").assertIsDisplayed()
        rule.onNodeWithText("译文").assertIsDisplayed()
        rule.onNodeWithText("删除快照").performScrollTo().assertIsDisplayed()
        capture("gap-snapshot")
        rule.onNodeWithText("译文").performClick()
        rule.onNodeWithText("这是译文。原文还在，切换之后才显示这一段。").performScrollTo().assertIsDisplayed()
        capture("gap-snapshot-translated")
        rule.onNodeWithContentDescription("更多操作").performClick()
        rule.onNodeWithText("分享链接").assertIsDisplayed()
        rule.onNodeWithText("归档").assertIsDisplayed()
        capture("gap-detail-menu")
        rule.onNodeWithText("标为已读").performClick()
        rule.runOnIdle { vm.closeDetail(); vm.tab(0); vm.clearMessage() }
        rule.onNodeWithTag("collection-search").assertIsDisplayed()
        capture("gap-collection-search")
        rule.runOnIdle { vm.tab(1); vm.clearMessage() }
        rule.onNodeWithContentDescription("展开搜索").performClick()
        rule.onNodeWithContentDescription("关闭搜索").assertIsDisplayed()
        capture("gap-notes-search")
        rule.runOnIdle {
            vm.tab(0)
            vm.edit(Draft())
            vm.extractText("https://sspai.com/post/a https://www.zhihu.com/question/1")
            vm.clearMessage()
        }
        rule.onNodeWithText("把 2 条全部收藏").assertIsDisplayed()
        capture("gap-multi-link")
        rule.runOnIdle { vm.edit(vm.draft!!.copy(title = "还没保存的标题")) }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("放弃本次编辑？").assertIsDisplayed()
        rule.onNodeWithText("继续编辑").performClick()
        rule.onNodeWithText("保存收藏").assertIsDisplayed()
        rule.runOnIdle { vm.cancel() }
    }

    @Test fun conversationAndDarkSnapshot() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val id = runBlocking {
            VaultDb.get(rule.activity).bookmarks().insert(Bookmark(
                url = "https://sspai.com/post/talk",
                canonical = "https://sspai.com/post/talk",
                title = "对话所围绕的那篇文章"
            ))
        }
        rule.runOnIdle { vm.reload() }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.any { it.id == id } } }
        rule.runOnIdle {
            vm.analysis.records["b-$id"] = AnalysisRecord(
                "catchup", "system", "阅读", 80,
                listOf(
                    AnalysisMessage("assistant", "用一段话把这篇内容收成可以核对的结论。"),
                    AnalysisMessage("user", "有没有一个这周就能做的小动作？"),
                    AnalysisMessage("assistant", "先挑一篇已经读过的文章，写下一条可以在周五前验证的行动。")
                )
            )
            vm.openAnalysisConversation("b-$id")
            vm.clearMessage()
        }
        show(vm)
        rule.onNodeWithText("有没有一个这周就能做的小动作？").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送追问").assertIsDisplayed()
        capture("gap-conversation")
        show(vm, dark = true)
        capture("gap-conversation-dark")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun narrowLargeSnapshotActionsStayReachable() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val id = runBlocking {
            VaultDb.get(rule.activity).bookmarks().insert(Bookmark(
                url = "https://sspai.com/post/narrow",
                canonical = "https://sspai.com/post/narrow",
                title = "窄屏上的一篇长快照"
            ))
        }
        Snapshots.save(rule.activity, id, "短正文。" + "再补一段，让展开阅读在大字号下仍然找得到。".repeat(12))
        rule.runOnIdle { vm.reload() }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.any { it.id == id } } }
        rule.runOnIdle { vm.show(vm.items.first { it.id == id }) }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.currentSnapshot != null } }
        show(vm, scale = 2f)
        rule.onNodeWithText("展开全文阅读").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("复制正文").performScrollTo().assertIsDisplayed()
        capture("gap-snapshot-narrow-large")
    }
}
