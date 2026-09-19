package com.filezen.files.core.fileops

import android.content.Context
import com.filezen.files.data.db.OperationRecord
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class RunningOp(
    val kind: OpKind,
    val label: String,
    val progress: OpProgress?,
    val cancellable: Boolean = true,
)

/**
 * Single queue for all file operations. Exposes progress + completion to the UI,
 * writes every outcome into the operation log, supports cancellation.
 */
class OperationRunner(
    private val ctx: Context,
    val engine: FileEngine,
    private val trash: TrashManager,
    private val db: ZenDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _current = MutableStateFlow<RunningOp?>(null)
    val current: StateFlow<RunningOp?> = _current

    private val _lastSummary = MutableStateFlow<OpSummary?>(null)
    val lastSummary: StateFlow<OpSummary?> = _lastSummary

    private var job: Job? = null

    fun cancel() { job?.cancel() }

    fun clearSummary() { _lastSummary.value = null }

    /** Post a summary directly — used by flows that aggregate several ops (e.g. auto-tidy). */
    fun postSummary(summary: OpSummary) { _lastSummary.value = summary }

    val isRunning: Boolean get() = job?.isActive == true

    /** Awaitable variant used by flows that must react to results (e.g. Inbox tidy). */
    suspend fun runSync(
        kind: OpKind,
        label: String,
        sourcesForLog: List<String>,
        targetDir: String?,
        work: suspend (ProgressCb) -> OpSummary,
    ): OpSummary {
        var lastEmit = 0L
        val cb: ProgressCb = { p ->
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEmit >= 80 || p.itemsDone >= p.itemsTotal) {
                _current.value = RunningOp(kind, label, p)
                lastEmit = now
            }
        }
        _current.value = RunningOp(kind, label, null)
        return try {
            val summary = work(cb).let { if (it.kind == kind) it else it.copy(kind = kind) }
            _lastSummary.value = summary
            record(kind, sourcesForLog, targetDir,
                status = when {
                    summary.cancelled -> "CANCELLED"
                    summary.failed == 0 -> "OK"
                    summary.succeeded == 0 -> "FAILED"
                    else -> "PARTIAL"
                },
                detail = summary.results.firstOrNull { it.error != null }?.error
                    ?: "${summary.succeeded}/${summary.total} ok",
                count = summary.total)
            summary
        } catch (ce: CancellationException) {
            record(kind, sourcesForLog, targetDir, "CANCELLED", "Cancelled by user", 0)
            OpSummary(kind, emptyList(), cancelled = true)
        } catch (e: Exception) {
            record(kind, sourcesForLog, targetDir, "FAILED", e.message, sourcesForLog.size)
            OpSummary(kind, sourcesForLog.map { ItemResult(it, null, ItemStatus.FAILED, e.message) })
        } finally {
            _current.value = null
        }
    }

    fun launch(
        kind: OpKind,
        label: String,
        sourcesForLog: List<String>,
        targetDir: String?,
        work: suspend (ProgressCb) -> OpSummary,
    ) {
        if (isRunning) return
        job = scope.launch {
            var lastEmit = 0L
            val cb: ProgressCb = { p ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastEmit >= 80 || p.itemsDone >= p.itemsTotal) {
                    _current.value = RunningOp(kind, label, p)
                    lastEmit = now
                }
            }
            _current.value = RunningOp(kind, label, null)
            try {
                val summary = work(cb).let { if (it.kind == kind) it else it.copy(kind = kind) }
                _lastSummary.value = summary
                record(kind, sourcesForLog, targetDir,
                    status = when {
                        summary.cancelled -> "CANCELLED"
                        summary.failed == 0 -> "OK"
                        summary.succeeded == 0 -> "FAILED"
                        else -> "PARTIAL"
                    },
                    detail = summary.results.firstOrNull { it.error != null }?.error
                        ?: "${summary.succeeded}/${summary.total} ok",
                    count = summary.total)
            } catch (ce: CancellationException) {
                val s = OpSummary(kind, emptyList(), cancelled = true)
                _lastSummary.value = s
                record(kind, sourcesForLog, targetDir, "CANCELLED", "Cancelled by user", 0)
            } catch (e: InsufficientSpaceException) {
                record(kind, sourcesForLog, targetDir, "FAILED", e.message, sourcesForLog.size)
                _lastSummary.value = OpSummary(kind, sourcesForLog.map {
                    ItemResult(it, null, ItemStatus.FAILED, "Not enough space")
                })
            } catch (e: Exception) {
                record(kind, sourcesForLog, targetDir, "FAILED", e.message, sourcesForLog.size)
                _lastSummary.value = OpSummary(kind, sourcesForLog.map {
                    ItemResult(it, null, ItemStatus.FAILED, e.message)
                })
            } finally {
                _current.value = null
                job = null
            }
        }
    }

    private suspend fun record(
        kind: OpKind, sources: List<String>, target: String?,
        status: String, detail: String?, count: Int,
    ) {
        db.operations().insert(
            OperationRecord(
                kind = kind.name,
                sources = sources.take(50).joinToString("\n"),
                targetDir = target,
                status = status,
                detail = detail,
                itemCount = count,
                timestamp = System.currentTimeMillis(),
            )
        )
    }
}
