package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Android rendering via Robolectric. Not an emulator/device screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        rule.waitForIdle()
        val file = File(System.getProperty("vault.screenshots"), "$name.png")
        file.parentFile!!.mkdirs()
        // PixelCopy/forceRedraw requires a real Window compositor. Draw the actual Android
        // view hierarchy through the native Skia-backed Canvas instead (not a mockup).
        rule.runOnIdle {
            val root = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: rule.activity.window.decorView
            check(root.width > 0 && root.height > 0)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun navigateAndRenderAllMajorScreens() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val samples = listOf(
            Bookmark(url = "https://x.com/Astronaut_1216/status/2097610542127223251?s=20", canonical = "https://x.com/Astronaut_1216/status/2097610542127223251", title = "值得慢慢看的宇宙", notes = "把浩瀚星空收进口袋。留一些时间，给日常之外的好奇心。", tags = "灵感,稍后读", updatedAt = 1788950400000),
            Bookmark(url = "https://developer.android.com/compose", canonical = "https://developer.android.com/compose", title = "用 Compose 构建更好的 Android 界面", notes = "关于状态、布局与设计系统的一份学习笔记。", tags = "技术,设计", updatedAt = 1788864000000),
            Bookmark(url = "https://example.com/design", canonical = "https://example.com/design", title = "好的设计，让内容呼吸", notes = "留白、层级、节奏——简单不等于单调。", tags = "设计,灵感", updatedAt = 1788777600000)
        )
        runBlocking { val dao = VaultDb.get(rule.activity).bookmarks(); dao.all().forEach { dao.delete(it.id) }; samples.forEach { dao.insert(it) } }
        rule.runOnIdle { vm.reload() }
        rule.waitUntil(20000) { rule.runOnIdle { vm.items.size == 3 } }
        rule.runOnIdle { vm.theme("light") }
        rule.onNodeWithText("我的收藏").assertIsDisplayed()
        capture("01-collection-light")
        rule.onNodeWithText("值得慢慢看的宇宙").performClick()
        rule.onNodeWithText("收藏详情").assertIsDisplayed()
        capture("02-detail-light")
        rule.onNodeWithContentDescription("编辑收藏").performClick()
        rule.onNodeWithText("保存收藏").assertIsDisplayed()
        capture("03-editor-light")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("继续编辑").performClick()
        rule.onNodeWithText("保存收藏").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("放弃编辑").performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("标签").performClick()
        rule.onAllNodesWithText("2 条收藏").onFirst().assertExists()
        capture("04-tags-light")
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("导出收藏").assertExists()
        capture("05-settings-light")
        rule.onNodeWithText("深色").performClick()
        rule.onNodeWithText("收藏").performClick()
        capture("06-collection-dark")
        val uri = Uri.parse("content://cn.linkvault.test/backup.json")
        Shadows.shadowOf(rule.activity.contentResolver).registerInputStream(uri, Backup.encode(samples).inputStream())
        rule.runOnIdle { vm.prepareImport(uri) }
        rule.waitUntil(20000) { rule.runOnIdle { vm.preview != null } }
        rule.onNodeWithText("确认导入备份").assertIsDisplayed()
        capture("07-import-preview-dark")
        rule.onNodeWithText("确认合并").performClick()
        rule.waitUntil(20000) { rule.runOnIdle { vm.preview == null && !vm.busy } }
        rule.runOnIdle { check(vm.items.size == 3); check(vm.message!!.contains("跳过 3 条")); vm.clearMessage(); vm.theme("system") }
    }
}
