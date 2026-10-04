package cn.linkvault

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TelegramStateTest {
    private lateinit var app: Application
    private lateinit var dao: BookmarkDao
    private val store = ViewModelStore()
    private val models = mutableListOf<VaultViewModel>()
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        dao = VaultDb.get(app).bookmarks()
        app.getSharedPreferences("appearance", 0).edit().clear().commit()
        app.getSharedPreferences("draft", 0).edit().clear().commit()
        runBlocking { dao.allIncludingDeleted().forEach { dao.delete(it.id) } }
    }
    private fun vm(): VaultViewModel = VaultViewModel(app, SavedStateHandle()).also { models += it; store.put("vm-${models.size}", it) }
    private fun waitUntil(condition: () -> Boolean) = runBlocking { withTimeout(10_000) {
        while (!condition()) { dispatcher.scheduler.advanceTimeBy(25); dispatcher.scheduler.runCurrent(); delay(5) }
    } }
    private fun insert(path: String): Long = runBlocking { dao.insert(Bookmark(url = "https://example.com/$path", canonical = "https://example.com/$path", title = "保留标题", notes = "保留备注", tags = "原标签", pinned = true)) }
    @After fun cleanup() {
        val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        store.clear(); runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        Dispatchers.resetMain()
    }

    @Test fun collectionDensityPersistsAndConversationNavigationDoesNotChangeDetails() {
        val first = vm(); assertTrue(first.compactCollection)
        first.compactCollection(false)
        assertFalse(vm().compactCollection)
        val id = insert("navigation")
        first.show(runBlocking { dao.byId(id)!! })
        first.openAnalysisConversation("b-$id")
        assertEquals("b-$id", first.analysisConversationKey)
        first.closeAnalysisConversation()
        assertEquals(id, first.detailId)
        first.openAnalysisConversation("invalid")
        assertNull(first.analysisConversationKey)
        first.openAnalysisConversation("b-$id"); first.tab(3)
        assertNull(first.analysisConversationKey)
    }

    @Test fun undoRestoresOnlyTheCurrentDeletionAndPreservesBookmarkData() {
        val vm = vm(); val id = insert("undo")
        val original = runBlocking { dao.byId(id)!! }
        vm.delete(id); waitUntil { !vm.busy && vm.deletionUndo != null }
        val action = vm.deletionUndo!!
        vm.undoDeletion(action); waitUntil { !vm.busy }
        assertNull(vm.deletionUndo)
        assertEquals(original, runBlocking { dao.byId(id)!! })
    }

    @Test fun staleUndoCannotUndoANewerDeletionOrRevivePermanentlyDeletedData() {
        val vm = vm(); val one = insert("one"); val two = insert("two")
        vm.delete(one); waitUntil { !vm.busy && vm.deletionUndo != null }
        val old = vm.deletionUndo!!
        vm.delete(two); waitUntil { !vm.busy && vm.deletionUndo?.token != old.token }
        vm.undoDeletion(old)
        assertTrue(runBlocking { dao.byId(one)!!.deletedAt > 0L })
        val current = vm.deletionUndo!!
        runBlocking { dao.deleteForever(two) }
        vm.undoDeletion(current); waitUntil { !vm.busy }
        assertNull(runBlocking { dao.byId(two) })
        assertTrue(runBlocking { dao.byId(one)!!.deletedAt > 0L })
    }

    @Test fun batchUndoRestoresOnlyItemsActuallyDeletedByTheBatch() {
        val vm = vm(); val one = insert("batch-one"); val two = insert("batch-two")
        val previousDeletion = System.currentTimeMillis() - 1000L
        runBlocking { dao.softDelete(two, previousDeletion) }
        vm.bulkDelete(setOf(one, two, Long.MAX_VALUE)); waitUntil { !vm.busy && vm.deletionUndo != null }
        val action = vm.deletionUndo!!
        assertEquals(setOf(one), action.ids)
        vm.undoDeletion(action); waitUntil { !vm.busy }
        assertEquals(0L, runBlocking { dao.byId(one)!!.deletedAt })
        assertEquals(previousDeletion, runBlocking { dao.byId(two)!!.deletedAt })
    }

    @Test fun undoWaitsForAnInProgressExportInsteadOfDroppingTheAction() {
        val vm = vm(); val id = insert("busy-undo")
        vm.delete(id); waitUntil { !vm.busy && vm.deletionUndo != null }
        val undo = vm.deletionUndo!!
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val uri = android.net.Uri.parse("content://cn.linkvault.test/busy-export")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, object : java.io.ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); super.write(bytes, offset, count)
            }
        })
        try {
            vm.export(uri); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); assertTrue(vm.busy)
            vm.undoDeletion(undo)
            assertTrue(runBlocking { dao.byId(id)!!.deletedAt > 0L })
        } finally { release.countDown() }
        waitUntil { vm.deletionUndo == null && !vm.busy }
        assertEquals(0L, runBlocking { dao.byId(id)!!.deletedAt })
    }

    @Test fun shareDuringConversationIsQueuedWithoutCreatingAHiddenEditor() {
        val vm = vm(); val id = insert("share-chat")
        vm.openAnalysisConversation("b-$id")
        vm.conversationQuestion("b-$id", "未发送的问题")
        vm.receive(Shared("https://example.com/shared", "新分享标题"))
        assertNull(vm.draft); assertEquals("b-$id", vm.analysisConversationKey)
        assertEquals("https://example.com/shared", vm.pending)
        vm.importPending(); assertNull(vm.draft)
        vm.closeAnalysisConversation(); vm.importPending()
        assertEquals("https://example.com/shared", vm.draft?.url)
        assertEquals("未发送的问题", vm.conversationDraft("b-$id").question)
    }

    @Test fun queuedHtmlOnlyAndHeadlineSharesKeepTheirLinks() {
        val vm = vm(); val id = insert("html-share-chat")
        listOf("" to "https://example.com/html-only", "文章标题" to "https://example.com/html-with-headline").forEach { (text, url) ->
            vm.edit(null); vm.openAnalysisConversation("b-$id")
            vm.receive(Shared(text, "", "<a href=\"$url\">文章</a>"))
            assertNotNull(vm.pendingHtml)
            vm.closeAnalysisConversation(); vm.importPending()
            assertEquals(url, vm.draft?.url)
            assertNull(vm.pending); assertNull(vm.pendingHtml)
        }
    }

    @Test fun allAnalysisClearPathsAlsoRemoveTheConversationDraft() {
        val vm = vm(); val key = "n-123"
        vm.conversationQuestion(key, "未发送问题")
        vm.openAnalysisConversation(key)
        vm.analysis.afterNoteSave(Note(id = 123, text = "密文", secret = true))
        assertEquals("", vm.conversationDraft(key).question)
        assertNull(vm.analysisConversationKey)
    }
}
