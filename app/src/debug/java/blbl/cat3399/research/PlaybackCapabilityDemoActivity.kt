package blbl.cat3399.research

import android.app.Activity
import android.content.Intent
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.MangoVideoActivity

/** Debug-only probe for local decoder size/rate support; it performs no network or playback work. */
class PlaybackCapabilityDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = buildReport()
        Log.i(TAG, report)
        val reportView = TextView(this).apply {
            text = report
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.START
            setPadding(24, 24, 24, 24)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(18, 19, 21))
                addView(
                    Button(this@PlaybackCapabilityDemoActivity).apply {
                        text = "播放公开 B 站测试视频"
                        setOnClickListener {
                            startActivity(Intent(this@PlaybackCapabilityDemoActivity, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_BVID, TEST_BVID))
                        }
                    },
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(
                    Button(this@PlaybackCapabilityDemoActivity).apply {
                        text = "打开芒果中餐厅网页播放器"
                        setOnClickListener {
                            startActivity(Intent(this@PlaybackCapabilityDemoActivity, MangoVideoActivity::class.java)
                                .putExtra(MangoVideoActivity.EXTRA_URL, MANGO_TEST_URL))
                        }
                    },
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(
                    ScrollView(this@PlaybackCapabilityDemoActivity).apply { addView(reportView) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun buildReport(): String = buildString {
        appendLine("Playback capability demo (device codecs only)")
        appendLine("model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
        appendLine("target=1920x1080 at 24/30/60 fps; HDR signaling is not inferred from resolution support")
        for (mime in VIDEO_MIMES) {
            appendLine("[$mime]")
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            if (decoders.isEmpty()) {
                appendLine("  no regular decoder reported")
                continue
            }
            decoders.forEach { decoder ->
                val caps = runCatching { decoder.getCapabilitiesForType(mime) }.getOrNull()
                val video = caps?.videoCapabilities
                val support = listOf(24, 30, 60).joinToString { fps ->
                    "$fps=${video?.let { runCatching { it.areSizeAndRateSupported(1920, 1080, fps.toDouble()) }.getOrDefault(false) } == true}"
                }
                val acceleration = if (Build.VERSION.SDK_INT >= 29) {
                    "hardware=${decoder.isHardwareAccelerated} software=${decoder.isSoftwareOnly}"
                } else {
                    "acceleration=not-reported-before-api-29"
                }
                appendLine("  ${decoder.name}: $support $acceleration")
                caps?.profileLevels?.takeIf { it.isNotEmpty() }?.let { levels ->
                    appendLine("    profiles=${levels.joinToString { "${it.profile}/${it.level}" }}")
                }
            }
        }
        append("A capability result does not prove the server supplied that quality or that a stream rendered successfully.")
    }

    private companion object {
        const val TAG = "PlaybackDemo"
        const val TEST_BVID = "BV1fu411n7aD"
        const val MANGO_TEST_URL = "https://www.mgtv.com/s/19898605.html?lastp=codex_test"
        val VIDEO_MIMES = listOf("video/avc", "video/hevc", "video/av01")
    }
}
