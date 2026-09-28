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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotesStateTest {
    private val master = "MiMa-123456"
    private lateinit var app: Application
    private lateinit var handle: SavedStateHandle
    private lateinit var vm: VaultViewModel
    private lateinit var notes: NotesController
    private lateinit var db: VaultDb

    @Before fun setUp() {
        // 派生次数是安全参数，但整轮测试跑 12 万次没有意义；仍留在 open() 允许的区间内。
        NoteCrypto.iterations = 2_000
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        db = VaultDb.get(app)
        runBlocking { db.notes().clear(); db.vault().clear() }
        handle = SavedStateHandle()
        vm = VaultViewModel(app, handle)
        notes = vm.notes
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun awaitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition()) delay(5) }
    }

    private fun settle(ms: Long = 250) = runBlocking { delay(ms) }

    /**
     * 上一个写操作还没收尾时，控制层会直接忽略新的调用（busy 门，界面上对应按钮置灰），
     * 所以用例里连续两次落盘操作之间要先等一次。
     */
    private fun idle(c: NotesController = notes) = awaitUntil { !c.busy }

    private fun type(text: String, secret: Boolean = false) {
        notes.create()
        notes.change(notes.draft!!.copy(text = text, secret = secret))
        idle()
        notes.save()
    }

    @Test fun plainNoteSavesSearchesAndDeletesForever() {
        type("会议室 4102\n白板笔没水了")
        awaitUntil {notes.rows.size == 1 }
        assertNull(notes.draft)

        val row = notes.rows.single()
        assertFalse(row.secret)
        assertEquals("标题行只有首行，不带换行", "会议室 4102", row.heading)
        assertEquals("剩余内容单独一行，卡片不会把同一段字显示两遍", "白板笔没水了", row.summary)
        val single = NoteRow(0L, "只有一行的笔记", false, 0L, 0L)
        assertEquals("只有一行的笔记", single.heading)
        assertEquals("单行笔记没有剩余内容，卡片不该再把它画一遍", "", single.summary)

        notes.search("白板")
        assertEquals(1, notes.visible.size)
        notes.search("查不到的词")
        assertTrue(notes.visible.isEmpty())
        assertEquals("明文笔记不该报锁定隐藏条数", 0, notes.hiddenByLock)

        notes.search("")
        idle()
        notes.delete(row.id)
        awaitUntil {notes.rows.isEmpty() }
        assertEquals(0, runBlocking { db.notes().count() })
    }

    @Test fun privateNoteRefusesToStoreBeforeAMasterPasswordExists() {
        type("内网账号 abc / 密码 xyz987", secret = true)
        assertTrue("没设过主密码，应该要求先设", notes.masterSetupRequired)
        assertFalse(notes.unlockRequired)
        assertEquals("一个字都不该落盘", 0, runBlocking { db.notes().count() })

        idle()
        notes.setupMaster("短口令", "短口令")
        assertNotNull(vm.error); vm.clearError()
        assertFalse(notes.hasMaster)
        idle()
        notes.setupMaster(master, "另一个值")
        assertNotNull(vm.error); vm.clearError()
        assertFalse(notes.hasMaster)

        idle()
        notes.setupMaster(master, master)
        awaitUntil {notes.rows.size == 1 }
        assertTrue(notes.unlocked)
        assertNull("设完密码要接着把草稿存掉，不能让用户再点一次", notes.draft)

        val stored = runBlocking { db.notes().all() }.single()
        assertTrue(stored.secret)
        assertEquals("私密正文绝不能写进明文列", "", stored.text)
        assertFalse("密文里不该出现原文片段", stored.cipher.contains("xyz987"))
        assertEquals("内网账号 abc / 密码 xyz987", notes.plainText(stored.id))
    }

    @Test fun lockedPrivateNotesStayHiddenButStillGetCounted() {
        notes.setupMaster(master, master)
        awaitUntil {notes.hasMaster }
        type("验证码 6391", secret = true)
        awaitUntil {notes.rows.size == 1 }

        notes.lock()
        assertFalse(notes.unlocked)
        awaitUntil {notes.rows.single().locked }
        val locked = notes.rows.single()
        assertTrue(locked.locked)
        assertEquals("", locked.text)
        assertEquals("", locked.heading)
        assertEquals("锁着的时候摘要行也不能带出正文", "", locked.summary)
        assertNull(notes.plainText(locked.id))

        notes.search("6391")
        assertTrue("没解锁就是搜不到", notes.visible.isEmpty())
        assertEquals("但必须如实报出有条数在这里", 1, notes.hiddenByLock)

        // 编辑入口也一样：点进去只能触发解锁，不能绕过。
        notes.search("")
        notes.open(locked)
        assertTrue(notes.unlockRequired)
        assertNull(notes.draft)
    }

    @Test fun wrongUnlockKeepsTheDraftAndTheRightOneFinishesTheSave() {
        notes.setupMaster(master, master)
        awaitUntil {notes.hasMaster }
        notes.lock()

        notes.create()
        notes.change(notes.draft!!.copy(text = "数据库只读账号", secret = true))
        idle()
        notes.save()
        assertTrue(notes.unlockRequired)

        idle()
        notes.unlock("不对的密码12")
        assertNotNull(vm.error); vm.clearError()
        assertFalse(notes.unlocked)
        assertNotNull("解锁失败不能把草稿弄丢", notes.draft)
        assertEquals(0, runBlocking { db.notes().count() })

        idle()
        notes.unlock(master)
        awaitUntil {notes.rows.size == 1 }
        assertNull(notes.draft)
        assertTrue(notes.rows.single().secret)
        assertFalse(notes.unlockRequired)
    }

    @Test fun changeMasterAbortsEntirelyWhenAnyNoteBelongsToAnotherPassword() {
        notes.setupMaster(master, master)
        awaitUntil {notes.hasMaster }
        type("本机的私密内容", secret = true)
        awaitUntil {notes.rows.size == 1 }

        val foreign = NoteCrypto.sealText("Qita-123456".toCharArray(), NoteCrypto.newSalt(), "别人备份里的内容")
        val foreignId = runBlocking { db.notes().insert(Note(cipher = foreign, secret = true)) }
        awaitUntil {notes.rows.size == 2 }
        assertTrue(notes.rows.any { it.unreadable })

        idle()
        notes.changeMaster(master, "XinMiMa-1234", "XinMiMa-1234")
        awaitUntil { vm.error != null }
        assertTrue(vm.error!!.contains("1 条"))
        assertTrue(vm.error!!.contains("一个字都没改"))
        vm.clearError()

        // 失败不能动过任何一条：本机那条还得用原主密码解得开。
        val stored = runBlocking { db.notes().all() }
        assertEquals(2, stored.size)
        val kept = stored.first { it.id == foreignId }
        assertEquals("别人备份里的内容", NoteCrypto.openText(KeySession("Qita-123456".toCharArray()), kept.cipher))
        assertEquals("本机的私密内容", NoteCrypto.openText(KeySession(master.toCharArray()),
            stored.first { it.id != foreignId }.cipher))

        notes.open(notes.rows.first { it.unreadable })
        assertNotNull(vm.error)
        assertNull("解不开的笔记不能进编辑器，否则保存就是覆盖", notes.draft)
        vm.clearError()
    }

    @Test fun changeMasterReencryptsWithTheNewPasswordOnly() {
        notes.setupMaster(master, master)
        awaitUntil {notes.hasMaster }
        type("第一条私密", secret = true)
        awaitUntil {notes.rows.size == 1 }
        val before = runBlocking { db.notes().all() }.single().cipher

        idle()
        notes.changeMaster(master, "XinMiMa-1234", "不一致")
        assertNotNull(vm.error); vm.clearError()
        idle()
        notes.changeMaster("错的旧密码12", "XinMiMa-1234", "XinMiMa-1234")
        awaitUntil { vm.error != null }
        assertTrue(vm.error!!.contains("旧主密码不对"))
        vm.clearError()
        assertEquals("放弃修改时密文原样保留", before, runBlocking { db.notes().all() }.single().cipher)

        val old = KeySession(master.toCharArray())
        val fresh = KeySession("XinMiMa-1234".toCharArray())
        idle()
        notes.changeMaster(master, "XinMiMa-1234", "XinMiMa-1234")
        awaitUntil { runBlocking { db.notes().all() }.single().cipher != before }
        val after = runBlocking { db.notes().all() }.single()
        assertEquals("第一条私密", NoteCrypto.openText(fresh, after.cipher))
        assertThrows(IllegalArgumentException::class.java) { NoteCrypto.openText(old, after.cipher) }
        awaitUntil { notes.plainText(after.id) == "第一条私密" }
    }

    @Test fun clipboardCaptureStoresOnceAndSaysSo() {
        notes.capture("同一段文字")
        awaitUntil {notes.rows.size == 1 }
        awaitUntil { vm.message != null }
        assertEquals("同一段文字", notes.rows.single().text)
        assertFalse("从提示条存下来的默认不是私密笔记", notes.rows.single().secret)
        vm.clearMessage()

        notes.capture("  同一段文字  ")
        awaitUntil { vm.message != null }
        assertTrue(vm.message!!.contains("已经在笔记里"))
        assertEquals(1, notes.rows.size)
    }

    @Test fun textIsCappedAtTheSharedLimit() {
        notes.create("字".repeat(NoteCrypto.MAX_TEXT + 500))
        assertEquals(NoteCrypto.MAX_TEXT, notes.draft!!.text.length)
        notes.change(notes.draft!!.copy(text = "短"))
        assertEquals("短", notes.draft!!.text)
    }

    @Test fun unsavedNoteDraftSurvivesProcessDeathWithoutTheKey() {
        notes.create()
        notes.change(notes.draft!!.copy(text = "还没保存的笔记", secret = true))

        val copy = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = VaultViewModel(app, copy).notes
        awaitUntil { restored.draft?.text == "还没保存的笔记" }
        assertTrue(restored.draft!!.secret)
        assertFalse("主密码不跟着草稿存：新进程必须重新解锁", restored.unlocked)

        idle(restored)
        restored.save()
        assertTrue("恢复出来的私密草稿在没有主密码时只能走设密码，不能直接落盘", restored.masterSetupRequired)
        assertEquals("没解锁之前一个字都不该写进明文列", 0, runBlocking { db.notes().count() })
    }

    /** MainActivity.onStop 会调 lock()：切出去一下不能把写到一半的私密口令丢掉。 */
    @Test fun autoLockInBackgroundKeepsAHalfTypedPrivateDraft() {
        notes.setupMaster(master, master)
        awaitUntil {notes.hasMaster }
        type("已存好的私密笔记", secret = true)
        awaitUntil { notes.rows.size == 1 }
        val firstId = notes.rows.single().id

        notes.create()
        notes.change(notes.draft!!.copy(text = "写到一半的口令", secret = true))
        idle()

        notes.lock()
        assertTrue(notes.draftLocked)
        assertEquals("写到一半的口令", notes.draft?.text)
        assertTrue("锁上之后解锁框要自动弹回来，不然用户只看到一个空白页", notes.unlockRequired)
        assertTrue("屏幕上不能留任何私密正文", notes.rows.all { it.locked })

        notes.unlock(master)
        awaitUntil {notes.rows.size == 2 }
        assertFalse(notes.draftLocked)
        assertFalse(notes.unlockRequired)
        assertNull("解锁后要顺手把草稿存掉，用户不该再点一次保存", notes.draft)
        val fresh = runBlocking { db.notes().all() }.first { it.id != firstId }
        assertEquals("写到一半的口令", notes.plainText(fresh.id))
        assertEquals("", fresh.text)
    }

    @Test fun lockingRightAfterUnlockDoesNotLetASlateRenderPutTheTextBack() {
        notes.setupMaster(master, master)
        awaitUntil { notes.hasMaster }
        type("内网 VPN 口令", secret = true)
        awaitUntil { notes.rows.size == 1 }

        // 解锁要重算每一条正文，锁定只要掩码；两次重算都在后台线程跑，完成顺序不定。
        idle()
        notes.unlock(master)
        notes.lock()
        settle()
        val row = notes.rows.single()
        assertTrue("晚到的旧结果不能把明文写回屏幕", row.locked)
        assertEquals("", row.text)
        assertNull(notes.plainText(row.id))
    }
}
