package blbl.cat3399.core.account

import android.content.Context
import android.content.SharedPreferences
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.CookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Cookie + display-name storage for the WebView QR login sites. */
class WebSiteSessionStore(
    context: Context,
    val site: WebLoginSite,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("blbl_${site.key}_account", Context.MODE_PRIVATE)

    val cookies = CookieStore(context.applicationContext, "blbl_${site.key}_cookies")

    data class Account(
        val username: String,
        val uid: String?,
    )

    fun currentAccount(): Account? {
        if (!hasLoginCookie()) return null
        val name = prefs.getString(KEY_USERNAME, null)?.trim()?.takeIf { it.isNotBlank() } ?: site.accountName
        return Account(
            username = name,
            uid = prefs.getString(KEY_UID, null)?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    fun hasLoginCookie(): Boolean =
        site.requiredCookies.any { name ->
            cookies.getCookieValue(name)?.trim()?.takeIf { it.isNotBlank() && !it.equals("deleted", true) } != null
        }

    /** Copy `CookieManager.getCookie()` output into the OkHttp cookie jar. */
    fun saveFromWebView(cookieHeader: String?) {
        val header = cookieHeader?.trim().orEmpty()
        if (header.isEmpty()) return
        val url = site.cookieUrls.firstOrNull()?.toHttpUrlOrNull() ?: return
        val parsed = ArrayList<Cookie>()
        header.split(';').forEach { pair ->
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) return@forEach
            val cookie =
                Cookie.parse(
                    url = url,
                    setCookie = "$name=$value; domain=${site.cookieDomain}; path=/",
                ) ?: return@forEach
            parsed.add(cookie)
        }
        if (parsed.isEmpty()) return
        cookies.upsertAll(parsed)
        AppLog.i("WebLogin", "saved ${parsed.size} cookies for ${site.key}")
    }

    fun saveAccount(
        username: String,
        uid: String?,
    ) {
        val name = username.trim().ifBlank { site.accountName }
        val editor = prefs.edit().putString(KEY_USERNAME, name)
        val safeUid = uid?.trim()?.takeIf { it.isNotBlank() && it != "0" }
        if (safeUid == null) editor.remove(KEY_UID) else editor.putString(KEY_UID, safeUid)
        editor.apply()
    }

    fun clear() {
        cookies.clearAll()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_UID = "uid"
    }
}
