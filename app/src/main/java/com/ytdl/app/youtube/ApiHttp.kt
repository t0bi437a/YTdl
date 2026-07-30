package com.ytdl.app.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Helper for talking to a pool of interchangeable public API instances.
 *
 * Instances are hit all at once and the first valid reply wins; the rest are
 * cancelled. That turns "8 dead instances × a long timeout" (minutes of
 * spinning) into "as fast as the quickest live instance", which is what keeps
 * the quality sheet and channel screens responsive.
 */
object ApiHttp {

    private val UA = InnerTube.WEB.userAgent

    /**
     * @return (instance, responseBody) of the first instance whose body passes
     *   [validate], or null when every instance failed.
     */
    suspend fun raceGet(
        bases: List<String>,
        path: String,
        validate: (String) -> Boolean,
    ): Pair<String, String>? = coroutineScope {
        val results = Channel<Pair<String, String>?>(Channel.UNLIMITED)
        val jobs = bases.map { base ->
            launch(Dispatchers.IO) {
                val outcome = try {
                    val body = body(base + path)
                    if (body != null && validate(body)) base to body else null
                } catch (e: Exception) {
                    null
                }
                results.trySend(outcome)
            }
        }

        var found: Pair<String, String>? = null
        var received = 0
        while (received < bases.size && found == null) {
            val r = results.receive()
            received++
            if (r != null) found = r
        }
        jobs.forEach { it.cancel() }
        found
    }

    fun body(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        Http.apiClient.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string()
            return when {
                body.isNullOrBlank() -> null
                body.startsWith("<") -> null // an HTML error page
                else -> body
            }
        }
    }
}
