package blbl.cat3399.feature.player

import android.content.Context
import android.widget.ImageButton
import androidx.core.view.ViewCompat

/** Icon-only action styled by the shared player control bar. */
internal class PlayerIconButton(
    context: Context,
    iconRes: Int,
    private var description: String,
    onClick: () -> Unit,
) : ImageButton(context) {
    init {
        PlayerControlBar.styleCommonIcon(this)
        contentDescription = description
        ViewCompat.setTooltipText(this, description)
        setOnClickListener { onClick() }
        setImageResource(iconRes)
    }

    fun update(iconRes: Int, description: String) {
        if (this.description != description) {
            this.description = description
            contentDescription = description
            ViewCompat.setTooltipText(this, description)
        }
        setImageResource(iconRes)
    }
}
