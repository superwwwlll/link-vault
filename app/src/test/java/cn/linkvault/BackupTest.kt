package cn.linkvault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupTest {
    @Before fun fastKeyDerivation() { NoteCrypto.iterations = 10_000 }

    private fun item(url: String = "https://x.com/Astronaut_1216/status/2097610542127223251?s=20") = Bookmark(url = url, canonical = Links.canonical(url), title = "中文标题", notes = "备注\n第二行 ✨", tags = "技术,稍后读", updatedAt = 1234)
    private fun invalid(bytes: ByteArray) { assertThrows(Exception::class.java) { Backup.decode(bytes) } }
    @Test fun roundTripPreservesUnicodeAndOriginalUrl() {
        val original = item()
        val result = Backup.decode(Backup.encode(listOf(original)))
        assertEquals(listOf(original), result)
    }
    @Test fun nestingBombRejectedBeforeParsing() { invalid(("[".repeat(10000) + "0" + "]".repeat(10000)).toByteArray()) }
    @Test fun emptyBackupIsValid() { assertEquals(emptyList<Bookmark>(), Backup.decode(Backup.encode(emptyList()))) }
    @Test fun formatAndVersionAreRequired() {
        val root = JSONObject(String(Backup.encode(listOf(item()))))
        root.put("version", Backup.VERSION + 1); invalid(root.toString().toByteArray())
        root.put("version", Backup.VERSION.toString()); invalid(root.toString().toByteArray())
        root.put("version", 1).put("format", "other"); invalid(root.toString().toByteArray())
    }
    @Test fun malformedInputRejected() {
        listOf("", "[]", "{", "null", "{\"format\":\"cn.linkvault.backup\",\"version\":1}").forEach { invalid(it.toByteArray()) }
        invalid(byteArrayOf(0xC3.toByte(), 0x28)); invalid(Backup.encode(emptyList()) + "garbage".toByteArray())
    }
    @Test fun unsafeUrlRejectsWholeFile() {
        val root = JSONObject(String(Backup.encode(listOf(item(), item("https://example.com")))))
        root.getJSONArray("bookmarks").getJSONObject(1).put("url", "javascript:alert(1)")
        invalid(root.toString().toByteArray())
    }
    @Test fun invalidFieldTypesAndLengthsRejected() {
        fun changed(key: String, value: Any) { val root = JSONObject(String(Backup.encode(listOf(item())))); root.getJSONArray("bookmarks").getJSONObject(0).put(key, value); invalid(root.toString().toByteArray()) }
        changed("title", "x".repeat(201)); changed("notes", JSONObject.NULL); changed("tags", "技术")
        changed("tags", JSONArray().put("a,b")); changed("updatedAt", -1); changed("updatedAt", 1.5)
    }
    @Test fun byteAndRecordLimits() {
        val oversized = ByteArray(Backup.MAX_BYTES + 1)
        assertThrows(Exception::class.java) { Backup.read(oversized.inputStream()) }
        invalid(oversized)
        val root = JSONObject(String(Backup.encode(emptyList())))
        val rows = JSONArray(); repeat(Backup.MAX_ITEMS + 1) { rows.put(JSONObject()) }
        root.put("bookmarks", rows); invalid(root.toString().toByteArray())
    }
    @Test fun mergeIsIdempotentAndDoesNotOverwrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, VaultDb::class.java).build()
        try {
            val old = item().copy(title = "原有标题", notes = "不能覆盖")
            val id = db.bookmarks().insert(old)
            val incoming = listOf(item(), item("https://example.com/new"), item("https://example.com/new"))
            assertEquals(1, Backup.countNew(incoming, db.bookmarks().all()))
            assertEquals(MergeResult(1, 2), Backup.merge(db, incoming))
            assertEquals(MergeResult(0, 3), Backup.merge(db, incoming))
            assertEquals(old.copy(id = id), db.bookmarks().byKey(old.canonical))
            assertEquals(2, db.bookmarks().all().size)
        } finally { db.close() }
    }
    @Test fun failedMergeRollsBackAllRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, VaultDb::class.java).build()
        try {
            var failed = false
            try { Backup.merge(db, listOf(item(), item().copy(url = "file:///unsafe"))) } catch (_: Exception) { failed = true }
            assertTrue(failed); assertTrue(db.bookmarks().all().isEmpty())
        } finally { db.close() }
    }

    @Test fun olderBackupsWithoutTheNewFieldsStillImport() {
        val root = JSONObject(String(Backup.encode(listOf(item()))))
        val row = root.getJSONArray("bookmarks").getJSONObject(0)
        listOf("createdAt", "pinned", "archived", "read", "summary", "siteName", "image", "fetchedAt").forEach { row.remove(it) }
        val restored = Backup.decode(root.toString().toByteArray()).single()
        // 缺少收藏时间时退回更新时间，宁可近似也不能让排序变成 1970 年
        assertEquals(1234L, restored.createdAt)
        assertEquals(1234L, restored.updatedAt)
        assertFalse(restored.pinned); assertFalse(restored.archived); assertFalse(restored.read)
        assertEquals("", restored.summary); assertEquals("", restored.siteName); assertEquals(0L, restored.fetchedAt)
        // 1.3.8 之前的备份根本没有 image 这个键，导入后就是「没有封面图」，不能报错
        assertEquals("", restored.image)
    }

    @Test fun newFieldsRoundTripExactly() {
        val rich = item().copy(createdAt = 99L, pinned = true, archived = true, read = true, summary = "页面描述", siteName = "示例站", image = "https://cdn.example.com/cover.jpg", fetchedAt = 555L)
        assertEquals(listOf(rich), Backup.decode(Backup.encode(listOf(rich))))
    }

    @Test fun invalidNewFieldTypesOrLengthsAreRejected() {
        fun changed(key: String, value: Any) {
            val root = JSONObject(String(Backup.encode(listOf(item()))))
            root.getJSONArray("bookmarks").getJSONObject(0).put(key, value)
            invalid(root.toString().toByteArray())
        }
        changed("pinned", "yes"); changed("read", 1); changed("archived", JSONObject.NULL.let { listOf(1) })
        changed("createdAt", -1); changed("createdAt", 1.5); changed("fetchedAt", -5)
        changed("summary", "x".repeat(8001)); changed("siteName", "x".repeat(201))
        changed("image", "x".repeat(501)); changed("image", 42)
    }

    /** 一键备份只清理自己写出去的文件，其余一概不动。 */
    @Test fun backupPruningKeepsOnlyTheNewestBackupsAndIgnoresEverythingElse() {
        val names = listOf(
            "链藏备份-20260101-0900.json",
            "链藏备份-20260301-0900.json",
            "链藏备份-20260201-0900.json",
            "链藏书签-20251201-0900.html",
            "链藏加密备份-20260101-0900.lvault",
            "我的报销单.json",
        )

        assertEquals(listOf("链藏备份-20260101-0900.json"), Backup.staleBackups(names, 2))
        assertTrue(Backup.staleBackups(names, 3).isEmpty())
        val all = Backup.staleBackups(names, 0)
        assertEquals(3, all.size)
        assertTrue(all.all { it.startsWith(Backup.BACKUP_PREFIX) && it.endsWith(".json") })
    }

    @Test fun htmlBookmarksRoundTrip() {
        val sample = listOf(
            Bookmark(url = "https://example.com/test", canonical = "https://example.com/test", title = "测试标题", tags = "设计,灵感", createdAt = 1700000000000L),
            Bookmark(url = "https://developer.android.com", canonical = "https://developer.android.com", title = "Android 官方文档", tags = "技术", createdAt = 1700000500000L)
        )
        val htmlBytes = Backup.encodeHtml(sample)
        val decoded = Backup.decode(htmlBytes)
        assertEquals(2, decoded.size)
        assertEquals("https://example.com/test", decoded[0].url)
        assertEquals("测试标题", decoded[0].title)
        assertEquals("设计,灵感", decoded[0].tags)
        assertEquals("https://developer.android.com", decoded[1].url)
    }

    @Test fun markdownExportContainsAllItems() {
        val sample = listOf(
            Bookmark(url = "https://example.com/1", canonical = "https://example.com/1", title = "标题一", notes = "备忘录笔记", tags = "设计"),
            Bookmark(url = "https://example.com/2", canonical = "https://example.com/2", title = "标题二", tags = "")
        )
        val md = String(Backup.encodeMarkdown(sample))
        assertTrue(md.contains("标题一"))
        assertTrue(md.contains("https://example.com/1"))
        assertTrue(md.contains("备忘录笔记"))
        assertTrue(md.contains("标题二"))
        assertTrue(md.contains("## 设计"))
        assertTrue(md.contains("## 未分类"))
    }

    @Test fun portalHtmlExportContainsAllItemsAndControls() {
        val sample = listOf(
            Bookmark(url = "https://github.com/superwwwlll/link-vault", canonical = "https://github.com/superwwwlll/link-vault", title = "链藏开源仓库", notes = "重点关注", tags = "开源,Android"),
            Bookmark(url = "https://kotlinlang.org", canonical = "https://kotlinlang.org", title = "Kotlin 官网", summary = "官方语言门户", tags = "开发")
        )
        val html = String(Backup.encodePortalHtml(sample))
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("链藏 · 个人导航书签"))
        assertTrue(html.contains("链藏开源仓库"))
        assertTrue(html.contains("Kotlin 官网"))
        assertTrue(html.contains("重点关注"))
        assertTrue(html.contains("官方语言门户"))
        assertTrue(html.contains("开源 (1)"))
        assertTrue(html.contains("Android (1)"))
        assertTrue(html.contains("开发 (1)"))
        assertTrue(html.contains("id=\"search\""))
        assertTrue(html.contains("copyUrl"))
    }

    /**
     * 回归：链接里带引号时不能把页面脚本里的字符串提前闭合。
     *
     * 生成端把链接写进 onclick="copyUrl('...')" 时，HTML 属性会在交给 JS 之前先把 &#39; 还原成
     * 单引号，于是链接内容变成了代码。现在链接只待在 data-url 属性里，由脚本自己取。
     */
    @Test fun portalHtmlExportKeepsBookmarkUrlOutOfInlineScript() {
        val hostile = "https://example.com/-');steal('all');"
        val html = String(Backup.encodePortalHtml(listOf(Bookmark(url = hostile, canonical = hostile, title = "可疑链接"))))

        assertFalse("页面里不允许出现任何内联事件处理器",
            Regex("""(?i)\sonclick\s*=""").containsMatchIn(html))
        val script = Regex("(?s)<script>(.*?)</script>").find(html)!!.groupValues[1]
        assertFalse("内联脚本里不允许出现链接原文", script.contains(hostile))
        assertFalse("链接里的内容不允许变成可执行代码", script.contains("steal("))
        val embedded = Regex("""data-url="([^"]*)"""").find(html)!!.groupValues[1]
        assertEquals("链接应以转义形式留在属性里", "https://example.com/-&#39;);steal(&#39;all&#39;);", embedded)
    }

    // ------------------------------------------------------------ v2：笔记与主密码

    private fun sealedNote(password: String, text: String): Note = Note(
        cipher = NoteCrypto.sealText(password.toCharArray(), NoteCrypto.newSalt(), text),
        secret = true, createdAt = 1000, updatedAt = 2000
    )

    private fun masterRecord(password: String): VaultMaster = VaultMaster(
        salt = NoteCrypto.encode(NoteCrypto.newSalt()),
        verifier = NoteCrypto.verifierText(password.toCharArray(), NoteCrypto.newSalt()),
        createdAt = 1000
    )

    /** 1.4.0 及更早的设备导出的文件：没有 notes / vault 两项，必须照常导入。 */
    @Test fun v1FilesWithoutNotesStillImport() {
        val original = item()
        val root = JSONObject(String(Backup.encode(listOf(original)))).put("version", 1)
        val contents = Backup.decodeContents(root.toString().toByteArray())
        assertEquals(listOf(original), contents.bookmarks)
        assertTrue(contents.notes.isEmpty())
        assertNull(contents.master)
    }

    /** v1 文件里冒出笔记或主密码，只可能是手工改的：读一半等于骗用户说导入成功了。 */
    @Test fun v1FilesClaimingNotesOrMasterAreRejected() {
        val withNotes = JSONObject(String(Backup.encode(listOf(item()), listOf(Note(text = "普通笔记")))))
            .put("version", 1)
        invalid(withNotes.toString().toByteArray())
        val withVault = JSONObject(String(Backup.encode(listOf(item()))))
            .put("version", 1).put("vault", JSONObject().put("salt", "c2FsdA").put("verifier", "abc"))
        invalid(withVault.toString().toByteArray())
    }

    @Test fun notesAndMasterRoundTripThroughTheEncryptedChannel() {
        val original = item()
        val plain = Note(text = "内网代理 10.20.30.40:8888", createdAt = 1000, updatedAt = 2000)
        val secret = sealedNote(PASSWORD, "WiFi 口令")
        val master = masterRecord(PASSWORD)
        val contents = Backup.decodeContents(Backup.encode(listOf(original), listOf(plain, secret), master))
        assertEquals(listOf(original), contents.bookmarks)
        assertEquals(listOf(plain, secret), contents.notes)
        assertEquals(master.salt, contents.master?.salt)
        assertEquals(master.verifier, contents.master?.verifier)
    }

    /** 明文备份走的是默认参数这条路，所以挡下私密笔记的是 encode 本身，不是调用方记性。 */
    @Test fun fileWithoutAMasterCannotDescribeSecretNotes() {
        assertThrows(Exception::class.java) { Backup.encode(listOf(item()), listOf(sealedNote(PASSWORD, "口令"))) }
    }

    @Test fun inconsistentNoteRowsAreRejected() {
        fun file(vararg rows: JSONObject) = JSONObject(String(Backup.encode(listOf(item()))))
            .put("notes", JSONArray().apply { rows.forEach { put(it) } }).toString().toByteArray()
        // 私密笔记只该带密文：带明文等于把口令同时写两份，一份还是没加密的那份。
        invalid(file(JSONObject().put("secret", true).put("text", "明文冒充私密").put("updatedAt", 1)))
        invalid(file(JSONObject().put("secret", false).put("cipher", sealedNote(PASSWORD, "x").cipher).put("updatedAt", 1)))
        invalid(file(JSONObject().put("secret", true).put("cipher", "!!!不是 Base64!!!").put("updatedAt", 1)))
        invalid(file(JSONObject().put("secret", false).put("text", "没有时间戳")))
        invalid(file(JSONObject().put("secret", false)))
    }

    @Test fun malformedMasterRecordIsRejected() {
        val root = JSONObject(String(Backup.encode(listOf(item()))))
            .put("vault", JSONObject().put("salt", "!!!").put("verifier", "abc"))
        invalid(root.toString().toByteArray())
        val missingVerifier = JSONObject(String(Backup.encode(listOf(item()))))
            .put("vault", JSONObject().put("salt", NoteCrypto.encode(NoteCrypto.newSalt())))
        invalid(missingVerifier.toString().toByteArray())
    }

    /** 别人主密码封的密文要原样搬进来：本机此刻读不出，但一条都不该丢。 */
    @Test fun foreignSecretNotesSurviveImportUntouched() = runBlocking {
        val db = newDb()
        try {
            val contents = Backup.decodeContents(
                Backup.encode(listOf(item()), listOf(sealedNote(FOREIGN, "路由器管理口令")), masterRecord(FOREIGN))
            )
            val outcome = Backup.mergeAll(db, contents.bookmarks, contents.notes, contents.master)
            assertEquals(1, outcome.bookmarks.added)
            assertEquals(NotesMergeResult(1, 0, true), outcome.notes)
            val stored = db.notes().all().single()
            assertEquals("", stored.text)
            assertEquals(contents.notes.single().cipher, stored.cipher)
            // 文件里那个密码仍然打得开：导入没动过密文一个字节
            val key = KeySession(FOREIGN.toCharArray())
            assertEquals("路由器管理口令", NoteCrypto.openText(key, stored.cipher))
            key.lock()
            val wrong = KeySession(PASSWORD.toCharArray())
            assertFalse(NoteCrypto.check(wrong, NoteCrypto.decode(db.vault().master()!!.verifier)))
            wrong.lock()
        } finally { db.close() }
    }

    /** 本机已经设过主密码时，绝不能被这份备份换掉——那等于让现有全部私密笔记当场变解不开。 */
    @Test fun localMasterIsNeverReplacedByABackup() = runBlocking {
        val db = newDb()
        try {
            val local = masterRecord(PASSWORD)
            db.vault().insert(local.copy(id = VaultMaster.ROW_ID))
            val outcome = Backup.mergeNotes(db, listOf(sealedNote(FOREIGN, "口令")), masterRecord(FOREIGN))
            assertFalse(outcome.masterAdopted)
            assertEquals(local.salt, db.vault().master()?.salt)
        } finally { db.close() }
    }

    /** 明文按正文去重；私密的比对密文，而 iv 每次不同，所以换一次导出就会多出一条。 */
    @Test fun plainNotesDedupeButReExportedSecretNotesCannot() = runBlocking {
        val db = newDb()
        try {
            val text = Note(text = "同一句话", createdAt = 1, updatedAt = 1)
            assertEquals(NotesMergeResult(1, 1, false), Backup.mergeNotes(db, listOf(text, text), null))
            assertEquals(NotesMergeResult(0, 1, false), Backup.mergeNotes(db, listOf(text), null))
            assertEquals(1, db.notes().all().size)

            val first = sealedNote(PASSWORD, "同一句口令")
            assertEquals(NotesMergeResult(1, 0, false), Backup.mergeNotes(db, listOf(first), null))
            val second = sealedNote(PASSWORD, "同一句口令")
            assertNotEquals(first.cipher, second.cipher)
            assertEquals(NotesMergeResult(1, 0, false), Backup.mergeNotes(db, listOf(second), null))
            assertEquals(NotesMergeResult(0, 1, false), Backup.mergeNotes(db, listOf(second), null))
            assertEquals(3, db.notes().all().size)
        } finally { db.close() }
    }

    /** 界面那句「失败已整体回滚」：两条通道要么都写进去，要么一条都不留。 */
    @Test fun mergeAllRollsBackEveryTableWhenAnyRowFails() = runBlocking {
        val db = newDb()
        try {
            var failed = false
            try {
                Backup.mergeAll(
                    db,
                    listOf(item(), item().copy(url = "file:///unsafe")),
                    listOf(Note(text = "普通笔记"), sealedNote(PASSWORD, "口令")),
                    masterRecord(PASSWORD)
                )
            } catch (_: Exception) { failed = true }
            assertTrue("有一份内容没写进去就必须整体失败", failed)
            assertTrue(db.bookmarks().all().isEmpty())
            assertTrue(db.notes().all().isEmpty())
            assertNull(db.vault().master())
        } finally { db.close() }
    }

    private fun newDb(): VaultDb {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, VaultDb::class.java).build()
    }

    private companion object {
        const val PASSWORD = "本机的主密码password"
        const val FOREIGN = "另一台设备的主密码"
    }
}
