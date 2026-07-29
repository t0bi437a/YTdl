package com.ytdl.app.youtube

import org.json.JSONArray
import org.json.JSONObject

/**
 * InnerTube responses are deeply nested and Google reshuffles the wrappers every
 * few months. Instead of hard-coding paths we walk the tree and collect every
 * renderer of a given type, which survives most layout changes.
 */
object Json {

    fun findAll(root: Any?, key: String, limit: Int = Int.MAX_VALUE): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        walk(root, key, out, limit)
        return out
    }

    fun findFirst(root: Any?, key: String): JSONObject? = findAll(root, key, 1).firstOrNull()

    private fun walk(node: Any?, key: String, out: MutableList<JSONObject>, limit: Int) {
        if (out.size >= limit) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    if (out.size >= limit) return
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) {
                        out.add(v)
                        if (out.size >= limit) return
                    } else {
                        walk(v, key, out, limit)
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= limit) return
                    walk(node.opt(i), key, out, limit)
                }
            }
        }
    }

    /** Reads `obj.a.b.c`, returning null if any hop is missing. */
    fun path(root: JSONObject?, vararg keys: String): JSONObject? {
        var cur = root
        for (k in keys) {
            cur = cur?.optJSONObject(k) ?: return null
        }
        return cur
    }

    /**
     * InnerTube text nodes are either `{simpleText: "x"}` or
     * `{runs: [{text: "x"}, ...]}`; both appear for the same field.
     */
    fun text(node: JSONObject?): String {
        if (node == null) return ""
        node.optString("simpleText", "").let { if (it.isNotEmpty()) return it }
        val runs = node.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    fun text(parent: JSONObject?, field: String): String = text(parent?.optJSONObject(field))

    /** Largest thumbnail from a `{thumbnails: [{url, width, height}]}` node. */
    fun bestThumbnail(node: JSONObject?): String {
        val arr = node?.optJSONArray("thumbnails") ?: return ""
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

    /** "1:02:03" / "10:23" / "45" -> seconds. */
    fun parseDuration(text: String): Long {
        if (text.isBlank()) return 0
        val parts = text.trim().split(":").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) return 0
        var seconds = 0L
        for (p in parts) seconds = seconds * 60 + p
        return seconds
    }
}
