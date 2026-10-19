package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import java.io.File
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
class UiPolishTest {
    private val originalIterations = NoteCrypto.iterations
    @org.junit.After fun restore() { NoteCrypto.iterations = originalIterations }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun seed(scale: Float = 1f, dark: Boolean = false): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.all().forEach { dao.delete(it.id) }
            dao.insert(Bookmark(url = "https://example.com/reading", canonical = "https://example.com/reading",
                title = "结构化阅读：从收藏到行动", notes = "保留信息来源，记录依据，再选一个可以验证的行动。", tags = "阅读,技术"))
            VaultDb.get(rule.activity).notes().clear()
            VaultDb.get(rule.activity).vault().clear()
        }
        rule.runOnIdle {
            vm.notes.afterImport()
            vm.analysis.auto(false); vm.fetchEnabled(false); vm.clearAiKey(); vm.notes.edit(null)
            vm.closeDetail(); vm.edit(null); vm.tab(0); vm.scope(0); vm.search(""); vm.filter(""); vm.reload()
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    VaultTheme(dark) { VaultScreen(vm) }
                }
            }
        }
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.loading && vm.items.size == 1 } }
        // NotesController 的 hasMaster 在 init 和 afterImport 里异步读库；VaultDb 是进程内单例，
        // init 可能先读到上一个用例留下的主密码。等它落到"未设主密码"再往下走，否则 setupMaster 会被拒绝。
        rule.waitUntil(20_000) { rule.runOnIdle { !vm.notes.hasMaster && !vm.notes.busy } }
        return vm
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots", "build/ui-polish/screenshots"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun scopes() {
        (0..3).forEach { index -> rule.onNodeWithTag("collection-scope-$index").assertIsDisplayed().performClick().assertIsSelected() }
        rule.onNodeWithTag("collection-scope-0").performClick()
    }

    @Test fun allFourScopesRemainReachableAtDoubleFont() {
        seed(2f); scopes(); capture("ui-163-collection-scope-large-text")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun narrowScopesAtOneAndHalfFont() {
        seed(1.5f); scopes(); capture("ui-163-collection-scope-narrow")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun extremeNarrowScopesAndDarkTheme() {
        seed(2f, dark = true); scopes(); capture("ui-163-collection-scope-narrow-dark-2x")
    }

    @Test @Config(qualifiers = "w320dp-h740dp-xxhdpi")
    fun normalNarrowScopes() { seed(); scopes(); capture("ui-163-collection-scope-narrow-1x") }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun wideScopesAtOneAndHalfFont() { seed(1.5f); scopes(); capture("ui-163-collection-scope-wide-1_5x") }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun wideScopesAtDoubleFont() { seed(2f); scopes(); capture("ui-163-collection-scope-wide-2x") }

    @Test fun unverifiedDetailShowsNotesAndSettingsShortcutConsumesTarget() {
        val vm = seed()
        rule.onNodeWithText("结构化阅读：从收藏到行动").performClick()
        rule.onNodeWithText("保留信息来源，记录依据，再选一个可以验证的行动。").assertIsDisplayed()
        rule.onNodeWithText("分析这篇内容").assertDoesNotExist()
        rule.onNodeWithContentDescription("复制原链接").assertIsDisplayed()
        capture("ui-163-detail-unconfigured")
        rule.onNodeWithText("去设置").performScrollTo().performClick()
        rule.onNodeWithText("你的 API Key").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("AI 接口").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(3, vm.tab); assertNull(vm.detailId); assertNull(vm.draft)
            assertNull(vm.analysisConversationKey); assertNull(vm.settingsTarget)
        }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("收藏与笔记仅存本机", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** Scroll-viewport clipping is not an undersized measured target. Check fully visible nodes;
     * detail is checked both at the top and after scrolling to its final AI action. */
    private fun assertVisibleTargets() {
        val min = 48f * rule.activity.resources.displayMetrics.density - 1f
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { node ->
            val bounds = node.boundsInRoot
            if (bounds.width > 0 && bounds.height > 0 && bounds.top >= root.top && bounds.bottom < root.bottom - 1f) {
                assertTrue("触控目标过小：${node.config} $bounds, minimum=$min", bounds.width >= min && bounds.height >= min)
            }
        }
    }

    @Test fun collectionUnlockedSecretNotesAndDetailTargetsAreAtLeast48dp() {
        val vm = seed()
        assertVisibleTargets()
        rule.onNodeWithText("结构化阅读：从收藏到行动").performClick()
        assertVisibleTargets()
        rule.onNodeWithText("去设置").performScrollTo()
        assertVisibleTargets()
        rule.runOnIdle { vm.closeDetail() }
        NoteCrypto.iterations = 2_000
        rule.runOnIdle { vm.notes.setupMaster("target-test-pass", "target-test-pass") }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.notes.hasMaster && vm.notes.unlocked && !vm.notes.busy } }
        rule.runOnIdle { vm.notes.create(); vm.notes.change(vm.notes.draft!!.copy(text = "测试私密内容", secret = true)); vm.notes.save() }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.notes.draft == null && vm.notes.rows.size == 1 } }
        rule.runOnIdle { vm.tab(1); vm.clearMessage() }
        rule.onNodeWithText("显示").assertIsDisplayed()
        assertVisibleTargets()
        rule.onNodeWithText("显示").performClick()
        rule.onNodeWithText("测试私密内容").assertIsDisplayed()
        rule.onNodeWithContentDescription("复制全文").assertIsDisplayed()
        assertVisibleTargets()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun noteEditorDefaultsToCollapsedAiAndPreservesSaveAndPrivacyControls() {
        val vm = seed()
        val id = runBlocking { VaultDb.get(rule.activity).notes().insert(Note(text = "结构化阅读\n把观点和证据分开，先尝试一个可验证的行动。")) }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.notes.rows.any { it.id == id } } }
        rule.runOnIdle { vm.notes.open(vm.notes.rows.first { it.id == id }); vm.clearMessage() }
        rule.onNodeWithText("保存笔记").assertIsDisplayed()
        rule.onNodeWithText("私密").assertIsDisplayed()
        rule.onNodeWithText("配置 AI 后可用").assertIsDisplayed()
        rule.onNodeWithText("去设置").assertDoesNotExist()
        capture("09-note-editor-light")
        rule.onNodeWithContentDescription("展开 AI").performScrollTo().performClick()
        rule.onNodeWithText("去设置").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("收起 AI").performScrollTo().performClick()
        rule.onNodeWithText("去设置").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performTextInput("新增未保存文字")
        rule.onNodeWithText("先保存再分析").performScrollTo().assertIsDisplayed()
    }

    @Test fun doubleFontConversationComposerUsesLessThan22PercentOfScreen() {
        val vm = seed(2f)
        rule.runOnIdle {
            vm.analysis.records["b-${vm.items.first().id}"] = AnalysisRecord("hash", "system", "摘要", 100,
                listOf(AnalysisMessage("assistant", "先确定一个行动，再检验结果。")))
            vm.openAnalysisConversation("b-${vm.items.first().id}")
        }
        rule.onNodeWithContentDescription("发送追问").assertIsDisplayed()
        rule.onNodeWithText("AI 接口未验证").assertIsDisplayed()
        val composer = rule.onNodeWithTag("analysis-conversation-composer").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("输入区超过屏高22%：$composer $root", composer.height < root.height * .22f)
        println("UI_POLISH_COMPOSER height=${composer.height}, screen=${root.height}, ratio=${composer.height / root.height}")
        capture("ui-162-conversation-large-text")
        rule.onNodeWithText("去设置").performClick()
        rule.onNodeWithText("你的 API Key").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertNull(vm.analysisConversationKey) }
    }
}
