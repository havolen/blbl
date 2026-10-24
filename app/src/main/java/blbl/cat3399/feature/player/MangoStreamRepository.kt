package blbl.cat3399.feature.player

import blbl.cat3399.core.account.MangoSessionStore
import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MangoStream(
    val url: String,
    val userAgent: String,
    val clipId: String,
    val width: Int,
    val height: Int,
    val definition: String,
)

/**
 * Native Mango HLS resolver for devices whose system WebView cannot run the
 * Mango PC page. It uses the same PC streamList endpoint as the web player,
 * with the full parameter set required for a valid CDN token.
 */
class MangoStreamRepository(context: android.content.Context) {
    private val session = MangoSessionStore(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .cookieJar(session.cookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(videoId: String, clipId: String): MangoStream? = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || clipId.isBlank()) return@withContext null
        val did = session.cookies.getCookieValue("uuid")
            ?.takeIf { it.isNotBlank() }
            ?: session.historyRequestId()
        val actualClipId = resolveClipId(videoId, clipId)
        val params = linkedMapOf(
            "playType" to "1",
            "auth_mode" to "1",
            "definitionType" to "2",
            "definition" to "2",
            "fileSourceType" to "1",
            "video_id" to videoId,
            "did" to did,
            "suuid" to UUID.randomUUID().toString(),
            "vf" to "h264",
            "cxid" to "",
            "entranceType" to "0",
            "type" to "pch5",
            "_support" to "10000000",
            "src" to "mgtv",
            "abroad" to "0",
            "appVersion" to "9.0.9",
            "allowedRC" to "1",
            "sdk_version" to "156",
            "cputy" to "Chrome",
            "av1cap" to "1",
            "cid" to actualClipId,
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val list = getJson("https://pcweb.api.mgtv.com/video/streamList?$query") ?: return@withContext null
        val data = list.optJSONObject("data") ?: return@withContext null
        val streams = data.optJSONArray("stream") ?: return@withContext null
        var best: JSONObject? = null
        for (i in 0 until streams.length()) {
            val item = streams.optJSONObject(i) ?: continue
            if (item.optString("url").isBlank()) continue
            val name = item.optString("standardName")
            if (best == null || rank(name) > rank(best.optString("standardName"))) best = item
        }
        val selected = best ?: run {
            AppLog.w("MangoStream", "no playable stream video=$videoId clip=$clipId")
            return@withContext null
        }
        val domain = data.optJSONArray("stream_domain")?.optString(0).orEmpty()
        if (domain.isBlank()) return@withContext null
        val atcl = getJson(domain + selected.optString("url")) ?: return@withContext null
        val url = atcl.optString("info").trim()
        if (url.isBlank() || atcl.optString("status") == "error") {
            AppLog.w("MangoStream", "atcl failed code=${atcl.optInt("code")} info=${atcl.optString("info")}")
            return@withContext null
        }
        AppLog.i(
            "MangoStream",
            "resolved video=$videoId clip=$clipId def=${selected.optString("standardName")} " +
                "size=${selected.optString("videoWidth")}x${selected.optString("videoHeight")}",
        )
        MangoStream(
            url = url,
            userAgent = USER_AGENT,
            clipId = actualClipId,
            width = selected.optInt("videoWidth", 0),
            height = selected.optInt("videoHeight", 0),
            definition = selected.optString("standardName"),
        )
    }

    private fun resolveClipId(videoId: String, clipId: String): String {
        val url = "https://mobile-thor.api.mgtv.com/v1/vod/info?allowedRC=1&_support=10000000&src=mgtv" +
            "&videoId=${java.net.URLEncoder.encode(videoId, "UTF-8")}" +
            "&clipId=${java.net.URLEncoder.encode(clipId, "UTF-8")}"
        return getJson(url)?.optJSONObject("data")?.optJSONObject("info")
            ?.optJSONObject("clip")?.optString("clipId").orEmpty().ifBlank { clipId }
    }

    private fun rank(name: String): Int = when (name) {
        "1080P" -> 4
        "720P" -> 3
        "576P" -> 2
        "480P" -> 1
        else -> 0
    }

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.mgtv.com/")
            .header("Origin", "https://www.mgtv.com")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
            }
        }.onFailure { AppLog.w("MangoStream", "request failed", it) }.getOrNull()
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
