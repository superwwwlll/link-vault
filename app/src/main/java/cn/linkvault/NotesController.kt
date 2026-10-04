package cn.linkvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表与编辑器用的一行笔记。 */
data class NoteRow(
    val id: Long,
    val text: String,
    val secret: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** 私密笔记、当前没解锁：内容一个字符都不读出来。 */
    val locked: Boolean = false,
    /** 已解锁，但这条解不开 —— 它属于另一个主密码，或者密文已损坏。 */
    val unreadable: Boolean = false
) {
    /** 列表标题行：只取首行，走收藏标题同一套清洗，所以不会出现换行和残留的站点名。 */
    val heading: String get() = if (locked || unreadable) "" else Titles.collapse(text.lineSequence().first())

    /** 标题行以下的剩余内容。单行笔记这里是空的，卡片就不会把同一段字显示两遍。 */
    val summary: String get() = if (locked || unreadable) "" else text.substringAfter('\n', "").trim()
}

data class NoteDraft(val id: Long = 0, val text: String = "", val secret: Boolean = false)

/**
 * 笔记区的全部状态与操作，刻意独立于 [VaultViewModel]（那边已接近 1,200 行）。
 *
 * 界面只认两个"读不出"：[NoteRow.locked]（没解锁）和 [NoteRow.unreadable]（解锁了也不属于这个主密码）。
 * 两者都必须显示出来，绝不能悄悄少一条 —— 用户把"搜不到"当成"没有"是最坏的失效方式。
 */
