package cn.linkvault
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StateTest {
    @Test fun restoreDraftAndKeepIncomingSharePending() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val handle = SavedStateHandle()
            val vm = VaultViewModel(app, handle)
            vm.edit(Draft(url = "https://example.com", title = "未完成", notes = "保留", tags = "标签"))
            vm.receive("https://x.com/a/status/1?s=20")
            assertEquals("未完成", vm.draft!!.title)
            assertNotNull(vm.pending)
            val restored = VaultViewModel(app, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
            assertEquals(vm.draft, restored.draft)
            assertEquals(vm.pending, restored.pending)
            restored.cancel(); restored.importPending()
            assertEquals("https://x.com/a/status/1?s=20", restored.draft!!.url)
            restored.extractText("https://a.example https://b.example")
            assertEquals("https://a.example", restored.draft!!.url)
            assertTrue(restored.notice!!.contains("仅取第一个"))
            restored.edit(restored.draft!!.copy(url = "javascript:alert(1)")); restored.save()
            assertNotNull(restored.error); assertNotNull(restored.draft)

            restored.setSort(2)
            assertEquals(2, restored.sortOrder)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun clipboardSnoopAndQuickSave() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = VaultViewModel(app, SavedStateHandle())
            val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", "看看这个 https://news.ycombinator.com/item?id=123 很有意思"))

            vm.checkClipboard(app)
            assertEquals("https://news.ycombinator.com/item?id=123", vm.clipboardCandidate)

            vm.quickSaveClipboard()
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (vm.busy || vm.message == null) kotlinx.coroutines.delay(10)
                }
            }
            assertNull(vm.clipboardCandidate)
            assertEquals("已收录剪贴板链接", vm.message)

            // Once saved, checking again should not surface it
            vm.checkClipboard(app)
            assertNull(vm.clipboardCandidate)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun randomReadPicksBookmark() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = VaultViewModel(app, SavedStateHandle())
            vm.randomRead()
            assertEquals("暂无收藏可供温故", vm.message)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun snapshotsStorageAndViewModelIntegration() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = VaultViewModel(app, SavedStateHandle())
            val sampleId = 9999L
            val content = "这是文章的离线纯净正文快照，保留关键信息。"

            Snapshots.save(app, sampleId, content)
            assertTrue(Snapshots.has(app, sampleId))
            assertEquals(content, Snapshots.get(app, sampleId))

            // 正文读取在 IO 线程上做，所以要等；而且只有「当前详情页就是这条」时才写入状态。
            vm.show(Bookmark(id = sampleId, url = "https://example.com/snapshot", canonical = "example.com/snapshot"))
            awaitUntil { vm.currentSnapshot == content }
            assertEquals(content, vm.currentSnapshot)

            vm.removeSnapshot(sampleId)
            awaitUntil { vm.currentSnapshot == null }
            assertNull(vm.currentSnapshot)
            assertFalse(Snapshots.has(app, sampleId))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun translationIsLoadedWithTheSnapshotAndDeletedTogether() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = VaultViewModel(app, SavedStateHandle())
            val id = 4242L
            Snapshots.save(app, id, "英文正文")
            Snapshots.saveTranslation(app, id, "中文译文")

            vm.show(Bookmark(id = id, url = "https://example.com/t", canonical = "example.com/t"))
            awaitUntil { vm.currentSnapshot != null && vm.currentTranslation != null }
            assertEquals("中文译文", vm.currentTranslation)

            // 译文离开原文没有意义：删快照必须连译文一起删掉
            vm.removeSnapshot(id)
            awaitUntil { vm.currentTranslation == null }
            assertFalse(Snapshots.has(app, id))
            assertNull(Snapshots.getTranslation(app, id))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun tidyTitlesRewritesOnlyEmptyTitlesAndNeverTouchesGoodOnes() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val dao = VaultDb.get(app).bookmarks()
            val junkId = runBlocking {
                dao.insert(Bookmark(url = "https://www.zhihu.com/question/1", canonical = "zhihu.com/question/1", title = "首页 - 知乎"))
            }
            val goodId = runBlocking {
                dao.insert(Bookmark(url = "https://example.com/keep", canonical = "example.com/keep", title = "已经很好的标题"))
            }
            runBlocking { Snapshots.save(app, junkId, "# 知乎上关于协程的好问题\n\n正文从这里开始。") }
            val vm = VaultViewModel(app, SavedStateHandle())
            vm.tidyTitles()
            awaitUntil { !vm.busy }
            assertEquals("知乎上关于协程的好问题", runBlocking { dao.byId(junkId) }!!.title)
            assertEquals("已经很好的标题", runBlocking { dao.byId(goodId) }!!.title)
            assertTrue(vm.message!!.contains("1 条"))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun translateGoesThroughTheSeamOnceAndCachesTheResult() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val realTransport = Translate.transport
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val id = 5150L
            runBlocking { Snapshots.save(app, id, "第一段。\n\n第二段。") }
            var calls = 0
            Translate.transport = { _, _, _ -> calls++; """{"choices":[{"message":{"content":"译文段落"}}]}""" }
            val vm = VaultViewModel(app, SavedStateHandle())
            val item = Bookmark(id = id, url = "https://example.com/x", canonical = "example.com/x")

            // 开关没开、没有快照时都不该发请求
            vm.translate(item); assertNotNull(vm.error); vm.clearError()
            vm.show(item)
            awaitUntil { vm.currentSnapshot != null }
            vm.aiEnabled(true); vm.aiKey("sk-demo")
            vm.translate(item)
            awaitUntil { !vm.translating }
            assertNull(vm.error)
            assertEquals("译文段落", vm.currentTranslation)
            assertEquals(1, calls)
            assertEquals("译文段落", Snapshots.getTranslation(app, id))

            // 译文已经落盘：重进页面直接读到，不再花一次钱
            vm.closeDetail()
            vm.show(item)
            awaitUntil { vm.currentTranslation != null }
            assertEquals(1, calls)
        } finally {
            Translate.transport = realTransport
            Dispatchers.resetMain()
        }
    }

    private fun awaitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }
}
