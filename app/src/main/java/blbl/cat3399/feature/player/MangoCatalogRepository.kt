package blbl.cat3399.feature.player

import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MangoPart(
    val name: String,
    val clipId: String,
    val extras: Boolean = false,
)

data class MangoSeason(
    val name: String,
    val clipId: String,
    val relClipIdsCsv: String,
    val seriesClipIdsCsv: String,
    val parts: List<MangoPart>,
)

data class MangoCatalog(
    val seriesName: String,
    val seasons: List<MangoSeason>,
    val currentSeasonClipId: String,
    val currentClipId: String,
    val currentVideoId: String,
)

/**
 * Full Mango episode catalog.
 *
 * The PC page only renders a window around the current episode. The same data is
 * available from:
 *  - /v1/vod/info       seasons, part clip ids, first episode page
 *  - /vod/video/positives  paginated episodes for one season/part
 */
class MangoCatalogRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val infoCache = HashMap<String, JSONObject>()

    suspend fun loadCatalog(videoId: String, clipId: String): MangoCatalog = withContext(Dispatchers.IO) {
        AppLog.i("MangoCatalog", "loadCatalog video=$videoId clip=$clipId")
        val current = vodInfo(videoId, clipId)
        val currentInfo = current.optJSONObject("data")?.optJSONObject("info")
        val resolvedClipId = currentInfo?.optJSONObject("clip")?.optString("clipId").orEmpty()
            .ifBlank { clipId }
        val seriesName = currentInfo?.optJSONObject("series")?.optString("seriesName").orEmpty()
            .ifBlank { currentInfo?.optJSONObject("clip")?.optString("clipName").orEmpty() }
        val currentSeasonName = currentInfo?.optJSONObject("clip")?.optString("clipName").orEmpty()
        val tabs = seasonTabs(current)
        val seasonTabs = if (tabs.isEmpty()) {
            listOf(JSONObject().put("tabId", resolvedClipId).put("name", currentSeasonName).put("current", 1))
        } else {
            tabs
        }
        val seasons = coroutineScope {
            seasonTabs.map { tab ->
                async {
                    val seasonClipId = tab.optString("tabId")
                    val seasonName = tab.optString("name").ifBlank { currentSeasonName }
                    loadSeason(videoId, seasonClipId, seasonName)
                }
            }.awaitAll()
        }
        val currentSeason = seasons.firstOrNull { season ->
            season.clipId == resolvedClipId || season.parts.any { it.clipId == resolvedClipId }
        } ?: seasons.firstOrNull()
        AppLog.i("MangoCatalog", "catalog series=$seriesName seasons=${seasons.size} parts=${seasons.sumOf { it.parts.size }}")
        MangoCatalog(
            seriesName = seriesName,
            seasons = seasons,
            currentSeasonClipId = currentSeason?.clipId.orEmpty(),
            currentClipId = resolvedClipId,
            currentVideoId = videoId,
        )
    }

    suspend fun loadEpisodes(
        catalog: MangoCatalog,
        season: MangoSeason,
        part: MangoPart,
    ): List<MangoEpisode> = withContext(Dispatchers.IO) {
        val result = ArrayList<MangoEpisode>()
        var page = 1
        var pageTotal = 1
        var expectedTotal = -1
        while (page <= pageTotal) {
            val json = positives(
                videoId = catalog.currentVideoId,
                mainClipId = season.clipId,
                relClipIds = season.relClipIdsCsv,
                seriesClipIds = season.seriesClipIdsCsv,
                clipId = part.clipId,
                page = page,
            )
            val data = json.optJSONObject("data") ?: break
            data.optJSONObject("page")?.let { p ->
                pageTotal = p.optInt("pageTotal", 1).coerceAtLeast(1)
                expectedTotal = p.optInt("total", expectedTotal)
            }
            val list = data.optJSONArray("list") ?: break
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                // positives also returns previews/interviews/recommendations.
                // Main playlist is type=4; extras are deliberately isolated in
                // their own "花絮/更多" part.
                if (item.has("type")) {
                    val type = item.optInt("type", 4)
                    if (part.extras) {
                        if (type !in EXTRA_TYPES) continue
                    } else if (type != 4) {
                        continue
                    }
                }
                val videoId = item.optString("videoId").trim()
                val itemClipId = item.optString("clipId").trim().ifBlank { part.clipId }
                if (videoId.isBlank()) continue
                val rawTitle = item.optJSONObject("detailTitle")?.optString("title")
                    ?.ifBlank { item.optString("title") }
                    ?: item.optString("title")
                val seriesLabel = composeSeriesLabel(catalog.seriesName, season.name)
                result.add(
                    MangoEpisode(
                        videoId = videoId,
                        clipId = itemClipId,
                        label = item.optString("serialno").trim(),
                        title = composeEpisodeTitle(seriesLabel, rawTitle),
                        active = videoId == catalog.currentVideoId,
                        imageUrl = item.optString("hImgUrl").ifBlank { item.optString("vImgUrl") },
                        date = item.optString("rbTitle").trim(),
                        seriesLabel = seriesLabel,
                    ),
                )
            }
            if (!part.extras && expectedTotal > 0 && result.size >= expectedTotal) break
            page++
        }
        AppLog.i(
            "MangoCatalog",
            "episodes season=${season.name} part=${part.name} count=${result.size} expected=$expectedTotal",
        )
        result.distinctBy { "${it.clipId}:${it.videoId}" }
    }

    private suspend fun loadSeason(videoId: String, seasonClipId: String, seasonName: String): MangoSeason {
        val info = vodInfo(videoId, seasonClipId)
        val rel = relClipIds(info).joinToString(",")
        val series = seriesClipIds(info).joinToString(",")
        val seasonTitle = info.optJSONObject("data")?.optJSONObject("info")
            ?.optJSONObject("clip")?.optString("clipName").orEmpty().ifBlank { seasonName }
        val parts = ArrayList<MangoPart>()
        parts.add(MangoPart(name = "正片", clipId = seasonClipId))
        val relIds = relClipIds(info)
        val partNames = coroutineScope {
            relIds.map { id ->
                async {
                    val partInfo = vodInfo(videoId, id)
                    val rawName = partInfo.optJSONObject("data")?.optJSONObject("info")
                        ?.optJSONObject("clip")?.optString("clipName").orEmpty()
                    val cleaned = rawName.removePrefix(seasonTitle).trim().ifBlank { rawName }
                    MangoPart(name = cleaned.ifBlank { "部分" }, clipId = id)
                }
            }.awaitAll()
        }
        parts.addAll(partNames)
        val mediaList = selectModule(info)?.optJSONObject("media")?.optJSONArray("list")
        var hasExtras = false
        if (mediaList != null) {
            for (i in 0 until mediaList.length()) {
                val type = mediaList.optJSONObject(i)?.optInt("type", 4) ?: continue
                if (type in EXTRA_TYPES) {
                    hasExtras = true
                    break
                }
            }
        }
        if (hasExtras) parts.add(MangoPart(name = "花絮/更多", clipId = seasonClipId, extras = true))
        return MangoSeason(
            name = seasonName,
            clipId = seasonClipId,
            relClipIdsCsv = rel,
            seriesClipIdsCsv = series,
            parts = parts,
        )
    }

    private fun vodInfo(videoId: String, clipId: String): JSONObject {
        val key = "$videoId:$clipId"
        infoCache[key]?.let { return it }
        val url = "https://mobile-thor.api.mgtv.com/v1/vod/info".toHttpUrl().newBuilder()
            .addQueryParameter("allowedRC", "1")
            .addQueryParameter("_support", "10000000")
            .addQueryParameter("src", "mgtv")
            .addQueryParameter("videoId", videoId)
            .addQueryParameter("clipId", clipId)
            .build()
        val json = getJson(url.toString())
        AppLog.i("MangoCatalog", "vodInfo clip=$clipId code=${json.optInt("code")} keys=${json.optJSONObject("data")?.keys()?.asSequence()?.joinToString(",")}")
        infoCache[key] = json
        return json
    }

    private fun positives(
        videoId: String,
        mainClipId: String,
        relClipIds: String,
        seriesClipIds: String,
        clipId: String,
        page: Int,
    ): JSONObject {
        val builder = "https://mobile.api.mgtv.com/vod/video/positives".toHttpUrl().newBuilder()
            .addQueryParameter("clipId", clipId)
            .addQueryParameter("t_mainClipId", mainClipId)
            .addQueryParameter("t_mainDisplayType", "2")
            .addQueryParameter("t_positiveVodRework", "0")
            .addQueryParameter("t_userIsVip", "unknown")
            .addQueryParameter("t_videoId", videoId)
            .addQueryParameter("pageNum", page.toString())
            .addQueryParameter("pageSize", "60")
            .addQueryParameter("allowedRC", "1")
            .addQueryParameter("_support", "10000000")
            .addQueryParameter("src", "mgtv")
        if (relClipIds.isNotBlank()) builder.addQueryParameter("t_relClipIds", relClipIds)
        if (seriesClipIds.isNotBlank()) builder.addQueryParameter("t_seriesClipIds", seriesClipIds)
        val url = builder.build()
        return getJson(url.toString())
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://www.mgtv.com/")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val body = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w("MangoCatalog", "http ${response.code} url=$url")
                    return JSONObject()
                }
                response.body?.string().orEmpty()
            }
        }.onFailure { AppLog.w("MangoCatalog", "request failed url=$url", it) }.getOrNull().orEmpty()
        return runCatching { JSONObject(body) }.getOrDefault(JSONObject())
    }

    private fun seasonTabs(info: JSONObject): List<JSONObject> {
        val tabs = selectModule(info)?.optJSONObject("media")?.optJSONArray("tabs") ?: return emptyList()
        val result = ArrayList<JSONObject>(tabs.length())
        for (i in 0 until tabs.length()) tabs.optJSONObject(i)?.let(result::add)
        return result
    }

    private fun relClipIds(info: JSONObject): List<String> = queryList(info, "t_relClipIds")

    private fun seriesClipIds(info: JSONObject): List<String> = queryList(info, "t_seriesClipIds")

    private fun queryList(info: JSONObject, name: String): List<String> {
        val url = selectModule(info)?.optString("url").orEmpty().replace("\\u0026", "&")
        if (url.isBlank()) return emptyList()
        val value = runCatching { url.toHttpUrl().queryParameter(name) }.getOrNull().orEmpty()
        return value.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun selectModule(info: JSONObject): JSONObject? {
        val modules = info.optJSONObject("data")?.optJSONObject("template")?.optJSONArray("modules") ?: return null
        for (i in 0 until modules.length()) {
            val module = modules.optJSONObject(i) ?: continue
            if (module.optString("title") == "选集" && module.optJSONObject("media") != null) return module
        }
        for (i in 0 until modules.length()) {
            val module = modules.optJSONObject(i) ?: continue
            if (module.optJSONObject("media")?.optJSONArray("tabs")?.length() ?: 0 > 0) return module
        }
        return null
    }

    private fun composeSeriesLabel(series: String, season: String): String {
        val s = series.trim()
        val n = season.trim()
        return when {
            s.isBlank() -> n
            n.isBlank() || n == s || s.contains(n) -> s
            else -> "$s $n"
        }
    }

    private fun composeEpisodeTitle(seriesLabel: String, title: String): String {
        val t = title.trim()
        if (seriesLabel.isBlank() || t.isBlank()) return t.ifBlank { seriesLabel }
        if (t.startsWith("[")) return t
        return "[$seriesLabel] $t"
    }

    private companion object {
        val EXTRA_TYPES = setOf(6, 10)
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
