package blbl.cat3399.core.api

import android.os.Build
import blbl.cat3399.core.account.MangoSessionStore
import org.json.JSONObject
import java.io.IOException

/** Reads the Mango account profile, including the website membership state when available. */
class MangoAccountApi(
    private val sessionStore: MangoSessionStore,
) {
    data class Profile(
        val username: String,
        val uid: String?,
        val vip: Boolean?,
    )

    private val client = MangoApiClient(sessionStore.cookies)

    suspend fun fetchProfile(): Profile {
        val ticket = sessionStore.cookies.getCookieValue("HDCN")
            ?.split('-')
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw MangoHistoryApi.MangoLoginRequiredException()
        val json = client.getJson(
            ACCOUNT_API_ORIGIN,
            "/user/get_login_user",
            mapOf(
                "allowedRC" to "1",
                "_support" to "10000000",
                "uuid" to sessionStore.historyRequestId(),
                "ticket" to ticket,
                "did" to (sessionStore.cookies.getCookieValue("__STKUUID")?.takeIf { it.isNotBlank() } ?: ticket),
                "device" to "pc",
                "osType" to "android",
                "osVersion" to Build.VERSION.RELEASE,
                "appVersion" to "9.0.9",
                "platform" to "4",
                "seqId" to sessionStore.historyRequestId(),
                "needVipIcon" to "1",
            ),
        )
        if (json.optInt("code", -1) != SUCCESS_CODE) throw IOException("芒果账号信息读取失败")
        val data = json.optJSONObject("data") ?: JSONObject()
        val user = data.optJSONObject("userInfo") ?: data.optJSONObject("user") ?: data
        val username = sequenceOf("nickname", "nickName", "userName", "username", "name")
            .map { user.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: throw IOException("芒果账号信息未返回昵称")
        val uid = sequenceOf("uid", "userId", "user_id", "id")
            .map { user.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && it != "0" }
        return Profile(username, uid, parseVip(user) ?: parseVip(data))
    }

    private fun parseVip(source: JSONObject): Boolean? {
        val info = source.optJSONObject("vipinfo") ?: source.optJSONObject("vipInfo") ?: source.optJSONObject("vip")
        if (info != null) {
            sequenceOf("type", "vipType", "vip_type", "level").forEach { key ->
                val value = info.opt(key) ?: return@forEach
                val number = when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                } ?: return@forEach
                return number > 0
            }
            if (info.has("status")) return info.optInt("status", 0) == 1
            if (info.has("isValidVip")) return info.optBoolean("isValidVip", false)
        }
        sequenceOf("isVip", "is_vip", "vipStatus", "vip_status").forEach { key ->
            if (source.has(key)) {
                return when (val value = source.opt(key)) {
                    is Boolean -> value
                    is Number -> value.toInt() > 0
                    is String -> value == "1" || value.equals("true", ignoreCase = true)
                    else -> null
                }
            }
        }
        return null
    }

    private companion object {
        const val SUCCESS_CODE = 200
        const val ACCOUNT_API_ORIGIN = "https://nuc.api.mgtv.com"
    }
}
