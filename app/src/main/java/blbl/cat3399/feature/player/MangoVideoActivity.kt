package blbl.cat3399.feature.player

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import blbl.cat3399.R
import blbl.cat3399.core.account.MangoSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityMangoVideoBinding
import blbl.cat3399.BuildConfig
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import org.json.JSONTokener

/** Uses Mango's official PC player to retain its 1080p stream options and session-bound media pipeline. */
class MangoVideoActivity : BaseActivity() {
    private lateinit var binding: ActivityMangoVideoBinding
    private val volumeBoost = PlayerVolumeBoost()
    private val playerContext get() = binding.root.context
    private lateinit var chromeClient: WebChromeClient
    private var visibleCustomView: android.view.View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var debugInfoOverlay: TextView? = null
    private var debugPageStatus: String? = null
    private var lastLoggedQuality: String? = null
    private var firstDebugControl: View? = null
    private var initialDebugControlFocusRequested = false
    private lateinit var bottomPanel: LinearLayout
    private lateinit var controlsHost: LinearLayout
    private var controlsScroll: android.widget.HorizontalScrollView? = null
    private var controlsVisible = true
    private var playbackPaused = true
    private var controlsVisibleBeforeCustomView = true
    private var episodePickerLoading = false
    private lateinit var playbackProgressBar: ProgressBar
    private lateinit var playbackCurrentTime: TextView
    private lateinit var playbackTotalTime: TextView
    private lateinit var danmakuToggle: DanmakuToggleButton
    private var playPauseIcon: PlayerIconButton? = null
    private var lastPerfLogAt: Long = 0L
    private var episodeOverlay: EpisodePickerOverlay? = null
    private var currentCatalog: MangoCatalog? = null
    private val catalogRepository = MangoCatalogRepository()
    private val streamRepository by lazy { MangoStreamRepository(this) }
    private var playbackProgressLoopRunning = false
    private val debugInfoHandler = Handler(Looper.getMainLooper())
    private val playbackProgressHandler = Handler(Looper.getMainLooper())
    private val controlsHideHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable {
        if (!playbackPaused && episodeOverlay?.isShowing != true && visibleCustomView == null) {
            setBottomControlsVisible(false)
        }
    }
    private val refreshPlaybackProgress = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) {
                playbackProgressLoopRunning = false
                return
            }
            binding.webView.evaluateJavascript(PLAYBACK_PROGRESS_SCRIPT) { result ->
                updatePlaybackProgress(result)
                if (playbackProgressLoopRunning) {
                    playbackProgressHandler.postDelayed(this, PLAYBACK_PROGRESS_REFRESH_MS)
                }
            }
        }
    }
    private val refreshDebugInfo = object : Runnable {
        override fun run() {
            if (debugInfoOverlay?.visibility != View.VISIBLE || isFinishing || isDestroyed) return
            updateDebugInfo()
            debugInfoHandler.postDelayed(this, DEBUG_INFO_REFRESH_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangoVideoBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        volumeBoost.attach(0)
        Immersive.apply(this, blbl.cat3399.core.net.BiliClient.prefs.fullscreenEnabled)
        installPlaybackProgressBar()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (!isAllowedMangoUrl(url)) {
            finish()
            return
        }
        if (BuildConfig.DEBUG) {
            debugPlaybackCapture = null
            debugCurrentHlsDef = null
        }
        val legacyWebView = isLegacyWebView()
        val mobileMode = legacyWebView || intent.getBooleanExtra(EXTRA_MOBILE_MODE, false)
        AppLog.i("MangoPlayer", "webview legacy=$legacyWebView mobileMode=$mobileMode")
        val playbackUrl = if (mobileMode) {
            url.replaceFirst("://www.mgtv.com/", "://m.mgtv.com/")
        } else {
            url
        }

        if (legacyWebView) {
            // The WebView is only a fallback; hide it while resolving to avoid a white flash
            // between the list page and the native player.
            binding.root.setBackgroundColor(android.graphics.Color.BLACK)
            binding.webView.setBackgroundColor(android.graphics.Color.BLACK)
            binding.webView.visibility = View.INVISIBLE
            val ids = parseMangoVideoIds(url)
            if (ids == null) {
                AppToast.show(this, "当前播放页不支持原生解析")
                finish()
                return
            }
            lifecycleScope.launch {
                val stream = streamRepository.resolve(ids.first, ids.second)
                if (stream == null) {
                    AppToast.show(this@MangoVideoActivity, "播放地址解析失败")
                    finish()
                    return@launch
                }
                val queryStartSec = runCatching {
                    url.toHttpUrl().queryParameter("start_time")?.toLongOrNull()
                }.getOrNull() ?: 0L
                val startSec = intent.getLongExtra(EXTRA_START_POSITION_SEC, 0L)
                    .takeIf { it > 0L } ?: queryStartSec
                val probeIntent = android.content.Intent()
                    .setClassName(packageName, "blbl.cat3399.feature.player.MangoNativePlayerActivity")
                    .putExtra("mango_test_stream_url", stream.url)
                    .putExtra("mango_test_user_agent", stream.userAgent)
                    .putExtra(EXTRA_START_POSITION_MS, startSec.coerceAtLeast(0L) * 1_000L)
                    .putExtra(EXTRA_MANGO_VIDEO_ID, ids.first)
                    .putExtra(EXTRA_MANGO_PROGRAM_ID, ids.second)
                    .putExtra(EXTRA_MANGO_CLIP_ID, stream.clipId)
                runCatching { startActivity(probeIntent) }
                    .onFailure { AppToast.show(this@MangoVideoActivity, "无法启动 EXO 播放器") }
                finish()
            }
            return
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            if (!mobileMode) userAgentString = desktopPlaybackUserAgent(userAgentString)
        }
        chromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
            }

            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                if (view == null) return
                visibleCustomView?.let { binding.root.removeView(it) }
                visibleCustomView = view
                customViewCallback = callback
                binding.root.addView(view, android.view.ViewGroup.LayoutParams(-1, -1))
                controlsVisibleBeforeCustomView = controlsVisible
                setBottomControlsVisible(false)
                view.requestFocus()
            }

            override fun onHideCustomView() {
                visibleCustomView?.let(binding.root::removeView)
                visibleCustomView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                setBottomControlsVisible(controlsVisibleBeforeCustomView)
                binding.webView.requestFocus()
            }
        }
        binding.webView.webChromeClient = chromeClient
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (BuildConfig.DEBUG) captureDebugHlsRequest(request)
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return request?.url?.host?.let(::isAllowedMangoHost) != true
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                binding.progress.visibility = android.view.View.GONE
                if (pageUrl?.let(::isAllowedMangoUrl) == true) {
                    debugPageStatus = "播放页已加载"
                    val loaded = pageUrl.let { runCatching { it.toHttpUrl() }.getOrNull() }
                    AppLog.i(
                        "MangoPlayer",
                        "playback page loaded host=${loaded?.host ?: "-"} path=${loaded?.encodedPath ?: "-"}",
                    )
                    view?.evaluateJavascript(MANGO_DANMAKU_STATUS_SCRIPT) { result ->
                        val status = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                        if (status == "open" || status == "close") {
                            danmakuToggle.setDanmakuOn(status == "open", notify = false)
                        } else {
                            AppLog.w("MangoDanmaku", "web status unavailable result=${result.orEmpty()}")
                        }
                    }
                    view?.evaluateJavascript(PAUSED_FRAME_OVERLAY_SCRIPT, null)
                    view?.evaluateJavascript(SELECT_1080P_SDR_SCRIPT, null)
                    view?.evaluateJavascript(AUTO_ENTER_WEB_FULLSCREEN_SCRIPT, null)
                    intent.getLongExtra(EXTRA_START_POSITION_SEC, 0L)
                        .takeIf { it > 0L }
                        ?.let { view?.evaluateJavascript(historyResumeScript(it), null) }
                    scheduleControlsHide()
                    if (BuildConfig.DEBUG && !initialDebugControlFocusRequested) {
                        initialDebugControlFocusRequested = true
                        firstDebugControl?.post {
                            if (!isFinishing && visibleCustomView == null) firstDebugControl?.requestFocus()
                        }
                    }
                }
            }
        }

        installPlaybackControls()
        episodeOverlay = EpisodePickerOverlay(
            context = playerContext,
            parent = binding.root,
            loadEpisodes = { season, part ->
                val catalog = currentCatalog
                if (catalog == null) emptyList() else catalogRepository.loadEpisodes(catalog, season, part)
            },
            onSelect = { episode -> playEpisode(episode) },
        )
        installMangoCookiesAndLoad(playbackUrl)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            episodeOverlay?.takeIf { it.isShowing }?.let {
                it.dismiss()
                setBottomControlsVisible(true, requestFocus = true)
                return true
            }
            if (visibleCustomView != null) {
                chromeClient.onHideCustomView()
            } else if (controlsVisible) {
                setBottomControlsVisible(false)
            } else finish()
            return true
        }
        if (isDpadKey(keyCode)) {
            if (!controlsVisible) {
                setBottomControlsVisible(true, requestFocus = true)
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) showEpisodePicker()
                return true
            }
            scheduleControlsHide()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        volumeBoost.release()
        episodeOverlay?.release()
        customViewCallback?.onCustomViewHidden()
        stopPlaybackProgressUpdates()
        debugInfoHandler.removeCallbacksAndMessages(null)
        controlsHideHandler.removeCallbacksAndMessages(null)
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    private fun captureDebugHlsRequest(request: WebResourceRequest?) {
        val uri = request?.url ?: return
        if (!uri.host.orEmpty().endsWith("mgtv.com") || !uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) return
        uri.getQueryParameter("def")?.let { debugCurrentHlsDef = it }
        val previous = debugPlaybackCapture
        val definition = uri.getQueryParameter("def")
        if (definition != "4" && previous != null) return
        debugPlaybackCapture = DebugPlaybackCapture(
            url = uri.toString(),
            userAgent = request.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value,
        )
    }

    private fun installPlaybackProgressBar() {
        playbackCurrentTime = TextView(playerContext).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setShadowLayer(dp(2).toFloat(), 0f, 0f, android.graphics.Color.BLACK)
            text = "00:00"
        }
        playbackTotalTime = TextView(playerContext).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setShadowLayer(dp(2).toFloat(), 0f, 0f, android.graphics.Color.BLACK)
            text = "--:--"
        }
        playbackProgressBar = ProgressBar(playerContext, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PLAYBACK_PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(
                ThemeColor.resolve(this@MangoVideoActivity, R.attr.blblAccent, R.color.blbl_blue),
            )
            progressBackgroundTintList = ColorStateList.valueOf(android.graphics.Color.argb(72, 255, 255, 255))
            minimumHeight = 0
        }
        val progressRow = LinearLayout(playerContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(android.graphics.Color.argb(150, 0, 0, 0))
            addView(playbackCurrentTime, LinearLayout.LayoutParams(dp(84), -2))
            addView(
                playbackProgressBar,
                LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                },
            )
            addView(playbackTotalTime, LinearLayout.LayoutParams(dp(84), -2))
        }
        bottomPanel = LinearLayout(playerContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_controls)
        }
        bottomPanel.addView(progressRow, LinearLayout.LayoutParams(-1, -2))
        binding.root.addView(
            bottomPanel,
            android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
        )
    }

    private fun installPlaybackControls() {
        danmakuToggle = DanmakuToggleButton(playerContext).apply {
            onToggle = { enabled -> setWebDanmakuEnabled(enabled) }
        }
        val playPause = PlayerIconButton(playerContext, R.drawable.ic_player_pause, "暂停") { toggleWebPlayer() }
        playPauseIcon = playPause
        val next = PlayerIconButton(playerContext, R.drawable.ic_player_next, "下一集") { playNextEpisode() }
        val playlist = PlayerIconButton(playerContext, R.drawable.ic_player_playlist, "播放列表") { showEpisodePicker() }
        val debugInfo = PlayerIconButton(playerContext, R.drawable.ic_player_debug, "调试信息") { toggleDebugInfo() }
        val exoPlayer = textPill("EXO 播放器") { launchExoProbe() }
        controlsHost = LinearLayout(playerContext).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        controlsScroll = PlayerControlBar.install(
            host = controlsHost,
            sourceName = getString(R.string.player_source_mango),
            actions = CommonPlayerActions(playPause, next, playlist, danmakuToggle),
            siteActions = listOf(debugInfo, exoPlayer),
        )
        bottomPanel.addView(controlsHost, LinearLayout.LayoutParams(-1, dp(48)))
        firstDebugControl = playPause
        if (BuildConfig.DEBUG) {
            debugInfoOverlay = TextView(playerContext).apply {
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(blbl.cat3399.R.color.player_overlay_bg)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.GONE
            }
            binding.root.addView(
                debugInfoOverlay,
                android.widget.FrameLayout.LayoutParams(dp(700), -2, Gravity.TOP or Gravity.START).apply {
                    marginStart = dp(16)
                    topMargin = dp(16)
                },
            )
        }
    }

    private fun textPill(label: String, onClick: () -> Unit): Button = Button(playerContext).apply {
        text = label
        isFocusable = true
        isFocusableInTouchMode = true
        applyPlayerPillStyle()
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun toggleDebugInfo() {
        val overlay = debugInfoOverlay ?: return
        if (overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
            debugInfoHandler.removeCallbacks(refreshDebugInfo)
        } else {
            overlay.visibility = View.VISIBLE
            lastLoggedQuality = null
            updateDebugInfo()
            debugInfoHandler.removeCallbacks(refreshDebugInfo)
            debugInfoHandler.postDelayed(refreshDebugInfo, DEBUG_INFO_REFRESH_MS)
        }
    }

    private fun updateDebugInfo() {
        val overlay = debugInfoOverlay ?: return
        val nativeHlsDef = debugCurrentHlsDef?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }.orEmpty()
        val debugScript = MANGO_DEBUG_INFO_SCRIPT.replace("__BLBL_NATIVE_HLS_DEF__", nativeHlsDef)
        binding.webView.evaluateJavascript(debugScript) { result ->
            if (overlay.visibility != View.VISIBLE) return@evaluateJavascript
            val pageInfo = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                ?: "等待网页播放器数据…"
            overlay.text = buildString {
                debugPageStatus?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
                append(pageInfo)
            }
            logDebugQuality(pageInfo)
        }
    }

    private fun logDebugQuality(pageInfo: String) {
        val quality = pageInfo.lineSequence().firstOrNull { it.startsWith("当前画质=") } ?: return
        if (quality == lastLoggedQuality) return
        lastLoggedQuality = quality
        AppLog.i("MangoPlayer", "debug info\n$pageInfo")
    }

    private fun startPlaybackProgressUpdates() {
        if (playbackProgressLoopRunning) return
        playbackProgressLoopRunning = true
        playbackProgressHandler.removeCallbacks(refreshPlaybackProgress)
        playbackProgressHandler.post(refreshPlaybackProgress)
    }

    private fun stopPlaybackProgressUpdates() {
        playbackProgressLoopRunning = false
        playbackProgressHandler.removeCallbacks(refreshPlaybackProgress)
    }

    private fun updatePlaybackProgress(result: String?) {
        val raw = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
        val parts = raw.split('|')
        val positionMs = parts.getOrNull(0)?.toDoubleOrNull()?.times(1_000.0)?.toLong()
        val durationMs = parts.getOrNull(1)?.toDoubleOrNull()?.times(1_000.0)?.toLong()
        val bufferedMs = parts.getOrNull(2)?.toDoubleOrNull()?.times(1_000.0)?.toLong()
        val paused = parts.getOrNull(3) == "1"
        val totalFrames = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val droppedFrames = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val nowUptime = SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG && nowUptime - lastPerfLogAt >= 5_000L) {
            lastPerfLogAt = nowUptime
            AppLog.i(
                "MangoPerf",
                "webview pos=${positionMs}ms total=$totalFrames dropped=$droppedFrames paused=$paused",
            )
        }
        playPauseIcon?.update(
            if (paused) R.drawable.ic_player_play else R.drawable.ic_player_pause,
            if (paused) "播放" else "暂停",
        )
        val resumed = playbackPaused && !paused
        playbackPaused = paused
        if (paused) {
            controlsHideHandler.removeCallbacks(hideControls)
            if (!controlsVisible) setBottomControlsVisible(true)
        } else if (resumed && controlsVisible) {
            scheduleControlsHide()
        }
        if (durationMs == null || durationMs <= 0L) {
            playbackCurrentTime.text = formatPlaybackTime(positionMs ?: 0L)
            playbackTotalTime.text = "--:--"
            playbackProgressBar.progress = 0
            playbackProgressBar.secondaryProgress = 0
            return
        }
        val position = (positionMs ?: 0L).coerceIn(0L, durationMs)
        val buffered = (bufferedMs ?: position).coerceIn(position, durationMs)
        playbackCurrentTime.text = formatPlaybackTime(position)
        playbackTotalTime.text = formatPlaybackTime(durationMs)
        playbackProgressBar.progress = ((position.toDouble() / durationMs) * PLAYBACK_PROGRESS_MAX)
            .toInt()
            .coerceIn(0, PLAYBACK_PROGRESS_MAX)
        playbackProgressBar.secondaryProgress = ((buffered.toDouble() / durationMs) * PLAYBACK_PROGRESS_MAX)
            .toInt()
            .coerceIn(playbackProgressBar.progress, PLAYBACK_PROGRESS_MAX)
    }

    private fun formatPlaybackTime(valueMs: Long): String {
        val totalSeconds = (valueMs / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun setBottomControlsVisible(visible: Boolean, requestFocus: Boolean = false) {
        controlsVisible = visible
        bottomPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && requestFocus) controlsScroll?.post { controlsScroll?.let(PlayerControlBar::focusStart) }
        controlsHideHandler.removeCallbacks(hideControls)
        if (visible) scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        controlsHideHandler.removeCallbacks(hideControls)
        if (!controlsVisible || playbackPaused || episodeOverlay?.isShowing == true || episodePickerLoading || visibleCustomView != null) return
        controlsHideHandler.postDelayed(hideControls, PLAYER_CONTROLS_AUTO_HIDE_MS)
    }

    private fun isDpadKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    private fun playNextEpisode() {
        val pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val ids = parseMangoVideoIds(pageUrl) ?: run {
            AppToast.show(this, "暂无下一集信息")
            return
        }
        AppToast.show(this, "正在查找下一集…")
        lifecycleScope.launch {
            val catalog = runCatching { catalogRepository.loadCatalog(ids.first, ids.second) }.getOrNull()
                ?: run {
                    AppToast.show(this@MangoVideoActivity, "下一集加载失败")
                    return@launch
                }
            val season = catalog.seasons.firstOrNull { it.clipId == catalog.currentSeasonClipId }
                ?: catalog.seasons.firstOrNull()
                ?: return@launch
            val part = season.parts.firstOrNull { !it.extras && it.name == "正片" }
                ?: season.parts.firstOrNull { !it.extras }
                ?: return@launch
            val episodes = runCatching { catalogRepository.loadEpisodes(catalog, season, part) }.getOrDefault(emptyList())
            val index = episodes.indexOfFirst { it.videoId == ids.first }
            val next = episodes.getOrNull(index + 1)
            if (index < 0 || next == null) {
                AppToast.show(this@MangoVideoActivity, "已经是最后一集")
                return@launch
            }
            startActivity(
                android.content.Intent(this@MangoVideoActivity, MangoVideoActivity::class.java)
                    .putExtra(EXTRA_URL, next.pageUrl),
            )
            finish()
        }
    }

    private fun showEpisodePicker() {
        setBottomControlsVisible(true)
        episodePickerLoading = true
        controlsHideHandler.removeCallbacks(hideControls)
        binding.webView.evaluateJavascript(MANGO_VIDEO_INFO_SCRIPT) { result ->
            val info = runCatching { JSONTokener(result).nextValue() as? JSONObject }.getOrNull()
            val videoId = info?.optString("videoId").orEmpty()
            val clipId = info?.optString("clipId").orEmpty()
            if (videoId.isBlank() || clipId.isBlank()) {
                AppToast.show(this, "未获取到芒果剧集信息")
                episodePickerLoading = false
                scheduleControlsHide()
                return@evaluateJavascript
            }
            AppToast.show(this, "正在加载选集…")
            lifecycleScope.launch {
                val catalog = runCatching { catalogRepository.loadCatalog(videoId, clipId) }.getOrNull()
                if (catalog == null || catalog.seasons.isEmpty()) {
                    AppToast.show(this@MangoVideoActivity, "选集加载失败，请重试")
                    episodePickerLoading = false
                    scheduleControlsHide()
                    return@launch
                }
                episodePickerLoading = false
                currentCatalog = catalog
                episodeOverlay?.show(catalog, videoId, catalog.currentClipId)
            }
        }
    }

    private fun playEpisode(episode: MangoEpisode) {
        episodeOverlay?.dismiss()
        binding.webView.loadUrl(episode.pageUrl)
        binding.webView.requestFocus()
    }

    private fun setWebDanmakuEnabled(enabled: Boolean) {
        val script = MANGO_DANMAKU_TOGGLE_SCRIPT.replace("__BLBL_DANMAKU_ON__", enabled.toString())
        binding.webView.evaluateJavascript(script) { result ->
            AppLog.i("MangoPlayer", "danmaku toggle enabled=$enabled result=${result.orEmpty().trim('"')}")
        }
    }

    private fun historyResumeScript(positionSec: Long): String = """
        (() => {
          if (window.__blblMangoResumeApplied) return;
          const targetFromHistory = $positionSec;
          let attempts = 0;
          const resume = () => {
            if (window.__blblMangoResumeApplied) return;
            const video = document.querySelector('video');
            if (!video || video.readyState < 1 || !Number.isFinite(video.duration) || video.duration <= 0) {
              if (++attempts < 30) setTimeout(resume, 500);
              return;
            }
            const target = Math.min(targetFromHistory, Math.max(0, video.duration - 1));
            if (video.currentTime >= target - 2) {
              window.__blblMangoResumeApplied = true;
              return;
            }
            try {
              const player = window.mgtvPlayer;
              if (typeof player?.userActions?.seek === 'function') player.userActions.seek(target);
              else video.currentTime = target;
              window.__blblMangoResumeApplied = true;
            } catch (_) {
              if (++attempts < 30) setTimeout(resume, 500);
            }
          };
          setTimeout(resume, 2000);
        })();
    """.trimIndent()

    private fun seekWebPlayer(offsetSeconds: Int) {
        val script = """
            (() => {
              const player = window.mgtvPlayer;
              const video = document.querySelector('video');
              if (!video) return 'video element unavailable';
              const target = Math.max(0, video.currentTime + ($offsetSeconds));
              if (typeof player?.userActions?.seek === 'function') player.userActions.seek(target);
              else video.currentTime = target;
              return 'seek to ' + target.toFixed(1) + 's';
            })()
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { result ->
            AppLog.i("MangoPlayer", "WebView: ${result.orEmpty().trim('"')}")
        }
    }

    private fun toggleWebPlayer() {
        val script = """
            (() => {
              const player = window.mgtvPlayer;
              const video = document.querySelector('video');
              if (!video) return 'video element unavailable';
              if (typeof player?.userActions?.togglePlay === 'function') player.userActions.togglePlay();
              else if (video.paused) video.play(); else video.pause();
              return 'time=' + video.currentTime.toFixed(1) + 's paused=' + video.paused;
            })()
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { result ->
            AppLog.i("MangoPlayer", "WebView: ${result.orEmpty().trim('"')}")
        }
    }

    private fun launchExoProbe() {
        val historyPositionMs = intent.getLongExtra(EXTRA_START_POSITION_SEC, 0L)
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
        val capturePositionScript = """
            (() => {
              const video = document.querySelector('video');
              if (!video || video.readyState < 1 || video.currentTime <= 0) return -1;
              const positionMs = Math.round(video.currentTime * 1000);
              if (!video.paused) video.pause();
              return positionMs;
            })()
        """.trimIndent()
        binding.webView.evaluateJavascript(capturePositionScript) { result ->
            val currentPositionMs = result?.trim('"')?.toDoubleOrNull()?.toLong()?.takeIf { it > 0L }
            val startPositionMs = currentPositionMs ?: historyPositionMs
            binding.webView.evaluateJavascript(MANGO_DANMAKU_INFO_SCRIPT) { infoResult ->
                val info = runCatching { JSONTokener(infoResult).nextValue() as? JSONObject }.getOrNull()
                binding.webView.evaluateJavascript(MANGO_VIDEO_INFO_SCRIPT) { currentResult ->
                    val current = runCatching { JSONTokener(currentResult).nextValue() as? JSONObject }.getOrNull()
                    val probeIntent = android.content.Intent()
                        .setClassName(packageName, "blbl.cat3399.feature.player.MangoNativePlayerActivity")
                        .putExtra(EXTRA_START_POSITION_MS, startPositionMs)
                        .putExtra(EXTRA_HAS_WEB_VIEW_RETURN, true)
                        .putExtra(EXTRA_DANMAKU_CDN_VERSION, info?.optString("cdnVersion").orEmpty())
                        .putExtra(EXTRA_DANMAKU_INTERVAL_SEC, info?.optInt("interval", 60) ?: 60)
                        .putExtra(EXTRA_MANGO_VIDEO_ID, current?.optString("videoId").orEmpty())
                        .putExtra(EXTRA_MANGO_PROGRAM_ID, current?.optString("programId").orEmpty())
                        .putExtra(EXTRA_MANGO_CLIP_ID, current?.optString("clipId").orEmpty())
                    runCatching { startActivity(probeIntent) }
                        .onFailure { AppLog.w("MangoPlayer", "EXO 播放器：尚未捕获到芒果 HLS 媒体流") }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * playerContext.resources.displayMetrics.density).toInt()

    override fun onPause() {
        stopPlaybackProgressUpdates()
        debugInfoHandler.removeCallbacks(refreshDebugInfo)
        binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.evaluateJavascript(AUTO_ENTER_WEB_FULLSCREEN_SCRIPT, null)
        startPlaybackProgressUpdates()
        if (debugInfoOverlay?.visibility == View.VISIBLE) {
            debugInfoHandler.removeCallbacks(refreshDebugInfo)
            updateDebugInfo()
            debugInfoHandler.postDelayed(refreshDebugInfo, DEBUG_INFO_REFRESH_MS)
        }
    }

    private fun installMangoCookiesAndLoad(url: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val cookies = MangoSessionStore(applicationContext).cookies.loadForRequest(MANGO_SITE.toHttpUrl())
        AppLog.d("MangoPlayer", "installing web cookies count=${cookies.size}")
        installCookieAt(cookieManager, cookies, 0, url)
    }

    private fun installCookieAt(
        manager: CookieManager,
        cookies: List<Cookie>,
        index: Int,
        url: String,
    ) {
        if (index >= cookies.size) {
            manager.flush()
            binding.webView.loadUrl(url)
            return
        }
        manager.setCookie(MANGO_SITE, cookies[index].toWebViewCookie()) {
            installCookieAt(manager, cookies, index + 1, url)
        }
    }

    private fun Cookie.toWebViewCookie(): String = buildString {
        append(name).append('=').append(value)
        if (!hostOnly) append("; Domain=.").append(domain.removePrefix("."))
        append("; Path=").append(path)
        if (secure) append("; Secure")
        if (httpOnly) append("; HttpOnly")
        if (persistent && expiresAt < Long.MAX_VALUE) append("; Expires=").append(EXPIRES.format(Date(expiresAt)))
    }

    private fun isLegacyWebView(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val packageVersion = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
            val packageMajor = packageVersion?.substringBefore('.')?.toIntOrNull() ?: 0
            AppLog.i("MangoPlayer", "webview package=$packageVersion major=$packageMajor")
            if (packageMajor > 0) return packageMajor < 80
        }
        val ua = android.webkit.WebSettings.getDefaultUserAgent(this)
        val major = CHROME_VERSION.find(ua)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return major in 1..79
    }

    private fun parseMangoVideoIds(url: String): Pair<String, String>? {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        val segments = parsed.pathSegments
        return when {
            segments.size >= 3 && segments[0] == "b" -> {
                val clipId = segments[1].trim()
                val videoId = segments[2].removeSuffix(".html").trim()
                if (clipId.isBlank() || videoId.isBlank()) null else videoId to clipId
            }
            segments.size >= 2 && segments[0] == "s" -> {
                val videoId = segments[1].removeSuffix(".html").trim()
                if (videoId.isBlank()) null else videoId to videoId
            }
            segments.size >= 3 && segments[0] == "l" -> {
                val clipId = segments[1].trim()
                val videoId = segments[2].removeSuffix(".html").trim()
                if (clipId.isBlank() || videoId.isBlank()) null else videoId to clipId
            }
            segments.size >= 2 && segments[0] == "l" -> {
                val videoId = segments[1].removeSuffix(".html").trim()
                if (videoId.isBlank()) null else videoId to videoId
            }
            else -> null
        }
    }

    private fun isAllowedMangoUrl(value: String): Boolean =
        runCatching { value.toHttpUrl() }.getOrNull()?.let { it.isHttps && isAllowedMangoHost(it.host) } == true

    private fun isAllowedMangoHost(host: String): Boolean =
        host == "mgtv.com" || host.endsWith(".mgtv.com") || host == "hitv.com" || host.endsWith(".hitv.com")

    private fun desktopPlaybackUserAgent(current: String): String {
        // Mango otherwise redirects this WebView to its mobile player, which selected a lower default stream.
        val chromeVersion = CHROME_VERSION.find(current)?.groupValues?.get(1) ?: return current
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    companion object {
        private val AUTO_ENTER_WEB_FULLSCREEN_SCRIPT = """
            (() => {
              if (window.__blblMangoFullscreenGuard) return;
              window.__blblMangoFullscreenGuard = true;

              const installFullscreenStyle = () => {
                if (document.getElementById('blbl-mango-hide-fullscreen')) return;
                const style = document.createElement('style');
                style.id = 'blbl-mango-hide-fullscreen';
                style.textContent = '.as_comp-fullscreen, .mgtv-player-control-fullscreen, [aria-label*="全屏"], [title*="全屏"] {' +
                  'display:none!important;visibility:hidden!important;pointer-events:none!important;}';
                (document.head || document.documentElement).appendChild(style);
              };

              const isFullscreen = video => {
                const player = window.mgtvPlayer;
                const helper = player?.userActions?.player?.fullScreenHelper;
                if (helper?.isWebFullscreen === true) return true;
                if (document.querySelector('.mgtv-player-wrap.web-fullscreen, .vod-main.is-web-fullscreen')) return true;
                const rect = video?.getBoundingClientRect();
                return !!rect && rect.width >= window.innerWidth * 0.98 && rect.height >= window.innerHeight * 0.98;
              };

              let enterPending = false;
              const enforce = () => {
                installFullscreenStyle();
                if (document.visibilityState === 'hidden' || enterPending) return;
                const video = document.querySelector('video');
                if (!video || video.readyState < 1 || isFullscreen(video)) return;
                const action = window.mgtvPlayer?.userActions?.webFullscreen;
                if (typeof action !== 'function') return;
                enterPending = true;
                try { action.call(window.mgtvPlayer.userActions); } catch (_) {}
                setTimeout(() => { enterPending = false; }, 350);
              };

              document.addEventListener('fullscreenchange', enforce);
              document.addEventListener('webkitfullscreenchange', enforce);
              document.addEventListener('visibilitychange', enforce);
              window.addEventListener('resize', enforce);
              document.addEventListener('play', enforce, true);
              document.addEventListener('pause', enforce, true);
              const interval = setInterval(enforce, 1000);
              window.addEventListener('pagehide', () => {
                clearInterval(interval);
                window.__blblMangoFullscreenGuard = false;
              }, { once: true });
              enforce();
            })();
        """.trimIndent()

        @Volatile
        internal var debugPlaybackCapture: DebugPlaybackCapture? = null

        @Volatile
        internal var debugCurrentHlsDef: String? = null

        internal data class DebugPlaybackCapture(val url: String, val userAgent: String?)

        private val SELECT_1080P_SDR_SCRIPT = """
            (() => {
              const choose = () => {
                if (window.__blblMangoQualityRequested) return;
                const player = window.mgtvPlayer;
                const streams = player && player.dataSource && player.dataSource.videoSource && player.dataSource.videoSource.streams;
                const playable = Array.isArray(streams) ? streams.filter(stream => stream && stream.playable !== false) : [];
                const label = stream => [stream.barName, stream.labelName, stream.standardName, stream.name]
                  .map(value => String(value || '').trim().toUpperCase()).join(' ');
                const isSdr1080 = stream => /1080\s*P/.test(label(stream)) &&
                  !/(HDR|DOLBY|杜比|VIVID|真彩|\+|PLUS)/.test(label(stream));
                const target = playable.find(stream => /1080\s*P\s*SDR/.test(label(stream))) ||
                  playable.find(stream => /^1080\s*P$/.test(String(stream.standardName || '').trim().toUpperCase())) ||
                  playable.find(isSdr1080);
                if (target && player.userActions && typeof player.userActions.changeDefinition === 'function') {
                  window.__blblMangoQualityRequested = true;
                  Promise.resolve(player.userActions.changeDefinition(target)).catch(() => {});
                  return;
                }
                const attempt = window.__blblMangoQualityAttempts || 0;
                if (attempt < 20) {
                  window.__blblMangoQualityAttempts = attempt + 1;
                  setTimeout(choose, 500);
                }
              };
              choose();
            })();
        """.trimIndent()

        /** Reuses the paused video frame; only the small play control is clickable. */
        private val PAUSED_FRAME_OVERLAY_SCRIPT = """
            (() => {
              if (window.__blblPauseOverlayInstalled) return;
              window.__blblPauseOverlayInstalled = true;

              const attach = () => {
                const video = document.querySelector('video');
                if (!video || !document.body) return false;

                const overlay = document.createElement('div');
                Object.assign(overlay.style, {
                  position: 'fixed', display: 'none', alignItems: 'center', justifyContent: 'center',
                  zIndex: '2147483647', background: 'rgba(0,0,0,.24)', pointerEvents: 'none'
                });
                const button = document.createElement('button');
                button.type = 'button';
                button.setAttribute('aria-label', '继续播放');
                button.setAttribute('title', '继续播放');
                button.tabIndex = 0;
                Object.assign(button.style, {
                  width: '72px', height: '72px', borderRadius: '50%',
                  border: '2px solid rgba(255,255,255,.88)',
                  background: 'rgba(16,17,19,.72)', color: '#fff', fontSize: '30px',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  padding: '0 0 0 4px', pointerEvents: 'auto', cursor: 'pointer',
                  transition: 'none', animation: 'none', boxShadow: 'none'
                });
                button.textContent = '▶';
                overlay.appendChild(button);
                document.body.appendChild(overlay);
                let hasPlayed = !video.paused || video.currentTime > 0;

                const update = () => {
                  const rect = video.getBoundingClientRect();
                  const visible = hasPlayed && video.paused && !video.ended && rect.width > 0 && rect.height > 0;
                  overlay.style.display = visible ? 'flex' : 'none';
                  if (!visible) return;
                  overlay.style.left = rect.left + 'px';
                  overlay.style.top = rect.top + 'px';
                  overlay.style.width = rect.width + 'px';
                  overlay.style.height = rect.height + 'px';
                };
                const resume = event => {
                  event.preventDefault();
                  event.stopPropagation();
                  const player = window.mgtvPlayer;
                  if (typeof player?.userActions?.togglePlay === 'function') player.userActions.togglePlay();
                  else video.play().catch(() => {});
                };
                button.addEventListener('click', resume);
                button.addEventListener('keydown', event => {
                  if (event.key === 'Enter' || event.key === ' ') resume(event);
                });
                video.addEventListener('pause', update);
                video.addEventListener('play', () => { hasPlayed = true; update(); });
                video.addEventListener('playing', () => { hasPlayed = true; update(); });
                video.addEventListener('ended', update);
                window.addEventListener('resize', update);
                window.addEventListener('scroll', update, true);
                document.addEventListener('fullscreenchange', update);
                update();
                return true;
              };

              if (attach()) return;
              const observer = new MutationObserver(() => {
                if (attach()) observer.disconnect();
              });
              observer.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()

        /** Mango exposes both a display label (barName, e.g. "1080P SDR") and a resolution label (standardName, "1080P"). */
        private val MANGO_DEBUG_INFO_SCRIPT = """
            (() => {
              const video = document.querySelector('video');
              if (!video) return '等待视频元素…';
              const player = window.mgtvPlayer;
              const streams = player?.dataSource?.videoSource?.streams;
              const text = value => String(value == null ? '' : value).trim();
              const validDef = value => /^[1-9][0-9]*$/.test(value) ? value : '';
              const defOf = url => {
                try { return validDef(text(new URL(url, location.href).searchParams.get('def'))); } catch (_) { return ''; }
              };
              const hls = performance.getEntriesByType('resource').filter(item => /\.m3u8/i.test(item.name));
              let hlsDef = '';
              for (let i = hls.length - 1; i >= 0; i--) {
                const def = defOf(hls[i].name);
                if (def) { hlsDef = def; break; }
              }
              const configDef = validDef(text(player?.config?.currentDefinition));
              const nativeDef = validDef(text(__BLBL_NATIVE_HLS_DEF__));
              const activeDef = configDef || nativeDef || hlsDef;
              const unique = values => values.filter((value, index, list) => value && list.indexOf(value) === index);
              const options = Array.isArray(streams) ? streams.map(item => {
                const bar = text(item?.barName);
                const label = text(item?.labelName);
                const standard = text(item?.standardName);
                const name = text(item?.name);
                const labels = unique([bar, label, standard, name]);
                return {
                  def: text(item?.def),
                  bar: bar || '-',
                  label: label || '-',
                  standard: standard || '-',
                  name: name || '-',
                  display: labels[0] || '?',
                  labels: labels.join('/') || '?',
                  vip: item?.needVip === true,
                  playable: item?.playable !== false
                };
              }).filter(item => item.display !== '?') : [];
              const selected = options.find(item => item.def && item.def === activeDef);
              const quality = selected
                ? selected.display + ' | def=' + selected.def + ' | bar=' + selected.bar +
                  ' | label=' + selected.label + ' | std=' + selected.standard +
                  ' | name=' + selected.name + ' | auto=' + (player?.config?.isAutoDefinition === true)
                : (activeDef ? 'def=' + activeDef + '（未匹配到档位）' : '未知');
              const optionLines = options.map(item =>
                (item.def === activeDef ? '* ' : '  ') + item.labels +
                ' | def=' + (item.def || '?') +
                (item.vip ? ' | VIP' : '') +
                (item.playable ? '' : ' | 不可播')
              );
              let bufferedEnd = 0;
              try { if (video.buffered.length) bufferedEnd = video.buffered.end(video.buffered.length - 1); } catch (_) {}
              let totalFrames = 0;
              let droppedFrames = 0;
              try {
                const playbackQuality = video.getVideoPlaybackQuality?.();
                totalFrames = playbackQuality?.totalVideoFrames || video.webkitDecodedFrameCount || 0;
                droppedFrames = playbackQuality?.droppedVideoFrames || video.webkitDroppedFrameCount || 0;
              } catch (_) {}
              const now = performance.now();
              const previous = window.__blblMangoFrameSample;
              const elapsed = previous ? (now - previous.time) / 1000 : 0;
              const fps = elapsed > 0 && totalFrames >= previous.frames ? Math.round((totalFrames - previous.frames) / elapsed) : null;
              window.__blblMangoFrameSample = { time: now, frames: totalFrames };
              const helper = player?.userActions?.player?.fullScreenHelper;
              const full = helper?.isWebFullscreen === true || !!document.querySelector('.mgtv-player-wrap.web-fullscreen');
              const time = value => Number.isFinite(value) ? value.toFixed(1) + 's' : '-';
              return [
                'state=' + (video.paused ? 'PAUSED' : 'PLAYING') + ' ready=' + video.readyState + ' network=' + video.networkState,
                'pos=' + time(video.currentTime) + ' buf=' + time(bufferedEnd) + ' dur=' + time(video.duration),
                'video=' + video.videoWidth + 'x' + video.videoHeight + ' rate=' + video.playbackRate + ' fullscreen=' + full,
                '当前画质=' + quality,
                'def config=' + (configDef || '-') + ' native=' + (nativeDef || '-') + ' hls=' + (hlsDef || '-'),
                '可选画质=' + (optionLines.length ? '\n' + optionLines.join('\n') : '-'),
                'frames=' + totalFrames + ' dropped=' + droppedFrames + ' fps~=' + (fps === null ? '-' : fps)
              ].join('\n');
            })()
        """.trimIndent()

        private val MANGO_VIDEO_INFO_SCRIPT = """
            (() => {
              const player = window.mgtvPlayer;
              const info = player?.dataSource?.videoSource?.sourceData?.info;
              const parts = location.pathname.split('/').filter(Boolean);
              const routeProgramId = (parts[0] === 'b' || parts[0] === 'l') ? parts[1] : '';
              const videoId = parts[2] ? parts[2].replace('.html', '') : String(info?.video_id || '');
              const programId = String(player?.config?.plid || routeProgramId || info?.collection_id || videoId);
              const clipId = String(player?.config?.cid || info?.clip_id || info?.clipId || videoId);
              return { videoId: videoId, programId: programId, clipId: clipId };
            })()
        """.trimIndent()

        private val MANGO_DANMAKU_STATUS_SCRIPT = """
            (() => {
              const player = window.mgtvPlayer;
              const plugin = (player?.plugins || []).find(item => item?.name === 'DanmuPlugin');
              return plugin?.getDanmuStatus?.() || 'unknown';
            })()
        """.trimIndent()

        private val MANGO_DANMAKU_TOGGLE_SCRIPT = """
            (() => {
              const player = window.mgtvPlayer;
              const plugin = (player?.plugins || []).find(item => item?.name === 'DanmuPlugin');
              if (!plugin?.danmu) return 'unavailable';
              const isOpen = plugin.getDanmuStatus?.() === 'open';
              if (isOpen !== __BLBL_DANMAKU_ON__) plugin.danmu.toggleOpen();
              return plugin.getDanmuStatus?.() || 'unknown';
            })()
        """.trimIndent()

        private val MANGO_DANMAKU_INFO_SCRIPT = """
            (() => {
              const player = window.mgtvPlayer;
              const plugin = (player?.plugins || []).find(item => item?.name === 'DanmuPlugin');
              const fetcher = plugin?.danmu?.fetcher;
              return {
                cdnVersion: String(fetcher?.cdn_version || ''),
                interval: Number(fetcher?.interval || 60)
              };
            })()
        """.trimIndent()

        /** Returns currentTime|duration|bufferedEnd in seconds for the native progress bar. */
        private val PLAYBACK_PROGRESS_SCRIPT = """
            (() => {
              const video = document.querySelector('video');
              if (!video) return '';
              const duration = Number(video.duration);
              if (!Number.isFinite(duration) || duration <= 0) return '';
              let buffered = 0;
              try { if (video.buffered.length) buffered = video.buffered.end(video.buffered.length - 1); } catch (_) {}
              let totalFrames = 0;
              let droppedFrames = 0;
              try {
                const quality = video.getVideoPlaybackQuality?.();
                totalFrames = quality?.totalVideoFrames || video.webkitDecodedFrameCount || 0;
                droppedFrames = quality?.droppedVideoFrames || video.webkitDroppedFrameCount || 0;
              } catch (_) {}
              return String(video.currentTime) + '|' + String(duration) + '|' + String(buffered) + '|' +
                (video.paused ? '1' : '0') + '|' + String(totalFrames) + '|' + String(droppedFrames);
            })()
        """.trimIndent()

        const val MANGO_SITE = "https://www.mgtv.com"
        const val EXTRA_URL = "mango_video_url"
        const val EXTRA_START_POSITION_SEC = "mango_start_position_sec"
        const val EXTRA_START_POSITION_MS = "mango_start_position_ms"
        const val EXTRA_HAS_WEB_VIEW_RETURN = "mango_has_webview_return"
        const val EXTRA_DANMAKU_CDN_VERSION = "mango_danmaku_cdn_version"
        const val EXTRA_DANMAKU_INTERVAL_SEC = "mango_danmaku_interval_sec"
        const val EXTRA_MANGO_VIDEO_ID = "mango_video_id"
        const val EXTRA_MANGO_PROGRAM_ID = "mango_program_id"
        const val EXTRA_MANGO_CLIP_ID = "mango_clip_id"
        const val EXTRA_MOBILE_MODE = "mango_mobile_mode"
        private const val DEBUG_INFO_REFRESH_MS = 1_000L
        private const val PLAYBACK_PROGRESS_REFRESH_MS = 500L
        private const val PLAYER_CONTROLS_AUTO_HIDE_MS = 4_000L
        private const val PLAYBACK_PROGRESS_MAX = 1_000
        private val CHROME_VERSION = Regex("Chrome/([0-9.]+)")
        val EXPIRES = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }
}
