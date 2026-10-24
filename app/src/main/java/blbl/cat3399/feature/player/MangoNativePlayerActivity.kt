@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.account.MangoSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.theme.ThemePresets
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.core.ui.UiDensity
import blbl.cat3399.core.ui.userScaledContext
import blbl.cat3399.feature.player.DanmakuToggleButton
import blbl.cat3399.feature.player.EpisodePickerOverlay
import blbl.cat3399.feature.player.MangoCatalog
import blbl.cat3399.feature.player.MangoEpisode
import blbl.cat3399.feature.player.MangoCatalogRepository
import blbl.cat3399.feature.player.MangoVideoActivity
import blbl.cat3399.feature.player.PlayerIconButton
import blbl.cat3399.feature.player.applyPlayerPillStyle
import blbl.cat3399.feature.player.danmaku.DanmakuConfig
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.danmaku.DanmakuView
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Native Mango HLS player used when the system WebView is too old. */
class MangoNativePlayerActivity : Activity() {
    private lateinit var uiContext: Context
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var danmakuView: DanmakuView
    private lateinit var danmakuToggle: DanmakuToggleButton
    private var debugInfoOverlay: TextView? = null
    private var speedHint: TextView? = null
    private var controlsHost: LinearLayout? = null
    private var controlsScroll: android.widget.HorizontalScrollView? = null
    private lateinit var bottomPanel: LinearLayout
    private var controlsVisible = true
    private var episodePickerLoading = false
    private var playPauseIcon: PlayerIconButton? = null
    private var lastPerfLogAt: Long = 0L
    private var episodeOverlay: EpisodePickerOverlay? = null
    private var currentCatalog: MangoCatalog? = null
    private val catalogRepository = MangoCatalogRepository()
    private val catalogScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var danmakuEnabled = true
    private var danmakuCount = 0
    private var danmakuCdnVersion = ""
    private var danmakuIntervalSec = 60
    private var danmakuLoadStarted = false
    @Volatile private var danmakuLoadGeneration = 0
    private var danmakuExecutor: ExecutorService? = null
    private var httpClient: OkHttpClient? = null
    private var mediaUserAgent = ""
    private var requestedStartPositionMs = 0L
    private var currentVideoId = ""
    private var currentProgramId = ""
    private var currentClipId = ""
    private var resumePositionChecked = false
    private val danmakuConfig = DanmakuConfig(
        enabled = true,
        opacity = 1f,
        textSizeSp = 16f,
        fontWeight = DanmakuFontWeight.Bold,
        strokeWidthPx = 4,
        speedLevel = 4,
        area = 1f,
        laneDensity = DanmakuLaneDensity.Standard,
        showHighLikeIcon = true,
    )
    private val seekController: PlayerSeekKeyController by lazy {
        PlayerSeekKeyController(
            object : PlayerSeekKeyController.Host {
                override fun isSeekKeyActive(): Boolean = !controlsVisible || progressBar.hasFocus()

                override fun currentPositionMs(): Long = player?.currentPosition ?: 0L

                override fun durationMs(): Long = player?.duration ?: 0L

                override fun seekTo(positionMs: Long) {
                    player?.seekTo(positionMs)
                    updateStatus()
                }

                override fun playbackSpeed(): Float = player?.playbackParameters?.speed ?: 1f

                override fun setPlaybackSpeed(speed: Float) {
                    player?.setPlaybackSpeed(speed)
                }

                override fun onSeekStep(targetMs: Long, durationMs: Long) {
                    showControls(requestFocus = false)
                    handler.removeCallbacks(hideControlsRunnable)
                    progressBar.post { progressBar.requestFocus() }
                    updateStatus()
                }

                override fun onHoldSpeedStart(speed: Float) {
                    handler.removeCallbacks(hideControlsRunnable)
                    showSpeedHint("${speed.toInt()}x 快进")
                }

                override fun onHoldSpeedStop() {
                    hideSpeedHint()
                    scheduleControlsHide()
                }
            },
        )
    }

