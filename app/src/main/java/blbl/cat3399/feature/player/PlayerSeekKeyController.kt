package blbl.cat3399.feature.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Shared D-pad seek behavior for every site player.
 *
 * - Short press LEFT/RIGHT: seek by [STEP_MS] (20 seconds).
 * - Long press RIGHT: play at [HOLD_SPEED] (3x) until the key is released.
 * - The first press reveals the controls and puts focus on the progress bar, so the user can
 *   see where the activated seek point is.
 */
internal class PlayerSeekKeyController(
    private val host: Host,
    private val stepMs: Long = STEP_MS,
    private val holdSpeed: Float = HOLD_SPEED,
) {
    interface Host {
        /** True only when the current focus/controls state should turn LEFT/RIGHT into a seek. */
        fun isSeekKeyActive(): Boolean

        fun currentPositionMs(): Long

        fun durationMs(): Long

        fun seekTo(positionMs: Long)

        fun playbackSpeed(): Float

        fun setPlaybackSpeed(speed: Float)

        /** Show the controls (if needed) and focus the progress bar. */
        fun onSeekStep(targetMs: Long, durationMs: Long)

        fun onHoldSpeedStart(speed: Float)

        fun onHoldSpeedStop()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingHold: Runnable? = null
    private var holding = false
    private var previousSpeed = 1f
    private var handledKeyCode = 0

    fun handle(event: KeyEvent): Boolean {
        val direction = directionOf(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return handledKeyCode == event.keyCode
                if (!host.isSeekKeyActive()) return false
                handledKeyCode = event.keyCode
                val duration = host.durationMs().coerceAtLeast(0L)
                val rawTarget = host.currentPositionMs() + direction * stepMs
                val target = if (duration > 0L) rawTarget.coerceIn(0L, duration) else rawTarget.coerceAtLeast(0L)
                host.seekTo(target)
                host.onSeekStep(target, duration)
                if (direction > 0) {
                    previousSpeed = host.playbackSpeed().takeIf { it.isFinite() && it > 0f } ?: 1f
                    val runnable =
                        Runnable {
                            pendingHold = null
                            holding = true
                            host.setPlaybackSpeed(holdSpeed)
                            host.onHoldSpeedStart(holdSpeed)
                        }
                    pendingHold = runnable
                    handler.postDelayed(runnable, HOLD_DELAY_MS)
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                if (handledKeyCode != event.keyCode) return false
                handledKeyCode = 0
                pendingHold?.let {
                    handler.removeCallbacks(it)
                    pendingHold = null
                }
                if (holding) {
                    holding = false
                    host.setPlaybackSpeed(previousSpeed)
                    host.onHoldSpeedStop()
                }
                return true
            }
        }
        return false
    }

    fun release() {
        pendingHold?.let { handler.removeCallbacks(it) }
        pendingHold = null
        if (holding) {
            holding = false
            host.setPlaybackSpeed(previousSpeed)
            host.onHoldSpeedStop()
        }
        handledKeyCode = 0
    }

    companion object {
        const val STEP_MS: Long = 20_000L
        const val HOLD_SPEED: Float = 3f
        private const val HOLD_DELAY_MS = 450L

        fun directionOf(keyCode: Int): Int? =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -1
                else -> null
            }
    }
}
