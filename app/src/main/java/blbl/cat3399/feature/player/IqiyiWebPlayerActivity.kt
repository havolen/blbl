package blbl.cat3399.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityIqiyiWebPlayerBinding
import okhttp3.HttpUrl.Companion.toHttpUrl

/** H5 fallback for iQiyi short/UGC videos that are not exposed through the native HLS API. */
class IqiyiWebPlayerActivity : BaseActivity() {
    private lateinit var binding: ActivityIqiyiWebPlayerBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIqiyiWebPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        window.decorView.keepScreenOn = true

        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        binding.tvTitle.text = title.ifBlank { getString(blbl.cat3399.R.string.iqiyi_player_title) }
        binding.btnExit.setOnClickListener { finish() }
        binding.btnExit.requestFocus()

        installIqiyiCookies()
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
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
                    AppLog.d("IqiyiWebPlayer", "page finished host=${url?.toHttpUrlOrNullHost() ?: "-"}")
                    view?.evaluateJavascript(AUTOPLAY_JS, null)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    val scheme = target.scheme.orEmpty()
                    if (scheme != "http" && scheme != "https") return true
                    val host = target.host.orEmpty()
                    return !(host == "iqiyi.com" || host.endsWith(".iqiyi.com") || host.endsWith(".iqiyipic.com"))
                }
            }
        binding.webView.loadUrl(url)
    }

    private fun installIqiyiCookies() {
        val store = IqiyiSessionStore(applicationContext)
        val cookies = store.cookies.loadForRequest("https://m.iqiyi.com/".toHttpUrl())
        if (cookies.isEmpty()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        for (cookie in cookies) {
            manager.setCookie(
                "https://m.iqiyi.com/",
                "${cookie.name}=${cookie.value}; Domain=.iqiyi.com; Path=/",
            )
        }
        manager.flush()
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun String.toHttpUrlOrNullHost(): String? =
        runCatching { this.toHttpUrl().host }.getOrNull()

    companion object {
        const val EXTRA_URL = "iqiyi_web_url"
        const val EXTRA_TITLE = "iqiyi_web_title"

        private const val AUTOPLAY_JS = """
            (function() {
              var videos = document.querySelectorAll('video');
              for (var i = 0; i < videos.length; i++) {
                try {
                  videos[i].muted = false;
                  var p = videos[i].play();
                  if (p && p.catch) p.catch(function(){});
                } catch (e) {}
              }
            })();
        """

        fun intent(
            context: Context,
            url: String,
            title: String,
        ): Intent = Intent(context, IqiyiWebPlayerActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
    }
}
