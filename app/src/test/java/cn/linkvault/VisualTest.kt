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

    /**
     * 等短动画落地再截图。
     *
     * 页签指示条 220ms、筛选胶囊 160ms、添加按钮 180ms。waitForIdle 只跑到当前帧，
     * 直接截图会拍到半截的指示条。
     */
    private fun settle() {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(600)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    /**
     * 一张能看出「位图真的被画出来了」的图：两块不同深度的色带，而不是和卡片底色接近的纯色。
     *
     * 截图通道绝不能碰真实网络，所以 Images.fetcher 在用例里换成这个固定字节。
     */
    private fun stubImage(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(58, 96, 160))
        canvas.drawRect(0f, height * 0.62f, width.toFloat(), height.toFloat(), android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(196, 148, 96)
        })
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun capture(name: String) {
        settle()
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
        rule.runOnIdle { vm.theme("light"); vm.scope(0); vm.search("") }
        rule.onNodeWithText("我的收藏").assertIsDisplayed()
        // 真实点击验收：页签必须可点，搜索框必须能输入并筛选，而不是只看截图。
        rule.onNode(hasText("未读") and hasClickAction()).performClick()
        rule.runOnIdle { check(vm.scope == 1) { "scope after unread click=${vm.scope}" } }
        rule.onNode(hasText("全部") and hasClickAction()).performClick()
        rule.runOnIdle { check(vm.scope == 0) }
        rule.onNode(hasSetTextAction()).performTextInput("Compose")
        rule.onNodeWithText("用 Compose 构建更好的 Android 界面").assertIsDisplayed()
        rule.onNodeWithContentDescription("清空搜索").performClick()
        capture("01-collection-light")
        // 添加按钮压在列表右下角，必须给卡片让位：向下滚收起、回到顶部再出现。
        // 截图看不到这件事，所以这里按语义节点断言，而不是靠肉眼看图。
        rule.onAllNodes(hasContentDescription("收藏链接")).assertCountEquals(1)
        rule.onNodeWithTag("collection-list").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onAllNodes(hasContentDescription("收藏链接")).assertCountEquals(0)
        rule.onNodeWithTag("collection-list").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onAllNodes(hasContentDescription("收藏链接")).assertCountEquals(1)
        rule.onNodeWithText("值得慢慢看的宇宙").performClick()
        rule.onNodeWithText("收藏详情").assertIsDisplayed()
        capture("02-detail-light")
        // 抓取默认关闭，详情页的「抓取页面信息 / 提取正文快照」入口在上面的图里根本不出现。
        // 空态收纳后这三行是详情页的主要入口，必须单独拍一张。
        rule.runOnIdle { vm.fetchEnabled(true) }
        capture("02b-detail-empty-light")
        // 上面两张是「什么都没抓到」的样子。抓到封面图和带图正文之后长什么样，得再拍两张。
        val coverUrl = "https://pbs.twimg.com/media/cover.png"
        val inlineUrl = "https://example.com/diagram.png"
        val cover = stubImage(1000, 300)
        val diagram = stubImage(900, 640)
        Images.fetcher = { url -> if (url == coverUrl) cover else if (url == inlineUrl) diagram else ByteArray(0) }
        val before = runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            val stored = dao.byKey(samples[0].canonical)!!
            dao.update(stored.copy(image = coverUrl, summary = "一条已经抓到封面图和描述的收藏，用来检查有内容时的排版。", fetchedAt = 1788950400000))
            Snapshots.save(
                rule.activity, stored.id,
                """
                    # 带图的正文

                    这一段的文字要足够长，才能看清折叠态只留八行、展开态铺开全文的差别，也才点得到下面那个展开按钮。
                    第二段接着补充一些内容：图片出现在正文中间时，不应该把上下文的行距挤乱，也不该让卡片的高度跟着抖一下。
                    第三段再写几句，凑够真实文章的密度：标题、正文、引用、列表和图片各自的位置都要能一眼分得清。

                    ![一张流程图](%INLINE%)

                    > 引用一句：留白让内容呼吸。

                    - 列表第一项
                    - 列表第二项
                """.trimIndent().replace("%INLINE%", inlineUrl)
            )
            stored
        }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("值得慢慢看的宇宙").performClick()
        rule.onNodeWithText("收藏详情").assertIsDisplayed()
        // 图片在后台线程解码，等它真的进到语义树再截图，否则拍到的是「还没加载完」的空页。
        // 用未合并树查：正文快照整块包在 SelectionContainer 里，图片的描述会被合并到父节点上。
        rule.waitUntil(20_000) { rule.onAllNodes(hasContentDescription("封面图"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        capture("02c-detail-cover-light")
        // 展开按钮被封面图顶到屏幕外了，不先滚到位点击是空操作。
        rule.onNodeWithText("展开全文阅读").performScrollTo().performClick()
        rule.waitUntil(20_000) { Images.cached(inlineUrl) != null }
        rule.onNode(hasContentDescription("一张流程图"), useUnmergedTree = true).performScrollTo()
        capture("02d-detail-snapshot-image-light")
        // 造出来的封面图和快照只为了拍这两张图，必须还原：后面的列表与深色截图不能带上测试文案。
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.update(before)
            Snapshots.delete(rule.activity, before.id)
        }
        Images.clear()
        rule.onNodeWithText("收起阅读模式").performScrollTo().performClick()
        rule.runOnIdle { vm.fetchEnabled(false) }
        Images.fetcher = { Net.fetchImage(it) }
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
