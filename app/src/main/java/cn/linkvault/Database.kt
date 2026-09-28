package cn.linkvault

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks", indices = [Index(value = ["canonical"], unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val canonical: String,
    val title: String = "",
    val notes: String = "",
    val tags: String = "",
    /** 何时收藏的。updatedAt 只记录内容改动，两者分开才能稳定地按收藏时间排序。 */
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** 已读过 / 已处理，列表里会弱化显示。 */
    val read: Boolean = false,
    /** 手动抓取到的页面描述，可为空。 */
    val summary: String = "",
    /** 抓取到的站点名，可为空；为空时用内置站点表。 */
    val siteName: String = "",
    /**
     * 抓取到的封面图绝对地址，可为空。
     *
     * 只存 URL，不存图片本体：快照是纯文本文件，图片跟着下载就要管配额、清理和备份体积。
     * 因此断网时文字读得懂、图裂，这是「离线可读」承诺的边界，写在这里以免日后当成 bug。
     */
    val image: String = "",
    val fetchedAt: Long = 0,
    /** 回收站时间；0 表示正常收藏。 */
    val deletedAt: Long = 0
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE deletedAt = 0 ORDER BY pinned DESC, createdAt DESC, id DESC")
    fun observe(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE deletedAt = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE deletedAt > 0 ORDER BY deletedAt DESC, id DESC")
    fun observeTrash(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt ASC, id ASC")
    suspend fun allIncludingDeleted(): List<Bookmark>

    /** 清理回收站前先拿到将被删掉的 id：正文快照是散在磁盘上的文件，行没了得跟着清。 */
    @Query("SELECT id FROM bookmarks WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun trashedIdsBefore(before: Long): List<Long>

    @Query("DELETE FROM bookmarks WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun purgeTrash(before: Long): Int

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE canonical = :key LIMIT 1")
    suspend fun byKey(key: String): Bookmark?

    @Insert suspend fun insert(item: Bookmark): Long
    @Update suspend fun update(item: Bookmark): Int
    /** 测试与数据库清理使用；用户界面删除走 softDelete。 */
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("UPDATE bookmarks SET deletedAt = :at WHERE id = :id AND deletedAt = 0") suspend fun softDelete(id: Long, at: Long): Int
    @Query("UPDATE bookmarks SET deletedAt = 0 WHERE id = :id AND deletedAt > 0") suspend fun restore(id: Long): Int
    @Query("DELETE FROM bookmarks WHERE id = :id AND deletedAt > 0") suspend fun deleteForever(id: Long): Int
    @Query("UPDATE bookmarks SET read = :value WHERE id IN (:ids) AND deletedAt = 0") suspend fun markReadBulk(ids: List<Long>, value: Boolean): Int
    @Query("UPDATE bookmarks SET archived = :value WHERE id IN (:ids) AND deletedAt = 0") suspend fun archiveBulk(ids: List<Long>, value: Boolean): Int

    /** 状态位刻意不走 @Update：它们不该改动 updatedAt，否则列表会在标记已读时跳来跳去。 */
    @Query("UPDATE bookmarks SET pinned = :value WHERE id = :id") suspend fun pin(id: Long, value: Boolean): Int
    @Query("UPDATE bookmarks SET archived = :value WHERE id = :id") suspend fun archive(id: Long, value: Boolean): Int
    @Query("UPDATE bookmarks SET read = :value WHERE id = :id") suspend fun markRead(id: Long, value: Boolean): Int
    @Query("UPDATE bookmarks SET tags = :tags WHERE id = :id") suspend fun setTags(id: Long, tags: String): Int
    /** 整理标题走这里：和 setTags 同理，内容清洗不该把条目顶到列表最前面。 */
    @Query("UPDATE bookmarks SET title = :title WHERE id = :id") suspend fun setTitle(id: Long, title: String): Int
    @Query("UPDATE bookmarks SET summary = :summary, siteName = :siteName, title = :title, image = :image, fetchedAt = :now WHERE id = :id")
    suspend fun applyFetch(id: Long, summary: String, siteName: String, title: String, image: String, now: Long): Int
}

/**
 * 一条笔记。
 *
 * 密文用 Base64 字符串存而不是 BLOB：Room 的 data class 放数组会让 equals/hashCode 走引用比较，
 * 而且加密备份的 payload 本来就是 JSON —— 到那里也得编成字符串，不如一开始就一种形态。
 *
 * `secret` 决定正文在哪：普通笔记写在 [text]（明文，与收藏备注同级），
 * 私密笔记 [text] 留空、正文进 [cipher]。没有"半私密"第三种状态。
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String = "",
    /** [NoteCrypto] 的自描述封套（Base64）；非私密笔记为空串。 */
    val cipher: String = "",
    val secret: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** 排序与列表首行都按它，所以「从剪贴板存为笔记」也要正经刷新时间。 */
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 主密码的验证数据，全库只有一行（id 固定为 1）。
 *
 * verifier 是用主密码派生的密钥加密的一段固定文本：先有它，用户才能在没有一条私密笔记的时候
 * 就知道自己这次密码输对了没有 —— 否则只能"存一条试试"，错了就得重来。
 */
@Entity(tableName = "vault")
data class VaultMaster(
    @PrimaryKey val id: Long = ROW_ID,
    val salt: String,
    val verifier: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 全库只有这一行，id 写死；导入别人的备份时也要用它盖掉文件里带来的任意 id。 */
        const val ROW_ID = 1L
    }
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC, id DESC")
    fun observe(): Flow<List<Note>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC, id DESC")
    suspend fun all(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Note?

    /** 改密码要判断"是否一条都没漏"，靠条数对账。 */
    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int

    /** 导入去重只比对非私密笔记的明文正文；密文每条都不同，比了也没意义。 */
    @Query("SELECT `text` FROM notes WHERE `cipher` = ''")
    suspend fun plainTexts(): List<String>

    @Insert suspend fun insert(item: Note): Long
    @Insert suspend fun insertAll(items: List<Note>): List<Long>
    @Update suspend fun update(item: Note): Int
    /** 笔记刻意不做回收站：密码正文在磁盘上多留 30 天是纯负担。 */
    @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: Long): Int
    /** 测试沙箱与整库重建使用；用户界面一律走逐条 delete。 */
    @Query("DELETE FROM notes") suspend fun clear(): Int
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault WHERE id = 1 LIMIT 1")
    suspend fun master(): VaultMaster?

    @Insert suspend fun insert(item: VaultMaster): Long
    @Update suspend fun update(item: VaultMaster): Int
    /** 与 [NoteDao.clear] 同因：只给测试和整库重建，界面上没有"清除主密码"这条路。 */
    @Query("DELETE FROM vault") suspend fun clear(): Int
}

@Database(entities = [Bookmark::class, Note::class, VaultMaster::class], version = 5, exportSchema = true)
abstract class VaultDb : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
    abstract fun notes(): NoteDao
    abstract fun vault(): VaultDao

    companion object {
        @Volatile private var instance: VaultDb? = null

        fun get(context: Context): VaultDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, VaultDb::class.java, "link-vault.db")
                .addMigrations(*VAULT_MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
