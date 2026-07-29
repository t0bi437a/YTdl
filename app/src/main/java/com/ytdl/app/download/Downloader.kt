package com.ytdl.app.download

import com.ytdl.app.youtube.Http
import com.ytdl.app.youtube.InnerTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Plain ranged HTTP fetcher with resume and progress reporting. */
object Downloader {

    private const val BUFFER = 128 * 1024
    private val USER_AGENT = InnerTube.PLAYER_CLIENTS.first().userAgent

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /**
     * Appends to [target] using a Range request when it already has content.
     *
     * @param onProgress called with (bytesOnDisk, totalBytes) as data arrives.
     * @return total bytes on disk when finished.
     */
    suspend fun fetch(
        url: String,
        target: File,
        expectedSize: Long = 0,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()

        var existing = if (target.exists()) target.length() else 0L
        if (expectedSize > 0 && existing >= expectedSize) {
            onProgress(existing, expectedSize)
            return@withContext existing
        }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        Http.client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)

            // A server that ignores our Range restarts the file from zero.
            if (existing > 0 && response.code != 206) existing = 0

            val body = response.body ?: throw IOException("Empty response body")
            val remaining = body.contentLength()
            val total = when {
                expectedSize > 0 -> expectedSize
                remaining > 0 -> existing + remaining
                else -> 0L
            }

            val append = existing > 0
            var written = existing
            var lastReported = 0L

            body.byteStream().use { input ->
                java.io.FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        // Throttle UI/state churn to roughly every 512 KB.
                        if (written - lastReported >= 512 * 1024) {
                            lastReported = written
                            onProgress(written, total)
                        }
                    }
                    output.flush()
                }
            }

            onProgress(written, if (total > 0) total else written)
            return@withContext written
        }
    }

    /** Small one-shot fetch used for subtitles and thumbnails. */
    suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
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
