package cn.linkvault

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TelegramSettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsCurrentValuesAndAppearanceChangesCollectionDensity() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        rule.runOnIdle {
            vm.theme("dark")
            vm.fetchEnabled(false)
            vm.analysis.auto(false)
            vm.aiEnabled(false)
            vm.compactCollection(false)
            vm.tab(3)
        }
        rule.onNodeWithText("LINK VAULT").assertDoesNotExist()
        rule.onNodeWithText("深色").assertIsDisplayed()
        rule.onNodeWithText("自动分析未开启").assertIsDisplayed()
        rule.onNodeWithText("当前 ${vm.installedVersionName}").assertIsDisplayed()
        rule.onNodeWithText("可能计费", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("外观").performScrollTo().performClick()
        rule.onNodeWithText("紧凑").performClick()
        rule.runOnIdle { assertTrue(vm.compactCollection) }
        rule.onNodeWithText("舒适").performClick()
        rule.runOnIdle { assertFalse(vm.compactCollection) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("当前 ${vm.installedVersionName}").assertIsDisplayed()
    }
}
