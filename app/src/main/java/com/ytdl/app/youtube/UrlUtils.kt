package com.ytdl.app.youtube

import android.net.Uri

object UrlUtils {

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val URL_IN_TEXT = Regex(
        "(https?://)?(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/[^\\s\"'<>]+",
        RegexOption.IGNORE_CASE
    )

    /** True when the input looks like a YouTube link (or a bare video id). */
    fun isYoutubeInput(text: String): Boolean = extractVideoId(text) != null

    /** Pulls the first YouTube URL out of arbitrary shared text. */
    fun findUrl(text: String): String? = URL_IN_TEXT.find(text)?.value

    /**
     * Accepts anything the YouTube app or a browser might hand us:
     * watch?v=, youtu.be/, /shorts/, /live/, /embed/, /v/, music.youtube.com,
     * a full share text with a title in front of the link, or a bare 11-char id.
     */
    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (VIDEO_ID.matches(trimmed)) return trimmed

        val raw = findUrl(trimmed) ?: return null
        val normalized = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"

        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments = uri.pathSegments ?: emptyList()

        val candidate = when {
            host == "youtu.be" -> segments.firstOrNull()
            segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed", "v") -> segments[1]
            else -> uri.getQueryParameterSafe("v")
        }
        return candidate?.takeIf { VIDEO_ID.matches(it) }
    }

    /** Playlist id, when the link points at one. */
    fun extractPlaylistId(input: String): String? {
        val raw = findUrl(input.trim()) ?: return null
        val normalized = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        return uri.getQueryParameterSafe("list")
    }

    private fun Uri.getQueryParameterSafe(key: String): String? =
        runCatching { getQueryParameter(key) }.getOrNull()

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    fun thumbnailUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}
