package cn.linkvault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupTest {
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
        root.put("version", 2); invalid(root.toString().toByteArray())
        root.put("version", "1"); invalid(root.toString().toByteArray())
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
        listOf("createdAt", "pinned", "archived", "read", "summary", "siteName", "fetchedAt").forEach { row.remove(it) }
        val restored = Backup.decode(root.toString().toByteArray()).single()
        // 缺少收藏时间时退回更新时间，宁可近似也不能让排序变成 1970 年
        assertEquals(1234L, restored.createdAt)
        assertEquals(1234L, restored.updatedAt)
        assertFalse(restored.pinned); assertFalse(restored.archived); assertFalse(restored.read)
        assertEquals("", restored.summary); assertEquals("", restored.siteName); assertEquals(0L, restored.fetchedAt)
    }

    @Test fun newFieldsRoundTripExactly() {
        val rich = item().copy(createdAt = 99L, pinned = true, archived = true, read = true, summary = "页面描述", siteName = "示例站", fetchedAt = 555L)
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
}
