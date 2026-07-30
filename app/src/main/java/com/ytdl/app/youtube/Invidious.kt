package com.ytdl.app.youtube

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Client for the public Invidious API (https://docs.invidious.io/api/).
 *
 * A second, independent pool of proxying instances used when every Piped
 * instance is down. `local=true` makes Invidious serve the media through its
 * own `/videoplayback` proxy, so — like Piped — the URLs take a Range header
 * and don't hit googlevideo's mid-file 403. All instances are raced; returns
 * null when none answer. First page only.
 */
object Invidious {

    private val INSTANCES = listOf(
        "https://invidious.nerdvpn.de",
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.jing.rocks",
        "https://iv.ggtyler.dev",
        "https://invidious.privacyredirect.com",
    )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ---------------------------------------------------------------- search

    suspend fun searchVideos(query: String): SearchPage? = search(query, "video")

    suspend fun searchChannels(query: String): SearchPage? = search(query, "channel")

    private suspend fun search(query: String, type: String): SearchPage? {
        val (_, body) = ApiHttp.raceGet(
            INSTANCES, "/api/v1/search?q=${enc(query)}&type=$type",
        ) { it.startsWith("[") } ?: return null
        return parseItems(JSONArray(body))
    }

    suspend fun channel(channelId: String): SearchPage? {
        val (_, body) = ApiHttp.raceGet(
            INSTANCES, "/api/v1/channels/$channelId/videos",
        ) { it.contains("\"videos\"") || it.startsWith("[") } ?: return null
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val arr = obj?.optJSONArray("videos") ?: runCatching { JSONArray(body) }.getOrNull()
        ?: return null
        return parseItems(arr)
    }

    private fun parseItems(items: JSONArray): SearchPage {
        val videos = ArrayList<VideoItem>()
        val channels = ArrayList<ChannelItem>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            when (it.optString("type", "")) {
                "channel" -> parseChannel(it)?.let(channels::add)
                "video", "shortVideo", "" -> parseVideo(it)?.let(videos::add)
            }
        }
        return SearchPage(videos.distinctBy { it.id }, channels.distinctBy { it.id }, null)
    }

    private fun parseVideo(it: JSONObject): VideoItem? {
        val id = it.optString("videoId", "").ifEmpty { return null }
        val duration = it.optLong("lengthSeconds", 0L)
        return VideoItem(
            id = id,
            title = it.optString("title", ""),
            author = it.optString("author", ""),
            channelId = it.optString("authorId", "").takeIf { s -> s.startsWith("UC") },
            durationSeconds = duration,
            durationText = if (duration > 0) formatDuration(duration) else "",
            viewCountText = formatCount(it.optLong("viewCount", -1L), "views"),
            publishedText = it.optString("publishedText", ""),
            thumbnailUrl = bestThumb(it.optJSONArray("videoThumbnails"))
                .ifEmpty { UrlUtils.thumbnailUrl(id) },
            isLive = it.optBoolean("liveNow", false),
        )
    }

    private fun parseChannel(it: JSONObject): ChannelItem? {
        val id = it.optString("authorId", "").ifEmpty { return null }
        return ChannelItem(
            id = id,
            name = it.optString("author", ""),
            thumbnailUrl = bestThumb(it.optJSONArray("authorThumbnails")),
            subscriberText = formatCount(it.optLong("subCount", -1L), "subscribers"),
            videoCountText = it.optLong("videoCount", -1L).let { v -> if (v >= 0) "$v videos" else "" },
        )
    }

    // ---------------------------------------------------------------- streams

    suspend fun streams(videoId: String): StreamInfo? {
        val (base, body) = ApiHttp.raceGet(
            INSTANCES, "/api/v1/videos/$videoId?local=true",
        ) { it.contains("adaptiveFormats") || it.contains("formatStreams") } ?: return null

        val obj = JSONObject(body)
        if (obj.optString("error").isNotBlank()) return null

        val video = ArrayList<MediaStream>()
        val audio = ArrayList<MediaStream>()

        obj.optJSONArray("adaptiveFormats")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseFormat(it, base, muxed = false)?.let { s ->
                    if (s.kind == StreamKind.AUDIO_ONLY) audio.add(s) else video.add(s)
                } }
            }
        }
        obj.optJSONArray("formatStreams")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseFormat(it, base, muxed = true)?.let(video::add) }
            }
        }
        if (video.isEmpty() && audio.isEmpty()) return null

        return StreamInfo(
            id = videoId,
            title = obj.optString("title", videoId),
            author = obj.optString("author", ""),
            channelId = obj.optString("authorId", "").takeIf { it.startsWith("UC") },
            durationSeconds = obj.optLong("lengthSeconds", 0L),
            thumbnailUrl = bestThumb(obj.optJSONArray("videoThumbnails"))
                .ifEmpty { UrlUtils.thumbnailUrl(videoId) },
            viewCount = obj.optLong("viewCount", 0L),
            videoStreams = video,
            audioStreams = audio,
            subtitles = parseCaptions(obj, base),
            clientName = "INVIDIOUS",
            clientUserAgent = InnerTube.WEB.userAgent,
            proxied = true,
        )
    }

    private fun parseFormat(it: JSONObject, base: String, muxed: Boolean): MediaStream? {
        var url = it.optString("url", "").ifEmpty { return null }
        // Some instances return a relative /videoplayback path.
        if (url.startsWith("/")) url = base + url

        val type = it.optString("type", "")
        val container = when {
            type.contains("mp4") -> "mp4"
            type.contains("webm") -> "webm"
            type.contains("3gpp") -> "3gp"
            else -> "mp4"
        }
        val codec = Regex("codecs=\"?([^\"]+)\"?").find(type)?.groupValues?.get(1)
            ?.split(",")?.firstOrNull()?.trim().orEmpty()

        val isAudio = type.startsWith("audio")
        val resolution = it.optString("resolution", "").ifEmpty { it.optString("qualityLabel", "") }
        val height = resolution.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0
        val bitrate = it.optString("bitrate", "0").toLongOrNull() ?: 0L

        val kind = when {
            isAudio -> StreamKind.AUDIO_ONLY
            muxed -> StreamKind.MUXED
            else -> StreamKind.VIDEO_ONLY
        }
        val label = when {
            isAudio -> if (bitrate > 0) "${bitrate / 1000} kbps" else "audio"
            else -> resolution.ifEmpty { if (height > 0) "${height}p" else "video" }
        }

        val itag = it.optString("itag", "").toIntOrNull()
            ?: Regex("itag=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val contentLength = it.optString("clen", "0").toLongOrNull() ?: 0L

        return MediaStream(
            itag = itag,
            url = url,
            mimeType = type,
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

    private fun parseCaptions(obj: JSONObject, base: String): List<SubtitleTrack> {
        val arr = obj.optJSONArray("captions") ?: return emptyList()
        val out = ArrayList<SubtitleTrack>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            var url = t.optString("url", "")
            if (url.isEmpty()) continue
            if (url.startsWith("/")) url = base + url
            out.add(
                SubtitleTrack(
                    languageCode = t.optString("language_code", t.optString("languageCode", "und")),
                    name = t.optString("label", ""),
                    url = url,
                    autoGenerated = false,
                )
            )
        }
        return out
    }

    private fun bestThumb(arr: JSONArray?): String {
        if (arr == null) return ""
        var best = ""
        var bestW = -1
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val w = t.optInt("width", 0)
            if (w >= bestW) {
                bestW = w
                best = t.optString("url", "")
            }
        }
        return if (best.startsWith("//")) "https:$best" else best
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
