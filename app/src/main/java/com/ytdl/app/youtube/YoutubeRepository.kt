package com.ytdl.app.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Everything the UI needs from YouTube: search, channel listings and streams. */
object YoutubeRepository {

    // ---------------------------------------------------------------- search

    suspend fun searchVideos(query: String): SearchPage = withContext(Dispatchers.IO) {
        runCatching { Piped.searchVideos(query) }.getOrNull()
            ?.takeIf { it.videos.isNotEmpty() || it.channels.isNotEmpty() }
            ?: parsePage(InnerTube.search(query, InnerTube.Filters.VIDEOS))
    }

    suspend fun searchChannels(query: String): SearchPage = withContext(Dispatchers.IO) {
        runCatching { Piped.searchChannels(query) }.getOrNull()
            ?.takeIf { it.videos.isNotEmpty() || it.channels.isNotEmpty() }
            ?: parsePage(InnerTube.search(query, InnerTube.Filters.CHANNELS))
    }

    suspend fun continueSearch(token: String): SearchPage = withContext(Dispatchers.IO) {
        if (token.startsWith("PIPED:")) Piped.continuePage(token)
        else parsePage(InnerTube.searchContinuation(token.removePrefix("ITUBE:")))
    }

    suspend fun channelVideos(channelId: String): SearchPage = withContext(Dispatchers.IO) {
        runCatching { Piped.channel(channelId) }.getOrNull()
            ?.takeIf { it.videos.isNotEmpty() }
            ?: parsePage(InnerTube.browse(channelId, InnerTube.Filters.CHANNEL_VIDEOS))
    }

    suspend fun continueBrowse(token: String): SearchPage = withContext(Dispatchers.IO) {
        if (token.startsWith("PIPED:")) Piped.continuePage(token)
        else parsePage(InnerTube.browseContinuation(token.removePrefix("ITUBE:")))
    }

    private fun parsePage(root: JSONObject): SearchPage {
        val videos = Json.findAll(root, "videoRenderer").mapNotNull(::parseVideo) +
            Json.findAll(root, "compactVideoRenderer").mapNotNull(::parseVideo) +
            Json.findAll(root, "gridVideoRenderer").mapNotNull(::parseVideo)
        val channels = Json.findAll(root, "channelRenderer").mapNotNull(::parseChannel)

        val continuation = Json.findAll(root, "continuationCommand")
            .firstNotNullOfOrNull { it.optString("token", "").takeIf(String::isNotEmpty) }

        return SearchPage(
            videos = videos.distinctBy { it.id },
            channels = channels.distinctBy { it.id },
            continuation = continuation?.let { "ITUBE:$it" },
        )
    }

    private fun parseVideo(r: JSONObject): VideoItem? {
        val id = r.optString("videoId", "").ifEmpty { return null }

        val badges = r.optJSONArray("badges")?.toString().orEmpty()
        val thumbOverlay = r.optJSONArray("thumbnailOverlays")?.toString().orEmpty()
        val isLive = badges.contains("LIVE", ignoreCase = true) ||
            thumbOverlay.contains("LIVE", ignoreCase = true)

        val durationText = Json.text(r, "lengthText")
            .ifEmpty { Json.text(Json.path(r, "thumbnailOverlayTimeStatusRenderer", "text")) }

        val author = Json.text(r, "ownerText")
            .ifEmpty { Json.text(r, "longBylineText") }
            .ifEmpty { Json.text(r, "shortBylineText") }

        val channelId = Json.findAll(r, "browseEndpoint")
            .firstNotNullOfOrNull { it.optString("browseId", "").takeIf { s -> s.startsWith("UC") } }

        return VideoItem(
            id = id,
            title = Json.text(r, "title"),
            author = author,
            channelId = channelId,
            durationSeconds = Json.parseDuration(durationText),
            durationText = durationText,
            viewCountText = Json.text(r, "viewCountText")
                .ifEmpty { Json.text(r, "shortViewCountText") },
            publishedText = Json.text(r, "publishedTimeText"),
            thumbnailUrl = Json.bestThumbnail(r.optJSONObject("thumbnail"))
                .ifEmpty { UrlUtils.thumbnailUrl(id) },
            isLive = isLive,
        )
    }

