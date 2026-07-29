package com.ytdl.app.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * In-memory queue with a JSON file behind it, so the list survives the app
 * being killed while a foreground download is running.
 */
object DownloadStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var file: File? = null
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val f = File(context.filesDir, "downloads.json")
        file = f
        scope.launch {
            val restored = runCatching {
                if (f.exists()) json.decodeFromString<List<DownloadTask>>(f.readText())
                else emptyList()
            }.getOrDefault(emptyList())

            // Anything that claimed to be in flight when the process died is
            // resumable, not running.
            _tasks.value = restored.map {
                if (it.isActive && it.status != DownloadStatus.QUEUED) {
                    it.copy(status = DownloadStatus.PAUSED)
                } else it
            }
        }
    }

    fun snapshot(): List<DownloadTask> = _tasks.value

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun add(task: DownloadTask) {
        val nextOrder = (_tasks.value.maxOfOrNull { it.queueOrder } ?: 0L) + 1
        _tasks.value = listOf(task.copy(queueOrder = nextOrder)) + _tasks.value
        persist()
    }

    fun addAll(tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) return
        var order = (_tasks.value.maxOfOrNull { it.queueOrder } ?: 0L)
        val stamped = tasks.map { it.copy(queueOrder = ++order) }
        _tasks.value = stamped.reversed() + _tasks.value
        persist()
    }

    /** Queue order, waiting jobs first. */
    fun queued(): List<DownloadTask> =
        _tasks.value.filter { it.isActive }.sortedBy { it.queueOrder }

    /** Swaps a task with its neighbour in the waiting queue. */
    fun move(id: String, up: Boolean) {
        val waiting = _tasks.value
            .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
            .sortedBy { it.queueOrder }
        val index = waiting.indexOfFirst { it.id == id }
        if (index < 0) return
        val other = waiting.getOrNull(if (up) index - 1 else index + 1) ?: return
        val current = waiting[index]

        _tasks.value = _tasks.value.map {
            when (it.id) {
                current.id -> it.copy(queueOrder = other.queueOrder)
                other.id -> it.copy(queueOrder = current.queueOrder)
                else -> it
            }
        }
        persist()
    }

    fun pauseAllWaiting() {
        _tasks.value = _tasks.value.map {
            if (it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.PAUSED) else it
        }
        persist()
    }

    fun resumeAllPaused() {
        _tasks.value = _tasks.value.map {
            if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) {
                it.copy(status = DownloadStatus.QUEUED, error = null)
            } else it
        }
        persist()
    }

    fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        var changed = false
        _tasks.value = _tasks.value.map {
            if (it.id == id) {
                changed = true
                transform(it)
            } else it
        }
        if (changed) persist()
    }

    fun remove(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.isFinished }
        persist()
    }

    private fun persist() {
        val snapshot = _tasks.value
        val target = file ?: return
        scope.launch {
            writeLock.withLock {
                runCatching { target.writeText(json.encodeToString(snapshot)) }
            }
        }
    }
}
