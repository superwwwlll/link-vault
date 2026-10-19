package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
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
class TagInputUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun editor(tags: String = "", common: String = "技术"): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.all().forEach { dao.delete(it.id) }
            dao.insert(Bookmark(url = "https://example.com/tags", canonical = "https://example.com/tags", title = "常用标签", tags = common))
        }
        rule.runOnIdle { vm.reload(); vm.edit(Draft(tags = tags)) }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.size == 1 } }
        rule.runOnIdle {
            rule.activity.setContent {
                VaultTheme(dark = false) {
                    val focus = LocalFocusManager.current
                    Column {
                        TagInput(vm, vm.draft!!)
                        TextButton(onClick = { focus.clearFocus() }) { Text("完成编辑") }
                    }
                }
            }
        }
        return vm
    }

    @Test fun separatorDoneRemovalAndCommonTagPersistInDraft() {
        val vm = editor()
        val input = rule.onNode(hasSetTextAction())
        input.performTextInput("设计，灵感")
        input.performImeAction()
        rule.onNodeWithContentDescription("移除标签 设计").assertExists()
        rule.onNodeWithContentDescription("移除标签 灵感").assertExists().performClick()
        rule.runOnIdle { assertEquals("设计", vm.draft!!.tags) }
        rule.onNodeWithText("技术").performClick()
        rule.runOnIdle { assertEquals("设计,技术", vm.draft!!.tags) }
        rule.onNodeWithContentDescription("移除标签 技术").assertExists()
    }

    @Test fun focusLossCommitsAndNormalizedDuplicateIsNotAdded() {
        val vm = editor("Design")
        val input = rule.onNode(hasSetTextAction())
        input.performTextInput(" design；设计")
        input.performImeAction()
        rule.runOnIdle { assertEquals("Design,设计", vm.draft!!.tags) }
        input.performTextInput("灵感")
        // Moving focus to another reachable control commits the unfinished text.
        rule.onNodeWithText("技术").performClick()
        rule.onNodeWithText("完成编辑").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(setOf("Design", "设计", "技术", "灵感"), parseTags(vm.draft!!.tags).toSet()) }
    }

    @Test fun commonTagsLimitCanExpandAndEditorRendersChips() {
        editor("设计,灵感", (1..13).joinToString(",") { "标签${it.toString().padStart(2, '0')}" })
        rule.onNodeWithText("标签12").assertExists()
        rule.onNodeWithText("标签13").assertDoesNotExist()
        rule.onNodeWithText("更多…").performClick()
        rule.onNodeWithText("标签13").assertExists()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots"), "ui-163-editor-tags.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun lengthLimitKeepsExistingTagsAndReportsReason() {
        val original = "设".repeat(999)
        val vm = editor(original)
        rule.onNode(hasSetTextAction()).performTextInput("技术，")
        rule.runOnIdle {
            assertEquals(original, vm.draft!!.tags)
            assertEquals("标签长度已达上限", vm.error)
        }
    }
}
