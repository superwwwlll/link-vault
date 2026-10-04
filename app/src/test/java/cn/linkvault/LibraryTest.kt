package cn.linkvault

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibraryTest {
    private lateinit var app: Application
    private lateinit var dao: BookmarkDao
    private val store = ViewModelStore()
    private val models = mutableListOf<VaultViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        dao = VaultDb.get(app).bookmarks()
        runBlocking { dao.all().forEach { dao.delete(it.id) } }
    }

    @After fun tearDown() {
        if (::app.isInitialized) {
            app.getSharedPreferences("draft", Context.MODE_PRIVATE).edit().clear().commit()
            app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        }
        val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        store.clear()
        runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        Dispatchers.resetMain()
    }

    private fun viewModel(): VaultViewModel = VaultViewModel(app, SavedStateHandle()).also { models += it; store.put("vm", it) }
    private fun settle(vm: VaultViewModel) = runBlocking { withTimeout(10_000) { while (vm.busy) delay(10) } }
    private fun awaitItems(vm: VaultViewModel, size: Int) = runBlocking { withTimeout(10_000) { while (vm.items.size != size) delay(10) } }
    private fun insert(url: String, title: String = "", tags: String = "", read: Boolean = false, archived: Boolean = false): Long =
        runBlocking { dao.insert(Bookmark(url = url, canonical = Links.canonical(url), title = title, tags = tags, read = read, archived = archived, createdAt = 1000, updatedAt = 1000)) }

    @Test fun stateTogglesDoNotDisturbUpdatedAt() {
        val id = insert("https://example.com/state", title = "状态")
        val vm = viewModel()
        fun row() = runBlocking { dao.byId(id) }!!

        vm.togglePin(row()); settle(vm)
        assertTrue(row().pinned)
        assertEquals("置顶不该改动更新时间，否则列表会跳位", 1000L, row().updatedAt)

        vm.toggleRead(row()); settle(vm)
        assertTrue(row().read)
        assertEquals(1000L, row().updatedAt)

        vm.toggleArchived(row()); settle(vm)
        assertTrue(row().archived)
        assertEquals(1000L, row().updatedAt)
        assertTrue(vm.message!!.contains("已归档"))
    }

    @Test fun scopeSplitsUnreadReadAndArchived() {
        insert("https://example.com/u", title = "未读")
        insert("https://example.com/r", title = "已读", read = true)
        insert("https://example.com/a", title = "归档", archived = true)
        val vm = viewModel()
        awaitItems(vm, 3)

        vm.scope(0); assertEquals(2, vm.visible().size)
        vm.scope(1); assertEquals(listOf("未读"), vm.visible().map { it.title })
        vm.scope(2); assertEquals(listOf("已读"), vm.visible().map { it.title })
        vm.scope(3); assertEquals(listOf("归档"), vm.visible().map { it.title })
    }

    @Test fun archivingTheOpenBookmarkClosesTheDetailPage() {
        val id = insert("https://example.com/open", title = "打开着")
        val vm = viewModel()
        awaitItems(vm, 1)
        val item = runBlocking { dao.byId(id) }!!
        vm.show(item)
        assertEquals(id, vm.detailId)
        vm.toggleArchived(item); settle(vm)
        assertNull("归档后不该停留在已移出主列表的详情页", vm.detailId)
    }

    @Test fun renameAndDeleteTagTouchEveryItemAndMergeInsteadOfDuplicating() {
        insert("https://example.com/1", title = "一", tags = "设计,灵感")
        insert("https://example.com/2", title = "二", tags = "设计")
        val vm = viewModel()
        awaitItems(vm, 2)

        vm.renameTag("设计", "UI"); settle(vm)
        assertTrue(vm.message!!.contains("2 条"))
        val afterRename = runBlocking { dao.all() }.associate { it.title to it.tags }
        assertEquals("灵感,UI", afterRename["一"])
        assertEquals("UI", afterRename["二"])

        vm.renameTag("灵感", "UI"); settle(vm)
        assertEquals("合并到已有标签时不能产生重复", "UI", runBlocking { dao.all() }.first { it.title == "一" }.tags)

        vm.deleteTag("ui"); settle(vm)
        assertTrue(runBlocking { dao.all() }.all { it.tags.isEmpty() })
    }

    @Test fun renameTagRejectsNamesThatWouldSplitIntoTwoTags() {
        insert("https://example.com/1", title = "一", tags = "设计")
        val vm = viewModel()
        awaitItems(vm, 1)
        vm.renameTag("设计", "设计,UI")
        assertTrue(vm.error!!.contains("不能包含"))
        assertEquals("设计", runBlocking { dao.all() }.single().tags)
    }

    @Test fun saveAllDetectedStoresEachLinkOnce() {
        val vm = viewModel()
        vm.extractText("看看这两条 https://a.example/1 和 https://b.example/2 ，重复的 https://a.example/1 只算一次")
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), vm.detected)
        assertTrue(vm.notice!!.contains("仅取第一个"))
        // 编辑草稿仍然只放第一条，批量收藏是额外的选择
        assertEquals("https://a.example/1", vm.draft!!.url)

        vm.saveAllDetected(); settle(vm)
        assertEquals(2, runBlocking { dao.all() }.size)
        assertNull(vm.draft)
        assertTrue(vm.message!!.contains("已收藏 2 条"))
    }

    @Test fun fetchRequiresTheOptInSwitchAndHttps() {
        val id = insert("http://example.com/plain", title = "明文")
        val vm = viewModel()
        val item = runBlocking { dao.byId(id) }!!

        vm.fetch(item)
        assertTrue(vm.error!!.contains("未开启"))

        vm.fetchEnabled(true)
        vm.clearError()
        vm.fetch(item)
        assertTrue(vm.error!!.contains("https"))
        assertFalse("两种拒绝都必须在发起请求之前返回", vm.busy)
    }

    /** 保存后的自动补齐走的是同一个开关：没打开就一条请求都不发。 */
    @Test fun savingWithTheSwitchOffStartsNoRequest() {
        val vm = viewModel()
        vm.edit(Draft(url = "https://example.com/silent", title = "自己写的标题"))
        vm.save()
        settle(vm)
        runBlocking { delay(300) }

        assertEquals("抓取开关没打开，不该有任何一条被联网抓过", 0L, runBlocking { dao.byKey("https://example.com/silent") }!!.fetchedAt)
        assertNull(vm.fetchingId)
        assertFalse(vm.fetchingSnapshot)
    }

    /** 开关开着也不会碰明文 http —— 自动路径不能成为 http 请求的漏口。 */
    @Test fun savingPlainHttpStartsNoRequestEvenWithTheSwitchOn() {
        val vm = viewModel()
        vm.fetchEnabled(true)
        vm.edit(Draft(url = "http://example.com/plain", title = "明文"))
        vm.save()
        settle(vm)
        runBlocking { delay(300) }

        assertEquals(0L, runBlocking { dao.byKey("http://example.com/plain") }!!.fetchedAt)
        assertFalse(vm.fetchingSnapshot)
    }

    @Test fun draftIsRestoredOnlyAfterARealProcessRestart() {
        val first = viewModel()
        first.edit(Draft(url = "https://example.com/current", title = "当前会话"))
        assertNull("同一进程内新建的 ViewModel 不该捡到旧草稿", viewModel().draft)

        app.getSharedPreferences("draft", Context.MODE_PRIVATE).edit()
            .putBoolean("active", true).putString("process", "上一次进程")
            .putLong("id", 0).putString("url", "https://example.com/restored")
            .putString("title", "重启后还在").putString("notes", "备注").putString("tags", "标签")
            .apply()

        val restored = viewModel()
        assertEquals("https://example.com/restored", restored.draft!!.url)
        assertEquals("重启后还在", restored.draft!!.title)
        assertEquals("备注", restored.draft!!.notes)
        assertEquals("标签", restored.draft!!.tags)
    }

    /** 正文快照是散在磁盘上的文件：行删掉之后文件不能留在手机里。 */
    @Test fun permanentlyDeletingABookmarkAlsoDeletesItsArticleSnapshot() {
        val id = insert("https://example.com/gone", title = "要删掉")
        runBlocking { dao.softDelete(id, System.currentTimeMillis()) }
        Snapshots.save(app, id, "不该留下的正文")
        assertTrue(Snapshots.has(app, id))

        val vm = viewModel()
        vm.deleteForever(runBlocking { dao.byId(id) }!!)
        settle(vm)

        assertFalse(Snapshots.has(app, id))
        assertNull(runBlocking { dao.byId(id) })
    }

    /** 回收站满 30 天时会自动清理，快照文件也要跟着走。 */
    @Test fun autoPurgeOfExpiredTrashRemovesTheArticleSnapshot() {
        val id = insert("https://example.com/ancient", title = "过期")
        runBlocking { dao.softDelete(id, System.currentTimeMillis() - 31L * 86_400_000L) }
        Snapshots.save(app, id, "过期回收站的正文")

        viewModel()
        runBlocking { withTimeout(10_000) { while (Snapshots.has(app, id)) delay(10) } }

        assertFalse(Snapshots.has(app, id))
        assertNull(runBlocking { dao.byId(id) })
    }
}