    private fun parseChannel(r: JSONObject): ChannelItem? {
        val id = r.optString("channelId", "").ifEmpty { return null }
        return ChannelItem(
            id = id,
            name = Json.text(r, "title"),
            thumbnailUrl = Json.bestThumbnail(r.optJSONObject("thumbnail")),
            subscriberText = Json.text(r, "videoCountText")
                .ifEmpty { Json.text(r, "subscriberCountText") },
            videoCountText = Json.text(r, "subscriberCountText"),
        )
    }

    // ---------------------------------------------------------------- streams

    /**
     * Resolves playable streams. Piped (proxied, token-handled server-side) is
     * tried first because its URLs survive large downloads; the direct InnerTube
     * clients are the fallback when every Piped instance is unreachable.
     */
    suspend fun getStreams(videoId: String): StreamInfo = withContext(Dispatchers.IO) {
        runCatching { Piped.streams(videoId) }.getOrNull()
            ?.takeIf { it.videoStreams.isNotEmpty() || it.audioStreams.isNotEmpty() }
            ?.let { return@withContext it }
        getStreamsInnerTube(videoId)
    }

    private suspend fun getStreamsInnerTube(videoId: String): StreamInfo = withContext(Dispatchers.IO) {
        var lastError: String? = null
        var lastCause: Throwable? = null

        for (client in InnerTube.PLAYER_CLIENTS) {
            val response = try {
                InnerTube.player(videoId, client)
            } catch (e: IOException) {
                lastCause = e
                lastError = e.message
                continue
            }

            val status = response.optJSONObject("playabilityStatus")
            val state = status?.optString("status", "").orEmpty()
            if (state.isNotEmpty() && state != "OK") {
                lastError = Json.text(status, "reason")
                    .ifEmpty { status?.optString("reason").orEmpty() }
                    .ifEmpty { state }
                continue
            }

            val info = parseStreamInfo(videoId, response)
            if (info != null && (info.videoStreams.isNotEmpty() || info.audioStreams.isNotEmpty())) {
                return@withContext info.copy(
                    clientName = client.name,
                    clientUserAgent = client.userAgent,
                )
            }
            lastError = lastError ?: "no streams from ${client.name}"
        }

        throw ExtractionException(lastError ?: "Could not resolve streams", lastCause)
    }

    data class RecoveredStream(
        val stream: MediaStream,
        val userAgent: String,
        val proxied: Boolean,
    )

    /**
     * Called when a stream URL dies (403/410, or cut off mid-download). Fetches
     * a completely fresh set of streams and returns a live URL for the same
     * track. Piped is preferred (a new proxied URL needs no probe); otherwise
     * the InnerTube clients are walked and each candidate is probed first.
     */
    suspend fun recoverStream(
        videoId: String,
        itag: Int,
        kind: StreamKind,
        targetHeight: Int,
        targetBitrate: Long,
        /**
         * Set when the same itag keeps dying mid-download: forces a different
         * encoding of comparable quality instead of retrying a poisoned one.
         */
        excludeItag: Int? = null,
    ): RecoveredStream? = withContext(Dispatchers.IO) {
        runCatching { Piped.streams(videoId) }.getOrNull()?.let { info ->
            val all = info.videoStreams + info.audioStreams
            pickCandidate(all, kind, itag, excludeItag, targetHeight, targetBitrate)?.let {
                return@withContext RecoveredStream(it, info.clientUserAgent, proxied = true)
            }
        }

        for (client in InnerTube.PLAYER_CLIENTS) {
            val response = try {
                InnerTube.player(videoId, client)
            } catch (e: IOException) {
                continue
            }
            if (response.optJSONObject("playabilityStatus")
                    ?.optString("status", "OK") != "OK"
            ) continue

            val info = parseStreamInfo(videoId, response) ?: continue
            val all = info.videoStreams + info.audioStreams
            val candidate = pickCandidate(all, kind, itag, excludeItag, targetHeight, targetBitrate)
                ?: continue

            if (probeUrl(candidate.url, client.userAgent)) {
                return@withContext RecoveredStream(candidate, client.userAgent, proxied = false)
            }
        }
        null
    }

    private fun pickCandidate(
        all: List<MediaStream>,
        kind: StreamKind,
        itag: Int,
        excludeItag: Int?,
        targetHeight: Int,
        targetBitrate: Long,
    ): MediaStream? {
        if (excludeItag == null) {
            all.firstOrNull { it.itag == itag && it.url.isNotEmpty() }?.let { return it }
        }
        return all
            .filter { it.kind == kind && it.url.isNotEmpty() && it.itag != (excludeItag ?: -1) }
            .minWithOrNull(
                compareBy(
                    { kotlin.math.abs(it.height - targetHeight) },
                    { kotlin.math.abs(it.bitrate - targetBitrate) },
                )
            )
    }

