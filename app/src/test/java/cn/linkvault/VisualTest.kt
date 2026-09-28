package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
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

    /** 截图要拍的是排版，不是 KDF 强度：设备上的 12 万次派生在这里只会把用例拖慢十几秒。 */
    @Before fun fastKeyDerivation() { NoteCrypto.iterations = 2_000 }

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
        // 密钥配好了、但这条没快照：翻译按钮所在的正文卡片根本不存在，详情页得自己把原因说清楚。
        rule.runOnIdle { vm.fetchEnabled(false); vm.aiEnabled(true); vm.aiKey("sk-demo-key") }
        capture("02f-detail-needs-snapshot-light")
        rule.runOnIdle { vm.aiEnabled(false); vm.aiKey(""); vm.fetchEnabled(true) }
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
        // 译文视图：正文之上多一个「原文/译文」切换，切到译文时图片不摆出来。
        // 这里直接写译文文件，不走 Translate.transport：截图要拍的是排版，不是网络。
        val translated = """
            # 带图片的正文

            这段文字要足够长，才能看清译文和原文用的是同一套排版，也才点得到下面的展开按钮。
            第二段说明：切换开关只有一段译文存在时才出现，没有译文时不摆一个点不动的开关。
            第三段收尾：译文里不加载图片，图裂的样子不该出现在阅读模式里。

            > 一句引用：留白让内容呼吸。

            - 列表第一项
            - 列表第二项
        """.trimIndent()
        runBlocking { Snapshots.saveTranslation(rule.activity, before.id, translated) }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("值得慢慢看的宇宙").performClick()
        rule.waitUntil(20_000) { rule.runOnIdle { vm.currentTranslation != null } }
        rule.onNodeWithText("译文").performScrollTo().performClick()
        rule.runOnIdle { check(vm.currentTranslation == translated) }
        // 只滚到卡片标题，正文还在屏幕外；拍到译文中间才算真看清了排版。
        rule.onNode(hasText("切换开关只有一段译文存在时才出现", substring = true), useUnmergedTree = true).performScrollTo()
        capture("02e-detail-translated-light")
        rule.onNodeWithText("原文").performClick()
        rule.onNodeWithText("离线正文快照").performScrollTo().assertExists()
        // 造出来的封面图和快照只为了拍这两张图，必须还原：后面的列表与深色截图不能带上测试文案。
        runBlocking {
            val dao = VaultDb.get(rule.activity).bookmarks()
            dao.update(before)
            Snapshots.delete(rule.activity, before.id)
        }
        Images.clear()
        // 重新进过一次详情页，阅读模式回到折叠态，编辑按钮本来就在可视区内，直接点。
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
        // AI 翻译默认收起，展开后的配置表单是这一版新增的主要入口，单独拍一张。
        rule.runOnIdle {
            vm.aiEnabled(true); vm.aiEndpoint("https://api.example.com/v1/chat/completions")
            vm.aiModel("gpt-4o-mini"); vm.aiKey("sk-demo-key-0123456789")
        }
        rule.onNodeWithText("接口密钥").performScrollTo()
        capture("05b-settings-ai-light")
        rule.runOnIdle {
            vm.aiEnabled(false); vm.aiEndpoint(Translate.DEFAULT_ENDPOINT)
            vm.aiModel(Translate.DEFAULT_MODEL); vm.aiKey("")
        }
        // ------------------------------------------------------------ 笔记区（1.5.0 新增）
        // 库是进程内单例，上面清过收藏；笔记也先清一次，这几张截图每次拍到同一份内容。
        runBlocking { VaultDb.get(rule.activity).notes().clear() }
        val masterPassword = "链藏口令 demo 2026"
        rule.onNode(hasText("笔记") and hasClickAction()).performClick()
        rule.onNodeWithText("还没有笔记").assertIsDisplayed()
        // 没设过主密码时这一行只给「设主密码」：没有可解的东西，弹解锁框只是骗用户输一遍密码。
        rule.onNode(hasText("设主密码") and hasClickAction()).assertIsDisplayed()
        // 主密码弹窗和改密码页拍不出图：带输入框的 Compose 弹窗在这条 Robolectric 通道里
        // 窗口停在 0x0，而且 Espresso 永远等不到 idle（60 秒 AppNotIdleException）。
        // 所以这里直接走控制层，笔记页的三种状态（锁定/掩码/展开）仍然是真实渲染。
        rule.runOnIdle { vm.notes.setupMaster(masterPassword, masterPassword) }
        rule.waitUntil(20000) { rule.runOnIdle { vm.notes.hasMaster && vm.notes.unlocked && !vm.notes.busy } }
        rule.runOnIdle { vm.clearMessage() }
        // 私密笔记走一遍真实录入：开关拨开之后按钮要跟着变成「加密保存」，这一张图就是编辑页。
        rule.onAllNodes(hasContentDescription("新建笔记")).onFirst().performClick()
        rule.onNode(hasSetTextAction()).performTextInput("家里 WiFi：链藏-5G\n管理页口令 hello-vault-2026")
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)).performClick()
        rule.onNodeWithText("正文加密后才落盘", substring = true).assertIsDisplayed()
        capture("09-note-editor-light")
        rule.onNodeWithText("加密保存").performClick()
        rule.waitUntil(20000) { rule.runOnIdle { vm.notes.rows.size == 1 && vm.notes.draft == null } }
        rule.onAllNodes(hasContentDescription("新建笔记")).onFirst().performClick()
        rule.onNode(hasSetTextAction()).performTextInput("12306 账号 superwwwlll · 口令 Vaule-2026!")
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)).performClick()
        rule.onNodeWithText("加密保存").performClick()
        rule.waitUntil(20000) { rule.runOnIdle { vm.notes.rows.size == 2 } }
        // 明文那条直接写库：这一张要的是排版，再走一遍录入没有额外信息。时间给得早一点，
        // 让它排在两条私密之后，截图里三种状态（明文/掩码/展开）能同屏。
        runBlocking {
            VaultDb.get(rule.activity).notes().insert(
                Note(text = "内网代理 10.20.30.40:8888\n只在公司网络能用，出差要先连 VPN。", createdAt = 1788777600000, updatedAt = 1788777600000)
            )
        }
        rule.waitUntil(20000) { rule.runOnIdle { vm.notes.rows.size == 3 } }
        rule.runOnIdle { vm.clearMessage() }
        rule.onNode(hasText("锁定") and hasClickAction()).performClick()
        rule.waitUntil(20000) { rule.runOnIdle { !vm.notes.unlocked && vm.notes.rows.count { it.locked } == 2 } }
        capture("10-notes-locked-light")
        // 「解锁」入口必须真的在屏上点得到；弹窗一旦弹出这条通道就废了，所以点完立刻改走代码。
        rule.onAllNodes(hasText("解锁") and hasClickAction()).onFirst().assertIsDisplayed()
        rule.runOnIdle { vm.notes.unlock(masterPassword) }
        rule.waitUntil(20000) { rule.runOnIdle { vm.notes.unlocked && vm.notes.rows.none { it.locked } } }
        rule.runOnIdle { vm.clearMessage() }
        // 「显示」是逐条的：只展开最上面那条，下面那条仍是掩码，一张图同时看清两种状态。
        rule.onAllNodesWithText("显示").onFirst().performClick()
        capture("11-notes-unlocked-light")
        // 改密码弹窗同样拍不出来，这里只验收入口在设置页里存在。
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("修改主密码").performScrollTo().assertIsDisplayed()
        // 提示条的纯文本分支：链接那条 1.4.0 已经拍过，这里要的是「存为笔记」那颗按钮和它下面
        // 那行「存下来是明文笔记」。口令样子的内容无所谓——它只存在于测试沙箱。
        rule.onNodeWithText("收藏").performClick()
        val clipboard = rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("label", "工位机登录口令 Vault@2026 每季度换一次"))
        rule.runOnIdle { vm.checkClipboard(rule.activity) }
        rule.waitUntil(20000) { rule.runOnIdle { vm.clipboardText != null } }
        capture("13-clipboard-text-light")
        rule.runOnIdle { vm.dismissClipboard() }
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("深色").performScrollTo().performClick()
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
