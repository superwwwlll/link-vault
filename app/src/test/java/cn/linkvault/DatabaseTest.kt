package cn.linkvault
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseTest {
    @Test fun persistReopenUpdateSearchAndDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, VaultDb::class.java, name).build()
        val url = "https://x.com/Astronaut_1216/status/2097610542127223251?s=20"
        val id = db.bookmarks().insert(Bookmark(url = url, canonical = Links.canonical(url), title = "测试", tags = "技术,安卓"))
        db.close()
        db = Room.databaseBuilder(context, VaultDb::class.java, name).build()
        val row = db.bookmarks().byKey(Links.canonical(url))!!
        assertEquals(id, row.id); assertEquals(url, row.url)
        assertEquals(1, db.bookmarks().update(row.copy(notes = "更新备注")))
        assertEquals(1, db.bookmarks().all().filter { matches(it, "备注", "安卓") }.size)
        var duplicateRejected = false
        try { db.bookmarks().insert(row.copy(id = 0)) } catch (_: android.database.sqlite.SQLiteConstraintException) { duplicateRejected = true }
        assertTrue("唯一索引必须阻止重复", duplicateRejected)
        assertEquals(1, db.bookmarks().delete(id)); assertTrue(db.bookmarks().all().isEmpty())
        db.close(); context.deleteDatabase(name); Unit
    }
}
