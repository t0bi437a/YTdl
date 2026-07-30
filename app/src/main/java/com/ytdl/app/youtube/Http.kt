package com.ytdl.app.youtube

import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

object Http {

    /**
     * Shared client. HTTP/1.1 only: googlevideo occasionally mishandles very long
     * ranged HTTP/2 streams, and downloads are much more stable without it.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    /**
     * Short-timeout client for metadata APIs (Piped/Invidious/InnerTube). Public
     * instances are often dead, so a hung request must fail fast to let the next
     * instance or source take over — never the 60 s of the download client.
     */
    val apiClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
