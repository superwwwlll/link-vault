package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionHomeVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun millis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun seed(scale: Float = 1f, dark: Boolean = false): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            val samples = listOf(
                Bookmark(url = "https://sspai.com/post/reading-shelf", canonical = "https://sspai.com/post/reading-shelf",
                    title = "少数派：把读过的长文留在自己的书架上", tags = "阅读", pinned = true, createdAt = millis(2026, 10, 3)),
                Bookmark(url = "https://www.zhihu.com/question/reading-log", canonical = "https://www.zhihu.com/question/reading-log",
                    title = "如何给十年后的自己留一份还能检索的阅读记录，而不是又一个打不开的收藏夹", tags = "笔记,方法", createdAt = millis(2026, 8, 18)),
                Bookmark(url = "https://36kr.com/p/bookstores", canonical = "https://36kr.com/p/bookstores",
                    title = "城市里还在营业的独立书店，靠什么撑过这一年", read = true, createdAt = millis(2026, 6, 2)),
                Bookmark(url = "https://github.com/http/connection-cost", canonical = "https://github.com/http/connection-cost",
                    title = "一次连接里还剩下什么成本", tags = "网络", createdAt = millis(2025, 12, 11)),
                Bookmark(url = "https://zh.wikipedia.org/wiki/Printing", canonical = "https://zh.wikipedia.org/wiki/Printing",
                    title = "活版印刷的条目比想象中更好读", read = true, createdAt = millis(2025, 4, 9)),
                Bookmark(url = "https://juejin.cn/post/thin-list", canonical = "https://juejin.cn/post/thin-list",
                    title = "把收藏从堆叠收成能扫一眼的列表", tags = "界面", createdAt = millis(2026, 11, 2))
            )
            samples.forEach { dao.insert(it) }
        }
        rule.runOnIdle {
            vm.notes.edit(null); vm.closeDetail(); vm.tab(0); vm.scope(0); vm.filter(""); vm.search(""); vm.setSort(0)
            vm.compactCollection(true); vm.analysis.auto(false); vm.fetchEnabled(false); vm.clearMessage(); vm.dismissClipboard()
            vm.reload()
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    VaultTheme(dark) { VaultScreen(vm) }
                }
            }
        }
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.loading && vm.items.size == 6 } }
        rule.runOnIdle { vm.clearMessage(); vm.dismissClipboard() }
        return vm
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/home-v2"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun assertHomeChrome(vm: VaultViewModel) {
        listOf("未归档", "未读", "已读", "归档").forEachIndexed { index, label ->
            val scope = rule.onNodeWithTag("collection-scope-$index").fetchSemanticsNode().boundsInRoot
            val text = rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("collection-scope-$index")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$label 被裁切 text=$text scope=$scope", text.left >= scope.left - 1f && text.right <= scope.right + 1f && text.top >= scope.top - 1f && text.bottom <= scope.bottom + 1f)
        }
        val id = rule.runOnIdle { vm.visible().first().id }
        val add = rule.onNodeWithContentDescription("收藏链接").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("collection-bookmark-$id").fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithTag("collection-item-menu-$id").fetchSemanticsNode().boundsInRoot
        assertTrue("新增盖住了收藏 add=$add row=$row", add.bottom <= row.top + 1f)
        assertTrue("新增盖住了菜单 add=$add menu=$menu", menu.top >= add.bottom - 1f)
        rule.onNodeWithText("我的收藏").assertIsDisplayed()
        rule.onNodeWithTag("collection-result-count").assertIsDisplayed()
    }

    @Test fun regularHome() {
        val vm = seed()
        assertHomeChrome(vm)
        capture("home-v2-regular")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun narrowLargeTypeHome() {
        val vm = seed(scale = 2f)
        assertHomeChrome(vm)
        capture("home-v2-narrow-large")
    }

    @Test fun darkHome() {
        val vm = seed(dark = true)
        assertHomeChrome(vm)
        capture("home-v2-dark")
    }
}
