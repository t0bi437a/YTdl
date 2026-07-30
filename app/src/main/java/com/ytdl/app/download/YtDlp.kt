package com.ytdl.app.download

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around youtubedl-android — a real yt-dlp (plus ffmpeg) running
 * on-device. yt-dlp handles all the fragile parts (signature/PO-token, format
 * negotiation, resumable fragmented downloads, muxing), which is what makes
 * downloads stable regardless of how YouTube changes things.
 */
object YtDlp {

    private const val TAG = "YtDlp"

    @Volatile
    private var initialized = false
    private val initLock = Mutex()

    val isReady: Boolean get() = initialized

    /** Idempotent. Safe to call from anywhere; the heavy work runs once. */
    suspend fun ensureInit(context: Context) {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                initialized = true
            }
        }
    }

    /**
     * Pulls the newest yt-dlp binary so YouTube changes are handled without a
     * full app update. Best-effort — failure just keeps the bundled version.
     */
    suspend fun tryUpdate(context: Context) {
        if (!initialized) return
        runCatching {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(context)
            }
        }.onFailure { Log.w(TAG, "yt-dlp self-update skipped", it) }
    }

    /**
     * Runs a download. Blocking — call from a background context. [onProgress]
     * receives a 0..100 percentage. Throws on failure or when the process is
     * killed via [destroy].
     */
    fun execute(
        request: YoutubeDLRequest,
        processId: String,
        onProgress: (progress: Float, etaSeconds: Long, line: String) -> Unit,
    ) {
        YoutubeDL.getInstance().execute(request, processId) { progress, etaSeconds, line ->
            onProgress(progress, etaSeconds, line)
        }
    }

    /** Kills a running download started with [processId]. */
    fun destroy(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}
