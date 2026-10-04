package cn.linkvault

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionCompactUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun seed(): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.all().forEach { dao.delete(it.id) }
            repeat(16) { index ->
                dao.insert(Bookmark(url = "https://x.com/example/status/${index + 1}",
                    canonical = "https://x.com/example/status/${index + 1}", title = "收藏示例 $index",
                    summary = "网页摘要仍保留", tags = "很长的研究方法标签,技术,阅读,灵感,方法,工作",
                    createdAt = 100L + index))
            }
        }
        rule.runOnIdle { vm.tab(0); vm.scope(0); vm.search(""); vm.filter(""); vm.setSort(0); vm.reload() }
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.loading && vm.items.size == 16 } }
        return vm
    }

    @Test fun compactEntrancesAndScrollHidingRemainUsable() {
        seed()
        rule.onNodeWithTag("collection-result-count").assertTextEquals("16 条")
        rule.onNodeWithContentDescription("展开搜索").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("示例 15")
        rule.onNodeWithTag("collection-result-count").assertTextEquals("1 条")
        rule.onNodeWithContentDescription("清空搜索").performClick()
        rule.onNodeWithContentDescription("关闭搜索").performClick()
        rule.onNodeWithContentDescription("展开标签筛选").performClick()
        rule.onNodeWithContentDescription("收起标签筛选").performClick()
        rule.onNodeWithTag("collection-more").performClick()
        rule.onNodeWithText("随机翻一篇").assertIsDisplayed()
        rule.onNodeWithText("多选当前结果").performClick()
        rule.onNodeWithText("已选择 16 条").assertIsDisplayed()
        rule.onNodeWithContentDescription("取消多选").performClick()
        rule.onAllNodesWithText("X").onFirst().assertIsDisplayed()
        rule.onAllNodesWithContentDescription("查看全部 6 个标签").onFirst().performClick()
        rule.onNodeWithText("全部标签（6）").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithTag("collection-list").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithText("我的收藏").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("收藏链接").assertCountEquals(0)
        rule.onNodeWithTag("collection-list").performScrollToIndex(0)
        rule.waitForIdle()
        rule.onNodeWithText("我的收藏").assertIsDisplayed()
    }

    @Test fun doubleFontScaleKeepsCountAndAllTagsReachable() {
        val vm = seed()
        rule.runOnIdle {
            val item = vm.items.first()
            vm.analysis.records["b-${item.id}"] = AnalysisRecord("hash", "system", "quick", 100,
                listOf(AnalysisMessage("assistant", "# 总结\n这是一条短结论。")), suggestedTitle = "建议标题")
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    VaultTheme(dark = false) { CollectionPage(vm, onShare = {}, onDelete = {}, onCopy = {}) }
                }
            }
        }
        rule.onNodeWithTag("collection-result-count").assertIsDisplayed().assertTextEquals("16 条")
        rule.onNodeWithText("AI 摘要 · 这是一条短结论。").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("AI · 标题 / 标签建议待确认").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("查看全部 6 个标签").onFirst().performClick()
        rule.onNodeWithText("全部标签（6）").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
    }
}
