package blbl.cat3399.core.account

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException

/** Shared web-session verdict. Unknown means verification failed, not that the user logged out. */
object BiliSession {
    enum class Status { Unknown, Authenticated, SignedOut }

    data class State(
        val revision: Long = 0,
        val status: Status = Status.SignedOut,
        val mid: Long? = null,
        val name: String? = null,
        val avatarUrl: String? = null,
        /** null = the website did not return a verifiable membership state. */
        val vip: Boolean? = null,
    ) {
        val canAccessPersonal: Boolean get() = status != Status.SignedOut
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val verificationLock = Mutex()
    private var credential: String? = null
    private var credentialMid: String? = null
    @Volatile private var lastCheckedAtMs = 0L

    fun syncCredentials() {
        val token = BiliClient.cookies.getCookieValue("SESSDATA")
        val mid = BiliClient.cookies.getCookieValue("DedeUserID")
        synchronized(this) { if (token == credential && mid == credentialMid) return }
        val saved = BiliClient.accounts.accounts().firstOrNull { it.mid.toString() == mid }
        synchronized(this) {
            if (token == credential && mid == credentialMid) return
            // AccountSessionStore can call this while holding its own lock; never acquire that lock here.
            if (token != BiliClient.cookies.getCookieValue("SESSDATA")) return
            credential = token
            credentialMid = mid
            lastCheckedAtMs = 0L
            mutableState.value = State(
                revision = mutableState.value.revision + 1,
                status = if (token == null) Status.SignedOut else Status.Unknown,
                mid = mid?.toLongOrNull(),
                name = saved?.name,
                avatarUrl = saved?.avatarUrl,
                vip = null,
            )
            AppLog.i("Session", "credentials changed revision=${mutableState.value.revision} present=${token != null}")
        }
    }

    fun acceptQrLogin() {
        syncCredentials()
        check(BiliClient.cookies.hasSessData()) { "登录凭据不完整，请重新扫码" }
        synchronized(this) {
            mutableState.value = state.value.copy(
                status = Status.Authenticated,
                mid = credentialMid?.toLongOrNull() ?: BiliClient.prefs.appAuthSession?.mid,
            )
        }
    }

    suspend fun refresh(force: Boolean = false): State = verificationLock.withLock {
        syncCredentials()
        if (!BiliClient.cookies.hasSessData()) return@withLock state.value
        if (!force && System.currentTimeMillis() - lastCheckedAtMs < 60_000) return@withLock state.value
        try {
            fetchVerifiedNav()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // fetchVerifiedNav has already recorded the verdict without clearing credentials.
        }
        state.value
    }

    /** Profile and avatar share the exact same authentication verdict. */
    suspend fun profileNav(): JSONObject = verificationLock.withLock {
        syncCredentials()
        fetchVerifiedNav()
    }

    private fun parseVip(data: JSONObject): Boolean? {
        if (data.has("vipStatus")) return data.optInt("vipStatus", 0) > 0
        val vip = data.optJSONObject("vip") ?: return null
        if (vip.has("status")) return vip.optInt("status", 0) > 0
        if (vip.has("type")) return vip.optInt("type", 0) > 0
        return null
    }

    private suspend fun fetchVerifiedNav(): JSONObject {
        val before = state.value
        if (!BiliClient.cookies.hasSessData()) throw BiliApiException(-101, "请登录")
        try {
            val json = BiliApi.nav()
            if (state.value.revision != before.revision) throw CancellationException("Account changed")
            val code = json.optInt("code", 0)
            val data = json.optJSONObject("data")
            if (code == -101 || (code == 0 && data?.has("isLogin") == true && !data.optBoolean("isLogin"))) {
                markExpired(before.revision)
                throw BiliApiException(-101, "请重新登录")
            }
            if (code != 0 || data?.optBoolean("isLogin") != true || data.optLong("mid") <= 0) {
                throw IOException("Unable to verify account")
            }
            val mid = data.optLong("mid")
            val name = data.optString("uname").takeIf { it.isNotBlank() }
            val avatar = data.optString("face").takeIf { it.isNotBlank() }
            val vip = parseVip(data)
            synchronized(this) {
                if (state.value.revision != before.revision) throw CancellationException("Account changed")
                mutableState.value = before.copy(
                    status = Status.Authenticated,
                    mid = mid,
                    name = name,
                    avatarUrl = avatar,
                    vip = vip,
                )
            }
            BiliClient.accounts.saveCurrentSessionAsActive(BiliClient.prefs, BiliClient.cookies, name, avatar, mid)
            lastCheckedAtMs = System.currentTimeMillis()
            return json
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            synchronized(this) {
                if (state.value.revision == before.revision) {
                    mutableState.value = before.copy(status = Status.Unknown)
                    AppLog.w("Session", "verification unavailable; keeping account revision=${before.revision}")
                    lastCheckedAtMs = System.currentTimeMillis()
                }
            }
            throw t
        }
    }

    @Synchronized
    fun markExpired(expectedRevision: Long = state.value.revision) {
        val current = state.value
        if (current.revision != expectedRevision || current.status == Status.SignedOut) return
        mutableState.value = current.copy(revision = current.revision + 1, status = Status.SignedOut)
        lastCheckedAtMs = System.currentTimeMillis()
        AppLog.i("Session", "server requires login revision=${state.value.revision}")
    }

    suspend fun requireMid(): Long {
        val current = refresh()
        if (!current.canAccessPersonal) throw BiliApiException(-101, "请重新登录")
        return current.mid?.takeIf { it > 0 } ?: throw IOException("暂时无法验证账号，请重试")
    }
}
