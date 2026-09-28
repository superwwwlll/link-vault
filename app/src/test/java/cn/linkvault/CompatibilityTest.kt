package cn.linkvault
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CompatibilityTest {
    private fun openV1(name: String): SQLiteDatabase = openFixture("room-v1-schema.json", name)

    private fun openV4(name: String): SQLiteDatabase = openFixture("room-v4-schema.json", name)

    /** 按某个历史版本的 schema 手工建库，用来真实走一遍升级路径。 */
    private fun openFixture(resource: String, name: String): SQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            val indices = e.getJSONArray("indices")
            for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
        }
        val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
        return old
    }

    @Test fun opensOriginalV1SchemaAndPreservesExistingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("old-version-fixture.db")
        val old = openV1(file.name)
        old.execSQL("INSERT INTO bookmarks (id,url,canonical,title,notes,tags,updatedAt) VALUES (7,'https://example.com/old','https://example.com/old','旧版标题','原有备注','技术,安卓',1000)")
        old.version = 1; old.close()
        val db = Room.databaseBuilder(context, VaultDb::class.java, file.name).addMigrations(*VAULT_MIGRATIONS).build()
        try {
            val original = db.bookmarks().byId(7L)!!
            assertEquals("旧版标题", original.title)
            assertEquals("原有备注", original.notes)
            // v1 没有单独的收藏时间，迁移时应退回更新时间，而不是留一个 0 让排序乱掉。
            assertEquals(1000L, original.createdAt)
            assertEquals(1000L, original.updatedAt)
            assertFalse(original.pinned); assertFalse(original.archived); assertFalse(original.read)
            assertEquals("", original.summary); assertEquals("", original.siteName); assertEquals(0L, original.fetchedAt)

            Backup.merge(db, listOf(original.copy(id = 0, title = "不得覆盖")))
            assertEquals(original, db.bookmarks().byId(7L))
            assertEquals(1, db.bookmarks().all().size)
        } finally { db.close(); context.deleteDatabase(file.name) }
        Unit
    }

    @Test fun migrationRebasesDeduplicationKeysButNeverLosesOrMergesRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("old-keys-fixture.db")
        val old = openV1(file.name)
        // 20 号：带跟踪参数，且重算后与 21 号撞键 → 必须保留原键，绝不能合并或删除。
        old.execSQL("INSERT INTO bookmarks (id,url,canonical,title,notes,tags,updatedAt) VALUES (20,'https://example.com/keep?utm_source=newsletter','https://example.com/keep?utm_source=newsletter','带跟踪参数','','',2000)")
        old.execSQL("INSERT INTO bookmarks (id,url,canonical,title,notes,tags,updatedAt) VALUES (21,'https://example.com/keep','https://example.com/keep','干净版本','','',3000)")
        // 22 号：重算后不与任何行冲突 → 应该被重算。
        old.execSQL("INSERT INTO bookmarks (id,url,canonical,title,notes,tags,updatedAt) VALUES (22,'https://example.com/solo?utm_source=x','https://example.com/solo?utm_source=x','自己一条','','',4000)")
        old.version = 1; old.close()
        val db = Room.databaseBuilder(context, VaultDb::class.java, file.name).addMigrations(*VAULT_MIGRATIONS).build()
        try {
            val rows = db.bookmarks().all()
            assertEquals("三条收藏一条都不能丢", 3, rows.size)
            val colliding = rows.first { it.id == 20L }
            val clean = rows.first { it.id == 21L }
            val solo = rows.first { it.id == 22L }
            assertEquals("撞键时保留原键", "https://example.com/keep?utm_source=newsletter", colliding.canonical)
            assertEquals("https://example.com/keep", clean.canonical)
            assertEquals("不冲突的键应该被重算", "https://example.com/solo", solo.canonical)
            assertEquals("原文链接永远不改写", "https://example.com/keep?utm_source=newsletter", colliding.url)
            assertEquals("带跟踪参数", colliding.title)
            assertEquals(2000L, colliding.createdAt)
        } finally { db.close(); context.deleteDatabase(file.name) }
        Unit
    }

    @Test fun upgradingFromV4AddsNoteTablesAndLeavesExistingBookmarksAlone() = runBlocking {
        NoteCrypto.iterations = 10_000
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("v4-upgrade-fixture.db")
        val old = openV4(file.name)
        old.execSQL(
            "INSERT INTO bookmarks (id,url,canonical,title,notes,tags,createdAt,updatedAt,pinned,archived,read," +
                "summary,siteName,image,fetchedAt,deletedAt) " +
                "VALUES (9,'https://example.com/v4','https://example.com/v4','升级前就在','原备注','技术'," +
                "1000,2000,1,0,0,'描述','Example','',1500,0)"
        )
        old.version = 4; old.close()
        var db = Room.databaseBuilder(context, VaultDb::class.java, file.name).addMigrations(*VAULT_MIGRATIONS).build()
        val kept: Bookmark
        try {
            kept = db.bookmarks().byId(9L)!!
            assertEquals("原备注", kept.notes)
            assertEquals(1000L, kept.createdAt)
            assertTrue(kept.pinned)
            // 升级不该凭空长出笔记，也不该替用户设一个主密码。
            assertEquals(emptyList<Note>(), db.notes().all())
            assertNull(db.vault().master())

            val secretId = db.notes().insert(
                Note(cipher = NoteCrypto.sealText("MiMa-1234567".toCharArray(), NoteCrypto.newSalt(), "会议室门牌 4102"),
                    secret = true, createdAt = 2000, updatedAt = 2000)
            )
            val plainId = db.notes().insert(Note(text = "后来的明文笔记", createdAt = 3000, updatedAt = 3000))
            db.vault().insert(VaultMaster(salt = "c2FsdA==", verifier = "dmVyaWZpZXI="))
            assertEquals(2, db.notes().count())
            db.close()

            db = Room.databaseBuilder(context, VaultDb::class.java, file.name).addMigrations(*VAULT_MIGRATIONS).build()
            assertEquals("重开后收藏仍是一条，且内容没变", kept, db.bookmarks().byId(9L))
            assertEquals(listOf(plainId, secretId), db.notes().all().map { it.id })
            val secret = db.notes().byId(secretId)!!
            assertTrue(secret.secret)
            assertEquals("", secret.text)
            assertEquals("会议室门牌 4102", NoteCrypto.openText(KeySession("MiMa-1234567".toCharArray()), secret.cipher))
            assertThrows(IllegalArgumentException::class.java) {
                NoteCrypto.openText(KeySession("BieRen-MiMa-1".toCharArray()), secret.cipher)
            }
            assertEquals("c2FsdA==", db.vault().master()!!.salt)
        } finally { db.close(); context.deleteDatabase(file.name) }
        Unit
    }
}
