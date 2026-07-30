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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.ytdl.app.R
import com.ytdl.app.settings.SettingsRepository
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Foreground service that owns the download queue. Each task is a yt-dlp run;
 * a scheduler keeps at most `maxParallel` of them alive.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_ENQUEUE = "com.ytdl.app.ENQUEUE"
        const val ACTION_CANCEL = "com.ytdl.app.CANCEL"
        const val ACTION_PAUSE = "com.ytdl.app.PAUSE"
        const val ACTION_RESUME = "com.ytdl.app.RESUME"
        const val ACTION_SYNC = "com.ytdl.app.SYNC"
        const val EXTRA_ID = "id"

        private const val TAG = "DownloadService"

        private val MEDIA_EXTENSIONS = setOf("mp4", "mkv", "webm", "m4a", "opus", "mp3", "3gp", "ogg")

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

        private fun processId(id: String) = "ytdl_$id"
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
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                YtDlp.destroy(processId(id))
                scope.launch { stopJob(id); markStopped(id, DownloadStatus.CANCELED) }
            }

            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                YtDlp.destroy(processId(id))
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
                        .forEach { task -> jobs[task.id] = scope.launch { runTask(task.id) } }
                }
            }

            val active = activeTasks()
            if (foregroundStarted) {
                runCatching {
                    NotificationManagerCompat.from(this)
                        .notify(Notifications.FOREGROUND_ID, Notifications.progressNotification(this, active))
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
        if (status == DownloadStatus.CANCELED) taskDir(id).deleteRecursively()
    }

    private fun taskDir(id: String): File = File(File(cacheDir, "ytdlp"), id)

    // ---------------------------------------------------------------- worker

    private suspend fun runTask(id: String) {
        val config = settings.flow.first()

        try {
            if (config.wifiOnly && !isOnUnmeteredNetwork()) {
                DownloadStore.update(id) {
                    it.copy(status = DownloadStatus.WAITING_WIFI, error = getString(R.string.err_wifi_only))
                }
                delay(15_000)
                DownloadStore.update(id) {
                    if (it.status == DownloadStatus.WAITING_WIFI) {
                        it.copy(status = DownloadStatus.QUEUED, error = null)
                    } else it
                }
                return
            }

            YtDlp.ensureInit(applicationContext)

            val task = DownloadStore.get(id) ?: return
            DownloadStore.update(id) { it.copy(status = DownloadStatus.RUNNING, error = null) }

            val dir = taskDir(id).apply { mkdirs() }
            val resuming = dir.listFiles()?.any { it.name.endsWith(".part") } == true

            val request = buildRequest(task, dir, resuming)

            withContext(Dispatchers.IO) {
                YtDlp.execute(request, processId(id)) { progress, _, line ->
                    val merging = line.contains("[Merger]") || line.contains("[ExtractAudio]")
                    DownloadStore.update(id) {
                        if (!it.isActive) it
                        else it.copy(
                            percent = (progress / 100f).coerceIn(0f, 1f),
                            status = when {
                                merging -> DownloadStatus.MERGING
                                progress >= 100f -> DownloadStatus.SAVING
                                else -> DownloadStatus.RUNNING
                            },
                        )
                    }
                }
            }

            DownloadStore.update(id) { it.copy(status = DownloadStatus.SAVING, percent = 1f) }

            val output = findOutput(dir)
                ?: throw IllegalStateException(getString(R.string.err_no_streams))
            val extension = output.extension.lowercase()

            val uri = Output.publish(
                context = applicationContext,
                source = output,
                displayName = task.fileName,
                extension = extension,
                folderUri = config.folderUri,
            )
            publishSidecars(dir, task, config.folderUri)
            dir.deleteRecursively()

            DownloadStore.update(id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    resultUri = uri.toString(),
                    fileExtension = extension,
                    percent = 1f,
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
                else it.copy(status = DownloadStatus.FAILED, error = friendlyError(e))
            }
            DownloadStore.get(id)?.let {
                if (it.status == DownloadStatus.FAILED) Notifications.notifyFinished(this, it)
            }
        } finally {
            jobsLock.withLock { jobs.remove(id) }
        }
    }

    private fun buildRequest(
        task: DownloadTask,
        dir: File,
        resuming: Boolean,
    ): YoutubeDLRequest {
        val request = YoutubeDLRequest(task.sourceUrl)
        request.addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
        request.addOption("-f", task.formatSelector)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--no-warnings")
        request.addOption("--retries", "20")
        request.addOption("--fragment-retries", "20")

        if (task.audioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", task.mergeFormat)
        } else {
            request.addOption("--merge-output-format", task.mergeFormat)
        }

        if (task.downloadSubtitles) {
            request.addOption("--write-subs")
            request.addOption("--write-auto-subs")
            request.addOption("--sub-langs", "${Locale.getDefault().language}.*,en.*")
            request.addOption("--convert-subs", "srt")
        }
        if (task.saveThumbnail) request.addOption("--write-thumbnail")
        if (resuming) request.addOption("--continue")

        return request
    }

    /** The final media file yt-dlp produced (largest matching container). */
    private fun findOutput(dir: File): File? =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && it.extension.lowercase() in MEDIA_EXTENSIONS }
            ?.maxByOrNull { it.length() }

    private fun publishSidecars(dir: File, task: DownloadTask, folderUri: String?) {
        val sidecars = dir.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in setOf("srt", "vtt", "jpg", "jpeg", "png", "webp")
        } ?: return
        for (file in sidecars) {
            runCatching {
                // Keep any language tag yt-dlp added: "<id>.ru.srt" -> ".ru".
                val middle = file.name
                    .removePrefix(task.videoId)
                    .removeSuffix("." + file.extension)
                    .takeIf { it.isNotBlank() && it != "." } ?: ""
                Output.publish(
                    context = applicationContext,
                    source = file,
                    displayName = task.fileName + middle,
                    extension = file.extension.lowercase(),
                    folderUri = folderUri,
                )
            }
        }
    }

    private fun friendlyError(e: Throwable): String {
        val raw = e.message ?: e::class.java.simpleName
        return when {
            raw.contains("Sign in", ignoreCase = true) -> "Video requires sign-in / age-restricted"
            raw.contains("Private video", ignoreCase = true) -> "Private video"
            raw.contains("unavailable", ignoreCase = true) -> "Video unavailable"
            raw.length > 200 -> raw.take(200)
            else -> raw
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
