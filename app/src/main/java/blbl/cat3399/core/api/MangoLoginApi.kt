package blbl.cat3399.core.api

import blbl.cat3399.core.net.CookieStore
import org.json.JSONObject
import java.io.IOException

class MangoLoginApi(cookies: CookieStore) {
    private val client = MangoApiClient(cookies)

    data class QrCode(
        val value: String,
        internal val pollCode: String,
    )

    data class UserProfile(
        val username: String,
        val uid: String?,
        val vip: Boolean? = null,
    )

    sealed interface PollResult {
        data object Waiting : PollResult
        data object NeedsPhoneBinding : PollResult
        data class LoggedIn(val profile: UserProfile) : PollResult
    }

    suspend fun requestQrCode(): QrCode {
        val json = client.getJson(API_ORIGIN, "/GetMultiPic", commonParams())
        ensureSuccess(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        val value = data.optString("url", "").trim()
        val pollCode = data.optString("rcode", "").trim()
        if (value.isBlank() || pollCode.isBlank()) throw IOException("芒果暂未返回有效二维码")
        return QrCode(value, pollCode)
    }

    suspend fun poll(qrCode: QrCode): PollResult {
        val params = commonParams() + ("rcode" to qrCode.pollCode) + ("t" to System.currentTimeMillis().toString())
        val json = client.getJson(API_ORIGIN, "/GetQrcodeResult", params)
        if (json.optInt("code", -1) != CODE_SUCCESS) return PollResult.Waiting

        val data = json.optJSONObject("data") ?: JSONObject()
        return if (data.optBoolean("needRelate", false)) {
            PollResult.NeedsPhoneBinding
        } else {
            PollResult.LoggedIn(parseProfile(data))
        }
    }

    private fun commonParams(): Map<String, String> = mapOf(
        "_support" to "10000000",
        "deviceid" to "",
        "appVersion" to "",
        "dname" to "",
        "src" to "mgtv",
        "invoker" to "pcweb",
    )

    private fun parseVip(source: JSONObject): Boolean? {
        val info = source.optJSONObject("vipinfo") ?: source.optJSONObject("vipInfo") ?: source.optJSONObject("vip")
        if (info != null) {
            sequenceOf("type", "vipType", "vip_type", "level").forEach { key ->
                val value = info.opt(key)
                if (value != null) {
                    val number = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                    if (number != null) return number > 0
                }
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

    private fun ensureSuccess(json: JSONObject) {
        val code = json.optInt("code", -1)
        if (code != CODE_SUCCESS) throw IOException("芒果二维码申请失败 ($code)")
    }

    private fun parseProfile(data: JSONObject): UserProfile {
        val user = data.optJSONObject("user")
            ?: data.optJSONObject("userInfo")
            ?: data.optJSONObject("user_info")
            ?: data
        val username = sequenceOf("nickname", "nickName", "username", "name")
            .map { user.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: throw IOException("芒果登录成功，但未返回用户名")
        val uid = sequenceOf("uid", "userId", "user_id", "id")
            .map { user.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && it != "0" }
        return UserProfile(username, uid, parseVip(user) ?: parseVip(data))
    }

    private companion object {
        const val CODE_SUCCESS = 200
        const val API_ORIGIN = "https://nuc.api.mgtv.com"
    }
}
