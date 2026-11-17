package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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
class DetailEditorVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun seed(scale: Float = 1f, dark: Boolean = false): Pair<VaultViewModel, Bookmark> {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val stored = runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            val created = LocalDate.of(2026, 8, 18).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val id = dao.insert(Bookmark(
                url = "https://www.zhihu.com/question/reading-log",
                canonical = "https://www.zhihu.com/question/reading-log",
                title = "如何给十年后的自己留一份还能检索的阅读记录，而不是又一个打不开的收藏夹",
                notes = "先记下为什么值得留，再决定要不要做成一张以后还能找到的卡片。",
                tags = "笔记,方法",
                summary = "一篇关于把读过的东西留下来的页面描述。",
                createdAt = created,
                updatedAt = created
            ))
            dao.byId(id)!!
        }
        rule.runOnIdle {
            vm.notes.edit(null); vm.closeDetail(); vm.edit(null); vm.tab(0); vm.scope(0); vm.filter(""); vm.search("")
            vm.compactCollection(true); vm.analysis.auto(false); vm.fetchEnabled(false); vm.clearMessage(); vm.dismissClipboard()
            vm.reload()
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    VaultTheme(dark) { VaultScreen(vm) }
                }
            }
        }
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.loading && vm.items.size == 1 } }
        return vm to stored
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/detail-v2"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun regularDetailAndEditor() {
        val (vm, item) = seed()
        rule.runOnIdle { vm.show(item); vm.clearMessage() }
        rule.onNodeWithText("打开").assertIsDisplayed()
        rule.onNodeWithContentDescription("复制原链接").assertIsDisplayed()
        rule.onNodeWithText("我的一句").assertIsDisplayed()
        capture("detail-v2-regular")
        rule.runOnIdle { vm.open(item); vm.clearMessage() }
        rule.onNodeWithText("编辑收藏").assertIsDisplayed()
        rule.onNodeWithText("保存收藏").assertIsDisplayed()
        capture("editor-v2-edit")
        rule.runOnIdle { vm.edit(Draft()); vm.clearMessage() }
        rule.onNodeWithText("新建收藏").assertIsDisplayed()
        capture("editor-v2-new")
    }

    @Test fun darkDetailAndEditor() {
        val (vm, item) = seed(dark = true)
        rule.runOnIdle { vm.show(item); vm.clearMessage() }
        rule.onNodeWithText("打开").assertIsDisplayed()
        capture("detail-v2-dark")
        rule.runOnIdle { vm.open(item); vm.clearMessage() }
        rule.onNodeWithText("保存收藏").assertIsDisplayed()
        capture("editor-v2-dark")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun narrowLargeDetailAndEditor() {
        val (vm, item) = seed(scale = 2f)
        rule.runOnIdle { vm.show(item); vm.clearMessage() }
        rule.onNodeWithText("打开").assertIsDisplayed()
        rule.onNodeWithContentDescription("复制原链接").assertIsDisplayed()
        capture("detail-v2-narrow-large")
        rule.runOnIdle { vm.open(item); vm.clearMessage() }
        rule.onNodeWithText("保存收藏").assertIsDisplayed()
        capture("editor-v2-narrow-large")
    }
}
