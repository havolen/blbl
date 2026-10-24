package blbl.cat3399.feature.player

import android.graphics.Color
import android.os.Build
import blbl.cat3399.core.account.MangoSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Native client for the same danmaku configuration and feeds used by the Mango web player. */
internal object MangoDanmakuApi {
    internal data class Config(
        val cdnVersion: String?,
        val intervalSec: Int,
        val cdnHosts: List<String>,
    )

    fun fetchConfig(
        client: OkHttpClient,
        session: MangoSessionStore,
        videoId: String,
        programId: String,
        clipId: String,
        userAgent: String,
        fallbackIntervalSec: Int,
    ): Config? {
        if (videoId.isBlank() || programId.isBlank() || clipId.isBlank()) return null
        val url = requestUrl(
            "$API_ORIGIN/getctlbarrage",
            session,
            videoId,
            programId,
            clipId,
            extra = mapOf("version" to "9.0.0", "abroad" to "0", "os" to Build.VERSION.RELEASE, "platform" to "0", "mac" to ""),
        )
        val root = getJson(client, url, userAgent) ?: return null
        val data = root.optJSONObject("data") ?: return null
        val version = safeVersion(data.optString("cdn_version", ""))
        val hosts = data.optJSONArray("cdn_list")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    normalizeCdnHost(array.optString(index))?.let(::add)
                }
            }.distinct()
        }.orEmpty()
        val interval = data.optInt("interval", fallbackIntervalSec).coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        if (version == null && hosts.isEmpty()) {
            AppLog.w("MangoDanmaku", "config empty version=${data.optString("cdn_version", "")}")
            return Config(null, interval, DEFAULT_CDN_HOSTS)
        }
        AppLog.i("MangoDanmaku", "config version=$version hosts=${hosts.joinToString(",")} interval=$interval")
        return Config(version, interval, hosts.ifEmpty { DEFAULT_CDN_HOSTS })
    }

    /** Returns null for transport/format failure, and an empty list for a valid empty chunk. */
    fun fetchCdnChunk(
        client: OkHttpClient,
        hosts: List<String>,
        cdnVersion: String,
        index: Int,
        durationMs: Long,
        intervalSec: Int,
        userAgent: String,
    ): List<Danmaku>? {
        val version = safeVersion(cdnVersion) ?: return null
        for (host in (hosts.ifEmpty { DEFAULT_CDN_HOSTS }).mapNotNull(::normalizeCdnHost).distinct()) {
            val url = "https://$host/$version/$index.json"
            val body = getBody(client, url, userAgent) ?: continue
            val parsed = parseItems(body, durationMs, intervalSec, index)
            if (parsed == null) {
                AppLog.w("MangoDanmaku", "cdn parse failed host=$host index=$index")
            } else {
                return parsed
            }
        }
        return null
    }

    fun fetchServerChunk(
        client: OkHttpClient,
        session: MangoSessionStore,
        videoId: String,
        programId: String,
        clipId: String,
        index: Int,
        durationMs: Long,
        intervalSec: Int,
        userAgent: String,
    ): List<Danmaku>? {
        val startMs = index.toLong() * intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC) * 1_000L
        val url = requestUrl(
            "$API_ORIGIN/cdn/opbarrage",
            session,
            videoId,
            programId,
            clipId,
            extra = mapOf("time" to startMs.toString(), "allowedRC" to "1"),
        )
        val body = getBody(client, url, userAgent) ?: return null
        return parseItems(body, durationMs, intervalSec, index)
    }

    internal fun parseItems(body: String, durationMs: Long, intervalSec: Int, index: Int): List<Danmaku>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: root
        val array = data.optJSONArray("items") ?: data.optJSONArray("barrages") ?: return null
        val interval = intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        return buildList(array.length()) {
            for (itemIndex in 0 until array.length()) {
                val item = array.optJSONObject(itemIndex) ?: continue
                val text = item.optString("content", "").trim()
                if (text.isEmpty()) continue
                val rawTime = item.optString("time", "").toDoubleOrNull()
                    ?: item.optLong("time", -1L).takeIf { it >= 0L }?.toDouble()
                    ?: continue
                val timeMs = MangoDanmakuTime.toMillis(rawTime, durationMs, interval, index) ?: continue
                val mode = when (item.optInt("type", 0)) {
                    1 -> 5
                    2 -> 4
                    else -> 1
                }
                add(
                    Danmaku(
                        timeMs = timeMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        mode = mode,
                        text = text,
                        color = danmakuColor(item),
                        fontSize = 25,
                        weight = 0,
                        dmid = item.optLong("id", 0L).takeIf { it != 0L },
                    ),
                )
            }
        }
    }

    private fun danmakuColor(item: JSONObject): Int {
        val left = item.optJSONObject("v2_color")?.optJSONObject("color_left") ?: return Color.WHITE
        return Color.rgb(
            left.optInt("r", 255).coerceIn(0, 255),
            left.optInt("g", 255).coerceIn(0, 255),
            left.optInt("b", 255).coerceIn(0, 255),
        )
    }

    private fun requestUrl(
        url: String,
        session: MangoSessionStore,
        videoId: String,
        programId: String,
        clipId: String,
        extra: Map<String, String>,
    ): String {
        val uuid = session.historyRequestId()
        val deviceId = session.cookies.getCookieValue("__STKUUID")?.takeIf { it.isNotBlank() } ?: uuid
        val ticket = session.cookies.getCookieValue("HDCN")
            ?.substringBefore('-')
            ?.takeIf { it.isNotBlank() }
        return url.toHttpUrl().newBuilder().apply {
            addQueryParameter("vid", videoId)
            addQueryParameter("pid", programId)
            addQueryParameter("cid", clipId)
            addQueryParameter("uuid", uuid)
            addQueryParameter("deviceid", deviceId)
            ticket?.let { addQueryParameter("ticket", it) }
            extra.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
    }

    private fun getJson(client: OkHttpClient, url: String, userAgent: String): JSONObject? =
        getBody(client, url, userAgent)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun getBody(client: OkHttpClient, url: String, userAgent: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.mgtv.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.w(
                    "MangoDanmaku",
                    "http ${response.code} ${url.substringAfter("://").substringBefore('?')}",
                )
                return@use null
            }
            response.body?.string()
        }
    }.onFailure { AppLog.w("MangoDanmaku", "request failed (${it.javaClass.simpleName})") }.getOrNull()

    private fun safeVersion(value: String): String? {
        val version = value.trim().trim('/')
        return version.takeIf { it.isNotEmpty() && VERSION_PATTERN.matches(it) && it != "." && it != ".." }
    }

    private fun normalizeCdnHost(value: String): String? {
        val input = value.trim().removePrefix("//")
        val url = runCatching {
            if (input.startsWith("http://", true) || input.startsWith("https://", true)) input.toHttpUrl()
            else "https://$input".toHttpUrl()
        }.getOrNull() ?: return null
        val host = url.host.lowercase()
        return host.takeIf { it == "hitv.com" || it.endsWith(".hitv.com") }
    }

    private const val API_ORIGIN = "https://galaxy.bz.mgtv.com"
    private const val MIN_INTERVAL_SEC = 1
    private const val MAX_INTERVAL_SEC = 600
    private val VERSION_PATTERN = Regex("[A-Za-z0-9._-]+")
    private val DEFAULT_CDN_HOSTS = listOf("bullet-ws.hitv.com", "bullet-ali.hitv.com")
}

