package cn.linkvault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureFindUiTest {
    @get:Rule(order = 0)
    val quietStartup = object : ExternalResource() {
        override fun before() {
            ApplicationProvider.getApplicationContext<android.app.Application>()
                .getSharedPreferences("settings", 0).edit().putBoolean("autoCheck", false).commit()
        }
    }

    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun pasteSaveFindOpenEditAndReturnWithQueryIntact() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val url = "https://example.com/field-notes/seed-library"
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
        }
        rule.runOnIdle {
            vm.cancel()
            vm.closeDetail()
            vm.tab(0)
            vm.scope(0)
            vm.search("")
            vm.filter("")
            vm.reload()
            vm.fetchEnabled(false)
            vm.analysis.auto(false)
            vm.dismissClipboard()
        }
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.loading && vm.items.isEmpty() } }
        rule.onNodeWithTag("collection-list").performScrollToNode(hasText("收藏第一条链接"))
        rule.onNodeWithText("收藏第一条链接").assertIsDisplayed()
        capture("capture-empty-320")
        rule.onNodeWithTag("collection-add").performClick()
        rule.onNodeWithTag("editor-title").assertDoesNotExist()
        rule.onNodeWithText("保存收藏").assertIsNotEnabled()
        rule.runOnIdle {
            val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("示例", "社区种子图书馆 $url"))
        }
        rule.onNodeWithText("粘贴并提取").performClick()
        capture("capture-pasted-320")
        rule.onNodeWithText("保存收藏").performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.busy && vm.items.size == 1 && vm.detailId != null } }
        rule.onNodeWithContentDescription("返回").performClick()

        rule.onNodeWithTag("collection-search").performTextInput("seed-library")
        val bookmarkId = rule.runOnIdle { vm.items.single().id }
        rule.onNodeWithTag("collection-bookmark-$bookmarkId").performClick()
        rule.onNodeWithText("打开原文").performClick()
        rule.runOnIdle {
            val intent = shadowOf(rule.activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
        }
        rule.onNodeWithContentDescription("编辑收藏").performClick()
        rule.onNodeWithTag("editor-title").performTextReplacement("社区种子图书馆的春季借阅指南")
        rule.onNodeWithTag("editor-notes").performTextInput("带纸袋去交换种子")
        rule.onNodeWithText("保存收藏").performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.busy && vm.items.single().notes == "带纸袋去交换种子" } }
        rule.onNodeWithContentDescription("更多操作").performClick()
        rule.onNodeWithText("置顶").performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.single().pinned } }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithTag("collection-search").assertTextContains("seed-library")
        rule.onNodeWithTag("collection-search").assertIsNotFocused()
        rule.onNodeWithTag("collection-bookmark-$bookmarkId").assertIsDisplayed()
        rule.runOnIdle {
            val item = vm.items.single()
            assertEquals("社区种子图书馆的春季借阅指南", item.title)
            assertEquals(0L, item.fetchedAt)
            assertNull(vm.currentSnapshot)
        }
        capture("capture-search-return-320")
    }

    @Test fun manuallyReplacingExtractedLinkClearsOldBatch() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        rule.onNodeWithTag("collection-add").performClick()
        rule.onNodeWithTag("editor-url").performTextInput("https://example.com/a https://example.com/b")
        rule.onNodeWithText("从输入中提取").performClick()
        rule.onNodeWithText("把 2 条全部收藏").assertIsDisplayed()
        rule.onNodeWithTag("editor-url").performTextReplacement("https://example.com/replacement")
        rule.onNodeWithText("把 2 条全部收藏").assertDoesNotExist()
        rule.runOnIdle { assertTrue(vm.detected.isEmpty()); vm.cancel() }
    }

    private fun capture(name: String) {
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/screenshots"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
