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

/** Ranged HTTP fetcher tuned for googlevideo, with resume and progress. */
object Downloader {

    private const val BUFFER = 128 * 1024

    /**
     * googlevideo throttles — and often rejects with 403 — single requests for
     * a whole media file. Official clients ask for bounded pieces through the
     * `range` query parameter, so we do the same.
     */
    private const val CHUNK = 6L * 1024 * 1024

    private val FALLBACK_UA = InnerTube.PLAYER_CLIENTS.first().userAgent

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /**
     * Appends to [target] from where it left off.
     *
     * @param userAgent must be the UA of the InnerTube client that produced
     *   [url]; a mismatch is answered with 403.
     * @return total bytes on disk when finished.
     */
    suspend fun fetch(
        url: String,
        target: File,
        expectedSize: Long = 0,
        userAgent: String = "",
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val ua = userAgent.ifEmpty { FALLBACK_UA }

        var written = if (target.exists()) target.length() else 0L
        if (expectedSize > 0 && written >= expectedSize) {
            onProgress(written, expectedSize)
            return@withContext written
        }

        if (expectedSize > 0) {
            // Known size: pull bounded pieces via the range query parameter.
            var lastReported = written
            while (written < expectedSize) {
                coroutineContext.ensureActive()
                val end = minOf(written + CHUNK, expectedSize) - 1
                val pieceUrl = url + (if ('?' in url) "&" else "?") + "range=$written-$end"
                val got = readInto(pieceUrl, ua, rangeHeader = null, target, append = written > 0) {
                    if (written + it - lastReported >= 512 * 1024) {
                        lastReported = written + it
                        onProgress(written + it, expectedSize)
                    }
                }
                if (got <= 0) throw IOException("Empty chunk at $written")
                written += got
            }
            onProgress(written, expectedSize)
            written
        } else {
            // Unknown size (subtitles, thumbnails, odd streams): one request,
            // resumed with a Range header when partial data already exists.
            val header = if (written > 0) "bytes=$written-" else null
            var lastReported = written
            val got = readInto(url, ua, header, target, append = written > 0) {
                if (written + it - lastReported >= 512 * 1024) {
                    lastReported = written + it
                    onProgress(written + it, 0)
                }
            }
            written += got
            onProgress(written, written)
            written
        }
    }

    /** Streams one response body into [target]; returns bytes read. */
    private suspend fun readInto(
        url: String,
        userAgent: String,
        rangeHeader: String?,
        target: File,
        append: Boolean,
        onDelta: suspend (Long) -> Unit,
    ): Long {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (rangeHeader != null) builder.header("Range", rangeHeader)

        Http.client.newCall(builder.build()).execute().use { response ->
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
