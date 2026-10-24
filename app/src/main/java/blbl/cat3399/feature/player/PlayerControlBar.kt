package blbl.cat3399.feature.player

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.animation.AnimatorInflater
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
    val next: View,
    val playlist: View,
    val danmaku: View,
    val replay10: View,
    val forward10: View,
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
        val common = listOf(
            actions.playPause,
            actions.next,
            actions.playlist,
            actions.danmaku,
            actions.replay10,
            actions.forward10,
        )
        common.forEachIndexed { index, view ->
            (view.parent as? ViewGroup)?.removeView(view)
            if (view is ImageButton) styleCommonIcon(view)
            val params = LinearLayout.LayoutParams(dp(host.context, 64), dp(host.context, 48))
            if (index > 0) params.marginStart = dp(host.context, 3)
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
        row.addView(sourceLabel, LinearLayout.LayoutParams(-2, dp(host.context, 48)))

        siteActions.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            val params = view.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-2, dp(host.context, 48))
            if (params.width <= 0) params.width = -2
            params.height = dp(host.context, 48)
            params.marginStart = dp(host.context, 4)
            params.marginEnd = dp(host.context, 4)
            row.addView(view, params)
            when (view) {
                is android.widget.Button -> view.applyPlayerPillStyle()
                is ImageButton -> styleCommonIcon(view)
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
        val pad = dp(ctx, 6)
        button.setPadding(pad, pad, pad, pad)
        button.background = ContextCompat.getDrawable(ctx, R.drawable.bg_player_osd_pill)
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
}
