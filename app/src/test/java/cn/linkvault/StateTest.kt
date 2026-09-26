package cn.linkvault
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

            vm.loadSnapshot(sampleId)
            assertEquals(content, vm.currentSnapshot)

            vm.removeSnapshot(sampleId)
            assertNull(vm.currentSnapshot)
            assertFalse(Snapshots.has(app, sampleId))
        } finally { Dispatchers.resetMain() }
    }
}
