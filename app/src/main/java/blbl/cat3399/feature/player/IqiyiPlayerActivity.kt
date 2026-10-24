@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.R
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityIqiyiPlayerBinding
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Native HLS playback for iQiyi search/history items, sharing the app's EXO control styling. */
class IqiyiPlayerActivity : BaseActivity() {
    private lateinit var binding: ActivityIqiyiPlayerBinding
    private var player: ExoPlayer? = null
    private var playPause: PlayerIconButton? = null
    private var progressLoopRunning = false
    private val handler = Handler(Looper.getMainLooper())
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

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.trim().orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val startSec = intent.getLongExtra(EXTRA_START_POSITION_SEC, 0L).coerceAtLeast(0L)
        if (streamUrl.isBlank()) {
            finish()
            return
        }
        binding.tvTitle.text = title.ifBlank { getString(R.string.iqiyi_player_title) }
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        installControls(streamUrl, startSec)
    }

    private fun installControls(streamUrl: String, startSec: Long) {
        val playPauseButton = PlayerIconButton(this, R.drawable.ic_player_play, getString(R.string.player_btn_play_pause)) { togglePlayback() }
        playPause = playPauseButton
        val replay = PlayerIconButton(this, R.drawable.ic_player_replay_10, getString(R.string.player_btn_replay_10)) { seekBy(-10_000L) }
        val forward = PlayerIconButton(this, R.drawable.ic_player_forward_10, getString(R.string.player_btn_forward_10)) { seekBy(10_000L) }
        val exit = PlayerIconButton(this, R.drawable.ic_player_close, getString(R.string.iqiyi_player_exit)) { finish() }
        listOf(playPauseButton, replay, forward, exit).forEachIndexed { index, button ->
            val params = LinearLayout.LayoutParams(dp(64), dp(48))
            if (index > 0) params.marginStart = dp(4)
            binding.controlsRow.addView(button, params)
        }
        val source = android.widget.TextView(this).apply {
            text = getString(R.string.platform_iqiyi)
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.player_osd_icon_tint))
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        binding.controlsRow.addView(source, LinearLayout.LayoutParams(-2, dp(48)))

        val sessionStore = IqiyiSessionStore(applicationContext)
        val mediaClient =
            OkHttpClient.Builder()
                .cookieJar(sessionStore.cookies)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
        val dataSourceFactory =
            OkHttpDataSource.Factory(mediaClient)
                .setDefaultRequestProperties(
                    mapOf(
                        "User-Agent" to MEDIA_USER_AGENT,
                        "Referer" to "https://www.iqiyi.com/",
                    ),
                )
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(streamUrl))
        val exo =
            ExoPlayer.Builder(this)
                .build()
                .apply {
                    setMediaSource(mediaSource, startSec * 1_000L)
                    prepare()
                    playWhenReady = true
                    addListener(
                        object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                binding.loading.visibility =
                                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                                if (playbackState == Player.STATE_READY) {
                                    binding.tvError.visibility = View.GONE
                                }
                                if (playbackState == Player.STATE_ENDED) {
                                    playPause?.update(R.drawable.ic_player_play, getString(R.string.player_btn_play_pause))
                                }
                                updateProgress()
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                playPause?.update(
                                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                                    getString(R.string.player_btn_play_pause),
                                )
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
        binding.loading.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE
        playPauseButton.requestFocus()
        progressLoopRunning = true
        handler.post(progressTicker)
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
        updateProgress()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        progressLoopRunning = false
        handler.removeCallbacks(progressTicker)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "iqiyi_stream_url"
        const val EXTRA_TITLE = "iqiyi_stream_title"
        const val EXTRA_START_POSITION_SEC = "iqiyi_stream_start_sec"

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MEDIA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun intent(
            context: Context,
            streamUrl: String,
            title: String,
            startSec: Long = 0L,
        ): Intent = Intent(context, IqiyiPlayerActivity::class.java)
            .putExtra(EXTRA_STREAM_URL, streamUrl)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_START_POSITION_SEC, startSec)
    }
}
