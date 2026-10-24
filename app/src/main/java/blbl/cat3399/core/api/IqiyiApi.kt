package blbl.cat3399.core.api

import android.os.Build
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.CookieStore
import blbl.cat3399.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * iQiyi TV QR login.
 *
 * Flow verified against the iQiyi web login implementation:
 * gen_login_token.action -> QR contains token_login.action URL -> phone confirms ->
 * is_token_login.action returns A00000 with the user profile.
 */
class IqiyiApi(
    private val cookies: CookieStore,
) {
    data class QrCode(
        val token: String,
        val contentUrl: String,
        val expireSeconds: Int,
    )

    data class Profile(
        val username: String,
        val uid: String?,
        val vip: Boolean?,
    )

    data class Stream(
        val url: String,
        val qualityLabel: String,
        val width: Int,
        val durationSec: Int,
        val previewSeconds: Int = 0,
    ) {
        val isPreview: Boolean get() = previewSeconds > 0
    }

    sealed interface PollResult {
        data object Waiting : PollResult
        data object Expired : PollResult
        data class Success(val profile: Profile?, val data: JSONObject?) : PollResult
    }

    private val client =
        OkHttpClient.Builder()
            .cookieJar(cookies)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    suspend fun requestQrCode(): QrCode {
        val body =
            FormBody.Builder()
                .add("agenttype", AGENT_TYPE)
                .add("ptid", PTID)
                .add("device_name", Build.MODEL?.takeIf { it.isNotBlank() } ?: "AndroidTV")
                .build()
        val json = postForm(GEN_TOKEN_URL, body)
        if (json.optString("code") != API_SUCCESS) throw IOException(json.optString("msg", "爱奇艺二维码申请失败"))
        val data = json.optJSONObject("data") ?: throw IOException("爱奇艺未返回二维码令牌")
        val token = data.optString("token", "").trim()
        if (token.isBlank()) throw IOException("爱奇艺未返回二维码令牌")
        val content =
            TOKEN_LOGIN_URL.toHttpUrl().newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("agenttype", AGENT_TYPE)
                .build()
                .toString()
        return QrCode(
            token = token,
            contentUrl = content,
            expireSeconds = data.optInt("expire", DEFAULT_EXPIRE_SECONDS).coerceIn(30, 600),
        )
    }

    suspend fun poll(token: String): PollResult {
        val body =
            FormBody.Builder()
                .add("token", token)
                .add("cookieVer", "1")
                .add("agenttype", AGENT_TYPE)
                .add("ptid", PTID)
                .build()
        val json = postForm(IS_TOKEN_LOGIN_URL, body)
        return when (json.optString("code")) {
            API_SUCCESS -> {
                val data = json.optJSONObject("data")
                PollResult.Success(parseProfile(data), data)
            }
            "A00001" -> PollResult.Waiting
            "P00501" -> PollResult.Expired
            else -> PollResult.Waiting
        }
    }

    /** Returns the freshest nickname/VIP state; callers keep cached values if this fails. */
    suspend fun fetchProfile(): Profile? {
        val auth = cookies.getCookieValue("P00001")?.trim().orEmpty()
        if (auth.isBlank() || auth.equals("deleted", ignoreCase = true)) return null
        val url =
            USER_INFO_URL.toHttpUrl().newBuilder()
                .addQueryParameter("authcookie", auth)
                .addQueryParameter("agenttype", AGENT_TYPE)
                .addQueryParameter("fields", USER_INFO_FIELDS)
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", SITE_ORIGIN + "/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        val resolveStartMs = android.os.SystemClock.elapsedRealtime()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val body = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
                val json = withContext(Dispatchers.Default) { JSONObject(body) }
                if (json.optString("code") != API_SUCCESS) return null
                parseProfile(json.optJSONObject("data"))
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Resolves an iQiyi HLS stream for a search/history item.
     *
     * The public web player uses the same time+vid signature; the returned playlist is a
     * short-lived but unencrypted HLS manifest (segment URLs are signed by iQiyi).
     */
    suspend fun resolveStream(
        tvId: Long,
        videoId: String,
    ): Stream? {
        if (tvId <= 0L || videoId.isBlank()) return null
        val timestamp = System.currentTimeMillis()
        val signature = md5("$timestamp$VMS_KEY$videoId")
        val url =
            VMS_URL.toHttpUrl().newBuilder()
                .addPathSegment(tvId.toString())
                .addPathSegment(videoId)
                .addPathSegment("")
                .addQueryParameter("t", timestamp.toString())
                .addQueryParameter("sc", signature)
                .addQueryParameter("src", VMS_SRC)
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", SITE_ORIGIN + "/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        val resolveStartMs = android.os.SystemClock.elapsedRealtime()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val body = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
                val json = withContext(Dispatchers.Default) { JSONObject(body) }
                if (json.optString("code") != API_SUCCESS) return null
                val data = json.optJSONObject("data") ?: return null
                val duration = data.optInt("duration", 0)
                val previewMinutes = data.optString("previewTime", "0").toIntOrNull() ?: 0
                val previewSeconds =
                    when {
                        previewMinutes > 0 -> previewMinutes * 60
                        data.optInt("prv", 0) > 0 -> 60
                        else -> 0
                    }
                val candidates = ArrayList<StreamCandidate>()
                val streams = data.optJSONArray("vidl")
                if (streams != null) {
                    for (i in 0 until streams.length()) {
                        val item = streams.optJSONObject(i) ?: continue
                        val m3u = item.optString("m3u", "").trim()
                        if (m3u.isBlank()) continue
                        val screen = item.optString("screenSize", "")
                        val (width, height) = parseScreenSize(screen)
                        val vd = item.optInt("vd", 0)
                        candidates += StreamCandidate(
                            url = preferHttps(m3u),
                            width = width,
                            height = height,
                            vd = vd,
                            label = screen.ifBlank { if (width > 0) "${width}x$height" else "iQiyi" },
                        )
                    }
                }
                val fallback = data.optString("m3u", "").trim()
                if (candidates.isEmpty() && fallback.isNotBlank()) {
                    val (width, height) = parseScreenSize(data.optString("screenSize", ""))
                    candidates += StreamCandidate(
                        url = preferHttps(fallback),
                        width = width,
                        height = height,
                        vd = 0,
                        label = data.optString("screenSize", "iQiyi"),
                    )
                }
                val best = candidates.maxWithOrNull(
                    compareBy<StreamCandidate> { it.width * 1000 + it.height }
                        .thenBy { if (it.vd in H265_VD) 0 else 1 },
                ) ?: return null
                AppLog.i(
                    "IqiyiPerf",
                    "resolve best vd=${best.vd} label=${best.label} ${best.width}x${best.height} " +
                        "hevc=${best.vd in H265_VD} elapsed=${android.os.SystemClock.elapsedRealtime() - resolveStartMs}ms",
                )
                Stream(
                    url = best.url,
                    qualityLabel = best.label,
                    width = best.width,
                    durationSec = duration,
                    previewSeconds = previewSeconds,
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        }
    }

    suspend fun logout() {
        runCatching {
            val body = FormBody.Builder().build()
            val request =
                Request.Builder()
                    .url(LOGOUT_URL)
                    .header("Referer", SITE_ORIGIN + "/")
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()
            client.newCall(request).await().close()
        }
    }

    private suspend fun postForm(
        url: String,
        body: FormBody,
    ): JSONObject {
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", SITE_ORIGIN)
                .header("Referer", SITE_ORIGIN + "/")
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()
        val raw =
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("爱奇艺请求 HTTP ${response.code}")
                withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
            }
        return withContext(Dispatchers.Default) { JSONObject(raw) }
    }

    private fun parseProfile(data: JSONObject?): Profile? {
        if (data == null) return null
        val user =
            data.optJSONObject("userinfo")
                ?: data.optJSONObject("userInfo")
                ?: data.optJSONObject("user")
                ?: data
        val username =
            sequenceOf("nickname", "nickName", "uname", "userName", "username", "name")
                .map { user.optString(it, "").trim() }
                .firstOrNull { it.isNotBlank() && it != "null" }
        val uid =
            sequenceOf("uid", "userId", "user_id", "userIdStr", "id")
                .map { user.optString(it, "").trim() }
                .firstOrNull { it.isNotBlank() && it != "0" && it != "null" }
        val vip = parseVip(data) ?: parseVip(user)
        if (username == null && uid == null && vip == null) return null
        return Profile(username ?: "爱奇艺用户", uid, vip)
    }

    private fun parseVip(source: JSONObject): Boolean? {
        val info =
            source.optJSONObject("qiyi_vip_info")
                ?: source.optJSONObject("vipinfo")
                ?: source.optJSONObject("vipInfo")
                ?: source.optJSONObject("vip")
                ?: source.optJSONObject("vip_info")
                ?: source.optJSONObject("tv_vip_info")
                ?: source.optJSONObject("pps_vip_info")
        if (info != null) {
            sequenceOf("status", "vipStatus", "vip_status").forEach { key ->
                if (info.has(key)) {
                    val value = info.opt(key)
                    val number = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                    if (number != null) return number == 1
                }
            }
            sequenceOf("isValidVip", "isVip", "is_vip").forEach { key ->
                if (info.has(key)) return info.optBoolean(key, false)
            }
            sequenceOf("type", "vipType", "vip_type", "level", "vip_level").forEach { key ->
                if (info.has(key)) {
                    val value = info.opt(key)
                    val number = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                    if (number != null) return number > 0
                }
            }
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

    /** Some responses set cookies through Set-Cookie; older app flows return the auth cookie inline. */
    fun persistAuthCookie(data: JSONObject?) {
        if (data == null) return
        if (cookies.getCookieValue("P00001")?.isNotBlank() == true) return
        val auth =
            sequenceOf("authcookie", "P00001", "p00001")
                .map { data.optString(it, "").trim() }
                .firstOrNull { it.isNotBlank() && it != "deleted" }
                ?: return
        val cookie =
            Cookie.Builder()
                .name("P00001")
                .value(auth)
                .domain("iqiyi.com")
                .path("/")
                .expiresAt(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
                .build()
        cookies.upsert(cookie)
    }

    private data class StreamCandidate(
        val url: String,
        val width: Int,
        val height: Int,
        val vd: Int,
        val label: String,
    )

    private fun parseScreenSize(value: String): Pair<Int, Int> {
        val parts = value.lowercase().split('x')
        if (parts.size != 2) return 0 to 0
        return (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0)
    }

    private fun preferHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    private fun md5(value: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        const val API_SUCCESS = "A00000"
        const val AGENT_TYPE = "13"
        const val PTID = "02032001010000000000"
        const val DEFAULT_EXPIRE_SECONDS = 300
        const val GEN_TOKEN_URL = "https://passport.iqiyi.com/apis/qrcode/gen_login_token.action"
        const val TOKEN_LOGIN_URL = "https://passport.iqiyi.com/apis/qrcode/token_login.action"
        const val IS_TOKEN_LOGIN_URL = "https://passport.iqiyi.com/apis/qrcode/is_token_login.action"
        const val USER_INFO_URL = "https://passport.iqiyi.com/apis/user/info.action"
        const val LOGOUT_URL = "https://passport.iqiyi.com/user/logout.php"
        const val USER_INFO_FIELDS =
            "userinfo,qiyi_vip_info,pps_vip_info,tv_vip_info,tv_tennis_vip,sport_vip,qiyi_tennis_vip"
        const val SITE_ORIGIN = "https://www.iqiyi.com"
        const val VMS_URL = "https://cache.m.iqiyi.com/tmts"
        const val VMS_KEY = "d5fb4bd9d50c4be6948c97edd7254b0e"
        const val VMS_SRC = "76f90cbd92f94a2e925d83e8ccd22cb7"
        val H265_VD = setOf(17, 18, 21)
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
