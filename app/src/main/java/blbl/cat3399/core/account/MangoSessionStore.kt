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
    )

    fun currentAccount(): Account? {
        val username = prefs.getString(KEY_USERNAME, null)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (cookies.cookieHeaderFor(SESSION_CHECK_URL).isNullOrBlank()) return null
        return Account(username, prefs.getString(KEY_UID, null)?.trim()?.takeIf { it.isNotBlank() })
    }

    fun saveAccount(username: String, uid: String? = null) {
        val safeName = username.trim()
        require(safeName.isNotBlank()) { "芒果账号未返回用户名" }
        require(!cookies.cookieHeaderFor(SESSION_CHECK_URL).isNullOrBlank()) { "芒果登录未返回会话 Cookie" }
        val editor = prefs.edit().putString(KEY_USERNAME, safeName)
        uid?.trim()?.takeIf { it.isNotBlank() && it != "0" }?.let { editor.putString(KEY_UID, it) }
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
        const val SESSION_CHECK_URL = "https://nuc.api.mgtv.com/"
    }
}
