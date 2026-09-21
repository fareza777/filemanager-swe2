package com.filezen.files.core.sync

import android.content.Context
import com.filezen.files.core.remote.RemoteFs
import com.filezen.files.data.db.SyncPair
import com.filezen.files.data.db.ZenDatabase
import com.filezen.files.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import java.io.File

/** Runs a [SyncPair] end-to-end: build providers, execute, persist state + status. */
class SyncRunner(
    private val app: Context,
    private val db: ZenDatabase,
    private val settings: SettingsStore,
) {
    suspend fun run(
        pair: SyncPair,
        progress: FolderSyncEngine.Progress? = null,
    ): FolderSyncEngine.Result {
        val local = LocalSyncStore(File(pair.localFolder))
        val remote: SyncStore = if (pair.remoteConnId == null) {
            LocalSyncStore(File(pair.remoteFolder))
        } else {
            val conn = settings.remoteConnections.first()
                .firstOrNull { it.id == pair.remoteConnId }
                ?: throw IllegalStateException("Remote connection was removed")
            RemoteSyncStore(RemoteFs.of(conn), pair.remoteFolder)
        }
        try {
            val engine = FolderSyncEngine(local, remote, pair, app.cacheDir)
            val prev = db.syncState().stateFor(pair.id)
            val result = engine.run(prev, progress)
            if (pair.direction == "TWO_WAY") {
                db.syncState().replace(pair.id, result.newState)
            }
            db.syncPairs().updateStatus(pair.id, System.currentTimeMillis(), result.summary)
            return result
        } catch (e: Exception) {
            db.syncPairs().updateStatus(
                pair.id, System.currentTimeMillis(), "Failed: ${e.message}")
            throw e
        } finally {
            remote.close()
        }
    }

    /** All enabled pairs flagged "sync on open". */
    suspend fun runOnOpenPairs() {
        db.syncPairs().enabledPairs().filter { it.syncOnOpen }.forEach {
            runCatching { run(it, null) }
        }
    }
}
