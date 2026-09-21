package com.filezen.files.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [InboxItem::class, Favorite::class, TrashEntry::class, OperationRecord::class, SortRule::class, HashCache::class, FileIndexEntry::class, DocChunk::class, DocManifest::class],
    version = 4,
    exportSchema = false,
)
abstract class ZenDatabase : RoomDatabase() {
    abstract fun inbox(): InboxDao
    abstract fun favorites(): FavoritesDao
    abstract fun trash(): TrashDao
    abstract fun operations(): OperationDao
    abstract fun sortRules(): SortRuleDao
    abstract fun hashCache(): HashCacheDao
    abstract fun fileIndex(): FileIndexDao
    abstract fun docIndex(): DocIndexDao

    companion object {
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sort_rules ADD COLUMN sourcePath TEXT")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `doc_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `chunkIx` INTEGER NOT NULL, `title` TEXT NOT NULL, `snippet` TEXT NOT NULL, `embedding` BLOB NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `doc_manifest` (`path` TEXT NOT NULL, `size` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `sha256` TEXT NOT NULL, PRIMARY KEY(`path`))")
            }
        }

        @Volatile private var inst: ZenDatabase? = null
        fun get(ctx: Context): ZenDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, ZenDatabase::class.java, "filezen.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
