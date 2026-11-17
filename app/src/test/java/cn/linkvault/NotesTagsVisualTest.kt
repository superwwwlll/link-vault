package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotesTagsVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val originalIterations = NoteCrypto.iterations
    @After fun restore() { NoteCrypto.iterations = originalIterations }

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
            val file = File(System.getProperty("vault.screenshots", "build/notes-tags-v2"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun plainNotes(vm: VaultViewModel) {
        runBlocking {
            val notes = VaultDb.get(rule.activity).notes()
            notes.clear()
            VaultDb.get(rule.activity).vault().clear()
            notes.insert(Note(text = "把读过的一段话留下来\n不急着整理，先让它还能被找到。"))
            notes.insert(Note(text = "城南那家书店的地址：沿河走到底，二楼靠窗。"))
        }
        rule.runOnIdle { vm.notes.afterImport(); vm.tab(1); vm.clearMessage(); vm.dismissClipboard() }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.notes.rows.size == 2 && !vm.notes.hasMaster && !vm.notes.busy } }
    }

    @Test fun notesListEditorAndSecretStates() {
        NoteCrypto.iterations = 2_000
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        plainNotes(vm)
        show(vm)
        rule.onNodeWithContentDescription("新建笔记").assertIsDisplayed()
        rule.onNodeWithText("设主密码").assertIsDisplayed()
        capture("notes-v2-list")
        rule.runOnIdle { vm.notes.open(vm.notes.rows.first { it.text.startsWith("把读过") }); vm.clearMessage() }
        rule.onNodeWithText("保存笔记").assertIsDisplayed()
        rule.onNodeWithText("私密").assertIsDisplayed()
        capture("notes-v2-editor")
        rule.runOnIdle { vm.notes.edit(null); vm.notes.setupMaster("notes-pass", "notes-pass") }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.notes.hasMaster && vm.notes.unlocked && !vm.notes.busy } }
        rule.runOnIdle {
            vm.notes.create("门锁密码记在这张卡片背面，不要写进明文收藏。")
            vm.notes.change(vm.notes.draft!!.copy(secret = true))
            vm.notes.save()
        }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.notes.draft == null && vm.notes.secretTotal == 1 && !vm.notes.busy } }
        rule.runOnIdle { vm.tab(1); vm.clearMessage() }
        rule.onNodeWithText("显示").assertIsDisplayed()
        capture("notes-v2-unlocked")
        rule.onNodeWithText("显示").performClick()
        rule.onNodeWithText("门锁密码记在这张卡片背面，不要写进明文收藏。").assertIsDisplayed()
        capture("notes-v2-revealed")
        rule.runOnIdle { vm.notes.open(vm.notes.rows.first { it.secret }); vm.clearMessage() }
        rule.onNodeWithText("加密保存").assertIsDisplayed()
        rule.onNodeWithText("忘记主密码无法找回", substring = true).assertIsDisplayed()
        capture("notes-v2-editor-secret")
        rule.runOnIdle { vm.notes.edit(null); vm.notes.lock(); vm.clearMessage() }
        rule.onNodeWithText("已锁定 · 点按解锁").assertIsDisplayed()
        rule.onNodeWithText("解锁").assertIsDisplayed()
        capture("notes-v2-locked")
    }

    @Test fun tagsListAndRename() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.allIncludingDeleted().forEach { dao.delete(it.id) }
            dao.insert(Bookmark(url = "https://sspai.com/post/shelf", canonical = "https://sspai.com/post/shelf", title = "书架", tags = "阅读,笔记"))
            dao.insert(Bookmark(url = "https://www.zhihu.com/question/1", canonical = "https://www.zhihu.com/question/1", title = "记录", tags = "笔记,方法"))
            dao.insert(Bookmark(url = "https://juejin.cn/post/1", canonical = "https://juejin.cn/post/1", title = "界面", tags = "界面"))
        }
        rule.runOnIdle { vm.reload(); vm.tab(2); vm.clearMessage() }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.size == 3 } }
        show(vm)
        rule.onNodeWithText("排序：数量最多").assertIsDisplayed()
        capture("tags-v2-list")
        rule.onNodeWithContentDescription("标签操作：阅读").performClick()
        rule.onNodeWithText("重命名").performClick()
        rule.onNodeWithText("新标签名").assertIsDisplayed()
        capture("tags-v2-rename")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("标签操作：方法").performClick()
        rule.onNodeWithText("合并到…").performClick()
        rule.onNodeWithText("确认合并").assertIsDisplayed().assertIsNotEnabled()
        capture("tags-v2-merge")
        rule.onNodeWithText("阅读").performClick()
        rule.onNodeWithText("将「方法」合并到「阅读」").assertIsDisplayed()
        capture("tags-v2-merge-chosen")
    }

    @Test fun darkNotesAndTags() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        plainNotes(vm)
        show(vm, dark = true)
        capture("notes-v2-dark")
        rule.runOnIdle { vm.notes.open(vm.notes.rows.first()); vm.clearMessage() }
        rule.onNodeWithText("保存笔记").assertIsDisplayed()
        capture("notes-v2-editor-dark")
        rule.runOnIdle { vm.notes.edit(null) }
        rule.runOnIdle {
            runBlocking {
                val dao = VaultDb.get(rule.activity).bookmarks()
                dao.allIncludingDeleted().forEach { dao.delete(it.id) }
                dao.insert(Bookmark(url = "https://sspai.com/a", canonical = "https://sspai.com/a", title = "甲", tags = "阅读,笔记"))
            }
            vm.reload(); vm.tab(2); vm.clearMessage()
        }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.isNotEmpty() } }
        capture("tags-v2-dark")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun narrowLargeNotesAndTags() {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        plainNotes(vm)
        show(vm, scale = 2f)
        rule.onNodeWithContentDescription("新建笔记").assertIsDisplayed()
        capture("notes-v2-narrow-large")
        rule.runOnIdle { vm.notes.open(vm.notes.rows.first()); vm.clearMessage() }
        rule.onNodeWithText("保存笔记").assertIsDisplayed()
        capture("notes-v2-editor-narrow-large")
        rule.runOnIdle {
            runBlocking {
                val dao = VaultDb.get(rule.activity).bookmarks()
                dao.allIncludingDeleted().forEach { dao.delete(it.id) }
                dao.insert(Bookmark(url = "https://sspai.com/a", canonical = "https://sspai.com/a", title = "甲", tags = "阅读,方法"))
            }
            vm.notes.edit(null); vm.reload(); vm.tab(2); vm.clearMessage()
        }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.isNotEmpty() } }
        capture("tags-v2-narrow-large")
    }
}