internal object MangoDanmakuTime {
    fun toMillis(rawTime: Double, durationMs: Long, intervalSec: Int, index: Int): Long? {
        if (!rawTime.isFinite() || rawTime < 0.0) return null
        val durationSec = durationMs.coerceAtLeast(0L) / 1_000.0
        val interval = intervalSec.coerceIn(1, 600)
        val startSec = index.coerceAtLeast(0) * interval.toDouble()
        val endSec = startSec + interval
        val startMs = startSec * 1_000.0
        val endMs = endSec * 1_000.0
        val nearSecondsInChunk = rawTime >= startSec - 1.0 && rawTime <= endSec + 1.0
        val nearMillisInChunk = rawTime >= startMs - 1_000.0 && rawTime <= endMs + 1_000.0
        val millis = when {
            index > 0 && rawTime < startSec - 1.0 && rawTime in 0.0..(interval + 1.0) ->
                (startSec + rawTime) * 1_000.0
            nearSecondsInChunk -> rawTime * 1_000.0
            index > 0 && rawTime < startMs - 1_000.0 && rawTime in 0.0..(interval * 1_000.0 + 1_000.0) ->
                startMs + rawTime
            nearMillisInChunk -> rawTime
            rawTime <= durationSec + 1.0 -> rawTime * 1_000.0
            else -> rawTime
        }
        if (!millis.isFinite() || millis < 0.0 || millis > durationMs) return null
        return millis.toLong().coerceAtMost(Int.MAX_VALUE.toLong())
    }
}
