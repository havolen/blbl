package blbl.cat3399.feature.player

import android.content.Context
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import blbl.cat3399.R

/**
 * Danmaku switch shared by all players; [PlayerControlBar] supplies its common button style.
 */
internal class DanmakuToggleButton(context: Context) : ImageButton(context) {
    var isDanmakuOn: Boolean = true
        private set

    var onToggle: ((Boolean) -> Unit)? = null

    init {
        PlayerControlBar.styleCommonIcon(this)
        setOnClickListener { setDanmakuOn(!isDanmakuOn, notify = true) }
        render()
    }

    fun setDanmakuOn(enabled: Boolean, notify: Boolean) {
        if (isDanmakuOn == enabled) {
            render()
            return
        }
        isDanmakuOn = enabled
        render()
        if (notify) onToggle?.invoke(enabled)
    }

    private fun render() {
        isSelected = isDanmakuOn
        setImageResource(if (isDanmakuOn) R.drawable.ic_player_danmaku_on else R.drawable.ic_player_danmaku_off)
        val label = if (isDanmakuOn) "弹幕已开启" else "弹幕已关闭"
        contentDescription = label
        ViewCompat.setTooltipText(this, label)
    }
}
