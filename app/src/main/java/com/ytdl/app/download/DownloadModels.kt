package com.ytdl.app.download

import kotlinx.serialization.Serializable

enum class DownloadStatus {
    QUEUED, RUNNING, MERGING, SAVING, COMPLETED, FAILED, PAUSED, CANCELED, WAITING_WIFI
}

/**
 * A download handed to yt-dlp. Instead of concrete stream URLs it carries a
 * yt-dlp format selector; yt-dlp re-resolves and downloads at run time, so a
 * task stays valid even after signed URLs would have expired.
 */
@Serializable
data class DownloadTask(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String = "",
    val durationSeconds: Long = 0,

    /** The watch URL yt-dlp is pointed at. */
    val sourceUrl: String,
    /** yt-dlp `-f` expression, e.g. "bestvideo[height<=1080]+bestaudio/best". */
    val formatSelector: String,
    /** Container to merge into / extract to: mp4, webm, m4a, opus. */
    val mergeFormat: String = "mp4",
    val audioOnly: Boolean = false,

    val qualityLabel: String = "",
    val fileName: String = "download",
    val fileExtension: String = "mp4",

    val downloadSubtitles: Boolean = false,
    val saveThumbnail: Boolean = false,

    val status: DownloadStatus = DownloadStatus.QUEUED,
    /** 0f..1f. yt-dlp reports a percentage rather than exact byte counts. */
    val percent: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val resultUri: String? = null,
    val createdAt: Long = 0,
    /** Position in the queue; lower runs first. */
    val queueOrder: Long = 0,
) {
    val progress: Float get() = percent.coerceIn(0f, 1f)

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING ||
            status == DownloadStatus.MERGING || status == DownloadStatus.SAVING ||
            status == DownloadStatus.WAITING_WIFI

    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELED

    val displayName: String get() = "$fileName.$fileExtension"
}
