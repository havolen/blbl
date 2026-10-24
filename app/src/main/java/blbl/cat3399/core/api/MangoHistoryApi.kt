package blbl.cat3399.core.api

import android.os.Build
import blbl.cat3399.core.account.MangoSessionStore
import blbl.cat3399.core.model.VideoCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class MangoHistoryItem(
    val playId: String,
    val pType: Int,
    val pid: String,
    val vid: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Int,
    val watchedSec: Long,
    val updateTimeSec: Long,
    val nextVid: String?,
) {
    val progressPercent: Int
        get() = if (durationSec <= 0) 0 else ((watchedSec * 100) / durationSec).toInt().coerceIn(0, 100)

    fun pageUrl(): String {
        val path = when (pType) {
            1 -> "/s/$vid.html"
            2 -> "/l/$pid/$vid.html"
            3 -> "/b/$pid/$vid.html"
            else -> throw IllegalStateException("unsupported_mango_video_type")
        }
        return "$SITE_ORIGIN$path?start_time=$watchedSec&lastp=personal_center"
    }

    fun toVideoCard(): VideoCard = VideoCard(
        bvid = "mgtv:$playId",
        cid = null,
        title = title,
        coverUrl = coverUrl,
        durationSec = durationSec,
        ownerName = "芒果 TV",
        ownerFace = null,
        view = null,
        danmaku = null,
        pubDate = updateTimeSec,
        pubDateText = if (durationSec > 0) "已观看 $progressPercent%" else "观看记录",
        progressSec = watchedSec.takeIf { it > 0L },
        progressFinished = durationSec > 0 && watchedSec >= durationSec,
    )

    private companion object {
        const val SITE_ORIGIN = "https://www.mgtv.com"
    }
}

data class MangoHistoryPage(
    val items: List<MangoHistoryItem>,
    val nextBeforeTime: Long?,
)

class MangoHistoryApi(private val sessionStore: MangoSessionStore) {
    private val client = MangoApiClient(sessionStore.cookies)

    suspend fun loadPage(beforeTime: Long? = null): MangoHistoryPage {
        val account = sessionStore.currentAccount() ?: throw MangoLoginRequiredException()
        val uid = account.uid
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?: fetchAndSaveUid()
        val params = commonParams() + mapOf(
            "uid" to uid,
            "pageSize" to PAGE_SIZE,
            "from" to "2",
            "version" to WEB_VERSION,
            "isFilter" to "0",
        ) + (beforeTime?.let { mapOf("beforeTime" to it.toString()) } ?: emptyMap())
        val json = client.getJson(PLAY_HISTORY_ORIGIN, "/playHistory/v2/get", params)
        val code = json.optInt("code", -1)
        if (code != SUCCESS_CODE) throw MangoHistoryException(code)
        val data = json.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("playList") ?: JSONArray()
        val items = buildList(list.length()) {
            for (index in 0 until list.length()) {
                list.optJSONObject(index)?.let(::parseItem)?.let(::add)
            }
        }
        val nextTime = items.lastOrNull()?.updateTimeSec?.takeIf { it > 0L && items.size >= PAGE_SIZE.toInt() }
        return MangoHistoryPage(items, nextTime)
    }

    private suspend fun fetchAndSaveUid(): String {
        val json = client.getJson(
            ACCOUNT_API_ORIGIN,
            "/user/get_login_user",
            commonParams() + ("needVipIcon" to "1"),
        )
        val code = json.optInt("code", -1)
        if (code != SUCCESS_CODE) throw MangoLoginRequiredException()
        val data = json.optJSONObject("data") ?: JSONObject()
        val user = data.optJSONObject("userInfo") ?: data.optJSONObject("user") ?: data
        val uid = sequenceOf("uid", "userId", "user_id", "id")
            .map { user.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: throw IOException("芒果账号信息未返回用户编号")
        sessionStore.saveUid(uid)
        return uid
    }

    private fun commonParams(): Map<String, String> {
        val uuid = sessionStore.historyRequestId()
        val ticket = sessionStore.cookies.getCookieValue("HDCN")
            ?.split('-')
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw MangoLoginRequiredException()
        val deviceId = sessionStore.cookies.getCookieValue("__STKUUID")?.takeIf { it.isNotBlank() } ?: uuid
        return mapOf(
            "allowedRC" to "1",
            "_support" to "10000000",
            "uuid" to uuid,
            "ticket" to ticket,
            "did" to deviceId,
            "device" to "pc",
            "osType" to "android",
            "osVersion" to Build.VERSION.RELEASE,
            "appVersion" to WEB_VERSION,
            "platform" to "4",
            "seqId" to uuid,
        )
    }

    private fun parseItem(raw: JSONObject): MangoHistoryItem? {
        val type = raw.optInt("pType", -1)
        val videoId = raw.stringValue("vid") ?: return null
        if (type !in SUPPORTED_TYPES) return null
        val albumId = raw.stringValue("pid").orEmpty()
        val playId = raw.stringValue("playId") ?: "$type:$albumId:$videoId"
        val title = raw.stringValue("vName") ?: return null
        val cover = resolveCover(raw).orEmpty()
        val duration = raw.longValue("duration").coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val watched = raw.longValue("watchTime").coerceAtLeast(0L)
        return MangoHistoryItem(
            playId = playId,
            pType = type,
            pid = albumId,
            vid = videoId,
            title = title,
            coverUrl = cover,
            durationSec = duration,
            watchedSec = watched,
            updateTimeSec = raw.longValue("updateTime").coerceAtLeast(0L),
            nextVid = raw.stringValue("nextVid"),
        )
    }

    private fun resolveCover(item: JSONObject): String? {
        val raw = COVER_KEYS.firstNotNullOfOrNull { key ->
            item.optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }
        } ?: return null
        return when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> raw.replaceFirst("http://", "https://", ignoreCase = true)
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> IMAGE_ORIGIN + raw
            else -> "$IMAGE_ORIGIN/preview/sp_images/$raw"
        }
    }

    class MangoHistoryException(val apiCode: Int) : IOException("芒果历史接口异常 ($apiCode)")
    class MangoLoginRequiredException : IOException("芒果账号需要重新登录")

    private companion object {
        const val SUCCESS_CODE = 200
        const val PAGE_SIZE = "10"
        // Observed on the logged-in Mango history page; used by its web API wrapper.
        const val WEB_VERSION = "9.0.9"
        const val ACCOUNT_API_ORIGIN = "https://nuc.api.mgtv.com"
        const val PLAY_HISTORY_ORIGIN = "https://playhistory.bz.mgtv.com"
        const val IMAGE_ORIGIN = "https://2img.hitv.com"
        val SUPPORTED_TYPES = setOf(1, 2, 3)
        val COVER_KEYS = listOf("vImage", "vImg", "vPic", "image", "img", "poster", "cover", "coverUrl", "pic", "thumb")
    }
}

private fun JSONObject.stringValue(key: String): String? =
    optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.longValue(key: String): Long =
    optLong(key, optString(key, "0").toLongOrNull() ?: 0L)
