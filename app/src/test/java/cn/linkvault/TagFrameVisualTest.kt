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
class TagFrameVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun millis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun seed(scale: Float = 1f, dark: Boolean = false): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            dao.insert(Bookmark(
                url = "https://sspai.com/post/reading-shelf",
                canonical = "https://sspai.com/post/reading-shelf",
                title = "少数派：把读过的长文留在自己的书架上",
                notes = "先看结构，再决定要不要翻译。",
                tags = "阅读,笔记,方法,界面,很长的研究方法标签",
                pinned = true,
                createdAt = millis(2026, 10, 3)
            ))
            dao.insert(Bookmark(
                url = "https://www.zhihu.com/question/reading-log",
                canonical = "https://www.zhihu.com/question/reading-log",
                title = "如何给十年后的自己留一份还能检索的阅读记录",
                tags = "设计",
                createdAt = millis(2026, 8, 18)
            ))
        }
        rule.runOnIdle {
            vm.notes.edit(null); vm.closeDetail(); vm.edit(null); vm.tab(0); vm.scope(0); vm.filter(""); vm.search(""); vm.setSort(0)
            vm.compactCollection(true); vm.analysis.auto(false); vm.fetchEnabled(false); vm.clearMessage(); vm.dismissClipboard()
            vm.reload()
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    VaultTheme(dark) { VaultScreen(vm) }
                }
            }
        }
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.loading && vm.items.size == 2 } }
        rule.runOnIdle { vm.clearMessage(); vm.dismissClipboard() }
        return vm
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/tag-frame"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun assertFilterHitsDoNotOverlap() {
        val readingNode = rule.onNodeWithTag("collection-filter-阅读").performScrollTo()
        val noteNode = rule.onNodeWithTag("collection-filter-笔记").performScrollTo()
        val reading = readingNode.fetchSemanticsNode().boundsInRoot
        val note = noteNode.fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        assertTrue("筛选标签可点高度不足 $reading", reading.height >= 48f * density - 1f)
        assertTrue("筛选标签可点宽度不足 $reading", reading.width >= 48f * density - 1f)
        val separated = reading.right <= note.left + 0.5f || note.right <= reading.left + 0.5f ||
            reading.bottom <= note.top + 0.5f || note.bottom <= reading.top + 0.5f
        assertTrue("筛选标签可点区域重叠 reading=$reading note=$note", separated)
    }

    @Test fun lightTagsFilterDetailAndEditor() {
        val vm = seed()
        capture("tags-home")
        rule.onNodeWithContentDescription("展开标签筛选").performClick()
        assertFilterHitsDoNotOverlap()
        rule.onNodeWithTag("collection-filter-设计").performClick().assertIsSelected()
        rule.onNodeWithTag("collection-result-count").assertTextEquals("# 设计 · 1 条")
        capture("tags-filter")
        rule.onNodeWithTag("collection-filter-设计").performClick()
        rule.onNodeWithText("少数派：把读过的长文留在自己的书架上").performClick()
        rule.onNodeWithText("很长的研究方法标签").assertIsDisplayed()
        capture("tags-detail")
        rule.onNodeWithText("阅读").performClick()
        rule.onNodeWithTag("collection-result-count").assertTextEquals("# 阅读 · 1 条")
        rule.onNodeWithText("少数派：把读过的长文留在自己的书架上").performClick()
        rule.onNodeWithContentDescription("编辑收藏").performClick()
        rule.onNodeWithContentDescription("移除标签 方法").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(parseTags(vm.draft!!.tags).none { it == "方法" }) }
        capture("tags-editor")
    }

    @Test fun darkTags() {
        seed(dark = true)
        capture("tags-home-dark")
        rule.onNodeWithContentDescription("展开标签筛选").performClick()
        assertFilterHitsDoNotOverlap()
        capture("tags-filter-dark")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun narrowLargeTags() {
        seed(scale = 2f)
        capture("tags-home-narrow-large")
        rule.onAllNodesWithContentDescription("查看全部 5 个标签").onFirst().performScrollTo().assertExists()
        rule.onNodeWithContentDescription("展开标签筛选").performClick()
        assertFilterHitsDoNotOverlap()
        rule.onNodeWithTag("collection-filter-设计").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("collection-result-count").assertTextEquals("# 设计 · 1 条")
        capture("tags-filter-narrow-large")
        rule.onNodeWithTag("collection-filter-设计").performClick()
        rule.onNodeWithText("少数派：把读过的长文留在自己的书架上").performClick()
        rule.onNodeWithContentDescription("编辑收藏").performClick()
        rule.onNodeWithContentDescription("移除标签 很长的研究方法标签").performScrollTo().assertIsDisplayed()
        capture("tags-editor-narrow-large")
    }
}
