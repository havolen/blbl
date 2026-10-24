package blbl.cat3399.core.account

/**
 * Web-based QR login for sites without a stable public QR API.
 *
 * The official login page is loaded in a WebView with a desktop UA; once the page sets the
 * site's session cookie, the cookie is mirrored into this app's OkHttp [CookieStore].
 */
enum class WebLoginSite(
    val key: String,
    val title: String,
    val loginUrl: String,
    val cookieUrls: List<String>,
    val requiredCookies: List<String>,
    val cookieDomain: String,
    val qrTabSelector: String?,
    val desktopUserAgent: Boolean,
) {
    YOUKU(
        key = "youku",
        title = "优酷",
        loginUrl = "https://account.youku.com/",
        cookieUrls = listOf("https://account.youku.com/", "https://www.youku.com/"),
        requiredCookies = listOf("P_pck_rm", "P_sck"),
        cookieDomain = ".youku.com",
        qrTabSelector = "#YT-qrbtn",
        desktopUserAgent = true,
    ),
    TENCENT(
        key = "tencent",
        title = "腾讯视频",
        loginUrl =
            "https://graph.qq.com/oauth2.0/show?which=Login&display=pc&response_type=code" +
                "&client_id=101483052" +
                "&redirect_uri=https%3A%2F%2Fvideo.qq.com%2Ftransfer_login_page%2Findex.html" +
                "%3Fvplatform%3D1%26type%3Dqq%26clear%3D1%26appid%3D101483052",
        cookieUrls = listOf("https://v.qq.com/", "https://graph.qq.com/"),
        requiredCookies = listOf("vqq_vusession"),
        cookieDomain = ".qq.com",
        qrTabSelector = null,
        desktopUserAgent = true,
    ),
    DOUYIN(
        key = "douyin",
        title = "抖音",
        loginUrl = "https://sso.douyin.com/login/",
        cookieUrls = listOf("https://www.douyin.com/", "https://sso.douyin.com/"),
        requiredCookies = listOf("sessionid", "sessionid_ss"),
        cookieDomain = ".douyin.com",
        qrTabSelector = null,
        desktopUserAgent = true,
    ),
    ;

    val accountName: String
        get() = "$title 账号"

    companion object {
        fun fromKey(key: String?): WebLoginSite? = entries.firstOrNull { it.key == key }
    }
}