    private val refreshStatus = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiDensity.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePresets.applyTo(this)
        super.onCreate(savedInstanceState)
        uiContext = userScaledContext()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        window.decorView.keepScreenOn = true
        danmakuCdnVersion = intent.getStringExtra(MangoVideoActivity.EXTRA_DANMAKU_CDN_VERSION).orEmpty()
        danmakuIntervalSec = intent.getIntExtra(MangoVideoActivity.EXTRA_DANMAKU_INTERVAL_SEC, 60).coerceAtLeast(1)
        currentVideoId = intent.getStringExtra(MangoVideoActivity.EXTRA_MANGO_VIDEO_ID).orEmpty()
        currentProgramId = intent.getStringExtra(MangoVideoActivity.EXTRA_MANGO_PROGRAM_ID).orEmpty()
        currentClipId = intent.getStringExtra(MangoVideoActivity.EXTRA_MANGO_CLIP_ID).orEmpty()
        val container = FrameLayout(uiContext).apply {
            setBackgroundColor(Color.BLACK)
        }
        val root = FrameLayout(uiContext).apply {
            setBackgroundColor(Color.BLACK)
        }
        container.addView(root, FrameLayout.LayoutParams(-1, -1))
        playerView = PlayerView(uiContext).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
        }
        val videoFrame = FrameLayout(uiContext)
        videoFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        danmakuView = DanmakuView(uiContext).apply {
            isFocusable = false
            isClickable = false
            setPositionProvider { player?.currentPosition ?: 0L }
            setIsPlayingProvider { player?.isPlaying == true }
            setPlaybackSpeedProvider { player?.playbackParameters?.speed ?: 1f }
            setConfigProvider { danmakuConfig }
            visibility = if (danmakuEnabled) View.VISIBLE else View.GONE
        }
        videoFrame.addView(danmakuView, FrameLayout.LayoutParams(-1, -1))
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1))
        speedHint = TextView(uiContext).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundResource(R.drawable.bg_player_controls)
            visibility = View.GONE
        }
        root.addView(
            speedHint,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(24)
                marginEnd = dp(24)
            },
        )

        currentTime = TextView(uiContext).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            text = "00:00"
        }
        totalTime = TextView(uiContext).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = "--:--"
        }
        progressBar = ProgressBar(uiContext, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(
                ThemeColor.resolve(this@MangoNativePlayerActivity, R.attr.blblAccent, R.color.blbl_blue),
            )
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(72, 255, 255, 255))
            minimumHeight = 0
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, focused ->
                scaleY = if (focused) 3f else 1f
                progressTintList = ColorStateList.valueOf(
                    if (focused) {
                        Color.WHITE
                    } else {
                        ThemeColor.resolve(this@MangoNativePlayerActivity, R.attr.blblAccent, R.color.blbl_blue)
                    },
                )
            }
        }
        danmakuToggle = DanmakuToggleButton(uiContext).apply {
            onToggle = { enabled ->
                danmakuEnabled = enabled
                danmakuView.visibility = if (enabled) View.VISIBLE else View.GONE
                AppLog.i("MangoExo", "danmaku toggle=$enabled")
            }
        }
        val progressRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(2))
            addView(currentTime, LinearLayout.LayoutParams(dp(84), -2))
            addView(
                progressBar,
                LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                },
            )
            addView(totalTime, LinearLayout.LayoutParams(dp(84), -2))
        }
        bottomPanel = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_controls)
            clipChildren = false
            clipToPadding = false
        }
        bottomPanel.addView(progressRow, LinearLayout.LayoutParams(-1, -2))

        val playPause = PlayerIconButton(uiContext, R.drawable.ic_player_pause, "暂停") { togglePlayback() }
        playPauseIcon = playPause
        val next = PlayerIconButton(uiContext, R.drawable.ic_player_next, "下一集") { playNextEpisode() }
        val playlist = PlayerIconButton(uiContext, R.drawable.ic_player_playlist, "播放列表") { showEpisodePicker() }
        val debugInfo = PlayerIconButton(uiContext, R.drawable.ic_player_debug, "调试信息") { toggleDebugInfo() }
        val hasWebViewReturn = intent.getBooleanExtra(MangoVideoActivity.EXTRA_HAS_WEB_VIEW_RETURN, false)
        val controls = LinearLayout(uiContext).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        controlsScroll = PlayerControlBar.install(
            host = controls,
            sourceName = getString(R.string.player_source_mango),
            actions = CommonPlayerActions(playPause, next, playlist, danmakuToggle),
            siteActions = buildList {
                add(debugInfo)
                if (hasWebViewReturn) add(playerButton("WebView 播放器") { finish() })
            },
        )
        bottomPanel.addView(controls, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
        )
        controlsHost = controls
        debugInfoOverlay = TextView(uiContext).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.color.player_overlay_bg)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        container.addView(
            debugInfoOverlay,
            FrameLayout.LayoutParams(dp(620), -2, Gravity.TOP or Gravity.START).apply {
                marginStart = dp(16)
                topMargin = dp(16)
            },
        )
        setContentView(container)
        episodeOverlay = EpisodePickerOverlay(
            context = uiContext,
            parent = container,
            loadEpisodes = { season, part ->
                val catalog = currentCatalog
                if (catalog == null) emptyList() else catalogRepository.loadEpisodes(catalog, season, part)
            },
            onSelect = { episode -> playEpisodeInWebView(episode) },
        )

        val testStreamUrl = intent.getStringExtra("mango_test_stream_url").orEmpty()
        val capture = MangoVideoActivity.debugPlaybackCapture
        val playbackUrl = testStreamUrl.ifBlank { capture?.url.orEmpty() }
        val playbackUserAgent = if (testStreamUrl.isNotBlank()) {
            intent.getStringExtra("mango_test_user_agent")
        } else {
            capture?.userAgent
        }
        if (playbackUrl.isBlank()) {
            AppToast.show(this, "没有可播放的芒果 HLS 地址")
            controls.getChildAt(0)?.requestFocus()
            return
        }
        requestedStartPositionMs = if (intent.hasExtra(MangoVideoActivity.EXTRA_START_POSITION_MS)) {
            intent.getLongExtra(MangoVideoActivity.EXTRA_START_POSITION_MS, 0L).coerceAtLeast(0L)
        } else {
            intent.getLongExtra(MangoVideoActivity.EXTRA_START_POSITION_SEC, 0L)
                .coerceAtLeast(0L)
                .coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
        }
        startPlayback(playbackUrl, playbackUserAgent, requestedStartPositionMs)
        controls.getChildAt(0)?.let { it as? android.widget.HorizontalScrollView }
            ?.let(PlayerControlBar::focusStart)
        handler.post(refreshStatus)
    }

    private fun playNextEpisode() {
        if (currentVideoId.isBlank() || currentClipId.isBlank()) {
            AppToast.show(this, "暂无下一集信息")
            return
        }
        AppToast.show(this, "正在查找下一集…")
        catalogScope.launch {
            val catalog = runCatching { catalogRepository.loadCatalog(currentVideoId, currentClipId) }.getOrNull()
                ?: run {
                    AppToast.show(this@MangoNativePlayerActivity, "下一集加载失败")
                    return@launch
                }
            val season = catalog.seasons.firstOrNull { it.clipId == catalog.currentSeasonClipId }
                ?: catalog.seasons.firstOrNull()
                ?: return@launch
            val part = season.parts.firstOrNull { !it.extras && it.name == "正片" }
                ?: season.parts.firstOrNull { !it.extras }
                ?: return@launch
            val episodes = runCatching { catalogRepository.loadEpisodes(catalog, season, part) }.getOrDefault(emptyList())
            val index = episodes.indexOfFirst { it.videoId == currentVideoId }
            val next = episodes.getOrNull(index + 1)
            if (index < 0 || next == null) {
                AppToast.show(this@MangoNativePlayerActivity, "已经是最后一集")
                return@launch
            }
            startActivity(
                Intent(this@MangoNativePlayerActivity, MangoVideoActivity::class.java)
                    .putExtra(MangoVideoActivity.EXTRA_URL, next.pageUrl),
            )
            finish()
        }
    }

    private fun showEpisodePicker() {
        val videoId = intent.getStringExtra(MangoVideoActivity.EXTRA_MANGO_VIDEO_ID).orEmpty()
        val clipId = intent.getStringExtra(MangoVideoActivity.EXTRA_MANGO_CLIP_ID).orEmpty()
        if (videoId.isBlank() || clipId.isBlank()) {
            AppToast.show(this, "当前视频未获取到芒果选集信息")
            return
        }
        AppToast.show(this, "正在加载选集…")
        episodePickerLoading = true
        handler.removeCallbacks(hideControlsRunnable)
        catalogScope.launch {
            val catalog = runCatching { catalogRepository.loadCatalog(videoId, clipId) }.getOrNull()
            if (catalog == null || catalog.seasons.isEmpty()) {
                AppToast.show(this@MangoNativePlayerActivity, "选集加载失败，请重试")
                episodePickerLoading = false
                scheduleControlsHide()
                return@launch
            }
            episodePickerLoading = false
            currentCatalog = catalog
            episodeOverlay?.show(catalog, videoId, clipId)
        }
    }

    private fun playEpisodeInWebView(episode: MangoEpisode) {
        startActivity(
            Intent(this, MangoVideoActivity::class.java)
                .putExtra(MangoVideoActivity.EXTRA_URL, episode.pageUrl),
        )
        finish()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (seekController.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            val overlay = episodeOverlay
            if (overlay != null && overlay.isShowing) {
                overlay.dismiss()
                showControls(requestFocus = true)
                return true
            }
            if (controlsVisible) hideControls() else finish()
            return true
        }
        if (isDpadKey(keyCode)) {
            if (!controlsVisible) {
                showControls(requestFocus = true)
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    showEpisodePicker()
                } else {
                    controlsHost?.getChildAt(0)?.let { it as? android.widget.HorizontalScrollView }
                        ?.let(PlayerControlBar::focusStart)
                }
                return true
            }
            scheduleControlsHide()
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun playerButton(label: String, onClick: () -> Unit): Button = Button(uiContext).apply {
        text = label
        isFocusable = true
        isFocusableInTouchMode = true
        applyPlayerPillStyle()
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun togglePlayback() {
        player?.let { it.playWhenReady = !it.playWhenReady }
        showControls()
        updateStatus()
        scheduleControlsHide()
    }

    private fun toggleDebugInfo() {
        val overlay = debugInfoOverlay ?: return
        overlay.visibility = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateStatus()
    }

    private fun loadDanmaku(exo: ExoPlayer) {
        val durationMs = exo.duration.takeIf { it > 0L } ?: return
        val client = httpClient ?: return
        val intervalSec = danmakuIntervalSec.coerceAtLeast(1)
        val userAgent = mediaUserAgent
        val generation = ++danmakuLoadGeneration
        danmakuExecutor?.shutdownNow()
        val executor = Executors.newFixedThreadPool(3)
        danmakuExecutor = executor
        val session = MangoSessionStore(applicationContext)
        executor.submit {
            val config = MangoDanmakuApi.fetchConfig(
                client = client,
                session = session,
                videoId = currentVideoId,
                programId = currentProgramId,
                clipId = currentClipId,
                userAgent = userAgent,
                fallbackIntervalSec = intervalSec,
            )
            val cdnVersion = config?.cdnVersion ?: danmakuCdnVersion.trim().trim('/').takeIf { it.isNotBlank() }
            val actualIntervalSec = config?.intervalSec ?: intervalSec
            val cdnHosts = config?.cdnHosts.orEmpty()
            if (generation != danmakuLoadGeneration || Thread.currentThread().isInterrupted) return@submit
            val chunkCount = ((durationMs + actualIntervalSec * 1_000L - 1L) / (actualIntervalSec * 1_000L))
                .toInt()
                .coerceIn(1, MAX_DANMAKU_CHUNKS)
            AppLog.i("MangoDanmaku", "start durationMs=$durationMs chunks=$chunkCount config=${config != null}")

            val futures = (0 until chunkCount).map { index ->
                executor.submit(
                    Callable {
                        if (generation != danmakuLoadGeneration || Thread.currentThread().isInterrupted) {
                            emptyList()
                        } else {
                            val cdnItems = cdnVersion?.let {
                                MangoDanmakuApi.fetchCdnChunk(
                                    client, cdnHosts, it, index, durationMs, actualIntervalSec, userAgent,
                                )
                            }
                            cdnItems ?: MangoDanmakuApi.fetchServerChunk(
                                client = client,
                                session = session,
                                videoId = currentVideoId,
                                programId = currentProgramId,
                                clipId = currentClipId,
                                index = index,
                                durationMs = durationMs,
                                intervalSec = actualIntervalSec,
                                userAgent = userAgent,
                            ).orEmpty()
                        }
                    },
                )
            }
            executor.shutdown()
            val items = ArrayList<Danmaku>()
            futures.forEach { future -> runCatching { future.get() }.getOrNull()?.let(items::addAll) }
            val unique = items
                .distinctBy { item -> item.dmid ?: ((item.timeMs.toLong() shl 20) xor item.text.hashCode().toLong()) }
                .filter { it.timeMs.toLong() <= durationMs }
                .sortedBy { it.timeMs }
            runOnUiThread {
                if (generation != danmakuLoadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                danmakuCount = unique.size
                danmakuView.setDanmakus(unique)
                if (unique.isEmpty()) {
                    AppLog.w("MangoDanmaku", "loaded empty chunks=$chunkCount config=${config != null}")
                } else {
                    AppLog.i("MangoDanmaku", "loaded count=${unique.size} chunks=$chunkCount config=${config != null}")
                }
            }
        }
    }

    private fun startPlayback(url: String, capturedUserAgent: String?, startPositionMs: Long) {
        val session = MangoSessionStore(applicationContext)
        val client = OkHttpClient.Builder()
            .cookieJar(session.cookies)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val userAgent = capturedUserAgent?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
        httpClient = client
        mediaUserAgent = userAgent
        val source = HlsMediaSource.Factory(
            OkHttpDataSource.Factory(client)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(
                    mapOf(
                        "Referer" to "https://www.mgtv.com/",
                        "Origin" to "https://www.mgtv.com",
                    ),
                ),
        ).createMediaSource(MediaItem.fromUri(url))
        // Select embedded text tracks even when their language is undetermined; Mango's HLS
        // subtitles are usually tagged "und", and the default selector would leave them off.
        val trackSelector =
            DefaultTrackSelector(this).apply {
                setParameters(
                    buildUponParameters()
                        .setSelectUndeterminedTextLanguage(true)
                        .setPreferredTextLanguage("zh"),
                )
            }
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build().also { exo ->
            exo.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            ensureResumePosition(exo)
                            if (!danmakuLoadStarted && exo.duration > 0L) {
                                danmakuLoadStarted = true
                                loadDanmaku(exo)
                            }
                        }
                        updateStatus()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                        val selected = textGroups.count { group ->
                            (0 until group.length).any { group.isTrackSelected(it) }
                        }
                        AppLog.i("MangoSub", "textGroups=${textGroups.size} selectedGroups=$selected")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateStatus()
                        if (isPlaying) scheduleControlsHide() else showControls()
                    }
                    override fun onVideoSizeChanged(videoSize: VideoSize) = updateStatus()
                    override fun onPlayerError(error: PlaybackException) {
                        AppToast.show(this@MangoNativePlayerActivity, "ExoPlayer: ${error.errorCodeName}")
                    }
                },
            )
            playerView.player = exo
            exo.setMediaSource(source, startPositionMs)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val position = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        exo.seekTo(position)
        showControls()
        updateStatus()
        scheduleControlsHide()
    }

    private fun showSpeedHint(text: String) {
        speedHint?.text = text
        speedHint?.visibility = View.VISIBLE
    }

    private fun hideSpeedHint() {
        speedHint?.visibility = View.GONE
    }

    private fun showControls(requestFocus: Boolean = false) {
        controlsVisible = true
        bottomPanel.visibility = View.VISIBLE
        if (requestFocus) controlsScroll?.post { controlsScroll?.let(PlayerControlBar::focusStart) }
        scheduleControlsHide()
    }

    private fun hideControls() {
        controlsVisible = false
        bottomPanel.visibility = View.GONE
        handler.removeCallbacks(hideControlsRunnable)
    }

    private val hideControlsRunnable = Runnable {
        if (player?.isPlaying == true && episodeOverlay?.isShowing != true) hideControls()
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && player?.isPlaying == true && episodeOverlay?.isShowing != true && !episodePickerLoading) {
            handler.postDelayed(hideControlsRunnable, PLAYER_CONTROLS_AUTO_HIDE_MS)
        }
    }

    private fun isDpadKey(keyCode: Int): Boolean =
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER

    private fun updateStatus() {
        val exo = player ?: return
        val positionMs = exo.currentPosition.coerceAtLeast(0L)
        val durationMs = exo.duration.takeIf { it > 0L }
        currentTime.text = formatTime(positionMs)
        totalTime.text = formatTime(durationMs)
        playPauseIcon?.update(
            if (exo.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
            if (exo.isPlaying) "暂停" else "播放",
        )
        val overlay = debugInfoOverlay
        if (overlay?.visibility == View.VISIBLE) {
            val counters = exo.videoDecoderCounters
            val videoWidth = exo.videoSize.width
            val videoHeight = exo.videoSize.height
            val quality = when {
                videoHeight >= 2160 -> "4K"
                videoHeight >= 1080 -> "1080P"
                videoHeight >= 720 -> "720P"
                videoHeight >= 480 -> "480P"
                videoHeight > 0 -> "${videoHeight}P"
                else -> "--"
            }
            val state = when {
                exo.playbackState == Player.STATE_BUFFERING -> "缓冲中"
                exo.isPlaying -> "播放中"
                else -> "暂停"
            }
            overlay.text = buildString {
                append("EXO 播放器\n")
                append("状态: ").append(state).append('\n')
                append("画面: ").append(videoWidth).append('×').append(videoHeight)
                append(" (").append(quality).append(")\n")
                append("进度: ").append(formatTime(positionMs)).append(" / ").append(formatTime(durationMs)).append('\n')
                append("丢帧: ").append(counters?.droppedBufferCount ?: -1)
                append(" / ").append(counters?.renderedOutputBufferCount ?: -1).append('\n')
                append("弹幕: ").append(danmakuCount).append(if (danmakuEnabled) " (开)" else " (关)")
            }
        }
        val nowUptime = SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG && nowUptime - lastPerfLogAt >= 5_000L) {
            lastPerfLogAt = nowUptime
            val counters = exo.videoDecoderCounters?.also { it.ensureUpdated() }
            AppLog.i(
                "MangoPerf",
                "exo pos=${exo.currentPosition}ms rendered=${counters?.renderedOutputBufferCount ?: -1} " +
                    "dropped=${counters?.droppedBufferCount ?: -1} size=${exo.videoSize.width}x${exo.videoSize.height} " +
                    "playing=${exo.isPlaying}",
            )
        }
        if (durationMs == null) {
            progressBar.progress = 0
            progressBar.secondaryProgress = 0
        } else {
            progressBar.progress = ((positionMs.toDouble() / durationMs) * PROGRESS_MAX)
                .toInt()
                .coerceIn(0, PROGRESS_MAX)
            progressBar.secondaryProgress = ((exo.bufferedPosition.coerceAtLeast(0L).toDouble() / durationMs) * PROGRESS_MAX)
                .toInt()
                .coerceIn(progressBar.progress, PROGRESS_MAX)
        }
    }

    private fun formatTime(valueMs: Long?): String {
        if (valueMs == null || valueMs < 0L) return "--:--"
        val totalSeconds = valueMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun ensureResumePosition(exo: ExoPlayer) {
        if (resumePositionChecked || requestedStartPositionMs <= 0L || exo.duration <= requestedStartPositionMs) return
        resumePositionChecked = true
        if (exo.currentPosition + RESUME_POSITION_TOLERANCE_MS < requestedStartPositionMs) {
            exo.seekTo(requestedStartPositionMs)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        handler.removeCallbacks(hideControlsRunnable)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (player != null) {
            handler.removeCallbacks(refreshStatus)
            handler.post(refreshStatus)
            scheduleControlsHide()
        }
    }

    override fun onDestroy() {
        seekController.release()
        episodeOverlay?.release()
        catalogScope.cancel()
        handler.removeCallbacks(refreshStatus)
        handler.removeCallbacks(hideControlsRunnable)
        danmakuLoadGeneration++
        danmakuExecutor?.shutdownNow()
        danmakuExecutor = null
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * uiContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val STATUS_REFRESH_MS = 500L
        const val PLAYER_CONTROLS_AUTO_HIDE_MS = 4_000L
        const val RESUME_POSITION_TOLERANCE_MS = 2_000L
        const val PROGRESS_MAX = 1_000
        const val MAX_DANMAKU_CHUNKS = 2_000
    }
}
