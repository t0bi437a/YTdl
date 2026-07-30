package com.ytdl.app.youtube

import android.util.Base64
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Client for the public Piped API (https://github.com/TeamPiped/Piped).
 *
 * Piped resolves streams server-side — including the anti-bot token that a
 * plain app cannot produce — and hands back URLs served through its own proxy.
 * Those URLs accept ranged requests, don't expire in minutes and don't answer
 * 403 mid-file, which is what makes large downloads reliable. Several public
 * instances are tried in turn because any single one is often down.
 */
object Piped {

    /** Public instances, tried in order; the last working one is remembered. */
    private val INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.reallyaboring.stream",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi-libre.kavin.rocks",
        "https://piped-api.lunar.icu",
    )

    val USER_AGENT = InnerTube.WEB.userAgent

    @Volatile
    private var preferred: String? = null

    private fun ordered(): List<String> {
        val p = preferred ?: return INSTANCES
        return listOf(p) + INSTANCES.filter { it != p }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun getOn(instance: String, path: String): JSONObject? = try {
        val request = Request.Builder()
            .url("$instance$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(request).execute().use { r ->
            val body = r.body?.string()
            when {
                !r.isSuccessful -> null
                body.isNullOrBlank() -> null
                body.startsWith("<") -> null // an HTML error page
                else -> JSONObject(body).takeUnless { it.has("error") }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun get(path: String): JSONObject? {
        for (instance in ordered()) {
            val obj = getOn(instance, path)
            if (obj != null) {
                preferred = instance
                return obj
            }
        }
        return null
    }

    // ---------------------------------------------------------------- search

    fun searchVideos(query: String): SearchPage = search(query, "videos")

    fun searchChannels(query: String): SearchPage = search(query, "channels")

    private fun search(query: String, filter: String): SearchPage {
        val obj = get("/search?q=${enc(query)}&filter=$filter")
            ?: throw ExtractionException("Piped search unavailable")
        val items = obj.optJSONArray("items") ?: JSONArray()
        val page = parseItems(items)
        val next = obj.optString("nextpage", "")
        return page.copy(
            continuation = encodeCont("search", preferred.orEmpty(), next, query, filter),
        )
    }

    fun channel(channelId: String): SearchPage {
        val obj = get("/channel/$channelId")
            ?: throw ExtractionException("Piped channel unavailable")
        val items = obj.optJSONArray("relatedStreams") ?: JSONArray()
        val page = parseItems(items)
        val next = obj.optString("nextpage", "")
        return page.copy(
            continuation = encodeCont("channel", preferred.orEmpty(), next, channelId, ""),
        )
    }

    fun continuePage(token: String): SearchPage {
        val parts = decodeCont(token) ?: return SearchPage(emptyList(), emptyList(), null)
        val (kind, instance, nextpage, a, b) = parts
        if (nextpage.isBlank() || nextpage == "null") {
            return SearchPage(emptyList(), emptyList(), null)
        }

        val path = when (kind) {
            "search" -> "/nextpage/search?nextpage=${enc(nextpage)}&q=${enc(a)}&filter=$b"
            "channel" -> "/nextpage/channel/$a?nextpage=${enc(nextpage)}"
            else -> return SearchPage(emptyList(), emptyList(), null)
        }

        val obj = getOn(instance, path) ?: return SearchPage(emptyList(), emptyList(), null)
        val items = obj.optJSONArray("items")
            ?: obj.optJSONArray("relatedStreams")
            ?: JSONArray()
        val page = parseItems(items)
        val next = obj.optString("nextpage", "")
        return page.copy(continuation = encodeCont(kind, instance, next, a, b))
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
            viewCountText = formatViews(it.optLong("views", -1L)),
            publishedText = it.optString("uploadedDate", "").ifEmpty { it.optString("uploaded", "") },
            thumbnailUrl = it.optString("thumbnail", "").ifEmpty { UrlUtils.thumbnailUrl(id) },
            isLive = duration <= 0 && it.optBoolean("isShort", false).not() &&
                it.optString("uploadedDate", "").isEmpty(),
        )
    }

    private fun parseChannel(it: JSONObject): ChannelItem? {
        val url = it.optString("url", "")
        val id = url.substringAfterLast("/channel/", "").ifEmpty { return null }
        return ChannelItem(
            id = id,
            name = it.optString("name", ""),
            thumbnailUrl = it.optString("thumbnail", ""),
            subscriberText = formatSubs(it.optLong("subscribers", -1L)),
            videoCountText = it.optLong("videos", -1L).let {
                if (it >= 0) "$it videos" else ""
            },
        )
    }

    // ---------------------------------------------------------------- streams

    fun streams(videoId: String): StreamInfo {
        val obj = get("/streams/$videoId")
            ?: throw ExtractionException("Piped streams unavailable")

        obj.optString("error").takeIf { it.isNotBlank() }?.let {
            throw ExtractionException(it)
        }

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
            clientUserAgent = USER_AGENT,
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
            else -> if (audioOnly) "mp4" else "mp4"
        }
        val codec = it.optString("codec", "")
            .ifEmpty { Regex("codecs=\"?([^\";]+)").find(mime)?.groupValues?.get(1).orEmpty() }
            .split(",").firstOrNull()?.trim().orEmpty()

        val quality = it.optString("quality", "")
        val height = it.optInt("height", 0)
            .takeIf { it > 0 } ?: quality.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0
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
        val contentLength = it.optLong("contentLength", -1L).let { if (it < 0) 0L else it }

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
            val url = t.optString("url", "").ifEmpty { continue }
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

    // ------------------------------------------------------------- utilities


    private data class Cont(
        val kind: String,
        val instance: String,
        val nextpage: String,
        val a: String,
        val b: String,
    )

    private fun encodeCont(
        kind: String,
        instance: String,
        nextpage: String,
        a: String,
        b: String,
    ): String {
        val payload = listOf(kind, instance, nextpage, a, b).joinToString("\u0001")
        return "PIPED:" + Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeCont(token: String): Cont? {
        if (!token.startsWith("PIPED:")) return null
        return try {
            val decoded = String(Base64.decode(token.removePrefix("PIPED:"), Base64.NO_WRAP))
            val parts = decoded.split("\u0001")
            if (parts.size < 5) null
            else Cont(parts[0], parts[1], parts[2], parts[3], parts[4])
        } catch (e: Exception) {
            null
        }
    }

    private fun formatDuration(seconds: Long): String {
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatViews(views: Long): String =
        if (views < 0) "" else "${compact(views)} views"

    private fun formatSubs(subs: Long): String =
        if (subs < 0) "" else "${compact(subs)} subscribers"

    private fun compact(value: Long): String {
        if (value < 1000) return value.toString()
        val units = arrayOf("K", "M", "B")
        var v = value.toDouble()
        var i = -1
        while (v >= 1000 && i < units.lastIndex) {
            v /= 1000
            i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f%s" else "%.1f%s", v, units[i])
    }
}
