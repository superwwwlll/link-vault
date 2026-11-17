package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTrashVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun show(vm: VaultViewModel, scale: Float = 1f, dark: Boolean = false) {
        rule.runOnIdle {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    VaultTheme(dark) { VaultScreen(vm) }
                }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/settings-v2"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun vm(): VaultViewModel = ViewModelProvider(rule.activity)[VaultViewModel::class.java]

    private fun seedTrash(vm: VaultViewModel) {
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            val now = System.currentTimeMillis()
            dao.insert(Bookmark(url = "https://sspai.com/post/shelf", canonical = "https://sspai.com/post/shelf", title = "书架上的一本旧书", deletedAt = now))
            dao.insert(Bookmark(url = "https://www.zhihu.com/question/1", canonical = "https://www.zhihu.com/question/1", title = "一条准备恢复的记录", deletedAt = now - 1_000))
        }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.trashItems.size == 2 } }
    }

    @Test fun settingsHomeBackupAndTrash() {
        val vm = vm()
        seedTrash(vm)
        rule.runOnIdle { vm.tab(3); vm.clearMessage() }
        show(vm)
        rule.onNodeWithText("备份与更新").assertIsDisplayed()
        rule.onNodeWithText("外观").assertIsDisplayed()
        capture("settings-v2-home")
        rule.onNodeWithText("外观").performScrollTo().performClick()
        rule.onNodeWithText("跟随系统").assertIsDisplayed()
        rule.onNodeWithText("紧凑").assertIsDisplayed()
        capture("settings-v2-appearance")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("AI 接口").performScrollTo().performClick()
        rule.onNodeWithText("你的 API Key").assertIsDisplayed()
        rule.onNodeWithText("测试连接").assertIsDisplayed()
        capture("settings-v2-ai")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("备份与更新").performScrollTo().performClick()
        rule.onNodeWithText("回收站").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("导出收藏").performScrollTo().assertIsDisplayed()
        capture("settings-v2-backup")
        rule.onNodeWithText("回收站").performScrollTo().performClick()
        rule.onNodeWithText("书架上的一本旧书").assertIsDisplayed()
        rule.onAllNodesWithText("恢复").onFirst().assertIsDisplayed()
        capture("trash-v2-list")
        rule.onAllNodesWithText("永久删除").onFirst().performClick()
        rule.onNodeWithText("永久删除这条收藏？").assertIsDisplayed()
        capture("trash-v2-confirm")
        rule.onNodeWithText("取消").performClick()
        rule.onAllNodesWithText("恢复").onFirst().performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { vm.trashItems.size == 1 && !vm.busy } }
        rule.runOnIdle { vm.clearMessage() }
        rule.onNodeWithText("一条准备恢复的记录").assertIsDisplayed()
        capture("trash-v2-restored")
    }

    @Test fun darkSettingsAndTrash() {
        val vm = vm()
        seedTrash(vm)
        rule.runOnIdle { vm.tab(3); vm.clearMessage() }
        show(vm, dark = true)
        capture("settings-v2-dark")
        rule.runOnIdle { vm.openTrash(); vm.clearMessage() }
        rule.onNodeWithText("书架上的一本旧书").assertIsDisplayed()
        capture("trash-v2-dark")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun narrowLargeSettings() {
        val vm = vm()
        rule.runOnIdle { vm.tab(3); vm.clearMessage() }
        show(vm, scale = 2f)
        rule.onNodeWithText("备份与更新").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("未设密钥", substring = true).performScrollTo().assertIsDisplayed()
        capture("settings-v2-narrow-large")
        rule.onNodeWithText("备份与更新").performScrollTo().performClick()
        rule.onNodeWithText("回收站").performScrollTo().assertIsDisplayed()
        capture("settings-v2-backup-narrow-large")
    }
}
