package blbl.cat3399.core.api

import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class IqiyiSearchItem(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val pageUrl: String,
    val tvId: Long,
    val videoId: String,
    val videoPageUrl: String,
    val isShortVideo: Boolean,
    val durationSec: Int,
)

/** Native grid data for iQiyi web search. */
class IqiyiSearchApi(
    private val sessionStore: IqiyiSessionStore,
) {
    private val client =
        OkHttpClient.Builder()
            .cookieJar(sessionStore.cookies)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    suspend fun search(keyword: String): List<IqiyiSearchItem> = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        if (query.isBlank()) return@withContext emptyList()
        val url =
            SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("if", "html5")
                .addQueryParameter("key", query)
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://so.iqiyi.com/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        val body =
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("爱奇艺搜索 HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        val root = withContext(Dispatchers.Default) { JSONObject(body) }
        if (root.optString("code") != "A00000") throw IOException("爱奇艺搜索失败")
        val data = root.optJSONObject("data") ?: JSONObject()
        val docs = data.optJSONArray("docinfos") ?: JSONArray()
        val out = ArrayList<IqiyiSearchItem>(docs.length())
        val seen = HashSet<String>(docs.length() * 2)
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val album = doc.optJSONObject("albumDocInfo") ?: continue
            val videos = album.optJSONArray("videoinfos")
            val video = videos?.optJSONObject(0) ?: continue
            val tvId = video.optLong("tvId", 0L).takeIf { it > 0L }
                ?: video.optLong("qipu_id", 0L).takeIf { it > 0L }
                ?: continue
            val videoId = video.optString("vid", "").trim()
            if (videoId.isBlank()) continue
            if (!seen.add("$tvId:$videoId")) continue
            val albumTitle = clean(album.optString("albumTitle"))
            val episodeTitle = clean(video.optString("itemTitle"))
            val title = albumTitle.ifBlank { episodeTitle }
            if (title.isBlank()) continue
            val subtitle = when {
                episodeTitle.isBlank() -> channelLabel(album.optString("channel"))
                episodeTitle == title -> channelLabel(album.optString("channel"))
                else -> episodeTitle
            }.ifBlank { "爱奇艺" }
            val cover = normalizeUrl(
                album.optString("albumVImage").ifBlank {
                    video.optString("itemVImage").ifBlank { album.optString("albumImg") }
                },
            )
            out += IqiyiSearchItem(
                title = title,
                subtitle = subtitle,
                coverUrl = cover,
                pageUrl = normalizeUrl(album.optString("albumLink").ifBlank { video.optString("itemLink") }),
                videoPageUrl = normalizeUrl(video.optString("itemLink")),
                tvId = tvId,
                videoId = videoId,
                isShortVideo = album.optInt("videoDocType", 0) == 2,
                durationSec = video.optInt("timeLength", 0).coerceAtLeast(0),
            )
        }
        out
    }

    private fun channelLabel(raw: String): String = raw.substringBefore(',').trim()

    private fun normalizeUrl(value: String): String {
        val url = value.trim()
        if (url.isEmpty()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://www.iqiyi.com$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }
    }

    private fun clean(value: String): String = value
        .replace(HTML_TAG, "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\u200b", "")
        .trim()

    private companion object {
        const val SEARCH_URL = "https://search.video.iqiyi.com/o"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val HTML_TAG = Regex("<[^>]+>")
    }
}