class NotesController(
    private val db: VaultDb,
    private val scope: CoroutineScope,
    private val saved: SavedStateHandle,
    private val toast: (String) -> Unit,
    private val fail: (String) -> Unit,
    private val onSaved: (Note) -> Unit = {},
    private val onDeleted: (Long) -> Unit = {}
) {
    private val notes = db.notes()
    private val vault = db.vault()

    private var raw by mutableStateOf(emptyList<Note>())
    private var session: KeySession? = null
    private var master: VaultMaster? = null
    private var collectJob: Job? = null

    var rows by mutableStateOf(emptyList<NoteRow>()); private set
    var search by mutableStateOf(saved.get<String>("noteSearch") ?: ""); private set
    var hasMaster by mutableStateOf(false); private set
    var unlocked by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var draft by mutableStateOf(restoreDraft()); private set
    /** 界面据此弹出解锁框 / 首次设主密码框。 */
    var unlockRequired by mutableStateOf(false); private set
    var masterSetupRequired by mutableStateOf(false); private set

    val total: Int get() = raw.size
    val secretTotal: Int get() = raw.count { it.secret }

    init {
        scope.launch {
            master = vault.master()
            hasMaster = master != null
            reload()
        }
    }

    /**
     * 导入写完库之后叫一次：备份里可能自带一条主密码记录，而本机原来没设过时才会启用它。
     * [hasMaster] 只在启动时读过一次，不重读的话界面会一直显示"还没设主密码"。
     */
    fun afterImport() {
        scope.launch {
            master = vault.master()
            hasMaster = master != null
        }
    }

    fun reload() {
        collectJob?.cancel()
        collectJob = scope.launch {
            notes.observe().catch { if (it is CancellationException) throw it; fail("读取笔记失败：${it.localizedMessage}") }
                .collect { raw = it; publish() }
        }
    }

    /**
     * 解锁状态变了就要重算展示内容，而派生密钥可能很慢，所以放后台线程。
     *
     * 令牌用于丢弃过期结果：列表变化（Room 的 emission）和会话变化（锁定、改密码）都会各自
     * 发起一次重算，后台线程完成顺序和发起顺序无关。谁都能写 rows 的话，一次带着旧密文快照的
     * 重算晚到，界面上就会出现"这条笔记解不开"（其实好好的），反过来一次带明文的晚到，
     * 就是用户按了锁定之后私密内容还留在屏幕上。
     */
    private var publishToken = 0
    private fun publish() {
        val token = ++publishToken
        val snapshot = raw
        val key = session
        scope.launch {
            val next = withContext(Dispatchers.Default) { snapshot.map { render(it, key) } }
            if (token == publishToken) rows = next
        }
    }

    private fun render(note: Note, key: KeySession?): NoteRow = when {
        !note.secret -> NoteRow(note.id, note.text, false, note.createdAt, note.updatedAt)
        key == null -> NoteRow(note.id, "", true, note.createdAt, note.updatedAt, locked = true)
        else -> runCatching { NoteRow(note.id, NoteCrypto.openText(key, note.cipher), true, note.createdAt, note.updatedAt) }
            .getOrDefault(NoteRow(note.id, "", true, note.createdAt, note.updatedAt, unreadable = true))
    }

    val visible: List<NoteRow> get() {
        val query = search.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return rows
        return rows.filter { !it.locked && !it.unreadable && it.text.lowercase(Locale.ROOT).contains(query) }
    }

    /** 因为没解锁而没能参与搜索的条数。搜索词为空时不报，否则只是白打扰。 */
    val hiddenByLock: Int get() = if (search.isBlank()) 0 else rows.count { it.locked }

    fun search(value: String) { search = value.take(500); saved["noteSearch"] = search }

    // ------------------------------------------------------------ 编辑器

    fun create(seed: String = "") {
        edit(NoteDraft(text = seed.trim().take(NoteCrypto.MAX_TEXT)))
    }

    fun open(row: NoteRow) {
        if (row.unreadable) { fail("这条笔记属于另一个主密码，先解锁原来那个才能看和改"); return }
        if (row.locked) { requireKey(); return }
        edit(NoteDraft(row.id, row.text, row.secret))
    }

    fun edit(value: NoteDraft?) {
        draft = value
        persist(value)
        unlockRequired = false
    }

    /**
     * 草稿的三个字段必须一起写。少写 secret 那一项，进程被杀后重新恢复出来的草稿就会
     * 变成一条明文草稿，而用户以为它还是私密的 —— 下一次保存就直接把密码写进明文列。
     */
    private fun persist(value: NoteDraft?) {
        if (value == null) {
            saved["noteDraftId"] = null; saved["noteDraftText"] = null; saved["noteDraftSecret"] = null
        } else {
            saved["noteDraftId"] = value.id
            saved["noteDraftText"] = value.text
            saved["noteDraftSecret"] = value.secret
        }
    }

    fun change(value: NoteDraft) {
        val next = value.copy(text = value.text.take(NoteCrypto.MAX_TEXT))
        draft = next
        persist(next)
    }

    fun save() {
        val current = draft ?: return
        if (busy) return
        busy = true
        scope.launch {
            try { store(current) } finally { busy = false }
        }
    }

    /**
     * 校验 + 落盘。私密正文在没解锁时不会走到写库那一步，而是把界面切到解锁流程，
     * 解锁/设密码成功后由那边直接再调本函数，草稿一字不丢。
     */
    private suspend fun store(value: NoteDraft) {
        val body = value.text.trim()
        if (body.isEmpty()) { fail("笔记内容不能为空"); return }
        if (value.secret && session == null) { requireKey(); return }
        val existing = if (value.id > 0) notes.byId(value.id) else null
        val now = System.currentTimeMillis()
        val row = if (value.secret) {
            val salt = master?.salt?.let { NoteCrypto.decode(it) } ?: run { requireKey(); return }
            Note(
                id = existing?.id ?: 0L, text = "", cipher = NoteCrypto.sealTextWith(session!!, salt, body),
                secret = true, createdAt = existing?.createdAt ?: now, updatedAt = now
            )
        } else {
            Note(id = existing?.id ?: 0L, text = body, cipher = "", secret = false,
                createdAt = existing?.createdAt ?: now, updatedAt = now)
        }
        val id = if (existing == null) notes.insert(row) else { notes.update(row); row.id }
        onSaved(row.copy(id = id))
        edit(null)
        toast(if (existing == null) "已保存笔记" else "已更新笔记")
    }

    /** 界面也可以直接要钥匙：笔记页的「解锁」入口走的就是这条。 */
    fun requireKey() {
        if (hasMaster) unlockRequired = true else masterSetupRequired = true
    }

    /** 关掉解锁/设密码弹窗：草稿原样留着，用户可以选择不解锁继续别的事。 */
    fun dismissKeyPrompt() { unlockRequired = false; masterSetupRequired = false }

    /** 从首页提示条一键存下剪贴板文字。默认按普通笔记存，用户想加密再进编辑页打开开关。 */
    fun capture(text: String) {
        val body = text.trim().take(NoteCrypto.MAX_TEXT)
        if (body.isEmpty()) { fail("剪贴板里没有文字"); return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                // 只对明文去重：密文每次 iv 不同，比对密文永远得不出"重复"。
                if (notes.plainTexts().any { it == body }) { toast("这段文字已经在笔记里了"); return@launch }
                val now = System.currentTimeMillis()
                val row = Note(text = body, createdAt = now, updatedAt = now)
                onSaved(row.copy(id = notes.insert(row)))
                toast("已存为笔记 · ${body.length} 字")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("存为笔记失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    fun delete(id: Long) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                check(notes.delete(id) == 1) { "这条笔记已不存在" }
                onDeleted(id)
                if (draft?.id == id) edit(null)
                toast("已删除笔记。笔记不进回收站，删掉就没了")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("删除失败：${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    /** 给复制按钮取明文。锁着或解不开时返回 null，界面不该复制出空字符串还不说明原因。 */
    fun plainText(id: Long): String? = rows.firstOrNull { it.id == id }?.takeIf { !it.locked && !it.unreadable }?.text

    // ------------------------------------------------------------ 主密码

    fun setupMaster(password: String, confirm: String) {
        if (busy) return
        if (hasMaster) { fail("主密码已经设置过了，要换请走「修改主密码」"); return }
        if (!NoteCrypto.validPassword(password.toCharArray())) { fail("主密码至少需要 ${NoteCrypto.MIN_PASSWORD} 个字符"); return }
        if (password != confirm) { fail("两次输入的主密码不一致"); return }
        busy = true
        scope.launch {
            try {
                val salt = NoteCrypto.newSalt()
                val row = VaultMaster(salt = NoteCrypto.encode(salt), verifier = NoteCrypto.verifierText(password.toCharArray(), salt))
                db.withTransaction { check(vault.insert(row) > 0L) { "主密码写入失败" } }
                master = row
                hasMaster = true
                session = KeySession(password.toCharArray())
                unlocked = true
                masterSetupRequired = false
                publish()
                toast("主密码已设置。它不记在任何地方，忘了无法找回")
                draft?.let { store(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("设置主密码失败：${e.localizedMessage ?: "未知错误"}") }
            finally { busy = false }
        }
    }

    /**
     * 加密备份用的口令就是笔记主密码，不另起第二个。
     *
     * 已经设过主密码时必须对得上：填别's 密码导出的文件，用户会以为"这就是我的主密码备份"，
     * 等到恢复那天才发现解不开。没设过时这一次输入就地生效 —— 此刻库里还没有任何私密笔记，
     * 设定它没有副作用，正好兑现"只此一个密码"。
     */
    suspend fun masterForBackup(password: CharArray): VaultMaster {
        val current = vault.master()
        if (current == null) {
            require(NoteCrypto.validPassword(password)) { "主密码至少需要 ${NoteCrypto.MIN_PASSWORD} 个字符" }
            val salt = NoteCrypto.newSalt()
            val row = VaultMaster(salt = NoteCrypto.encode(salt), verifier = NoteCrypto.verifierText(password.copyOf(), salt))
            db.withTransaction { check(vault.insert(row) > 0L) { "主密码写入失败" } }
            master = row
            hasMaster = true
            return row
        }
        val key = KeySession(password.copyOf())
        val matched = runCatching { NoteCrypto.check(key, NoteCrypto.decode(current.verifier)) }.getOrDefault(false)
        key.lock()
        require(matched) { "这个密码不是你的笔记主密码。加密备份用的就是它，忘了请走「修改主密码」" }
        return current
    }

    fun unlock(password: String) {
        val current = master ?: run { masterSetupRequired = true; return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val key = KeySession(password.toCharArray())
                if (!NoteCrypto.check(key, NoteCrypto.decode(current.verifier))) {
                    key.lock()
                    fail("主密码不对")
                    return@launch
                }
                session = key
                unlocked = true
                unlockRequired = false
                publish()
                val secret = raw.count { it.secret }
                toast(if (secret > 0) "已解锁 $secret 条私密笔记" else "已解锁")
                draft?.let { store(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("解锁失败：${e.localizedMessage ?: "未知错误"}") }
            finally { busy = false }
        }
    }

    /**
     * 正在编辑的私密草稿：锁上之后内容不再显示，但草稿本身保留。
     *
     * 退到后台就会自动锁定，如果把草稿一并丢掉，用户切个应用回来就发现写到一半的口令没了。
     * 界面看到这个为 true 就换成解锁提示页，正文字符一个都不画出来。
     */
    val draftLocked: Boolean get() = draft?.secret == true && !unlocked

    /** 锁定：清掉会话密钥并把私密正文从屏幕上抹掉；草稿保留，解锁后接着写。 */
    fun lock() {
        session?.lock()
        session = null
        unlocked = false
        masterSetupRequired = false
        if (draftLocked) unlockRequired = true
        // 重算在后台线程，先把已经渲染出来的私密正文就地抹掉，别让屏幕等这一次 hop。
        rows = rows.map { if (it.secret) it.copy(text = "", locked = true, unreadable = false) else it }
        publish()
    }

    val canChangeMaster: Boolean get() = hasMaster

    /**
     * 修改主密码：全部私密笔记解密后按新口令与新 salt 重封，一个事务写完。
     *
     * 只要有一条解不开就整体放弃 —— 跳过它等于用"改了密码"这个动作静默丢掉一条内容，
     * 和 v1→v2 迁移里"宁可漏一次去重也不丢收藏"是同一条规矩。
     *
     * 待处理清单必须在事务里现读数据库，不能用内存快照：Flow 的 emission 是异步的，
     * 刚保存的笔记可能还没进 [raw]。漏掉一条又恰好把 vault 记录换成新 salt，那条就永久
     * 留在旧主密码下，界面上只会显示"解不开"，而用户以为密码已经换了。
     */
    fun changeMaster(old: String, fresh: String, confirm: String) {
        val current = master ?: run { fail("还没有设置主密码"); return }
        if (busy) return
        if (!NoteCrypto.validPassword(fresh.toCharArray())) { fail("新主密码至少需要 ${NoteCrypto.MIN_PASSWORD} 个字符"); return }
        if (fresh != confirm) { fail("两次输入的新主密码不一致"); return }
        busy = true
        scope.launch {
            try {
                val oldKey = KeySession(old.toCharArray())
                if (!NoteCrypto.check(oldKey, NoteCrypto.decode(current.verifier))) {
                    oldKey.lock()
                    fail("旧主密码不对，没有改动任何内容")
                    return@launch
                }
                val newSalt = NoteCrypto.newSalt()
                val newKey = KeySession(fresh.toCharArray())
                val next = VaultMaster(id = current.id, salt = NoteCrypto.encode(newSalt),
                    verifier = NoteCrypto.verifierText(fresh.toCharArray(), newSalt), createdAt = current.createdAt)
                val (undecryptable, rewritten) = withContext(Dispatchers.Default) {
                    db.withTransaction {
                        val bad = ArrayList<Long>()
                        val done = ArrayList<Note>()
                        notes.all().filter { it.secret }.forEach { note ->
                            val text = runCatching { NoteCrypto.openText(oldKey, note.cipher) }.getOrNull()
                            if (text == null) bad += note.id
                            else done += note.copy(cipher = NoteCrypto.sealTextWith(newKey, newSalt, text))
                        }
                        if (bad.isEmpty()) {
                            done.forEach { check(notes.update(it) == 1) { "笔记已不存在，改动整体回滚" } }
                            check(vault.update(next) == 1) { "主密码记录已不存在，改动整体回滚" }
                        }
                        bad.size to done.size
                    }
                }
                if (undecryptable > 0) {
                    fail("$undecryptable 条私密笔记用旧主密码解不开（可能来自别人的备份），已放弃修改，一个字都没改")
                    return@launch
                }
                master = next
                session = newKey
                unlocked = true
                publish()
                toast("已用新主密码重新加密 $rewritten 条私密笔记")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("修改主密码失败，改动已整体回滚：${e.localizedMessage ?: "未知错误"}") }
            finally { busy = false }
        }
    }

    private fun restoreDraft(): NoteDraft? {
        val text = saved.get<String>("noteDraftText") ?: return null
        // 标记读不到时按私密处理（fail-closed）：当成明文存下去会把密码写进明文列，
        // 当成私密最多是让用户多点一次开关，两个方向里只有一个是不可逆的。
        val secret = saved.get<Boolean>("noteDraftSecret") ?: true
        return NoteDraft(id = saved.get<Long>("noteDraftId") ?: 0L, text = text, secret = secret)
    }
}
