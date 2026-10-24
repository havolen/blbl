@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.R
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.api.IqiyiApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityIqiyiPlayerBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Native HLS playback for iQiyi search/history items, sharing the app's EXO control styling. */
class IqiyiPlayerActivity : BaseActivity() {
    private lateinit var binding: ActivityIqiyiPlayerBinding
    private var player: ExoPlayer? = null
    private var playPause: PlayerIconButton? = null
    private var nextButton: PlayerIconButton? = null
    private var dataSourceFactory: OkHttpDataSource.Factory? = null
    private var iqiyiApi: IqiyiApi? = null
    private var playlist: List<PlaylistEntry> = emptyList()
    private var playlistIndex: Int = 0
    private var resolvingNext = false
    private var controlsVisible = true
    private var speedHint: TextView? = null
    private val startedAtMs = SystemClock.elapsedRealtime()
    private var perfStatsCount = 0
    private val perfStats =
        object : Runnable {
            override fun run() {
                val exo = player ?: return
                val counters = exo.videoDecoderCounters
                AppLog.i(
                    "IqiyiPerf",
                    "t=${SystemClock.elapsedRealtime() - startedAtMs}ms pos=${exo.currentPosition} " +
                        "buf=${exo.bufferedPosition} state=${exo.playbackState} rate=${exo.playbackParameters.speed} " +
                        "rendered=${counters?.renderedOutputBufferCount ?: -1} dropped=${counters?.droppedBufferCount ?: -1}",
                )
                if (++perfStatsCount < 12) handler.postDelayed(this, 1_000L)
            }
        }
    private var progressLoopRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val seekController: PlayerSeekKeyController by lazy {
        PlayerSeekKeyController(
            object : PlayerSeekKeyController.Host {
                override fun isSeekKeyActive(): Boolean = !controlsVisible || binding.progress.hasFocus()

                override fun currentPositionMs(): Long = player?.currentPosition ?: 0L

                override fun durationMs(): Long = player?.duration ?: 0L

                override fun seekTo(positionMs: Long) {
                    player?.seekTo(positionMs)
                    updateProgress()
                }

                override fun playbackSpeed(): Float = player?.playbackParameters?.speed ?: 1f

                override fun setPlaybackSpeed(speed: Float) {
                    player?.setPlaybackSpeed(speed)
                }

                override fun onSeekStep(targetMs: Long, durationMs: Long) {
                    setControlsVisible(true)
                    handler.removeCallbacks(hideControlsRunnable)
                    binding.progress.post { binding.progress.requestFocus() }
                    updateProgress()
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
    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            if (progressLoopRunning) handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIqiyiPlayerBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        window.decorView.keepScreenOn = true
        binding.root.isFocusable = true
        speedHint = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundResource(R.drawable.bg_player_controls)
            visibility = View.GONE
        }
        binding.root.addView(
            speedHint,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(24)
                marginEnd = dp(24)
            },
        )

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.trim().orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val startSec = intent.getLongExtra(EXTRA_START_POSITION_SEC, 0L).coerceAtLeast(0L)
        playlist = parsePlaylist(intent.getStringExtra(EXTRA_PLAYLIST_JSON))
        playlistIndex =
            intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
                .coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        if (streamUrl.isBlank()) {
            finish()
            return
        }
        binding.tvTitle.text = title.ifBlank { getString(R.string.iqiyi_player_title) }
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        installControls(streamUrl, startSec)
    }

    private fun installControls(streamUrl: String, startSec: Long) {
        val playPauseButton =
            PlayerIconButton(this, R.drawable.ic_player_play, getString(R.string.player_btn_play_pause)) {
                togglePlayback()
                scheduleControlsHide()
            }
        playPause = playPauseButton
        nextButton =
            PlayerIconButton(this, R.drawable.ic_player_next, getString(R.string.player_btn_next)) {
                playNextEpisode()
                scheduleControlsHide()
            }
        val exit = PlayerIconButton(this, R.drawable.ic_player_close, getString(R.string.iqiyi_player_exit)) { finish() }
        PlayerControlBar.install(
            host = binding.controlsRow,
            sourceName = getString(R.string.platform_iqiyi),
            actions = CommonPlayerActions(playPauseButton, nextButton),
            siteActions = listOf(exit),
        )
        updateNextButton()

        val sessionStore = IqiyiSessionStore(applicationContext)
        iqiyiApi = IqiyiApi(sessionStore.cookies)
        val mediaClient =
            OkHttpClient.Builder()
                .cookieJar(sessionStore.cookies)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
        dataSourceFactory =
            OkHttpDataSource.Factory(mediaClient)
                .setDefaultRequestProperties(
                    mapOf(
                        "User-Agent" to MEDIA_USER_AGENT,
                        "Referer" to "https://www.iqiyi.com/",
                    ),
                )

        // Select embedded text tracks even when the language is undetermined; some iQiyi
        // streams expose subtitles only as an "und" track.
        val trackSelector =
            DefaultTrackSelector(this).apply {
                setParameters(buildUponParameters().setSelectUndeterminedTextLanguage(true))
            }
        val exo =
            ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .build()
                .apply {
                    addListener(
                        object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                AppLog.i(
                                    "IqiyiPerf",
                                    "state=$playbackState t=${SystemClock.elapsedRealtime() - startedAtMs}ms " +
                                        "pos=${player?.currentPosition ?: -1}",
                                )
                                binding.loading.visibility =
                                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                                if (playbackState == Player.STATE_READY) {
                                    binding.tvError.visibility = View.GONE
                                }
                                if (playbackState == Player.STATE_ENDED) {
                                    playPause?.update(R.drawable.ic_player_play, getString(R.string.player_btn_play_pause))
                                    setControlsVisible(true, requestFocus = true)
                                }
                                updateProgress()
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                AppLog.i("IqiyiPerf", "playing=$isPlaying t=${SystemClock.elapsedRealtime() - startedAtMs}ms")
                                playPause?.update(
                                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                                    getString(R.string.player_btn_play_pause),
                                )
                            }

                            override fun onRenderedFirstFrame() {
                                AppLog.i("IqiyiPerf", "firstFrame t=${SystemClock.elapsedRealtime() - startedAtMs}ms")
                            }

                            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                AppLog.i("IqiyiPerf", "videoSize=${videoSize.width}x${videoSize.height}")
                            }

                            override fun onTracksChanged(tracks: Tracks) {
                                for (group in tracks.groups) {
                                    for (i in 0 until group.length) {
                                        val format = group.getTrackFormat(i)
                                        if (group.type == C.TRACK_TYPE_VIDEO) {
                                            AppLog.i(
                                                "IqiyiPerf",
                                                "video codec=${format.codecs} size=${format.width}x${format.height} " +
                                                    "bitrate=${format.bitrate} fps=${format.frameRate}",
                                            )
                                        }
                                    }
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                AppLog.w("IqiyiPlayer", "playback failed code=${error.errorCodeName} type=${error.javaClass.simpleName}")
                                binding.loading.visibility = View.GONE
                                binding.tvError.text = getString(R.string.iqiyi_player_failed, error.errorCodeName)
                                binding.tvError.visibility = View.VISIBLE
                                playPause?.update(R.drawable.ic_player_play, getString(R.string.player_btn_play_pause))
                            }
                        },
                    )
                }
        player = exo
        binding.playerView.player = exo
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        startPlayback(streamUrl, startSec)
        binding.progress.isFocusable = true
        binding.progress.isFocusableInTouchMode = true
        binding.progress.setOnFocusChangeListener { _, focused ->
            binding.progress.progressTintList = android.content.res.ColorStateList.valueOf(
                if (focused) {
                    android.graphics.Color.WHITE
                } else {
                    blbl.cat3399.core.theme.ThemeColor.resolve(
                        this,
                        R.attr.blblAccent,
                        R.color.blbl_blue,
                    )
                },
            )
        }
        playPauseButton.requestFocus()
        progressLoopRunning = true
        handler.post(progressTicker)
        scheduleControlsHide()
    }

    private fun startPlayback(url: String, startSec: Long) {
        val exo = player ?: return
        val factory = dataSourceFactory ?: return
        binding.loading.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE
        val mediaSource = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(mediaSource, startSec * 1_000L)
        exo.prepare()
        exo.playWhenReady = true
        AppLog.i(
            "IqiyiPerf",
            "prepare t=${SystemClock.elapsedRealtime() - startedAtMs}ms host=${url.substringAfter("://").substringBefore('/')}",
        )
        perfStatsCount = 0
        handler.removeCallbacks(perfStats)
        handler.postDelayed(perfStats, 1_000L)
    }

    private fun playNextEpisode() {
        val next = playlist.getOrNull(playlistIndex + 1) ?: return
        if (resolvingNext) return
        val api = iqiyiApi ?: return
        resolvingNext = true
        setControlsVisible(true)
        AppToast.show(this, "正在解析下一集")
        lifecycleScope.launch {
            try {
                val stream = api.resolveStream(next.tvId, next.videoId)
                if (stream == null) {
                    AppToast.show(this@IqiyiPlayerActivity, "下一集暂不可用")
                    return@launch
                }
                if (stream.isPreview) {
                    AppToast.show(this@IqiyiPlayerActivity, "下一集需要会员，暂无法直接播放")
                    return@launch
                }
                playlistIndex += 1
                binding.tvTitle.text = next.title
                updateNextButton()
                startPlayback(stream.url, 0L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("IqiyiPlayer", "resolve next failed: ${error.javaClass.simpleName}")
                AppToast.show(this@IqiyiPlayerActivity, "下一集解析失败")
            } finally {
                resolvingNext = false
            }
        }
    }

    private fun updateNextButton() {
        val button = nextButton ?: return
        val hasNext = playlistIndex + 1 < playlist.size
        button.visibility = if (hasNext) View.VISIBLE else View.GONE
        if (!hasNext && button.hasFocus()) playPause?.requestFocus()
    }

    private fun showSpeedHint(text: String) {
        speedHint?.text = text
        speedHint?.visibility = View.VISIBLE
    }

    private fun hideSpeedHint() {
        speedHint?.visibility = View.GONE
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible) handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    private fun setControlsVisible(visible: Boolean, requestFocus: Boolean = false) {
        handler.removeCallbacks(hideControlsRunnable)
        controlsVisible = visible
        binding.bottomPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            if (requestFocus) playPause?.requestFocus()
            scheduleControlsHide()
        } else if (currentFocus != null) {
            binding.root.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (seekController.handle(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode
        if (!controlsVisible) {
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
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0L } ?: 0L
        val position = p.currentPosition.coerceAtLeast(0L)
        binding.tvCurrentTime.text = formatTime(position)
        binding.tvTotalTime.text = if (duration > 0L) formatTime(duration) else "--:--"
        binding.progress.progress =
            if (duration > 0L) ((position * 1000L) / duration).toInt().coerceIn(0, 1000) else 0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        seekController.release()
        progressLoopRunning = false
        handler.removeCallbacks(progressTicker)
        handler.removeCallbacks(hideControlsRunnable)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private data class PlaylistEntry(
        val tvId: Long,
        val videoId: String,
        val title: String,
    )

    companion object {
        const val EXTRA_STREAM_URL = "iqiyi_stream_url"
        const val EXTRA_TITLE = "iqiyi_stream_title"
        const val EXTRA_START_POSITION_SEC = "iqiyi_stream_start_sec"
        const val EXTRA_PLAYLIST_JSON = "iqiyi_stream_playlist"
        const val EXTRA_PLAYLIST_INDEX = "iqiyi_stream_playlist_index"

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val CONTROLS_HIDE_DELAY_MS = 4_000L
        private const val MEDIA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun intent(
            context: Context,
            streamUrl: String,
            title: String,
            startSec: Long = 0L,
            playlistJson: String? = null,
            playlistIndex: Int = 0,
        ): Intent =
            Intent(context, IqiyiPlayerActivity::class.java)
                .putExtra(EXTRA_STREAM_URL, streamUrl)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_START_POSITION_SEC, startSec)
                .putExtra(EXTRA_PLAYLIST_JSON, playlistJson)
                .putExtra(EXTRA_PLAYLIST_INDEX, playlistIndex)

        private fun parsePlaylist(raw: String?): List<PlaylistEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val tvId = item.optLong("tvId")
                    val videoId = item.optString("videoId").trim()
                    val title = item.optString("title").trim()
                    if (tvId <= 0L || videoId.isBlank()) null else PlaylistEntry(tvId, videoId, title)
                }
            }.getOrDefault(emptyList())
        }
    }
}
