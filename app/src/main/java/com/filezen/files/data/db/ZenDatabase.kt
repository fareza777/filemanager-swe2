package com.filezen.files.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [InboxItem::class, Favorite::class, TrashEntry::class, OperationRecord::class, SortRule::class],
    version = 1,
    exportSchema = false,
)
abstract class ZenDatabase : RoomDatabase() {
    abstract fun inbox(): InboxDao
    abstract fun favorites(): FavoritesDao
    abstract fun trash(): TrashDao
    abstract fun operations(): OperationDao
    abstract fun sortRules(): SortRuleDao

    companion object {
        @Volatile private var inst: ZenDatabase? = null
        fun get(ctx: Context): ZenDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, ZenDatabase::class.java, "filezen.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
