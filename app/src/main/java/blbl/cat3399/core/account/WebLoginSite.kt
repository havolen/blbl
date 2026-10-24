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
    val desktopUserAgent: Boolean,
    /** Element that switches the page to its QR view (dispatches mouse events, not just click). */
    val qrTriggerSelector: String? = null,
    /** Text fallback for the QR view switch. */
    val qrTriggerText: String? = null,
    /** Selector that only exists once the QR view is actually visible. */
    val qrReadySelector: String? = null,
    /** Optional second page to load after the first cookie appears (QQ OAuth authorization). */
    val secondaryUrl: String? = null,
    val secondaryCookie: String? = null,
    /** Text of the authorization button on the secondary page. */
    val secondaryConfirmText: String? = null,
) {
    YOUKU(
        key = "youku",
        title = "优酷",
        // Load the passport iframe document directly; the outer page embeds it cross-origin,
        // so injected scripts cannot reach its QR toggle.
        loginUrl =
            "https://cnpassport.youku.com/mini_login.htm?lang=zh_cn&appName=youku&appEntrance=youku" +
                "&styleType=vertical&bizParams=&notLoadSsoView=true&notKeepLogin=false&isMobile=false" +
                "&pid=20160317PLF000211&returnUrl=http%3A%2F%2Fyouku.com",
        cookieUrls = listOf("https://account.youku.com/", "https://www.youku.com/"),
        requiredCookies = listOf("P_pck_rm", "P_sck"),
        cookieDomain = ".youku.com",
        desktopUserAgent = true,
        qrTriggerSelector = ".view-type-qrcode",
        qrTriggerText = "扫码登录更安全",
        qrReadySelector = ".qrcode-login",
    ),
    TENCENT(
        key = "tencent",
        title = "腾讯视频",
        // QQ's ptlogin iframe shows the QR view by default.
        loginUrl =
            "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609&daid=383&style=33" +
                "&login_text=%E7%99%BB%E5%BD%95&hide_title_bar=1&hide_border=1&target=self" +
                "&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&pt_3rd_aid=101483052" +
                "&pt_feedback_link=https%3A%2F%2Fsupport.qq.com%2Fproducts%2F77942" +
                "%3FcustomInfo%3D.appid101483052&theme=2&verify_theme=",
        cookieUrls = listOf("https://v.qq.com/", "https://graph.qq.com/", "https://ptlogin2.qq.com/"),
        requiredCookies = listOf("vqq_vusession"),
        cookieDomain = ".qq.com",
        desktopUserAgent = true,
        secondaryUrl =
            "https://graph.qq.com/oauth2.0/show?which=Login&display=pc&response_type=code" +
                "&client_id=101483052" +
                "&redirect_uri=https%3A%2F%2Fvideo.qq.com%2Ftransfer_login_page%2Findex.html" +
                "%3Fvplatform%3D1%26type%3Dqq%26clear%3D1%26appid%3D101483052",
        secondaryCookie = "p_skey",
        secondaryConfirmText = "授权",
    ),
    DOUYIN(
        key = "douyin",
        title = "抖音",
        // The desktop SPA needs a newer WebView than Android 9 TVs ship with; the passport
        // page is the lightest official page and shows the QR block directly on mobile UAs.
        // The creator center ships polyfills and a login modal, so it has a better
        // chance on Android 9 TV WebViews than the main sso SPA.
        loginUrl = "https://creator.douyin.com/",
        cookieUrls = listOf("https://www.douyin.com/", "https://creator.douyin.com/"),
        requiredCookies = listOf("sessionid", "sessionid_ss"),
        cookieDomain = ".douyin.com",
        desktopUserAgent = true,
        qrTriggerText = "登录",
        qrReadySelector = null,
    ),
    ;

    val accountName: String
        get() = "$title 账号"

    companion object {
        fun fromKey(key: String?): WebLoginSite? = entries.firstOrNull { it.key == key }
    }
}
