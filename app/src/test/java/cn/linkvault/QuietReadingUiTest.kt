package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 首页密度、两档底栏、详情先后和快速收藏。不发网络，不调用付费分析。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuietReadingUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val originalFetcher = Images.fetcher
    @After fun restoreFetcher() { Images.fetcher = originalFetcher }

    private fun vm(): VaultViewModel = ViewModelProvider(rule.activity)[VaultViewModel::class.java]

    private fun clear() {
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            VaultDb.get(rule.activity).notes().clear()
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/quiet-use"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun waitIdle(check: () -> Boolean) {
        rule.waitUntil(20_000) { rule.runOnIdle { check() } }
    }

    @Test fun homeShowsFiveItemsAndSecondaryEntriesStayReachable() {
        clear()
        val vm = vm()
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            repeat(5) { index ->
                val n = index + 1
                dao.insert(Bookmark(
                    url = "https://example.com/post/$n",
                    canonical = "https://example.com/post/$n",
                    title = "第${n}条收藏",
                    tags = "阅读",
                    notes = if (n == 5) "只在有备注时出现" else "",
                    createdAt = 1_700_000_000_000L + n
                ))
            }
        }
        rule.runOnIdle {
            vm.tab(0); vm.scope(0); vm.filter(""); vm.search(""); vm.fetchEnabled(false); vm.aiEnabled(false); vm.analysis.auto(false)
            vm.reload(); vm.clearMessage()
        }
        waitIdle { !vm.loading && vm.items.size == 5 }
        (1..5).forEach { rule.onNodeWithText("第${it}条收藏").assertIsDisplayed() }
        rule.onNodeWithText("只在有备注时出现").assertIsDisplayed()
        rule.onNode(hasText("收藏") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("笔记") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("标签") and hasClickAction()).assertDoesNotExist()
        rule.onNode(hasText("设置") and hasClickAction()).assertDoesNotExist()
        capture("quiet-home")
        listOf("未归档", "未读", "已读", "归档").forEachIndexed { index, _ ->
            rule.onNodeWithTag("collection-scope-$index").performScrollTo().performClick().assertIsSelected()
        }
        rule.onNodeWithTag("collection-scope-0").performScrollTo().performClick()
        rule.onNodeWithContentDescription("展开标签筛选").performClick()
        rule.onNodeWithText("管理标签").assertIsDisplayed()
        capture("quiet-tags-entry")
        rule.onNodeWithText("管理标签").performClick()
        rule.onNodeWithText("阅读").assertIsDisplayed()
        capture("quiet-tags")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("第1条收藏").assertIsDisplayed()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("备份与更新").performScrollTo().assertIsDisplayed()
        capture("quiet-settings")
        rule.onNodeWithText("备份与更新").performScrollTo().performClick()
        rule.onNodeWithText("回收站").performScrollTo().performClick()
        rule.onNodeWithText("回收站为空").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNode(hasText("笔记") and hasClickAction()).performClick()
        rule.onNodeWithContentDescription("新建笔记").assertIsDisplayed()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("外观").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("还没有笔记").assertIsDisplayed()
    }

    @Test fun detailReadsSnapshotBeforeActionsAndHighlightsOpenWithoutOne() {
        clear()
        val vm = vm()
        val id = runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.insert(Bookmark(
                url = "https://sspai.com/post/quiet",
                canonical = "https://sspai.com/post/quiet",
                title = "没有正文的一篇",
                notes = "备注留在正文后面",
                tags = "阅读",
                summary = "页面描述不该挡在正文前面。",
                image = "https://example.com/cover.png",
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L
            ))
        }
        rule.runOnIdle {
            vm.tab(0); vm.fetchEnabled(true); vm.aiEnabled(false); vm.analysis.auto(false); vm.reload(); vm.clearMessage()
        }
        waitIdle { vm.items.size == 1 }
        Images.fetcher = { ByteArray(0) }
        rule.onNodeWithText("没有正文的一篇").performClick()
        rule.onNodeWithText("打开原文").assertIsDisplayed()
        rule.onNodeWithContentDescription("复制原链接").assertIsDisplayed()
        rule.onNodeWithText("离线正文快照").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("封面图").assertCountEquals(0)
        val title = rule.onNodeWithText("没有正文的一篇").fetchSemanticsNode().boundsInRoot.top
        val notes = rule.onNodeWithText("备注留在正文后面").fetchSemanticsNode().boundsInRoot.top
        assertTrue("备注盖过了标题", title < notes)
        capture("quiet-detail-empty")
        val article = "这一段是已经留在本地的正文，打开详情时应该先读到它。\n\n" + "后面还有足够的段落，用来把管理按钮留在正文后面。".repeat(8)
        Snapshots.save(rule.activity, id, article)
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("没有正文的一篇").performClick()
        waitIdle { vm.currentSnapshot != null }
        val bodyNode = rule.onNode(hasText("这一段是已经留在本地的正文", substring = true), useUnmergedTree = true)
        bodyNode.assertIsDisplayed()
        capture("quiet-detail-body")
        rule.onNodeWithText("抓取页面信息").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("删除这条收藏").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("打开原文").assertIsDisplayed()
        rule.onNodeWithText("译成中文").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("编辑收藏").assertIsDisplayed()
        Snapshots.delete(rule.activity, id)
    }

    @Test fun sharePrefillsTitleAndEditKeepsExistingFields() {
        clear()
        val vm = vm()
        val id = runBlocking {
            VaultDb.get(rule.activity).bookmarks().insert(Bookmark(
                url = "https://example.com/kept",
                canonical = "https://example.com/kept",
                title = "原标题",
                notes = "原备注还在",
                tags = "阅读,方法",
                summary = "原页面描述",
                image = "https://example.com/kept.png",
                fetchedAt = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L
            ))
        }
        rule.runOnIdle {
            vm.tab(0); vm.fetchEnabled(false); vm.aiEnabled(false); vm.analysis.auto(false); vm.reload(); vm.clearMessage()
        }
        waitIdle { vm.items.size == 1 }
        rule.runOnIdle { vm.open(vm.items.single { it.id == id }) }
        rule.onNodeWithText("编辑收藏").assertIsDisplayed()
        rule.onNodeWithText("原备注还在").assertIsDisplayed()
        rule.runOnIdle { vm.edit(vm.draft!!.copy(title = "改过的标题")) }
        rule.onNodeWithText("保存收藏").performClick()
        waitIdle { vm.draft == null && !vm.busy }
        val kept = runBlocking { VaultDb.get(rule.activity).bookmarks().byId(id)!! }
        assertEquals("改过的标题", kept.title)
        assertEquals("原备注还在", kept.notes)
        assertEquals("阅读,方法", kept.tags)
        assertEquals("原页面描述", kept.summary)
        assertEquals("https://example.com/kept.png", kept.image)
        assertEquals(1_700_000_000_000L, kept.fetchedAt)

        rule.runOnIdle { vm.receive(Shared("https://sspai.com/post/quick-save", "分享里已经有的标题")) }
        assertEquals("分享里已经有的标题", vm.draft?.title)
        assertEquals("", vm.draft?.notes)
        assertEquals("", vm.draft?.tags)
        rule.onNodeWithText("保存收藏").assertIsDisplayed()
        capture("quiet-quick")
        rule.onNodeWithText("保存收藏").performClick()
        waitIdle { vm.draft == null && !vm.busy && vm.items.any { it.title == "分享里已经有的标题" } }

        rule.runOnIdle { vm.receive(Shared("https://mp.weixin.qq.com/s/AbCdEfGhIjKlMnOp")) }
        assertEquals("微信公众号", vm.draft?.title)
        rule.runOnIdle { vm.cancel() }

        rule.runOnIdle { vm.receive(Shared("看看 https://a.example/one 和 https://b.example/two")) }
        assertTrue(vm.notice!!.contains("仅取第一个"))
        rule.onNodeWithText("把 2 条全部收藏").performScrollTo().performClick()
        waitIdle { vm.draft == null && !vm.busy }
        val titles = runBlocking { VaultDb.get(rule.activity).bookmarks().all() }.map { it.canonical }
        assertTrue(titles.contains("https://a.example/one"))
        assertTrue(titles.contains("https://b.example/two"))

        rule.runOnIdle { vm.receive(Shared("https://sspai.com/post/quick-save", "再收一次")) }
        rule.onNodeWithText("保存收藏").performClick()
        waitIdle { vm.error != null }
        assertTrue(vm.error!!.contains("已收藏"))
        assertNull(runBlocking { VaultDb.get(rule.activity).bookmarks().all() }.find { it.title == "再收一次" })
    }
}
