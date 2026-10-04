package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真实长度数据的窄屏/大字号回归；原生渲染并非手机截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiLayoutRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val title = "T3 Code：统一管理本机多个编程 Agent 的指挥台"

    private fun seed(fontScale: Float): VaultViewModel {
        val vm = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
        val dao = VaultDb.get(rule.activity).bookmarks()
        runBlocking {
            dao.all().forEach { dao.delete(it.id) }
            repeat(12) { index ->
                val url = "https://x.com/example/status/${index + 1}"
                dao.insert(Bookmark(url = url, canonical = url,
                    title = if (index == 0) title else "开发者基于日常使用更新 AI 编排器分级：T3 Code Nightly 的实践记录 $index",
                    summary = "比较 Codex、Claude Code 与 Cursor 的多 Agent 工作方式，说明远程接管的适用边界。",
                    tags = "AI 编程 Agent,多 Agent 管理,开发工具,远程接管,工具评测",
                    createdAt = System.currentTimeMillis() - index * 1000L))
            }
        }
        rule.runOnIdle {
            vm.analysis.auto(false); vm.fetchEnabled(false); vm.theme("light"); vm.scope(0); vm.search(""); vm.tag(""); vm.setSort(0); vm.tab(0); vm.reload()
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    VaultTheme(false) { VaultScreen(vm) }
                }
            }
        }
        rule.waitUntil(20_000) { rule.runOnIdle { vm.items.size == 12 } }
        return vm
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File(System.getProperty("vault.screenshots"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun narrowScreenUsesContentBeforeMidScreen() {
        seed(1f)
        val heading = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        check(heading.top < root.height * 0.4f) { "收藏内容仍被顶部推到首屏下半区：$heading / $root" }
        rule.onNodeWithContentDescription("展开搜索").assertIsDisplayed().performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Nightly")
        rule.onNodeWithText(title).assertDoesNotExist()
        rule.onNodeWithContentDescription("清空搜索").performClick()
        rule.onNodeWithContentDescription("关闭搜索").performClick()
        capture("ui-161-collection-narrow")
        rule.onNodeWithText("标签", substring = false).performClick()
        rule.onNodeWithText("多 Agent 管理").assertIsDisplayed()
        capture("ui-161-tags-narrow")
        rule.onNodeWithText("设置", substring = false).performClick()
        capture("ui-161-settings-narrow")
    }

    @Test fun largeTextKeepsSearchAndLongTagAccessible() {
        seed(1.5f)
        rule.onNodeWithContentDescription("展开搜索").assertIsDisplayed()
        rule.onNodeWithText(title).assertIsDisplayed()
        capture("ui-161-collection-large-text")
        rule.onNodeWithText("标签", substring = false).performClick()
        rule.onNodeWithText("多 Agent 管理").assertIsDisplayed().performClick()
        rule.runOnIdle { check(ViewModelProvider(rule.activity)[VaultViewModel::class.java].filter == "多 Agent 管理") }
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText("设置", substring = false).performClick()
        capture("ui-161-settings-large-text")
        rule.onNodeWithText("AI 接口").performScrollTo().performClick()
        rule.onNodeWithText("你的 API Key").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("高级配置（API 地址）").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("API 地址（完整 chat/completions 地址）"))
            .performScrollTo().assertIsDisplayed()
        capture("ui-161-ai-large-text")
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithText("阅读助手").performScrollTo().performClick()
        rule.onNodeWithText("我的工作、兴趣与关注方向").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("编辑模板").performScrollTo().performClick()
        rule.onNodeWithText("提示词框架（可直接编辑）").performScrollTo().assertIsDisplayed()
        capture("ui-161-template-large-text")
    }
}
