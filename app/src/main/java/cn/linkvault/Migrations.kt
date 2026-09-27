package cn.linkvault

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：新增收藏时间、置顶、归档、已读与抓取字段。
 *
 * 老数据没有单独的收藏时间，用更新时间近似；
 * 去重键按扩展后的跟踪参数规则重算，但**绝不删除或静默合并任何一条收藏**——
 * 一旦新键与别的行冲突，就保留原来的键；宁可漏一次去重，也不能丢数据。
 *
 * 刻意写成具名类而不是匿名 object：KSP2 解析 `@Database` 类里嵌套的匿名类型会失败。
 */
class Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `read` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `summary` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `siteName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `fetchedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `bookmarks` SET `createdAt` = `updatedAt` WHERE `createdAt` = 0")
        rebaseCanonicalKeys(db)
    }

    private fun rebaseCanonicalKeys(db: SupportSQLiteDatabase) {
        val rows = ArrayList<Triple<Long, String, String>>()
        db.query("SELECT `id`, `url`, `canonical` FROM `bookmarks` ORDER BY `id` ASC").use { cursor ->
            while (cursor.moveToNext()) {
                rows += Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
            }
        }
        val used = rows.mapTo(HashSet()) { it.third }
        rows.forEach { (id, url, current) ->
            val fresh = runCatching { Links.canonical(url) }.getOrNull() ?: return@forEach
            if (fresh == current || fresh in used) return@forEach
            used.remove(current)
            used.add(fresh)
            db.execSQL("UPDATE `bookmarks` SET `canonical` = ? WHERE `id` = ?", arrayOf<Any>(fresh, id))
        }
    }
}

/** v2 → v3：把删除改为可恢复的回收站，旧收藏全部视为正常数据。 */
class Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3 → v4：封面图。
 *
 * 老数据一律空串，不回填：抓一次图是一次对外请求，不能在用户没点过「抓取」的情况下
 * 于升级路径上偷偷发出去。升级完列表看起来和升级前一模一样，这是有意的。
 */
class Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `image` TEXT NOT NULL DEFAULT ''")
    }
}

/** 全部迁移，按版本顺序排列。升级安装时由 Room 依序执行。 */
val VAULT_MIGRATIONS: Array<Migration> = arrayOf(Migration1To2(), Migration2To3(), Migration3To4())
