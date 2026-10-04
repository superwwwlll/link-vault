package cn.linkvault

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalysisConversationUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val realTransport = Analysis.transport
    @After fun restore() { Analysis.transport = realTransport }

    private fun show(messages: List<AnalysisMessage>): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        Analysis.transport = { _, _, _ -> error("浏览聊天页不得请求网络") }
        rule.runOnIdle {
            vm.analysis.auto(false)
            vm.analysis.records["n-900001"] = AnalysisRecord("hash", "system", "测试框架", 100, messages)
            rule.activity.setContent { VaultTheme(dark = false) { AnalysisConversationPage(vm, "n-900001", {}) } }
        }
        return vm
    }

    @Test fun summaryIsExpandableAndAllHistoryRemainsReachableWithFixedComposer() {
        show(listOf(AnalysisMessage("assistant", "原总结全文")) + (1..12).flatMap { index ->
            listOf(AnalysisMessage("user", "历史问题 $index"), AnalysisMessage("assistant", "历史答案 $index"))
        })
        rule.onNodeWithText("发送追问").assertIsDisplayed()
        rule.onNodeWithText("返回").assertIsDisplayed()
        rule.onNodeWithTag("analysis-conversation-history").performScrollToIndex(1)
        rule.onNodeWithText("历史问题 1").assertIsDisplayed()
        rule.onNodeWithTag("analysis-conversation-history").performScrollToIndex(0)
        rule.onNodeWithText("查看原总结").performClick()
        rule.onNodeWithText("原总结全文").assertIsDisplayed()
        rule.onNodeWithText("发送追问").assertIsDisplayed()
        rule.onNodeWithTag("analysis-conversation-history").performScrollToIndex(24)
        rule.onNodeWithText("历史答案 12").assertIsDisplayed()
        rule.onNodeWithText("返回").assertIsDisplayed()
    }

    @Test fun stoppingAndFailureKeepDraftAndDoNotAppendMessages() {
        val vm = show(listOf(AnalysisMessage("assistant", "原总结全文")))
        val input = rule.onNode(hasSetTextAction() and hasText("围绕这篇内容继续追问"))
        input.performTextInput("保留这个问题")
        rule.runOnIdle { vm.analysis.states["n-900001"] = "等待分析…" }
        rule.onNodeWithText("等待分析…").assertIsDisplayed()
        rule.onNodeWithText("停止生成").performClick()
        input.assertTextContains("保留这个问题")
        rule.runOnIdle { vm.analysis.errors["n-900001"] = "模拟失败，请重试" }
        rule.onNodeWithText("模拟失败，请重试").assertIsDisplayed()
        input.assertTextContains("保留这个问题")
        rule.runOnIdle { assertEquals(1, vm.analysis.records["n-900001"]!!.messages.size) }
    }
}
