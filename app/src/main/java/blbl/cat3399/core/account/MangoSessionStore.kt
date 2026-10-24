package blbl.cat3399.core.account

import android.content.Context
import android.content.SharedPreferences
import blbl.cat3399.core.net.CookieStore
import java.util.UUID

class MangoSessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val cookies = CookieStore(context.applicationContext, COOKIE_PREFS_NAME)

    data class Account(
        val username: String,
        val uid: String?,
        val vip: Boolean?,
    )

    fun currentAccount(): Account? {
        val username = prefs.getString(KEY_USERNAME, null)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (cookies.cookieHeaderFor(SESSION_CHECK_URL).isNullOrBlank()) return null
        return Account(
            username = username,
            uid = prefs.getString(KEY_UID, null)?.trim()?.takeIf { it.isNotBlank() },
            vip = when (prefs.getString(KEY_VIP, null)) {
                "1" -> true
                "0" -> false
                else -> null
            },
        )
    }

    fun saveAccount(
        username: String,
        uid: String? = null,
        vip: Boolean? = null,
    ) {
        val safeName = username.trim()
        require(safeName.isNotBlank()) { "芒果账号未返回用户名" }
        require(!cookies.cookieHeaderFor(SESSION_CHECK_URL).isNullOrBlank()) { "芒果登录未返回会话 Cookie" }
        val editor = prefs.edit().putString(KEY_USERNAME, safeName)
        val safeUid = uid?.trim()?.takeIf { it.isNotBlank() && it != "0" }
        if (safeUid == null) editor.remove(KEY_UID) else editor.putString(KEY_UID, safeUid)
        if (vip == null) editor.remove(KEY_VIP) else editor.putString(KEY_VIP, if (vip) "1" else "0")
        editor.apply()
    }

    fun saveVip(vip: Boolean?) {
        val editor = prefs.edit()
        if (vip == null) editor.remove(KEY_VIP) else editor.putString(KEY_VIP, if (vip) "1" else "0")
        editor.apply()
    }

    fun saveUid(uid: String) {
        uid.trim().takeIf { it.isNotBlank() }?.let { prefs.edit().putString(KEY_UID, it).apply() }
    }

    fun historyRequestId(): String {
        val saved = prefs.getString(KEY_HISTORY_REQUEST_ID, null)?.takeIf { it.isNotBlank() }
        if (saved != null) return saved
        val created = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_HISTORY_REQUEST_ID, created).apply()
        return created
    }

    fun clear() {
        cookies.clearAll()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "blbl_mango_account"
        const val COOKIE_PREFS_NAME = "blbl_mango_cookies"
        const val KEY_USERNAME = "username"
        const val KEY_UID = "uid"
        const val KEY_HISTORY_REQUEST_ID = "history_request_id"
        const val KEY_VIP = "vip"
        const val SESSION_CHECK_URL = "https://nuc.api.mgtv.com/"
    }
}
