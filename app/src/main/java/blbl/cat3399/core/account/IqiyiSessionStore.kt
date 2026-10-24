package blbl.cat3399.core.account

import android.content.Context
import android.content.SharedPreferences
import blbl.cat3399.core.net.CookieStore

/**
 * Single current iQiyi account. The login cookie (P00001) is the source of truth; the cached
 * nickname/uid/VIP only feed the account table before the profile request finishes.
 */
class IqiyiSessionStore(context: Context) {
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
        if (!hasLoginCookie()) return null
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

    fun hasLoginCookie(): Boolean {
        val value = cookies.getCookieValue(COOKIE_AUTH)?.trim().orEmpty()
        return value.isNotBlank() && !value.equals("deleted", ignoreCase = true)
    }

    fun saveAccount(
        username: String,
        uid: String?,
        vip: Boolean?,
    ) {
        val safeName = username.trim()
        require(safeName.isNotBlank()) { "爱奇艺账号未返回昵称" }
        require(hasLoginCookie()) { "爱奇艺登录未返回会话 Cookie" }
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

    fun clear() {
        cookies.clearAll()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "blbl_iqiyi_account"
        const val COOKIE_PREFS_NAME = "blbl_iqiyi_cookies"
        const val KEY_USERNAME = "username"
        const val KEY_UID = "uid"
        const val KEY_VIP = "vip"
        const val COOKIE_AUTH = "P00001"
    }
}
