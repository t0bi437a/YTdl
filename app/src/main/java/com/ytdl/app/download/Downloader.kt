package com.ytdl.app.download

import com.ytdl.app.youtube.Http
import com.ytdl.app.youtube.InnerTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Ranged HTTP fetcher with resume and progress reporting. */
object Downloader {

    private const val BUFFER = 128 * 1024

    /**
     * Direct googlevideo throttles — and 403s — requests for a whole media file,
     * so those are pulled in bounded pieces through the `range` query parameter,
     * exactly like the official clients. Proxied (Piped) URLs stream fine in one
     * connection and use a plain Range header instead.
     */
    private const val CHUNK = 6L * 1024 * 1024

    private val FALLBACK_UA = InnerTube.PLAYER_CLIENTS.first().userAgent

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /** A 2xx response that delivered fewer bytes than promised: URL is dying. */
    class StreamDiedException : IOException("Stream ended prematurely")

    private class ReadResult(val bytes: Long, val restarted: Boolean)

    /**
     * Appends to [target] from where it left off.
     *
     * @param userAgent UA of the client that produced [url]; a mismatch 403s.
     * @param proxied true for Piped URLs (single connection + Range header).
     * @return total bytes on disk when finished.
     */
    suspend fun fetch(
        url: String,
        target: File,
        expectedSize: Long = 0,
        userAgent: String = "",
        proxied: Boolean = false,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val ua = userAgent.ifEmpty { FALLBACK_UA }

        val existing = if (target.exists()) target.length() else 0L
        if (expectedSize > 0 && existing >= expectedSize) {
            onProgress(existing, expectedSize)
            return@withContext existing
        }

        if (!proxied && expectedSize > 0) {
            fetchChunked(url, ua, target, expectedSize, existing, onProgress)
        } else {
            fetchRanged(url, ua, target, expectedSize, existing, onProgress)
        }
    }

    /** googlevideo: fixed-size pieces requested with `&range=start-end`. */
    private suspend fun fetchChunked(
        url: String,
        ua: String,
        target: File,
        expectedSize: Long,
        startAt: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        var written = startAt
        var lastReported = written
        while (written < expectedSize) {
            coroutineContext.ensureActive()
            val end = minOf(written + CHUNK, expectedSize) - 1
            val pieceUrl = url + (if ('?' in url) "&" else "?") + "range=$written-$end"
            val got = readSimple(pieceUrl, ua, target, append = written > 0) { readSoFar ->
                if (written + readSoFar - lastReported >= 512 * 1024) {
                    lastReported = written + readSoFar
                    onProgress(written + readSoFar, expectedSize)
                }
            }
            if (got <= 0) throw StreamDiedException()
            written += got
        }
        onProgress(written, expectedSize)
        return written
    }

    /** Proxied or unknown-size: one connection, resumed with a Range header. */
    private suspend fun fetchRanged(
        url: String,
        ua: String,
        target: File,
        expectedSize: Long,
        startAt: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        val header = if (startAt > 0) "bytes=$startAt-" else null
        var lastReported = startAt
        val total = if (expectedSize > 0) expectedSize else 0L

        val result = readRanged(url, ua, header, target) { readSoFar ->
            val base = if (startAt > 0) startAt else 0L
            val current = base + readSoFar
            if (current - lastReported >= 512 * 1024) {
                lastReported = current
                onProgress(current, total)
            }
        }

        val written = if (result.restarted) result.bytes else startAt + result.bytes
        // A short read on a known-size file means the URL was cut off; surface it
        // so the caller re-resolves and resumes from here.
        if (expectedSize > 0 && written < expectedSize) throw StreamDiedException()
        onProgress(written, if (total > 0) total else written)
        return written
    }

    private suspend fun readRanged(
        url: String,
        userAgent: String,
        rangeHeader: String?,
        target: File,
        onDelta: suspend (Long) -> Unit,
    ): ReadResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (rangeHeader != null) builder.header("Range", rangeHeader)

        Http.client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body ?: throw IOException("Empty response body")

            // Asked to resume but the server ignored the Range and restarted.
            val restarted = rangeHeader != null && response.code == 200
            val append = rangeHeader != null && !restarted

            var read = 0L
            body.byteStream().use { input ->
                FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onDelta(read)
                    }
                    output.flush()
                }
            }
            return ReadResult(read, restarted)
        }
    }

    private suspend fun readSimple(
        url: String,
        userAgent: String,
        target: File,
        append: Boolean,
        onDelta: suspend (Long) -> Unit,
    ): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body ?: throw IOException("Empty response body")

            var read = 0L
            body.byteStream().use { input ->
                FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onDelta(read)
                    }
                    output.flush()
                }
            }
            return read
        }
    }

    /** Small one-shot fetch used for subtitles and thumbnails. */
    suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", FALLBACK_UA).build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
