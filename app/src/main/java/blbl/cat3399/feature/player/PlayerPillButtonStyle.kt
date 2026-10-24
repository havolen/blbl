package blbl.cat3399.feature.player

import android.animation.AnimatorInflater
import android.widget.Button
import androidx.core.content.ContextCompat
import blbl.cat3399.R

/** Shared visual state for the compact controls used by the Mango WebView and Exo test players. */
internal fun Button.applyPlayerPillStyle() {
    setBackgroundResource(R.drawable.bg_player_osd_pill)
    setTextColor(ContextCompat.getColorStateList(context, R.color.player_osd_icon_tint))
    setPadding(context.dp(6), 0, context.dp(6), 0)
    minWidth = context.dp(40)
    minHeight = context.dp(48)
    isAllCaps = false
    textSize = 14f
    gravity = android.view.Gravity.CENTER
    stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.blbl_focus_scale)
}

private fun android.content.Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
