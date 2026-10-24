package blbl.cat3399.core.api

import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class IqiyiHistoryItem(
    val title: String,
    val albumTitle: String,
    val coverUrl: String,
    val pageUrl: String,
    val tvId: Long,
    val videoId: String,
    val durationSec: Int,
    val watchedSec: Int,
    val progressPercent: Int,
    val addedAtSec: Long,
)

data class IqiyiHistoryPage(
    val items: List<IqiyiHistoryItem>,
    val hasMore: Boolean,
)

/** Logged-in iQiyi play history (the web "getallrc" endpoint). */
class IqiyiHistoryApi(
    private val sessionStore: IqiyiSessionStore,
) {
    private val client =
        OkHttpClient.Builder()
            .cookieJar(sessionStore.cookies)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    suspend fun loadPage(pageNum: Int): IqiyiHistoryPage = withContext(Dispatchers.IO) {
        val account = sessionStore.currentAccount() ?: throw IqiyiLoginRequiredException()
        val uid = account.uid?.takeIf { it.isNotBlank() }
            ?: sessionStore.cookies.getCookieValue("P00003")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IqiyiLoginRequiredException()
        val url =
            HISTORY_URL.toHttpUrl().newBuilder()
                .addQueryParameter("ckuid", uid)
                .addQueryParameter("only_long", "1")
                .addQueryParameter("vp", "0")
                .addQueryParameter("pageNum", pageNum.coerceAtLeast(1).toString())
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "text/plain, */*")
                .header("Referer", "https://www.iqiyi.com/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        val body =
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("爱奇艺历史 HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        val root = withContext(Dispatchers.Default) { JSONObject(unwrapJsonString(body)) }
        if (root.optString("code") != "A00000") throw IqiyiLoginRequiredException()
        val data = root.optJSONObject("data") ?: JSONObject()
        val total = data.optInt("total", 0)
        val records = data.optJSONArray("records") ?: org.json.JSONArray()
        val items = ArrayList<IqiyiHistoryItem>(records.length())
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val tvId = record.optLong("tvId", 0L).takeIf { it > 0L } ?: continue
            val videoId = record.optString("videoId", "").trim()
            if (videoId.isBlank()) continue
            val duration = record.optInt("videoDuration", 0).coerceAtLeast(0)
            val watched = record.optInt("videoPlayTime", 0).coerceIn(0, duration.coerceAtLeast(0))
            val title = clean(record.optString("videoName"))
                .ifBlank { clean(record.optString("albumName")) }
            if (title.isBlank()) continue
            items += IqiyiHistoryItem(
                title = title,
                albumTitle = clean(record.optString("albumName")),
                coverUrl = normalizeUrl(
                    record.optString("postImage").ifBlank {
                        record.optString("videoImageUrl").ifBlank {
                            record.optString("albumImageUrl")
                        }
                    },
                ),
                pageUrl = normalizeUrl(record.optString("videoUrl")),
                tvId = tvId,
                videoId = videoId,
                durationSec = duration,
                watchedSec = watched,
                progressPercent = if (duration > 0) (watched * 100 / duration).coerceIn(0, 100) else 0,
                addedAtSec = record.optLong("addtime", 0L).coerceAtLeast(0L),
            )
        }
        IqiyiHistoryPage(
            items = items,
            hasMore = pageNum.coerceAtLeast(1) * PAGE_SIZE < total,
        )
    }

    /** The history endpoint sometimes returns a JSON string containing the JSON object. */
    private fun unwrapJsonString(body: String): String {
        val raw = body.trim()
        if (!raw.startsWith("\"")) return raw
        return runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault(raw)
    }

    private fun normalizeUrl(value: String): String {
        val url = value.trim()
        if (url.isEmpty()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }
    }

    private fun clean(value: String): String = value
        .replace("\u200b", "")
        .replace("&amp;", "&")
        .trim()

    private companion object {
        const val HISTORY_URL = "https://l-rcd.iqiyi.com/apis/qiyirc/getallrc"
        const val PAGE_SIZE = 20
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

class IqiyiLoginRequiredException : IOException("爱奇艺账号需要重新登录")
