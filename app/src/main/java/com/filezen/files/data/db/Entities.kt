package com.filezen.files.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inbox_items")
data class InboxItem(
    @PrimaryKey val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val type: String,
    val root: String,
    val firstSeen: Long,
    val tidy: Boolean = false,
)

@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val path: String,
    val label: String,
    val addedAt: Long,
    val position: Int = 0,
)

@Entity(tableName = "trash")
data class TrashEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val originalPath: String,
    val trashPath: String,
    val size: Long,
    val isDir: Boolean,
    val deletedAt: Long,
)

@Entity(tableName = "operations")
data class OperationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val sources: String,   // newline-joined
    val targetDir: String?,
    val status: String,    // OK / PARTIAL / FAILED / CANCELLED
    val detail: String?,   // first error or counts
    val itemCount: Int,
    val timestamp: Long,
)

@Entity(tableName = "sort_rules")
data class SortRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchType: String,  // EXTENSION / CONTAINS / REGEX
    val pattern: String,
    val targetPath: String,
    val sourcePath: String? = null, // only files under this dir match (null = anywhere)
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Cached content hash for duplicate detection — rehash only when size/mtime change. */
@Entity(tableName = "hash_cache", primaryKeys = ["path"])
data class HashCache(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val sha256: String,
    val computedAt: Long,
)

/** Full-disk file index powering instant search and the calendar. */
@Entity(tableName = "file_index", primaryKeys = ["path"])
data class FileIndexEntry(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val type: String,
    val parentDir: String,
)

/** One text chunk of an indexed document (semantic content search). */
@Entity(tableName = "doc_chunks")
data class DocChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val chunkIx: Int,
    val title: String,
    val snippet: String,
    val embedding: ByteArray,
)

/** Content-index manifest: which file version produced which chunks. */
@Entity(tableName = "doc_manifest", primaryKeys = ["path"])
data class DocManifest(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val sha256: String,
)
