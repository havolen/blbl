package blbl.cat3399.feature.player

import android.media.audiofx.LoudnessEnhancer
import blbl.cat3399.core.log.AppLog

/**
 * Applies a fixed loudness gain to a player audio session.
 *
 * The TV's system volume is already at maximum in this scenario; Android players cannot exceed
 * volume 1.0, so the only in-app option is an audio effect. [LoudnessEnhancer] is available since
 * API 19 and works on the ExoPlayer audio session as well as the global output mix (session 0).
 */
internal class PlayerVolumeBoost(
    private val gainMb: Int = DEFAULT_GAIN_MB,
) {
    private var enhancer: LoudnessEnhancer? = null

    fun attach(audioSessionId: Int): Boolean {
        release()
        return runCatching {
            val effect = LoudnessEnhancer(audioSessionId)
            effect.setTargetGain(gainMb)
            effect.enabled = true
            enhancer = effect
            AppLog.i("VolumeBoost", "attached session=$audioSessionId gain=${gainMb / 100}dB")
            true
        }.getOrElse { error ->
            AppLog.w("VolumeBoost", "attach failed session=$audioSessionId", error)
            false
        }
    }

    fun release() {
        runCatching { enhancer?.release() }
        enhancer = null
    }

    companion object {
        /** +6 dB is roughly 2x amplitude; loud enough to be noticeable without hard clipping. */
        const val DEFAULT_GAIN_MB: Int = 600
    }
}
