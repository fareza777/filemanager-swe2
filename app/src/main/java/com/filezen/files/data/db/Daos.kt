package com.filezen.files.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox_items ORDER BY tidy ASC, firstSeen DESC")
    fun all(): Flow<List<InboxItem>>

    @Query("SELECT * FROM inbox_items WHERE tidy = 0 ORDER BY firstSeen DESC")
    fun untidy(): Flow<List<InboxItem>>

    @Query("SELECT * FROM inbox_items WHERE tidy = 1 ORDER BY firstSeen DESC")
    fun tidy(): Flow<List<InboxItem>>

    @Query("SELECT path FROM inbox_items")
    suspend fun allPaths(): List<String>

    @Query("SELECT * FROM inbox_items WHERE path = :path")
    suspend fun get(path: String): InboxItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<InboxItem>)

    @Query("UPDATE inbox_items SET tidy = :tidy WHERE path = :path")
    suspend fun setTidy(path: String, tidy: Boolean)

    @Query("UPDATE inbox_items SET path = :newPath, name = :newName WHERE path = :oldPath")
    suspend fun updatePath(oldPath: String, newPath: String, newName: String)

    @Query("DELETE FROM inbox_items WHERE path IN (:paths)")
    suspend fun remove(paths: List<String>)

    @Query("DELETE FROM inbox_items WHERE path = :path")
    suspend fun remove(path: String)
}

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorites ORDER BY position ASC, addedAt ASC")
    fun all(): Flow<List<Favorite>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: Favorite)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun remove(path: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path = :path)")
    suspend fun exists(path: String): Boolean
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun all(): Flow<List<TrashEntry>>

    @Insert
    suspend fun insert(e: TrashEntry): Long

    @Delete
    suspend fun delete(e: TrashEntry)

    @Query("SELECT * FROM trash WHERE id = :id")
    suspend fun get(id: Long): TrashEntry?

    @Query("SELECT COUNT(*) FROM trash")
    fun count(): Flow<Int>

    @Query("SELECT COALESCE(SUM(size),0) FROM trash")
    fun totalSize(): Flow<Long>

    @Query("SELECT * FROM trash WHERE deletedAt < :before")
    suspend fun entriesBefore(before: Long): List<TrashEntry>
}

@Dao
interface OperationDao {
    @Query("SELECT * FROM operations ORDER BY timestamp DESC LIMIT 300")
    fun recent(): Flow<List<OperationRecord>>

    @Insert
    suspend fun insert(r: OperationRecord): Long

    @Query("DELETE FROM operations WHERE timestamp < :before")
    suspend fun purgeBefore(before: Long)
}

@Dao
interface SortRuleDao {
    @Query("SELECT * FROM sort_rules ORDER BY createdAt ASC")
    fun all(): Flow<List<SortRule>>

    @Query("SELECT * FROM sort_rules WHERE enabled = 1")
    suspend fun enabled(): List<SortRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: SortRule): Long

    @Delete
    suspend fun delete(r: SortRule)

    @Query("UPDATE sort_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface HashCacheDao {
    @Query("SELECT sha256 FROM hash_cache WHERE path = :path AND size = :size AND lastModified = :mtime")
    suspend fun lookup(path: String, size: Long, mtime: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<HashCache>)

    @Query("DELETE FROM hash_cache WHERE path NOT IN (SELECT path FROM file_index)")
    suspend fun pruneOrphans()
}

@Dao
interface FileIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<FileIndexEntry>)

    @Query("DELETE FROM file_index WHERE path = :path")
    suspend fun remove(path: String)

    @Query("DELETE FROM file_index")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM file_index")
    suspend fun count(): Int

    @Query("SELECT * FROM file_index WHERE name LIKE '%' || :q || '%' COLLATE NOCASE ORDER BY lastModified DESC LIMIT :limit")
    suspend fun byName(q: String, limit: Int = 500): List<FileIndexEntry>

    @Query("SELECT * FROM file_index WHERE type = :type ORDER BY lastModified DESC LIMIT :limit")
    suspend fun byType(type: String, limit: Int = 2000): List<FileIndexEntry>

    @Query("SELECT * FROM file_index ORDER BY lastModified DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<FileIndexEntry>

    @Query("SELECT * FROM file_index WHERE lastModified BETWEEN :fromMs AND :toMs ORDER BY lastModified DESC LIMIT :limit")
    suspend fun byDateRange(fromMs: Long, toMs: Long, limit: Int = 2000): List<FileIndexEntry>

    @Query("SELECT DISTINCT (lastModified / 86400000) FROM file_index")
    suspend fun daysWithFiles(): List<Long>

    @Query("SELECT * FROM file_index ORDER BY lastModified DESC LIMIT :limit")
    suspend fun allRows(limit: Int = 50000): List<FileIndexEntry>
}

@Dao
interface DocIndexDao {
    @Query("SELECT * FROM doc_manifest")
    suspend fun manifest(): List<DocManifest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putManifest(e: DocManifest)

    @Query("DELETE FROM doc_manifest WHERE path = :path")
    suspend fun removeManifest(path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(c: List<DocChunk>)

    @Query("DELETE FROM doc_chunks WHERE path = :path")
    suspend fun removeChunks(path: String)

    @Query("SELECT COUNT(*) FROM doc_chunks")
    suspend fun chunkCount(): Int

    @Query("SELECT COUNT(DISTINCT path) FROM doc_chunks")
    fun indexedFileCount(): Flow<Int>

    @Query("SELECT * FROM doc_chunks")
    suspend fun allChunks(): List<DocChunk>

    @Query("DELETE FROM doc_chunks")
    suspend fun clearChunks()

    @Query("DELETE FROM doc_manifest")
    suspend fun clearManifest()
}
