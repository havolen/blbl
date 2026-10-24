package blbl.cat3399.core.api

import android.content.Context
import android.net.Uri
import android.provider.Settings
import blbl.cat3399.core.account.MangoSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MangoSearchItem(
    val title: String,
    val coverUrl: String,
    val pageUrl: String,
    val durationSec: Int,
    val subtitle: String,
)

data class MangoSearchPage(
    val items: List<MangoSearchItem>,
    val hasMore: Boolean,
)

/**
 * Mango PC search API. The endpoint requires a signed query:
 * MD5(secret + sorted-encodeURI-query + secret), while the final URL uses normal
 * percent-encoding for the same fields (the server only verifies the former).
 */
class MangoSearchApi(context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .cookieJar(MangoSessionStore(appContext).cookies)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun search(keyword: String, page: Int): MangoSearchPage = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        if (query.isEmpty()) return@withContext MangoSearchPage(emptyList(), false)
        val params = linkedMapOf(
            "src" to "mgtv",
            "did" to deviceId(),
            "timestamp" to timestamp(),
            "signVersion" to "1",
            "signNonce" to UUID.randomUUID().toString().replace("-", ""),
            "q" to query,
            "pn" to page.coerceAtLeast(1).toString(),
            "pc" to PAGE_SIZE.toString(),
            "corr" to "1",
            "_support" to "10000000",
            "allowedRC" to "1",
        )
        val signature = signature(params)
        val finalUrl = buildString {
            append(API_ORIGIN)
            append("/pc/search/v2?")
            append(
                params.entries.joinToString("&") { (name, value) ->
                    "${Uri.encode(name, "")}=${Uri.encode(value, "")}"
                },
            )
            append("&signature=").append(signature)
        }
        val request = Request.Builder()
            .url(finalUrl)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", SITE_ORIGIN)
            .header("Referer", "$SITE_ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("芒果搜索 HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) throw IOException("芒果搜索错误 ${root.optInt("code")}")
        val data = root.optJSONObject("data") ?: JSONObject()
        val contents = data.optJSONArray("contents") ?: JSONArray()
        val items = buildList(contents.length()) {
            for (index in 0 until contents.length()) {
                contents.optJSONObject(index)?.let(::parseContent)?.let(::add)
            }
        }
        MangoSearchPage(items = items, hasMore = data.optBoolean("hasMore", false))
    }

    private fun signature(params: Map<String, String>): String {
        val query = params.entries
            .filter { it.value.isNotBlank() }
            .sortedBy { it.key }
            .joinToString("&") { (name, value) ->
                "${Uri.encode(name, "")}=${Uri.encode(value, SIGN_ENCODE_ALLOW)}"
            }
        return md5(SECRET + query + SECRET)
    }

    private fun parseContent(content: JSONObject): MangoSearchItem? {
        val data = content.optJSONObject("data") ?: return null
        if (content.optString("type") == "video") {
            val pageUrl = normalizeUrl(data.optString("url"))
            if (pageUrl.isBlank()) return null
            return MangoSearchItem(
                title = composeDirectVideoTitle(
                    stripHtml(data.optString("title").ifBlank { data.optString("hitTitle") }),
                ),
                coverUrl = normalizeUrl(data.optString("pic")),
                pageUrl = pageUrl,
                durationSec = parseDuration(data.optString("updateInfo")),
                subtitle = typeLabel(data.optJSONArray("desc")).ifBlank { data.optString("updateInfo") },
            )
        }
        val years = data.optJSONArray("yearList") ?: return null
        for (y in 0 until years.length()) {
            val year = years.optJSONObject(y) ?: continue
            val sources = year.optJSONArray("sourceList") ?: continue
            for (s in 0 until sources.length()) {
                val source = sources.optJSONObject(s) ?: continue
                val videos = source.optJSONArray("videoList")
                if (videos != null && videos.length() > 0) {
                    val video = videos.optJSONObject(0) ?: continue
                    val pageUrl = normalizeUrl(video.optString("url"))
                    if (pageUrl.isBlank()) continue
                    val seriesName = seriesTitle(year.optString("hitTitle"))
                    val episodeTitle = stripHtml(video.optString("title").ifBlank { video.optString("subtitle") })
                    return MangoSearchItem(
                        title = composeSeriesTitle(seriesName, episodeTitle),
                        coverUrl = normalizeUrl(video.optString("pic").ifBlank { source.optString("pic") }),
                        pageUrl = pageUrl,
                        durationSec = parseDuration(video.optString("updateInfo")),
                        subtitle = typeLabel(year.optJSONArray("desc")).ifBlank { "芒果 TV" },
                    )
                }
                val pageUrl = normalizeUrl(source.optString("url"))
                if (pageUrl.isBlank()) continue
                return MangoSearchItem(
                    title = seriesTitle(year.optString("hitTitle")),
                    coverUrl = normalizeUrl(year.optString("pic")),
                    pageUrl = pageUrl,
                    durationSec = 0,
                    subtitle = typeLabel(year.optJSONArray("desc")).ifBlank { "芒果 TV" },
                )
            }
        }
        return null
    }

    private fun typeLabel(desc: JSONArray?): String {
        if (desc == null) return ""
        for (i in 0 until desc.length()) {
            val item = desc.optJSONObject(i) ?: continue
            if (item.optString("label") == "类型") return item.optString("text").trim()
        }
        return ""
    }

    private fun seriesTitle(raw: String): String {
        return stripHtml(raw)
            .replace(Regex("([\\u4e00-\\u9fa5])(\\d{4})"), "$1 $2")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun composeDirectVideoTitle(title: String): String {
        val clean = title.trim()
        if (clean.startsWith("[")) return clean
        val match = Regex("^(.+?)\\s+(第[0-9一二三四五六七八九十百]+[集期].*)$").find(clean) ?: return clean
        val series = seriesTitle(match.groupValues[1])
        if (series.isEmpty() || series.first().isDigit()) return clean
        if (series.startsWith("纯享") || series.startsWith("花絮") || series.startsWith("预告")) return clean
        return "[$series] ${match.groupValues[2]}"
    }

    private fun composeSeriesTitle(series: String, episode: String): String {
        val seriesName = series.trim()
        val episodeName = episode.trim()
        if (seriesName.isEmpty()) return episodeName
        if (episodeName.isEmpty()) return seriesName
        if (episodeName.startsWith("[$seriesName]")) return episodeName
        return "[$seriesName] $episodeName"
    }

    private fun parseDuration(value: String): Int {
        val parts = value.trim().split(':').mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun normalizeUrl(value: String): String {
        val url = value.trim()
        if (url.isEmpty()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$SITE_ORIGIN$url"
            else -> url
        }
    }

    private fun stripHtml(value: String): String = value
        .replace(HTML_TAG, "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    private fun deviceId(): String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        const val API_ORIGIN = "https://mobileso.bz.mgtv.com"
        const val SITE_ORIGIN = "https://www.mgtv.com"
        const val SECRET = "xHAa3YZflWLogZUOzl"
        const val SIGN_ENCODE_ALLOW = ";/?:@&=+$,#!~*'()-._"
        const val PAGE_SIZE = 10
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val HTML_TAG = Regex("<[^>]+>")
    }
}