    /** Cheap single-byte request that tells us whether a stream URL is alive. */
    private fun probeUrl(url: String, userAgent: String): Boolean = runCatching {
        val probe = url + (if ('?' in url) "&" else "?") + "range=0-0"
        val request = Request.Builder().url(probe)
            .header("User-Agent", userAgent)
            .build()
        Http.client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun parseStreamInfo(videoId: String, response: JSONObject): StreamInfo? {
        val streaming = response.optJSONObject("streamingData") ?: return null
        val details = response.optJSONObject("videoDetails")

        val streams = ArrayList<MediaStream>()
        for (field in listOf("formats", "adaptiveFormats")) {
            val arr = streaming.optJSONArray(field) ?: continue
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                parseFormat(f, muxed = field == "formats")?.let(streams::add)
            }
        }

        val video = streams.filter { it.isVideo }
        val audio = streams.filter { it.kind == StreamKind.AUDIO_ONLY }

        return StreamInfo(
            id = videoId,
            title = details?.optString("title").orEmpty().ifEmpty { videoId },
            author = details?.optString("author").orEmpty(),
            channelId = details?.optString("channelId"),
            durationSeconds = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L,
            thumbnailUrl = Json.bestThumbnail(details?.optJSONObject("thumbnail"))
                .ifEmpty { UrlUtils.thumbnailUrl(videoId) },
            viewCount = details?.optString("viewCount")?.toLongOrNull() ?: 0L,
            videoStreams = video,
            audioStreams = audio,
            subtitles = parseSubtitles(response),
        )
    }

    private fun parseFormat(f: JSONObject, muxed: Boolean): MediaStream? {
        // Formats without a plain `url` carry an obfuscated `signatureCipher`
        // that would need YouTube's player JS to unlock; skip them.
        val url = f.optString("url", "").ifEmpty { return null }

        val mime = f.optString("mimeType", "")
        val container = when {
            mime.contains("mp4") -> "mp4"
            mime.contains("webm") -> "webm"
            mime.contains("3gpp") -> "3gp"
            else -> "mp4"
        }
        val codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1)
            ?.split(",")?.firstOrNull()?.trim().orEmpty()

        val isAudioMime = mime.startsWith("audio")
        val height = f.optInt("height", 0)
        val kind = when {
            isAudioMime -> StreamKind.AUDIO_ONLY
            muxed -> StreamKind.MUXED
            else -> StreamKind.VIDEO_ONLY
        }

        val qualityLabel = when (kind) {
            StreamKind.AUDIO_ONLY -> {
                val kbps = (f.optLong("bitrate", 0L) / 1000).toInt()
                if (kbps > 0) "$kbps kbps" else f.optString("audioQuality", "audio")
            }

            else -> f.optString("qualityLabel", "").ifEmpty {
                if (height > 0) "${height}p" else f.optString("quality", "")
            }
        }

        return MediaStream(
            itag = f.optInt("itag", -1),
            url = url,
            mimeType = mime,
            container = container,
            codec = codec,
            kind = kind,
            qualityLabel = qualityLabel,
            height = height,
            fps = f.optInt("fps", 0),
            bitrate = f.optLong("bitrate", f.optLong("averageBitrate", 0L)),
            audioSampleRate = f.optString("audioSampleRate", "0").toIntOrNull() ?: 0,
            contentLength = f.optString("contentLength", "0").toLongOrNull() ?: 0L,
        )
    }

    private fun parseSubtitles(response: JSONObject): List<SubtitleTrack> {
        val list = Json.path(response, "captions", "playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return emptyList()
        val out = ArrayList<SubtitleTrack>()
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val base = t.optString("baseUrl", "")
            if (base.isEmpty()) continue
            out.add(
                SubtitleTrack(
                    languageCode = t.optString("languageCode", "und"),
                    name = Json.text(t, "name"),
                    url = if (base.contains("fmt=")) base else "$base&fmt=vtt",
                    autoGenerated = t.optString("kind", "") == "asr",
                )
            )
        }
        return out
    }

}
