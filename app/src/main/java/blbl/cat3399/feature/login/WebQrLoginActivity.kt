package blbl.cat3399.feature.login

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.account.WebLoginSite
import blbl.cat3399.core.account.WebSiteSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityWebQrLoginBinding

/**
 * QR login for sites that only expose a Web login page.
 *
 * The page is rendered with a desktop UA (mobile pages hide the QR entry for some sites), and the
 * session cookie is mirrored into OkHttp as soon as the page sets it.
 */
class WebQrLoginActivity : BaseActivity() {
    private lateinit var binding: ActivityWebQrLoginBinding
    private lateinit var site: WebLoginSite
    private lateinit var store: WebSiteSessionStore
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    private var pageFinished = false

    private val cookiePoll =
        object : Runnable {
            override fun run() {
                if (completed) return
                if (checkLogin()) return
                handler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
            }
        }

    private val qrTabClick =
        object : Runnable {
            override fun run() {
                if (completed || !pageFinished) return
                binding.webView.evaluateJavascript(QR_TAB_JS, null)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebQrLoginBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        site = WebLoginSite.fromKey(intent.getStringExtra(EXTRA_SITE)) ?: run {
            finish()
            return
        }
        store = WebSiteSessionStore(applicationContext, site)
        binding.tvTitle.text = "${site.title}扫码登录"
        binding.tvStatus.text = "正在加载${site.title}登录页…"
        binding.tvHint.text = "登录成功后会自动返回；若二维码过期，请按“刷新二维码”。"
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener {
            completed = false
            pageFinished = false
            binding.progress.visibility = View.VISIBLE
            binding.tvStatus.text = "正在刷新二维码…"
            binding.webView.reload()
            scheduleQrTabClicks()
            handler.removeCallbacks(cookiePoll)
            handler.postDelayed(cookiePoll, COOKIE_POLL_INTERVAL_MS)
        }
        binding.btnRefresh.requestFocus()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = if (site.desktopUserAgent) DESKTOP_UA else MOBILE_UA
        }
        binding.webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
        binding.webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    binding.progress.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.progress.visibility = View.GONE
                    pageFinished = true
                    binding.tvStatus.text = "请使用${site.title}手机 App 扫描二维码"
                    AppLog.d("WebQrLogin", "page finished host=${url?.substringBefore('?')?.take(48)}")
                    scheduleQrTabClicks()
                    handler.removeCallbacks(cookiePoll)
                    handler.postDelayed(cookiePoll, COOKIE_POLL_INTERVAL_MS)
                }
            }
        binding.webView.loadUrl(site.loginUrl)
        handler.postDelayed(cookiePoll, COOKIE_POLL_INTERVAL_MS)
    }

    private fun scheduleQrTabClicks() {
        if (site.qrTabSelector == null) return
        handler.removeCallbacks(qrTabClick)
        listOf(600L, 1_600L, 3_200L, 5_000L).forEach { delay ->
            handler.postDelayed({ if (!completed) binding.webView.evaluateJavascript(QR_TAB_JS, null) }, delay)
        }
    }

    private fun checkLogin(): Boolean {
        if (completed || !pageFinished) return false
        val manager = CookieManager.getInstance()
        val headers = site.cookieUrls.mapNotNull { url -> manager.getCookie(url)?.takeIf { it.isNotBlank() } }
        if (headers.isEmpty()) return false
        val cookieHeader = headers.joinToString("; ")
        val hasSession = site.requiredCookies.any { name -> Regex("(?:^|;\\s*)$name=([^;]+)").containsMatchIn(cookieHeader) }
        if (!hasSession) return false

        completed = true
        binding.tvStatus.text = "登录成功，正在保存会话…"
        store.saveFromWebView(cookieHeader)
        store.saveAccount(site.accountName, uidFromCookies(cookieHeader))
        AppLog.i("WebQrLogin", "login cookie captured site=${site.key}")
        binding.progress.visibility = View.GONE
        handler.removeCallbacks(cookiePoll)
        handler.postDelayed(
            {
                setResult(RESULT_OK)
                finish()
            },
            FINISH_DELAY_MS,
        )
        return true
    }

    private fun uidFromCookies(cookieHeader: String): String? =
        listOf("vqq_vuserid", "uid_tt", "__ysuid", "P_uid")
            .firstNotNullOfOrNull { name ->
                Regex("(?:^|;\\s*)$name=([^;]+)").find(cookieHeader)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE = "web_login_site"
        private const val COOKIE_POLL_INTERVAL_MS = 1_500L
        private const val FINISH_DELAY_MS = 900L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val QR_TAB_JS = """
            (function() {
              var clicked = function(el) {
                if (!el) return false;
                var target = el.closest('a,button,div,span,i') || el;
                target.click();
                return true;
              };
              if (clicked(document.querySelector('#YT-qrbtn'))) return 'clicked-id';
              var doc = document;
              try {
                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                  var inner = frames[i].contentDocument;
                  if (!inner) continue;
                  var nodes = inner.querySelectorAll('body *');
                  for (var j = 0; j < nodes.length; j++) {
                    var t = (nodes[j].textContent || '').trim();
                    if ((t === '扫码登录更安全' || t === '扫码登录') && nodes[j].children.length === 0) {
                      if (clicked(nodes[j])) return 'clicked-iframe';
                    }
                  }
                }
              } catch (e) {}
              var all = doc.querySelectorAll('body *');
              for (var k = 0; k < all.length; k++) {
                var text = (all[k].textContent || '').trim();
                if ((text === '扫码登录更安全' || text === '扫码登录') && all[k].children.length === 0) {
                  if (clicked(all[k])) return 'clicked-text';
                }
              }
              return 'none';
            })();
        """

        fun intent(context: Context, site: WebLoginSite): Intent =
            Intent(context, WebQrLoginActivity::class.java)
                .putExtra(EXTRA_SITE, site.key)
    }
}
