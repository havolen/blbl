package blbl.cat3399.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.KeyEvent
import android.widget.LinearLayout
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.R
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityIqiyiWebPlayerBinding
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/** H5 fallback for iQiyi short/UGC videos that are not exposed through the native HLS API. */
class IqiyiWebPlayerActivity : BaseActivity() {
    private lateinit var binding: ActivityIqiyiWebPlayerBinding
    private lateinit var sessionStore: IqiyiSessionStore
    private lateinit var playPause: PlayerIconButton
    private val handler = Handler(Looper.getMainLooper())
    private var desktopMode = false
    private var controlsVisible = true
    private var nativeHandoffStarted = false
    private var pageTitle = ""
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val playbackPoll =
        object : Runnable {
            override fun run() {
                updatePlaybackState()
                handler.postDelayed(this, 1000L)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIqiyiWebPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        window.decorView.keepScreenOn = true

        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        pageTitle = title
        desktopMode = intent.getBooleanExtra(EXTRA_DESKTOP, false)
        if (url.isBlank()) {
            finish()
            return
        }
        binding.tvTitle.text = title.ifBlank { getString(R.string.iqiyi_player_title) }
        sessionStore = IqiyiSessionStore(applicationContext)
        setupControls()

        installIqiyiCookies()
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = if (desktopMode) DESKTOP_UA else MOBILE_UA
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
                    AppLog.d("IqiyiWebPlayer", "page finished host=${url?.toHttpUrlOrNullHost() ?: "-"} desktop=$desktopMode")
                    if (desktopMode) {
                        applyDesktopLayout()
                        handler.postDelayed({ applyDesktopLayout() }, 1200L)
                        handler.postDelayed({ applyDesktopLayout() }, 3000L)
                    } else {
                        view?.evaluateJavascript(AUTOPLAY_JS, null)
                        view?.evaluateJavascript(DANMAKU_SIZE_JS, null)
                    }
                    handler.postDelayed({ binding.progress.visibility = View.GONE }, 800L)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val req = request ?: return null
                    // The web player already holds the signed VIP stream URL. Hand that URL to
                    // ExoPlayer instead of decoding 1080P with the old WebView's MSE player.
                    if (!nativeHandoffStarted &&
                        req.url.path.orEmpty().endsWith(".m3u8") &&
                        (req.url.host == "meta-cdn.video.iqiyi.com" || req.url.getQueryParameter("qd_tvid") != null)
                    ) {
                        nativeHandoffStarted = true
                        val nativeUrl = req.url.toString()
                        AppLog.i("IqiyiWebPlayer", "handoff native m3u8 host=${req.url.host}")
                        runOnUiThread {
                            startActivity(
                                IqiyiPlayerActivity.intent(
                                    this@IqiyiWebPlayerActivity,
                                    nativeUrl,
                                    pageTitle,
                                ),
                            )
                            finish()
                        }
                    }
                    if (!desktopMode) return null
                    if (!req.isForMainFrame) return null
                    val target = req.url
                    if (target.host != "www.iqiyi.com" || !target.path.orEmpty().startsWith("/v_")) return null
                    return runCatching { loadDesktopDocument(target.toString()) }.getOrNull()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    val scheme = target.scheme.orEmpty()
                    if (scheme != "http" && scheme != "https") return true
                    val host = target.host.orEmpty()
                    return !(host == "iqiyi.com" || host.endsWith(".iqiyi.com") || host.endsWith(".iqiyipic.com"))
                }
            }
        binding.webView.loadUrl(if (desktopMode) desktopUrl(url) else url)
        handler.post(playbackPoll)
    }

    private fun setupControls() {
        playPause =
            PlayerIconButton(this, R.drawable.ic_player_play, getString(R.string.player_btn_play_pause)) {
                togglePlayback()
                scheduleControlsHide()
            }
        val exit =
            PlayerIconButton(this, R.drawable.ic_player_close, getString(R.string.iqiyi_player_exit)) {
                finish()
            }
        binding.controlsRow.addView(playPause, LinearLayout.LayoutParams(dp(48), dp(48)))
        binding.controlsRow.addView(
            exit,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) },
        )
        binding.root.isFocusable = true
        playPause.requestFocus()
        scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible) handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    private fun setControlsVisible(visible: Boolean, requestFocus: Boolean = false) {
        handler.removeCallbacks(hideControlsRunnable)
        controlsVisible = visible
        binding.controlsRow.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            if (requestFocus) playPause?.requestFocus()
            scheduleControlsHide()
        } else if (currentFocus != null) {
            binding.root.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode
        if (!controlsVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                -> {
                    seekBy(-SEEK_STEP_SECONDS)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                -> {
                    seekBy(SEEK_STEP_SECONDS)
                    return true
                }
            }
            if (isDpadKey(keyCode)) {
                setControlsVisible(true, requestFocus = true)
                return true
            }
        } else if (isDpadKey(keyCode)) {
            scheduleControlsHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDpadKey(keyCode: Int): Boolean =
        keyCode in
            setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
            )

    private fun togglePlayback() {
        evaluateVideo("if(v.paused){v.play()}else{v.pause()}")
    }

    private fun seekBy(seconds: Int) {
        evaluateVideo(
            "var t=isFinite(v.duration)?v.duration:1e9;" +
                "v.currentTime=Math.max(0,Math.min(t,v.currentTime+($seconds)));",
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updatePlaybackState() {
        binding.webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');" +
                "return v?(v.paused?'paused':'playing'):'none';})()",
        ) { result ->
            val playing = result?.contains("playing") == true
            playPause.update(
                if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                getString(R.string.player_btn_play_pause),
            )
        }
    }

    private fun evaluateVideo(js: String) {
        binding.webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(!v)return;$js})()",
            null,
        )
    }

    private fun applyDesktopLayout() {
        binding.webView.evaluateJavascript(DESKTOP_LAYOUT_JS, null)
    }

    private fun desktopUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.host == "m.iqiyi.com" || parsed.host == "www.iqiyi.com") {
            return parsed.newBuilder().scheme("https").host("www.iqiyi.com").build().toString()
        }
        return url
    }

    private fun loadDesktopDocument(url: String): WebResourceResponse? {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
        val client =
            OkHttpClient.Builder()
                .cookieJar(sessionStore.cookies)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val html = injectDesktopViewport(body)
            return WebResourceResponse(
                "text/html",
                "utf-8",
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    private fun injectDesktopViewport(html: String): String {
        val meta =
            "<meta name=\"viewport\" content=\"width=1920, initial-scale=0.38, " +
                "maximum-scale=0.38, user-scalable=no\">"
        val style =
            "<style id=\"blbl-desktop-style\">" +
                "#areaLeftContainer{flex:1 1 100%!important;width:100%!important;max-width:none!important}" +
                "#container1MainContent,#container1,#areaContainer,#skeleton{width:100%!important;" +
                "max-width:none!important;overflow:visible!important}" +
                "[class*=\"NavHeader_navHeaderNew\"],[class*=\"topbarXPlayer\"]," +
                "[class*=\"configPage_configPageWrapper\"],[class*=\"configPage_configPage\"]," +
                "[class*=\"danmu\"],[class*=\"Danmu\"],[class*=\"danmaku\"],[class*=\"Danmaku\"]{display:none!important}" +
                "#outlayer.iqp-player{position:fixed!important;inset:0!important;" +
                "width:100vw!important;height:100vh!important;z-index:2147483647!important;" +
                "background:#000!important;overflow:visible!important}" +
                "#outlayer.iqp-player .iqp-player-videolayer,#outlayer.iqp-player video{" +
                "width:100%!important;height:100%!important}" +
                "#outlayer.iqp-player video{object-fit:contain!important}" +
                ".iqp-player{width:100vw!important;height:100vh!important;max-width:none!important;" +
                "max-height:none!important;overflow:visible!important}" +
                ".iqp-player.use-pc-bridge{position:fixed!important;inset:0!important;" +
                "z-index:2147483646!important}" +
                "html,body{overflow:hidden!important;margin:0!important}" +
                "</style>"
        val viewport = Regex("""<meta[^>]+name=["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE)
        val styled =
            if (viewport.containsMatchIn(html)) {
                viewport.replace(html, meta + style)
            } else {
                html.replaceFirst("<head>", "<head>" + meta + style, ignoreCase = true)
            }
        return styled
    }

    private fun installIqiyiCookies() {
        val cookies = sessionStore.cookies.loadForRequest("https://m.iqiyi.com/".toHttpUrl())
        if (cookies.isEmpty()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        for (host in listOf("https://www.iqiyi.com/", "https://m.iqiyi.com/")) {
            for (cookie in cookies) {
                manager.setCookie(
                    host,
                    "${cookie.name}=${cookie.value}; Domain=.iqiyi.com; Path=/",
                )
            }
        }
        manager.flush()
    }

    override fun onDestroy() {
        handler.removeCallbacks(playbackPoll)
        handler.removeCallbacks(hideControlsRunnable)
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun String.toHttpUrlOrNullHost(): String? =
        runCatching { this.toHttpUrl().host }.getOrNull()

    companion object {
        const val EXTRA_URL = "iqiyi_web_url"
        const val EXTRA_TITLE = "iqiyi_web_title"
        const val EXTRA_DESKTOP = "iqiyi_web_desktop"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val CONTROLS_HIDE_DELAY_MS = 5_000L
        private const val SEEK_STEP_SECONDS = 10
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val DESKTOP_LAYOUT_JS = """
            (function() {
              var player = document.querySelector('#outlayer.iqp-player');
              if (!player) return 'no-player';
              var left = document.querySelector('#areaLeftContainer');
              if (left) {
                left.style.setProperty('flex', '1 1 100%', 'important');
                left.style.setProperty('width', '100%', 'important');
                left.style.setProperty('max-width', 'none', 'important');
              }
              ['#container1MainContent', '#container1', '#areaContainer', '#skeleton'].forEach(function(sel) {
                var el = document.querySelector(sel);
                if (!el) return;
                el.style.setProperty('width', '100%', 'important');
                el.style.setProperty('max-width', 'none', 'important');
                el.style.setProperty('overflow', 'visible', 'important');
              });
              var hides = [
                '[class*="NavHeader_navHeaderNew"]',
                '[class*="topbarXPlayer"]',
                '[class*="configPage_configPageWrapper"]',
                '[class*="configPage_configPage"]',
                '[class*="danmu"]',
                '[class*="Danmu"]',
                '[class*="danmaku"]',
                '[class*="Danmaku"]'
              ];
              hides.forEach(function(sel) {
                document.querySelectorAll(sel).forEach(function(el) {
                  el.style.setProperty('display', 'none', 'important');
                });
              });
              var parent = player.parentElement;
              for (var i = 0; i < 3 && parent; i++, parent = parent.parentElement) {
                parent.style.setProperty('width', '100%', 'important');
                parent.style.setProperty('height', '100%', 'important');
                parent.style.setProperty('overflow', 'visible', 'important');
              }
              var style = document.getElementById('blbl-fullscreen-style');
              if (!style) {
                style = document.createElement('style');
                style.id = 'blbl-fullscreen-style';
                document.head.appendChild(style);
              }
              style.textContent =
                '#outlayer.iqp-player{position:fixed!important;inset:0!important;' +
                'width:100vw!important;height:100vh!important;z-index:2147483647!important;' +
                'background:#000!important}' +
                '#outlayer.iqp-player .iqp-player-videolayer,#outlayer.iqp-player video{' +
                'width:100%!important;height:100%!important}' +
                '#outlayer.iqp-player video{object-fit:contain!important}' +
                '.iqp-player{width:100vw!important;height:100vh!important;max-width:none!important;' +
                'max-height:none!important;overflow:visible!important}' +
                '.iqp-player.use-pc-bridge{position:fixed!important;inset:0!important;' +
                'z-index:2147483646!important}' +
                'body{overflow:hidden!important;margin:0!important}';
              var video = document.querySelector('video');
              if (video) {
                video.muted = false;
                var play = video.play();
                if (play && play.catch) play.catch(function() {});
              }
              window.dispatchEvent(new Event('resize'));
              return 'ok';
            })();
        """

        /** Keep the mobile player's danmaku below the player controls instead of oversized text. */
        private const val DANMAKU_SIZE_JS = """
            (function() {
              var id = 'blbl-danmaku-size';
              var style = document.getElementById(id);
              if (!style) {
                style = document.createElement('style');
                style.id = id;
                (document.head || document.documentElement).appendChild(style);
              }
              style.textContent =
                '[class*="danmu"],[class*="Danmu"],[class*="danmaku"],[class*="Danmaku"]{' +
                'font-size:3.2vh!important;line-height:1.2!important}' +
                '[class*="danmu"] *,[class*="danmaku"] *{font-size:inherit!important}';
              return 'ok';
            })();
        """

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
            desktop: Boolean = false,
        ): Intent = Intent(context, IqiyiWebPlayerActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_DESKTOP, desktop)
    }
}
