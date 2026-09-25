package com.filezen.files

import android.app.Application
import com.filezen.files.data.db.ZenDatabase
import com.filezen.files.data.prefs.SettingsStore
import com.filezen.files.core.fileops.FileEngine
import com.filezen.files.core.fileops.TrashManager
import com.filezen.files.core.fileops.OperationRunner
import com.filezen.files.core.inbox.InboxRepository
import com.filezen.files.billing.BillingManager
import kotlinx.coroutines.launch

class AppContainer(app: FileZenApp) {
    val db by lazy { ZenDatabase.get(app) }
    val settings by lazy { SettingsStore(app) }
    val fileEngine by lazy { FileEngine() }
    val trash by lazy { TrashManager(app, db) }
    val inbox by lazy { InboxRepository(app, db, settings, fileEngine) }
    val ops by lazy { OperationRunner(app, fileEngine, trash, db) }
    val billing by lazy { BillingManager(app, settings) }
    val fileIndex by lazy { com.filezen.files.core.scan.FileIndex(db) }
    val transfer by lazy { com.filezen.files.core.transfer.TransferServer() }
    val contentIndex by lazy { com.filezen.files.core.semsearch.ContentIndex(app, db) }
    val syncRunner by lazy { com.filezen.files.core.sync.SyncRunner(app, db, settings) }
    val shizuku by lazy { com.filezen.files.core.shizuku.ShizukuAccess(app) }
}

class FileZenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        container.billing // eagerly start billing connection
        // Build/refresh the disk index in the background — search and the
        // calendar read it instantly; scanning is incremental & cancellable.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ).launch {
            runCatching { container.fileIndex.rebuild() }
            // Keep the index live: watch the hot roots so new files hit
            // Recent/search within seconds, not next launch.
            container.fileIndex.startWatching(this, container.fileIndex.hotRoots())
            // Then refresh the document-content index (incremental — cheap once built).
            runCatching { container.contentIndex.sync() }
            // Folder pairs flagged "sync on open".
            runCatching { container.syncRunner.runOnOpenPairs() }
        }
    }

    companion object {
        lateinit var instance: FileZenApp
            private set
        val c: AppContainer get() = instance.container
    }
}
