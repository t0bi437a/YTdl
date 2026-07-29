package com.ytdl.app.youtube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal InnerTube (youtubei/v1) client.
 *
 * Only clients whose `/player` response contains ready-to-use stream URLs are
 * used, so the app never has to run YouTube's obfuscated signature JavaScript.
 */
object InnerTube {

    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
    private const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse"
    private const val JSON_MEDIA = "application/json; charset=utf-8"

    private const val WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ANDROID_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    private const val IOS_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"

    data class Client(
        val name: String,
        val version: String,
        val key: String,
        val clientId: Int,
        val userAgent: String,
        val extras: Map<String, Any> = emptyMap(),
        val androidSdk: Int? = null,
    )

    /**
     * Tried in order until one returns playable formats. Different clients break
     * at different times, so the fallback chain is what keeps the app working.
     */
    val PLAYER_CLIENTS: List<Client> = listOf(
        Client(
            name = "ANDROID_VR",
            version = "1.62.27",
            key = ANDROID_KEY,
            clientId = 28,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extras = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "osName" to "Android",
                "osVersion" to "12L",
            ),
            androidSdk = 32,
        ),
        Client(
            name = "IOS",
            version = "20.10.4",
            key = IOS_KEY,
            clientId = 5,
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; US)",
            extras = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82",
            ),
        ),
        Client(
            name = "ANDROID",
            version = "19.44.38",
            key = ANDROID_KEY,
            clientId = 3,
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            extras = mapOf(
                "osName" to "Android",
                "osVersion" to "11",
            ),
            androidSdk = 30,
        ),
        Client(
            name = "MWEB",
            version = "2.20250311.03.00",
            key = WEB_KEY,
            clientId = 2,
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        ),
    )

    val WEB = Client(
        name = "WEB",
        version = "2.20250312.04.00",
        key = WEB_KEY,
        clientId = 1,
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
    )

    private fun context(client: Client): JSONObject {
        val c = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "en")
            .put("gl", "US")
            .put("platform", if (client.name == "WEB") "DESKTOP" else "MOBILE")
            .put("utcOffsetMinutes", 0)
        client.extras.forEach { (k, v) -> c.put(k, v) }
        client.androidSdk?.let { c.put("androidSdkVersion", it) }
        return JSONObject().put("client", c)
    }

    private fun baseBody(client: Client): JSONObject = JSONObject()
        .put("context", context(client))
        .put("contentCheckOk", true)
        .put("racyCheckOk", true)

    private fun post(url: String, client: Client, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$url?key=${client.key}&prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
            .header("Content-Type", JSON_MEDIA)
            .header("User-Agent", client.userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-YouTube-Client-Name", client.clientId.toString())
            .header("X-YouTube-Client-Version", client.version)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .build()

        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("InnerTube ${response.code} for ${client.name}")
            }
            if (text.isBlank()) throw IOException("Empty InnerTube response")
            return JSONObject(text)
        }
    }

    fun player(videoId: String, client: Client): JSONObject =
        post(PLAYER_URL, client, baseBody(client).put("videoId", videoId))

    fun search(query: String, params: String?): JSONObject {
        val body = baseBody(WEB).put("query", query)
        if (params != null) body.put("params", params)
        return post(SEARCH_URL, WEB, body)
    }

    fun searchContinuation(token: String): JSONObject =
        post(SEARCH_URL, WEB, baseBody(WEB).put("continuation", token))

    fun browse(browseId: String, params: String?): JSONObject {
        val body = baseBody(WEB).put("browseId", browseId)
        if (params != null) body.put("params", params)
        return post(BROWSE_URL, WEB, body)
    }

    fun browseContinuation(token: String): JSONObject =
        post(BROWSE_URL, WEB, baseBody(WEB).put("continuation", token))

    /** Search filter params (base64 protobuf understood by the search endpoint). */
    object Filters {
        const val VIDEOS = "EgIQAQ%3D%3D"
        const val CHANNELS = "EgIQAg%3D%3D"
        /** A channel's "Videos" tab. */
        const val CHANNEL_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA"
    }
}
