package cn.linkvault

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun openSettings(): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        rule.runOnIdle { vm.tab(3) }
        return vm
    }

    private fun back() {
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test fun homeHasOnlyEntriesAndSystemBackReturnsBeforeLeavingSettings() {
        val vm = openSettings()
        rule.onNodeWithText("收藏与笔记存于本机").assertIsDisplayed()
        rule.onNodeWithText("你的 API Key").assertDoesNotExist()
        rule.onNodeWithText("导出收藏").assertDoesNotExist()
        rule.onNodeWithText("抓取").performScrollTo().performClick()
        rule.onNodeWithText("页面信息抓取").assertIsDisplayed()
        back()
        rule.runOnIdle { assertEquals(3, vm.tab) }
        rule.onNodeWithText("收藏与笔记存于本机").assertIsDisplayed()
        back()
        rule.runOnIdle { assertEquals(0, vm.tab) }
    }

    @Test fun aiAdvancedAddressIsFullyEditableAndKeyCanBeRevealed() {
        val vm = openSettings()
        rule.runOnIdle { vm.aiKey("settings-test-key") }
        rule.onNodeWithText("AI 接口").performScrollTo().performClick()
        rule.onNodeWithText("API 地址（完整 chat/completions 地址）").assertDoesNotExist()
        rule.onNodeWithText("显示").performClick()
        rule.onNodeWithText("隐藏").assertIsDisplayed().performClick()
        rule.onNodeWithText("高级配置（API 地址）").performScrollTo().performClick()
        val endpoint = "https://example.com/chat/completions?route=" + "a".repeat(310)
        rule.onNode(hasSetTextAction() and hasText("API 地址（完整 chat/completions 地址）"))
            .performScrollTo().performTextReplacement(endpoint)
        rule.runOnIdle { assertEquals(endpoint, vm.aiEndpoint) }
        rule.onNodeWithText("清除密钥").performScrollTo().performClick()
        rule.onNodeWithText("清除密钥？").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals("settings-test-key", vm.aiKey) }
    }

    @Test fun readingTemplateDeletionRequiresConfirmationAndEmptyProfileIsAllowed() {
        val vm = openSettings()
        rule.runOnIdle { vm.analysis.profile(""); vm.analysis.auto(false) }
        rule.onNodeWithText("阅读助手").performScrollTo().performClick()
        rule.onNodeWithText("未填写关注方向也可以分析；填写后，结果会更贴近你的需求。").assertIsDisplayed()
        rule.onNodeWithText("模板名称").assertDoesNotExist()
        rule.onNodeWithText("编辑模板").performScrollTo().performClick()
        rule.runOnIdle { vm.analysis.addTemplate() }
        val count = rule.runOnIdle { vm.analysis.templates.size }
        rule.onNodeWithText("删除当前模板").performScrollTo().performClick()
        rule.onNodeWithText("删除这个模板？").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(count, vm.analysis.templates.size) }
        rule.onNodeWithText("删除当前模板").performScrollTo().performClick()
        rule.onNodeWithText("删除", substring = false).performClick()
        rule.runOnIdle { assertEquals(count - 1, vm.analysis.templates.size) }
    }

    @Test fun translationDetailsAreCollapsedAndSharedInterfaceReturnsToTranslation() {
        openSettings()
        rule.onNodeWithText("翻译").performScrollTo().performClick()
        rule.onNodeWithText("一篇最多 24,000 字", substring = true).assertDoesNotExist()
        rule.onNodeWithText("详细隐私与用量说明").performScrollTo().performClick()
        rule.onNodeWithText("一篇最多 24,000 字", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("配置 AI 接口").performScrollTo().performClick()
        rule.onNodeWithText("你的 API Key").performScrollTo().assertIsDisplayed()
        back()
        rule.onNodeWithText("启用翻译").assertIsDisplayed()
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithText("收藏与笔记存于本机").assertIsDisplayed()
    }

    @Test fun encryptedBackupPasswordDialogRemainsInBackupSubpage() {
        openSettings()
        rule.onNodeWithText("备份与更新").performScrollTo().performClick()
        rule.onNodeWithText("加密导出").performScrollTo().performClick()
        rule.onNodeWithText("设置加密备份密码").assertIsDisplayed()
        rule.onNodeWithText("选择保存位置").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("导入加密备份").performScrollTo().performClick()
        rule.onNodeWithText("输入加密备份密码").assertIsDisplayed()
        rule.onNodeWithText("选择备份文件").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        back()
        rule.onNodeWithText("收藏与笔记存于本机").assertIsDisplayed()
    }
}
