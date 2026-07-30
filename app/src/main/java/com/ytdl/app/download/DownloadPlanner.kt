package com.ytdl.app.download

import com.ytdl.app.settings.Quality
import com.ytdl.app.settings.Settings
import com.ytdl.app.youtube.MediaStream
import com.ytdl.app.youtube.StreamInfo
import com.ytdl.app.youtube.UrlUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Turns "this video, that quality" into a yt-dlp [DownloadTask]. */
object DownloadPlanner {

    /** Metadata needed to build a task without resolving streams first. */
    data class VideoMeta(
        val id: String,
        val title: String,
        val author: String,
        val thumbnailUrl: String,
        val durationSeconds: Long,
    )

    /**
     * @param height max video height, or <= 0 for best; ignored when [audioOnly].
     */
    fun build(meta: VideoMeta, height: Int, audioOnly: Boolean, settings: Settings): DownloadTask {
        val webm = settings.preferWebm
        val cappedHeight = if (height > 0) height else 0
        val heightFilter = if (cappedHeight > 0) "[height<=$cappedHeight]" else ""

        val selector = when {
            audioOnly && webm ->
                "bestaudio[ext=webm]/bestaudio[acodec=opus]/bestaudio"
            audioOnly ->
                "bestaudio[ext=m4a]/bestaudio"
            webm ->
                "bestvideo$heightFilter[ext=webm]+bestaudio[ext=webm]/" +
                    "bestvideo$heightFilter+bestaudio/best$heightFilter/best"
            else ->
                "bestvideo$heightFilter[ext=mp4]+bestaudio[ext=m4a]/" +
                    "bestvideo$heightFilter+bestaudio/best$heightFilter/best"
        }

        val mergeFormat = when {
            audioOnly && webm -> "opus"
            audioOnly -> "m4a"
            webm -> "webm"
            else -> "mp4"
        }

        val label = when {
            audioOnly -> "Audio"
            cappedHeight > 0 -> "${cappedHeight}p"
            else -> "Best"
        }

        return DownloadTask(
            id = UUID.randomUUID().toString(),
            videoId = meta.id,
            title = meta.title,
            author = meta.author,
            thumbnailUrl = meta.thumbnailUrl.ifEmpty { UrlUtils.thumbnailUrl(meta.id) },
            durationSeconds = meta.durationSeconds,
            sourceUrl = UrlUtils.watchUrl(meta.id),
            formatSelector = selector,
            mergeFormat = mergeFormat,
            audioOnly = audioOnly,
            qualityLabel = label,
            fileName = fileName(settings.filenameTemplate, meta, label),
            fileExtension = mergeFormat,
            downloadSubtitles = settings.downloadSubtitles,
            saveThumbnail = settings.saveThumbnail,
            status = DownloadStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
        )
    }

    /** Convenience for the quality picker, which hands over a chosen stream. */
    fun build(
        info: StreamInfo,
        videoStream: MediaStream?,
        audioStream: MediaStream?,
        settings: Settings,
    ): DownloadTask {
        val meta = VideoMeta(info.id, info.title, info.author, info.thumbnailUrl, info.durationSeconds)
        val audioOnly = videoStream == null
        val height = videoStream?.height ?: 0
        return build(meta, height, audioOnly, settings)
    }

    /** Height the user's default quality maps to (0 = best). */
    fun defaultHeight(settings: Settings): Int = when (settings.defaultHeight) {
        Quality.ASK, Quality.BEST -> 0
        else -> settings.defaultHeight
    }

    private fun fileName(template: String, meta: VideoMeta, quality: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val filled = template
            .replace("%title%", meta.title)
            .replace("%author%", meta.author)
            .replace("%quality%", quality)
            .replace("%id%", meta.id)
            .replace("%date%", date)
            .ifBlank { meta.title.ifBlank { meta.id } }
        return Output.sanitize(filled)
    }
}
