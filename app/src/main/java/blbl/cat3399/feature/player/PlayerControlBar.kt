package blbl.cat3399.feature.player

import android.animation.AnimatorInflater
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import blbl.cat3399.R

/** Common playback actions are always presented in the same order by every site player. */
internal data class CommonPlayerActions(
    val playPause: View,
    val next: View? = null,
    val playlist: View? = null,
    val danmaku: View? = null,
)

/** Shared, scrollable playback strip. Site-specific actions follow the single site label. */
internal object PlayerControlBar {
    fun install(
        host: LinearLayout,
        sourceName: String,
        actions: CommonPlayerActions,
        siteActions: List<View>,
    ): HorizontalScrollView {
        host.removeAllViews()
        host.orientation = LinearLayout.HORIZONTAL
        host.gravity = Gravity.CENTER_VERTICAL
        host.clipChildren = false
        host.clipToPadding = false

        val row = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(host.context, 8), 0, dp(host.context, 8), 0)
        }
        // Seeking lives on the D-pad, so the strip only carries actions that a remote cannot do.
        val common = listOfNotNull(actions.playPause, actions.next, actions.playlist, actions.danmaku)
        common.forEachIndexed { index, view ->
            (view.parent as? ViewGroup)?.removeView(view)
            if (view is ImageButton) styleCommonIcon(view)
            val params = LinearLayout.LayoutParams(BUTTON_SIZE_DP.dp(host.context), BUTTON_SIZE_DP.dp(host.context))
            if (index > 0) params.marginStart = dp(host.context, 6)
            row.addView(view, params)
        }

        val sourceLabel = TextView(host.context).apply {
            text = sourceName
            textSize = 16f
            setTextColor(ContextCompat.getColorStateList(context, R.color.player_osd_icon_tint))
            gravity = Gravity.CENTER
            includeFontPadding = false
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
        }
        row.addView(sourceLabel, LinearLayout.LayoutParams(-2, BUTTON_SIZE_DP.dp(host.context)))

        siteActions.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            val params = view.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-2, BUTTON_SIZE_DP.dp(host.context))
            if (params.width <= 0) params.width = -2
            params.height = BUTTON_SIZE_DP.dp(host.context)
            params.marginStart = dp(host.context, 4)
            params.marginEnd = dp(host.context, 4)
            row.addView(view, params)
            when (view) {
                is android.widget.Button -> view.applyPlayerPillStyle()
                is ImageButton -> {
                    styleCommonIcon(view)
                    params.width = BUTTON_SIZE_DP.dp(host.context)
                }
            }
        }

        val scroll = HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, ViewGroup.LayoutParams(-2, -1))
        }
        host.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        return scroll
    }

    fun styleCommonIcon(button: ImageButton) {
        val ctx = button.context
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(ctx, 12)
        button.setPadding(pad, pad, pad, pad)
        button.background = ContextCompat.getDrawable(ctx, R.drawable.bg_player_osd_circle)
        button.imageTintList = ContextCompat.getColorStateList(ctx, R.color.player_osd_icon_tint)
        button.stateListAnimator = AnimatorInflater.loadStateListAnimator(ctx, R.animator.blbl_focus_scale)
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        button.defaultFocusHighlightEnabledCompat()
        ViewCompat.setTooltipText(button, button.contentDescription)
    }

    fun focusStart(scroll: HorizontalScrollView): Boolean {
        val row = scroll.getChildAt(0) as? ViewGroup ?: return false
        return row.getChildAt(0)?.requestFocus() == true
    }

    private fun ImageButton.defaultFocusHighlightEnabledCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun Int.dp(context: Context): Int = dp(context, this)

    /** Play/pause height reference used by the danmaku text size. */
    const val BUTTON_SIZE_DP: Int = 48
}
