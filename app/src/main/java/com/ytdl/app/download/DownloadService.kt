package com.ytdl.app.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.ytdl.app.R
import com.ytdl.app.settings.SettingsRepository
import com.ytdl.app.youtube.StreamKind
import com.ytdl.app.youtube.YoutubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Foreground service that owns the download queue. Each task runs as its own
 * coroutine; a scheduler keeps at most `maxParallel` of them alive.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_ENQUEUE = "com.ytdl.app.ENQUEUE"
        const val ACTION_CANCEL = "com.ytdl.app.CANCEL"
        const val ACTION_PAUSE = "com.ytdl.app.PAUSE"
        const val ACTION_RESUME = "com.ytdl.app.RESUME"
        const val ACTION_SYNC = "com.ytdl.app.SYNC"
        const val EXTRA_TASK = "task"
        const val EXTRA_ID = "id"

        private const val TAG = "DownloadService"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun enqueue(context: Context, task: DownloadTask) {
            DownloadStore.add(task)
            start(context, Intent(context, DownloadService::class.java).setAction(ACTION_ENQUEUE))
        }

        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL, id)
        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE, id)
        fun resume(context: Context, id: String) = send(context, ACTION_RESUME, id)
        fun sync(context: Context) =
            start(context, Intent(context, DownloadService::class.java).setAction(ACTION_SYNC))

        private fun send(context: Context, action: String, id: String) = start(
            context,
            Intent(context, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id)
        )

        private fun start(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start service", it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val jobsLock = Mutex()
    private lateinit var settings: SettingsRepository
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(applicationContext)
        Notifications.createChannels(this)
        settings = SettingsRepository(applicationContext)
        goForeground()
        scope.launch { watchQueue() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                // Older callers may still pass the task in the intent.
                intent.getStringExtra(EXTRA_TASK)?.let { payload ->
                    runCatching { json.decodeFromString<DownloadTask>(payload) }
                        .onSuccess { if (DownloadStore.get(it.id) == null) DownloadStore.add(it) }
                }
            }

            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                scope.launch { stopJob(id); markStopped(id, DownloadStatus.CANCELED) }
            }

            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                scope.launch { stopJob(id); markStopped(id, DownloadStatus.PAUSED) }
            }

            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                DownloadStore.update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = Notifications.progressNotification(this, activeTasks())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    Notifications.FOREGROUND_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(Notifications.FOREGROUND_ID, notification)
            }
            foregroundStarted = true
        }.onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    private fun activeTasks() = DownloadStore.snapshot().filter { it.isActive }

    // ------------------------------------------------------------- scheduler

    private suspend fun watchQueue() {
        while (true) {
            val config = settings.flow.first()
            val snapshot = DownloadStore.snapshot()

            jobsLock.withLock {
                jobs.entries.removeAll { (_, job) -> !job.isActive }
                val free = config.maxParallel - jobs.size
                if (free > 0) {
                    snapshot
                        .filter { it.status == DownloadStatus.QUEUED && !jobs.containsKey(it.id) }
                        .sortedWith(compareBy({ it.queueOrder }, { it.createdAt }))
                        .take(free)
                        .forEach { task ->
                            jobs[task.id] = scope.launch { runTask(task.id) }
                        }
                }
            }

            val active = activeTasks()
            if (foregroundStarted) {
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(this)
                        .notify(
                            Notifications.FOREGROUND_ID,
                            Notifications.progressNotification(this, active),
                        )
                }
            }

            if (active.isEmpty() && jobs.isEmpty()) {
                stopSelfSafely()
                return
            }
            delay(700)
        }
    }

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private suspend fun stopJob(id: String) {
        jobsLock.withLock { jobs.remove(id) }?.let { runCatching { it.cancelAndJoin() } }
    }

    private fun markStopped(id: String, status: DownloadStatus) {
        DownloadStore.update(id) { if (it.isFinished) it else it.copy(status = status) }
        if (status == DownloadStatus.CANCELED) tempFiles(id).forEach { it.delete() }
    }

    private fun tempDir(): File = File(cacheDir, "downloads").apply { mkdirs() }

    private fun tempFiles(id: String): List<File> = listOf(
        File(tempDir(), "$id.video"),
        File(tempDir(), "$id.audio"),
        File(tempDir(), "$id.out"),
    )

    // ---------------------------------------------------------------- worker

    private suspend fun runTask(id: String) {
        val config = settings.flow.first()
        var task = DownloadStore.get(id) ?: return

        try {
            if (config.wifiOnly && !isOnUnmeteredNetwork()) {
                DownloadStore.update(id) {
                    it.copy(status = DownloadStatus.WAITING_WIFI, error = getString(R.string.err_wifi_only))
                }
                // Re-queue so the scheduler picks it up again once Wi-Fi is back.
                delay(15_000)
                DownloadStore.update(id) {
                    if (it.status == DownloadStatus.WAITING_WIFI) {
                        it.copy(status = DownloadStatus.QUEUED, error = null)
                    } else it
                }
                return
            }

            DownloadStore.update(id) { it.copy(status = DownloadStatus.RUNNING, error = null) }

            val videoFile = File(tempDir(), "$id.video")
            val audioFile = File(tempDir(), "$id.audio")

            val needsVideo = !task.audioOnly && task.videoUrl != null
            val needsAudio = task.audioOnly || (!task.alreadyMuxed && task.audioUrl != null)

            val total = (if (needsVideo) task.videoBytes else 0L) +
                (if (needsAudio) task.audioBytes else 0L)
            DownloadStore.update(id) { it.copy(totalBytes = total) }

            var videoDone = 0L
            var audioDone = 0L

            if (needsVideo) {
                videoDone = fetchTrack(id, isVideoTrack = true, target = videoFile) { done ->
                    videoDone = done
                    publishProgress(id, videoDone + audioDone, total)
                }
            }

            if (needsAudio) {
                task = DownloadStore.get(id) ?: return
                audioDone = fetchTrack(id, isVideoTrack = false, target = audioFile) { done ->
                    audioDone = done
                    publishProgress(id, videoDone + audioDone, total)
                }
            }

            task = DownloadStore.get(id) ?: return

            // Decide what actually gets saved.
            val payload: File
            var extension = task.fileExtension

            if (task.audioOnly) {
                payload = audioFile
            } else if (task.alreadyMuxed || !needsAudio) {
                payload = videoFile
            } else {
                DownloadStore.update(id) { it.copy(status = DownloadStatus.MERGING) }
                val merged = File(tempDir(), "$id.out")
                val container = Muxer.mux(
                    videoFile = videoFile,
                    audioFile = audioFile,
                    output = merged,
                    preferWebm = task.videoContainer == "webm" && task.audioContainer == "webm",
                )
                extension = container
                videoFile.delete()
                audioFile.delete()
                payload = merged
            }

            DownloadStore.update(id) { it.copy(status = DownloadStatus.SAVING) }

            val uri = Output.publish(
                context = applicationContext,
                source = payload,
                displayName = task.fileName,
                extension = extension,
                folderUri = config.folderUri,
            )

            saveExtras(task, config.folderUri)

            DownloadStore.update(id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    resultUri = uri.toString(),
                    fileExtension = extension,
                    downloadedBytes = if (total > 0) total else it.downloadedBytes,
                    error = null,
                )
            }
            DownloadStore.get(id)?.let { Notifications.notifyFinished(this, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "download $id failed", e)
            DownloadStore.update(id) {
                if (it.status == DownloadStatus.CANCELED || it.status == DownloadStatus.PAUSED) it
                else it.copy(
                    status = DownloadStatus.FAILED,
                    error = e.message ?: e::class.java.simpleName,
                )
            }
            DownloadStore.get(id)?.let {
                if (it.status == DownloadStatus.FAILED) Notifications.notifyFinished(this, it)
            }
        } finally {
            jobsLock.withLock { jobs.remove(id) }
        }
    }

    /**
     * Downloads one track. A 403/404/410 means the signed URL is dead — expired,
     * blocked, or cut off mid-stream by YouTube's anti-abuse layer, which is
     * routine on large files. Each time the stream is re-resolved through the
     * InnerTube clients and the download resumes from where it stopped.
     *
     * The failure counter resets whenever a recovery actually advanced the
     * file, so a 700 MB download surviving many cut-offs still completes; only
     * repeated failures with zero progress abort. After a couple of dead ends
     * on the same itag the recovery is asked for a different encoding.
     */
    private suspend fun fetchTrack(
        taskId: String,
        isVideoTrack: Boolean,
        target: File,
        onProgress: suspend (Long) -> Unit,
    ): Long {
        var stalledFailures = 0
        var bytesAtLastFailure = -1L
        while (true) {
            val task = DownloadStore.get(taskId) ?: throw IllegalStateException("Task removed")
            val url = (if (isVideoTrack) task.videoUrl else task.audioUrl)
                ?: throw IllegalStateException("Missing stream URL")
            val expected = if (isVideoTrack) task.videoBytes else task.audioBytes

            try {
                return Downloader.fetch(url, target, expected, task.userAgent) { done, _ ->
                    onProgress(done)
                }
            } catch (e: IOException) {
                val recoverable = (e is Downloader.HttpStatusException &&
                    e.code in intArrayOf(403, 404, 410)) ||
                    e is Downloader.StreamDiedException
                if (!recoverable) throw e

                val onDisk = if (target.exists()) target.length() else 0L
                if (onDisk > bytesAtLastFailure) stalledFailures = 0
                bytesAtLastFailure = onDisk
                stalledFailures++
                if (stalledFailures > 4) throw e

                // Give the CDN a moment; instant retries tend to hit the same
                // anti-abuse verdict.
                delay(2000L * stalledFailures)

                val itag = if (isVideoTrack) task.videoItag else task.audioItag
                val kind = when {
                    !isVideoTrack -> StreamKind.AUDIO_ONLY
                    task.alreadyMuxed -> StreamKind.MUXED
                    else -> StreamKind.VIDEO_ONLY
                }
                // "1080p60" -> 1080; "128 kbps" -> 128000. Only used when a
                // substitute stream must be found.
                val labelNumber = task.qualityLabel.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                val recovered = YoutubeRepository.recoverStream(
                    videoId = task.videoId,
                    itag = itag,
                    kind = kind,
                    targetHeight = if (isVideoTrack) labelNumber else 0,
                    targetBitrate = if (isVideoTrack) 0L else labelNumber * 1000L,
                    excludeItag = if (stalledFailures >= 3) itag else null,
                ) ?: throw e

                // A different itag is a different encoding: partial data from
                // the old stream cannot be reused.
                if (recovered.stream.itag != itag) target.delete()

                DownloadStore.update(taskId) { t ->
                    val s = recovered.stream
                    if (isVideoTrack) {
                        t.copy(
                            videoUrl = s.url,
                            videoItag = s.itag,
                            videoContainer = s.container,
                            videoCodec = s.codec,
                            videoBytes = if (s.contentLength > 0) s.contentLength else t.videoBytes,
                            userAgent = recovered.userAgent,
                        )
                    } else {
                        t.copy(
                            audioUrl = s.url,
                            audioItag = s.itag,
                            audioContainer = s.container,
                            audioCodec = s.codec,
                            audioBytes = if (s.contentLength > 0) s.contentLength else t.audioBytes,
                            userAgent = recovered.userAgent,
                        )
                    }
                }
            }
        }
    }

    private fun publishProgress(id: String, done: Long, total: Long) {
        DownloadStore.update(id) { it.copy(downloadedBytes = done, totalBytes = total) }
    }

    private suspend fun saveExtras(task: DownloadTask, folderUri: String?) {
        if (task.subtitleUrl != null) {
            Downloader.fetchBytes(task.subtitleUrl)?.let { bytes ->
                writeSidecar(task, bytes, "vtt", folderUri, suffix = ".${task.subtitleLanguage}")
            }
        }
        if (task.saveThumbnail && task.thumbnailUrl.isNotEmpty()) {
            Downloader.fetchBytes(task.thumbnailUrl)?.let { bytes ->
                writeSidecar(task, bytes, "jpg", folderUri, suffix = "")
            }
        }
    }

    private fun writeSidecar(
        task: DownloadTask,
        bytes: ByteArray,
        extension: String,
        folderUri: String?,
        suffix: String,
    ) {
        runCatching {
            val temp = File(tempDir(), "${task.id}.$extension")
            temp.writeBytes(bytes)
            Output.publish(
                context = applicationContext,
                source = temp,
                displayName = task.fileName + suffix,
                extension = extension,
                folderUri = folderUri,
            )
        }
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        @Suppress("DEPRECATION")
        return !cm.isActiveNetworkMetered
    }
}
