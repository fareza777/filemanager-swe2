package com.filezen.files.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [InboxItem::class, Favorite::class, TrashEntry::class, OperationRecord::class, SortRule::class, HashCache::class, FileIndexEntry::class],
    version = 3,
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

    companion object {
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sort_rules ADD COLUMN sourcePath TEXT")
            }
        }

        @Volatile private var inst: ZenDatabase? = null
        fun get(ctx: Context): ZenDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, ZenDatabase::class.java, "filezen.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
