package cn.linkvault

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class TagOrderingTest {
    @Test fun countSortUsesStableCaseInsensitiveNameTieBreak() {
        val input = listOf("zebra" to 2, "Alpha" to 2, "Agent 管理" to 5)
        assertEquals(listOf("Agent 管理", "Alpha", "zebra"), visibleTags(input, "", TagSort.COUNT).map { it.first })
        assertEquals(listOf("Agent 管理", "Alpha", "zebra"), visibleTags(input, "", TagSort.NAME).map { it.first })
        assertEquals(listOf("Agent 管理" to 5), visibleTags(input, "AGENT", TagSort.COUNT))
        assertTrue(visibleTags(input, "not found", TagSort.NAME).isEmpty())
        assertEquals("zebra", input.first().first)
    }

    @Test fun nameSortDoesNotDependOnCount() {
        assertEquals(listOf("Alpha", "zebra"), visibleTags(listOf("zebra" to 9, "Alpha" to 1), "", TagSort.NAME).map { it.first })
    }

    @Test fun renameValidationMatchesExistingSingleTagRules() {
        assertTrue(validTagRename("原名", " Agent 管理 "))
        assertTrue(validTagRename("原名", "已有标签"))
        listOf("", "  ", "原名", " 原名 ", "A,B", "A，B", "A;B", "A；B", "A\nB", "A,", "a".repeat(1001)).forEach {
            assertFalse("Unexpected valid rename: $it", validTagRename("原名", it))
        }
        assertFalse(validTagRename("Agent", "AGENT"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagsPageTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: VaultViewModel
    private val longName = "多 Agent 管理与研究资料：完整名称不应被截断"


    @Before fun setUp() {
        vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.all().forEach { dao.delete(it.id) }
            dao.insert(Bookmark(url = "https://example.com/tags/1", canonical = "https://example.com/tags/1", title = "一", tags = "Alpha,zebra,$longName"))
            dao.insert(Bookmark(url = "https://example.com/tags/2", canonical = "https://example.com/tags/2", title = "二", tags = "zebra", archived = true))
        }
        rule.runOnIdle { vm.reload(); vm.tab(2) }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.size == 2 } }
    }

    @Test fun searchSortAndTagNavigationRemainAvailable() {
        rule.onNodeWithText(longName).assertIsDisplayed()
        rule.onNodeWithText("排序：数量最多").performClick()
        rule.onNodeWithText("名称 A–Z").performClick()
        rule.onNodeWithText("排序：名称 A–Z").assertIsDisplayed()
        rule.onNodeWithContentDescription("展开搜索").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("AGENT")
        rule.onNodeWithText(longName).assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertDoesNotExist()
        rule.onNodeWithContentDescription("清空搜索").performClick()
        rule.onNodeWithContentDescription("关闭搜索").performClick()
        rule.onNodeWithText("Alpha").performClick()
        rule.runOnIdle { assertEquals("Alpha", vm.filter); assertEquals(0, vm.tab) }
    }

    @Test fun mergeRequiresDestinationAndConfirmationAndIncludesArchivedItems() {
        rule.onNodeWithContentDescription("标签操作：zebra").performClick()
        rule.onNodeWithText("合并到…").performClick()
        rule.onNodeWithText("确认合并").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Alpha").performScrollTo().performClick()
        rule.onNodeWithText("将「zebra」合并到「Alpha」").assertIsDisplayed()
        // Selecting a destination must not mutate any data before confirmation.
        assertTrue(runBlocking { VaultDb.get(rule.activity).bookmarks().all() }.all { parseTags(it.tags).contains("zebra") })
        rule.onNodeWithText("确认合并").performScrollTo().performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.busy && vm.items.all { "zebra" !in parseTags(it.tags) } } }
        val rows = runBlocking { VaultDb.get(rule.activity).bookmarks().all() }
        assertEquals(2, rows.size)
        assertTrue(rows.all { parseTags(it.tags).count { tag -> tag == "Alpha" } == 1 })
        assertTrue(rows.any { it.archived })
    }

    @Test fun renameAndDeleteRequireExplicitActions() {
        rule.onNodeWithContentDescription("标签操作：Alpha").performClick()
        rule.onNodeWithText("重命名").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Beta")
        rule.onNodeWithText("重命名", substring = false).performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.busy && vm.items.any { "Beta" in parseTags(it.tags) } } }
        rule.onNodeWithContentDescription("标签操作：Beta").performClick()
        rule.onNodeWithText("删除标签").performClick()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithContentDescription("标签操作：Beta").performClick()
        rule.onNodeWithText("删除标签").performClick()
        rule.onNodeWithText("删除标签", substring = false).performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.busy && vm.items.all { "Beta" !in parseTags(it.tags) } } }
        assertEquals(2, runBlocking { VaultDb.get(rule.activity).bookmarks().all().size })
    }

    @Test fun editorSystemBackPreservesExpandedSearchWithoutChangingData() {
        rule.onNodeWithContentDescription("展开搜索").performClick()
        rule.onNodeWithContentDescription("标签操作：Alpha").performClick()
        rule.onNodeWithText("重命名").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("未保存的新名称")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(2, vm.tab) }
        rule.onNodeWithContentDescription("关闭搜索").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("未保存的新名称").assertDoesNotExist()
    }

    @Test fun largeTextKeepsFullNameAnd48DpMenuTarget() {
        rule.runOnIdle {
            rule.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    MaterialTheme { Column(Modifier.fillMaxSize()) { TagsPage(vm) } }
                }
            }
        }
        rule.onNodeWithTag("tags-list").performScrollToNode(hasText(longName))
        rule.onNodeWithText(longName).assertIsDisplayed()
        rule.onNodeWithContentDescription("标签操作：$longName")
            .assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            .performClick()
        rule.onNodeWithText("合并到…").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h800dp-xxhdpi")
    fun narrowLargeTextSearchHasFullWidthAndCanClearAndClose() {
        rule.runOnIdle {
            rule.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    MaterialTheme { Column(Modifier.fillMaxSize()) { TagsPage(vm) } }
                }
            }
        }
        rule.onNodeWithContentDescription("展开搜索").performClick()
        rule.onNode(hasSetTextAction()).assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(270f)).performTextInput("Agent")
        rule.onNodeWithText(longName).assertIsDisplayed()
        rule.onNodeWithContentDescription("清空搜索").assertIsDisplayed().performClick()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭搜索").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("展开搜索").assertIsDisplayed()
    }
}
