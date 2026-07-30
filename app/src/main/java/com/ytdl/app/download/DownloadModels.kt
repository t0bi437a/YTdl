package com.ytdl.app.download

import kotlinx.serialization.Serializable

enum class DownloadStatus {
    QUEUED, RUNNING, MERGING, SAVING, COMPLETED, FAILED, PAUSED, CANCELED, WAITING_WIFI
}

@Serializable
data class DownloadTask(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String = "",
    val durationSeconds: Long = 0,

    /** Direct stream URL for the video (or muxed) track; null for audio-only jobs. */
    val videoUrl: String? = null,
    val videoItag: Int = -1,
    val videoContainer: String = "mp4",
    val videoCodec: String = "",
    val videoBytes: Long = 0,

    val audioUrl: String? = null,
    val audioItag: Int = -1,
    val audioContainer: String = "mp4",
    val audioCodec: String = "",
    val audioBytes: Long = 0,

    /** User-Agent of the client that issued the stream URLs. */
    val userAgent: String = "",

    /** True when URLs are proxied (Piped): use a Range header, not chunk params. */
    val proxied: Boolean = false,

    /** True when the video track already contains audio and no muxing is needed. */
    val alreadyMuxed: Boolean = false,
    val audioOnly: Boolean = false,

    val qualityLabel: String = "",
    val fileName: String = "download",
    val fileExtension: String = "mp4",

    val subtitleUrl: String? = null,
    val subtitleLanguage: String = "",
    val saveThumbnail: Boolean = false,

    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val resultUri: String? = null,
    val createdAt: Long = 0,
    /** Position in the queue; lower runs first. Changed by the reorder buttons. */
    val queueOrder: Long = 0,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING ||
            status == DownloadStatus.MERGING || status == DownloadStatus.SAVING ||
            status == DownloadStatus.WAITING_WIFI

    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELED

    val displayName: String get() = "$fileName.$fileExtension"
}
