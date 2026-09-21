package com.filezen.files.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [InboxItem::class, Favorite::class, TrashEntry::class, OperationRecord::class, SortRule::class, HashCache::class, FileIndexEntry::class, DocChunk::class, DocManifest::class, SyncPair::class, SyncStateEntry::class, Fingerprint::class, FingerprintEntry::class],
    version = 8,
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
    abstract fun syncPairs(): SyncPairDao
    abstract fun syncState(): SyncStateDao
    abstract fun fingerprints(): FingerprintDao

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

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE doc_chunks ADD COLUMN terms TEXT NOT NULL DEFAULT ''")
                // Old rows have no terms — force a full re-index on next sync.
                db.execSQL("DELETE FROM doc_chunks")
                db.execSQL("DELETE FROM doc_manifest")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Indexer now rejects binary junk and stores better terms — re-index.
                db.execSQL("DELETE FROM doc_chunks")
                db.execSQL("DELETE FROM doc_manifest")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_pairs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `localFolder` TEXT NOT NULL, `remoteConnId` INTEGER, `remoteFolder` TEXT NOT NULL, `direction` TEXT NOT NULL, `conflictRule` TEXT NOT NULL, `deleteOrphans` INTEGER NOT NULL, `includeSubfolders` INTEGER NOT NULL, `syncOnOpen` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `lastSyncTime` INTEGER NOT NULL, `lastStatus` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`pairId` INTEGER NOT NULL, `relPath` TEXT NOT NULL, `localSize` INTEGER NOT NULL, `localMtime` INTEGER NOT NULL, `remoteSize` INTEGER NOT NULL, `remoteMtime` INTEGER NOT NULL, PRIMARY KEY(`pairId`, `relPath`))")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `fingerprints` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `cid` TEXT NOT NULL, `fileCount` INTEGER NOT NULL, `dirCount` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `takenAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `fingerprint_entries` (`fpId` INTEGER NOT NULL, `relPath` TEXT NOT NULL, `cid` TEXT NOT NULL, `size` INTEGER NOT NULL, `isDir` INTEGER NOT NULL, PRIMARY KEY(`fpId`, `relPath`))")
                // Embeddings moved from float32 to half-precision (vec_f16-style) — re-index once.
                db.execSQL("DELETE FROM doc_chunks")
                db.execSQL("DELETE FROM doc_manifest")
            }
        }

        @Volatile private var inst: ZenDatabase? = null
        fun get(ctx: Context): ZenDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, ZenDatabase::class.java, "filezen.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
