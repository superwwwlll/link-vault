package cn.linkvault

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionTelegramUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var selecting = false
    private var copied = false

    private fun seed(fontScale: Float = 1f): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.all().forEach { dao.delete(it.id) }
            repeat(20) { index ->
                dao.insert(Bookmark(url = "https://example.com/$index", canonical = "https://example.com/$index",
                    title = "收藏 $index", summary = "两行以内的网页摘要", createdAt = index.toLong()))
            }
        }
        rule.runOnIdle {
            vm.closeDetail(); vm.scope(0); vm.filter(""); vm.search(""); vm.setSort(0); vm.compactCollection(true); vm.reload()
        }
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.loading && vm.items.size == 20 } }
        rule.runOnIdle {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    VaultTheme(dark = false) {
                        CollectionPage(vm, onShare = {}, onDelete = {}, onCopy = { copied = true },
                            onSelectionChange = { selecting = it })
                    }
                }
            }
        }
        return vm
    }

    @Test fun toolbarStaysVisibleAndLongPressSelectsWithoutOpeningMenu() {
        val vm = seed()
        val firstId = rule.runOnIdle { vm.items.maxBy { it.createdAt }.id }
        rule.onNodeWithTag("collection-bookmark-$firstId").performTouchInput { longClick() }
        rule.onNodeWithText("已选择 1 条").assertIsDisplayed()
        rule.onNodeWithTag("collection-item-actions").assertDoesNotExist()
        rule.runOnIdle { assertTrue(selecting) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertFalse(selecting) }
        rule.onNodeWithTag("collection-list").performScrollToIndex(15)
        rule.onNodeWithText("我的收藏").assertIsDisplayed()
        rule.onNodeWithTag("collection-search-toggle").assertIsDisplayed()
        rule.onNodeWithTag("collection-scope-0").assertIsSelected()
    }

    @Test fun bottomSheetCopyDoesNotNavigateAndEditorOpensAfterDismissal() {
        val vm = seed()
        val firstId = rule.runOnIdle { vm.items.maxBy { it.createdAt }.id }
        rule.onNodeWithTag("collection-item-menu-$firstId").performClick()
        rule.onNodeWithTag("collection-item-actions").assertIsDisplayed()
        rule.onNodeWithText("复制链接").performClick()
        rule.onNodeWithTag("collection-item-actions").assertDoesNotExist()
        rule.runOnIdle { assertTrue(copied); assertNull(vm.detailId) }
        rule.onNodeWithTag("collection-item-menu-$firstId").performClick()
        rule.onNodeWithText("编辑", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("collection-item-actions").assertDoesNotExist()
        rule.runOnIdle { assertNull(vm.detailId); assertEquals(firstId, vm.draft?.id) }
    }

    @Test fun largeFontNarrowScreenKeepsArchivedFilterReachable() {
        seed(fontScale = 2f)
        rule.onNodeWithTag("collection-scope-3").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText("归档为空").assertIsDisplayed()
        rule.onNodeWithContentDescription("展开搜索").performClick()
        rule.onNodeWithTag("collection-search").assertIsDisplayed()
    }
}
