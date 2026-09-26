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
    @Query("UPDATE bookmarks SET summary = :summary, siteName = :siteName, title = :title, fetchedAt = :now WHERE id = :id")
    suspend fun applyFetch(id: Long, summary: String, siteName: String, title: String, now: Long): Int
}

@Database(entities = [Bookmark::class], version = 3, exportSchema = true)
abstract class VaultDb : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao

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
