package blbl.cat3399.core.account

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-account VIP preference.
 *
 * The website value is only a cache used for cold starts and offline rendering; the authoritative
 * website value is refreshed by each platform session. A local override always wins while present.
 */
class PlatformVipStore(context: Context) {
    enum class Platform(val id: String) {
        BILIBILI("bili"),
        IQIYI("iqiyi"),
        MANGO("mango"),
        TENCENT("tencent"),
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun override(platform: Platform, accountKey: String): Boolean? =
        read(KEY_OVERRIDE_PREFIX + storageKey(platform, accountKey))

    fun setOverride(
        platform: Platform,
        accountKey: String,
        vip: Boolean?,
    ) {
        val key = KEY_OVERRIDE_PREFIX + storageKey(platform, accountKey)
        write(key, vip)
    }

    fun siteVip(
        platform: Platform,
        accountKey: String,
    ): Boolean? = read(KEY_SITE_PREFIX + storageKey(platform, accountKey))

    fun setSiteVip(
        platform: Platform,
        accountKey: String,
        vip: Boolean?,
    ) {
        if (vip == null) return
        write(KEY_SITE_PREFIX + storageKey(platform, accountKey), vip)
    }

    fun effectiveVip(
        platform: Platform,
        accountKey: String,
        websiteVip: Boolean?,
    ): Boolean = override(platform, accountKey) ?: websiteVip ?: false

    fun clearAccount(
        platform: Platform,
        accountKey: String,
    ) {
        val key = storageKey(platform, accountKey)
        prefs.edit()
            .remove(KEY_OVERRIDE_PREFIX + key)
            .remove(KEY_SITE_PREFIX + key)
            .apply()
    }

    private fun read(key: String): Boolean? =
        when (prefs.getString(key, null)) {
            "1" -> true
            "0" -> false
            else -> null
        }

    private fun write(
        key: String,
        vip: Boolean?,
    ) {
        val editor = prefs.edit()
        if (vip == null) editor.remove(key) else editor.putString(key, if (vip) "1" else "0")
        editor.apply()
    }

    private fun storageKey(
        platform: Platform,
        accountKey: String,
    ): String = "${platform.id}:${accountKey.trim().ifBlank { DEFAULT_ACCOUNT_KEY }}"

    companion object {
        private const val PREFS_NAME = "blbl_platform_vip"
        private const val KEY_OVERRIDE_PREFIX = "override:"
        private const val KEY_SITE_PREFIX = "site:"
        const val DEFAULT_ACCOUNT_KEY = "default"

        fun bilibiliKey(mid: Long?): String = mid?.takeIf { it > 0L }?.let { "mid:$it" } ?: DEFAULT_ACCOUNT_KEY

        fun platformKey(
            uid: String?,
            fallback: String = DEFAULT_ACCOUNT_KEY,
        ): String = uid?.trim()?.takeIf { it.isNotBlank() } ?: fallback
    }
}
