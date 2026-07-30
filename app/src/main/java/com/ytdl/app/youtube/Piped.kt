package com.ytdl.app.youtube

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Client for the public Piped API (https://github.com/TeamPiped/Piped).
 *
 * Piped resolves streams server-side — including the anti-bot token a plain app
 * cannot produce — and returns URLs served through its own proxy, which accept
 * Range requests and don't 403 mid-file. Every call races all instances at once
 * (see [ApiHttp]); returns null when none answer so the caller can fall through
 * to the next source. Returns the first page only — no pagination.
 */
object Piped {

    private val INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.reallyaboring.stream",
        "https://pipedapi.leptons.xyz",
        "https://piapi.ggtyler.dev",
        "https://pipedapi.darkness.services",
    )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ---------------------------------------------------------------- search

    suspend fun searchVideos(query: String): SearchPage? = search(query, "videos")

    suspend fun searchChannels(query: String): SearchPage? = search(query, "channels")

    private suspend fun search(query: String, filter: String): SearchPage? {
        val (_, body) = ApiHttp.raceGet(
            INSTANCES, "/search?q=${enc(query)}&filter=$filter",
        ) { it.contains("\"items\"") } ?: return null
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return parseItems(items)
    }

    suspend fun channel(channelId: String): SearchPage? {
        val (_, body) = ApiHttp.raceGet(
            INSTANCES, "/channel/$channelId",
        ) { it.contains("relatedStreams") } ?: return null
        val items = JSONObject(body).optJSONArray("relatedStreams") ?: JSONArray()
        return parseItems(items)
    }

    private fun parseItems(items: JSONArray): SearchPage {
        val videos = ArrayList<VideoItem>()
        val channels = ArrayList<ChannelItem>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val url = it.optString("url", "")
            val type = it.optString("type", "")
            when {
                type == "channel" || url.startsWith("/channel/") -> parseChannel(it)?.let(channels::add)
                type == "stream" || url.contains("watch?v=") -> parseVideo(it)?.let(videos::add)
            }
        }
        return SearchPage(videos.distinctBy { it.id }, channels.distinctBy { it.id }, null)
    }

    private fun parseVideo(it: JSONObject): VideoItem? {
        val url = it.optString("url", "")
        val id = Regex("v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: return null
        val duration = it.optLong("duration", 0L)
        val channelId = it.optString("uploaderUrl", "").substringAfterLast("/channel/", "")
            .takeIf { it.startsWith("UC") }
        return VideoItem(
            id = id,
            title = it.optString("title", ""),
            author = it.optString("uploaderName", ""),
            channelId = channelId,
            durationSeconds = duration.coerceAtLeast(0),
            durationText = if (duration > 0) formatDuration(duration) else "",
            viewCountText = formatCount(it.optLong("views", -1L), "views"),
            publishedText = it.optString("uploadedDate", "").ifEmpty { it.optString("uploaded", "") },
            thumbnailUrl = it.optString("thumbnail", "").ifEmpty { UrlUtils.thumbnailUrl(id) },
            isLive = duration <= 0 && it.optString("uploadedDate", "").isEmpty() &&
                !it.optBoolean("isShort", false),
        )
    }

    private fun parseChannel(it: JSONObject): ChannelItem? {
        val url = it.optString("url", "")
        val id = url.substringAfterLast("/channel/", "").ifEmpty { return null }
        return ChannelItem(
            id = id,
            name = it.optString("name", ""),
            thumbnailUrl = it.optString("thumbnail", ""),
            subscriberText = formatCount(it.optLong("subscribers", -1L), "subscribers"),
            videoCountText = it.optLong("videos", -1L).let { v -> if (v >= 0) "$v videos" else "" },
        )
    }

    // ---------------------------------------------------------------- streams

    suspend fun streams(videoId: String): StreamInfo? {
        val (_, body) = ApiHttp.raceGet(
            INSTANCES, "/streams/$videoId",
        ) { it.contains("audioStreams") || it.contains("videoStreams") } ?: return null

        val obj = JSONObject(body)
        if (obj.optString("error").isNotBlank()) return null

        val video = ArrayList<MediaStream>()
        val audio = ArrayList<MediaStream>()
        obj.optJSONArray("videoStreams")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseStream(it, audioOnly = false)?.let(video::add) }
            }
        }
        obj.optJSONArray("audioStreams")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseStream(it, audioOnly = true)?.let(audio::add) }
            }
        }
        if (video.isEmpty() && audio.isEmpty()) return null

        val uploaderUrl = obj.optString("uploaderUrl", "")
        return StreamInfo(
            id = videoId,
            title = obj.optString("title", videoId),
            author = obj.optString("uploader", ""),
            channelId = uploaderUrl.substringAfterLast("/channel/", "").takeIf { it.startsWith("UC") },
            durationSeconds = obj.optLong("duration", 0L).coerceAtLeast(0),
            thumbnailUrl = obj.optString("thumbnailUrl", "").ifEmpty { UrlUtils.thumbnailUrl(videoId) },
            viewCount = obj.optLong("views", 0L).coerceAtLeast(0),
            videoStreams = video,
            audioStreams = audio,
            subtitles = parseSubtitles(obj),
            clientName = "PIPED",
            clientUserAgent = InnerTube.WEB.userAgent,
            proxied = true,
        )
    }

    private fun parseStream(it: JSONObject, audioOnly: Boolean): MediaStream? {
        val url = it.optString("url", "").ifEmpty { return null }
        val mime = it.optString("mimeType", "")
        val container = when {
            mime.contains("mp4") -> "mp4"
            mime.contains("webm") -> "webm"
            mime.contains("3gpp") -> "3gp"
            else -> "mp4"
        }
        val codec = it.optString("codec", "")
            .ifEmpty { Regex("codecs=\"?([^\";]+)").find(mime)?.groupValues?.get(1).orEmpty() }
            .split(",").firstOrNull()?.trim().orEmpty()

        val quality = it.optString("quality", "")
        val height = it.optInt("height", 0)
            .takeIf { h -> h > 0 } ?: quality.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0
        val bitrate = it.optLong("bitrate", 0L)

        val kind = when {
            audioOnly -> StreamKind.AUDIO_ONLY
            it.optBoolean("videoOnly", true) -> StreamKind.VIDEO_ONLY
            else -> StreamKind.MUXED
        }
        val label = when {
            audioOnly -> if (bitrate > 0) "${bitrate / 1000} kbps" else quality.ifEmpty { "audio" }
            else -> quality.ifEmpty { if (height > 0) "${height}p" else "video" }
        }

        val itag = Regex("itag=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val contentLength = it.optLong("contentLength", -1L).let { c -> if (c < 0) 0L else c }

        return MediaStream(
            itag = itag,
            url = url,
            mimeType = mime,
            container = container,
            codec = codec,
            kind = kind,
            qualityLabel = label,
            height = height,
            fps = it.optInt("fps", 0),
            bitrate = bitrate,
            audioSampleRate = 0,
            contentLength = contentLength,
        )
    }

    private fun parseSubtitles(obj: JSONObject): List<SubtitleTrack> {
        val arr = obj.optJSONArray("subtitles") ?: return emptyList()
        val out = ArrayList<SubtitleTrack>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val url = t.optString("url", "")
            if (url.isEmpty()) continue
            out.add(
                SubtitleTrack(
                    languageCode = t.optString("code", "und"),
                    name = t.optString("name", ""),
                    url = url,
                    autoGenerated = t.optBoolean("autoGenerated", false),
                )
            )
        }
        return out
    }

    private fun formatDuration(seconds: Long): String {
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatCount(value: Long, suffix: String): String {
        if (value < 0) return ""
        if (value < 1000) return "$value $suffix"
        val units = arrayOf("K", "M", "B")
        var v = value.toDouble()
        var i = -1
        while (v >= 1000 && i < units.lastIndex) {
            v /= 1000
            i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f%s %s" else "%.1f%s %s", v, units[i], suffix)
    }
}
