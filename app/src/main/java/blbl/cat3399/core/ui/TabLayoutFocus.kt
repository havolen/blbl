package blbl.cat3399.core.ui

import android.animation.AnimatorInflater
import android.os.Build
import android.view.ViewGroup
import blbl.cat3399.R
import com.google.android.material.tabs.TabLayout

fun TabLayout.enableDpadTabFocus(
    selectOnFocus: Boolean = true,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    enableDpadTabFocus(selectOnFocusProvider = { selectOnFocus }, onTabFocused = onTabFocused)
}

fun TabLayout.enableDpadTabFocus(
    selectOnFocusProvider: () -> Boolean,
    disableDefaultFocusHighlight: Boolean = false,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    val tabStrip = getChildAt(0) as? ViewGroup ?: return
    for (i in 0 until tabStrip.childCount) {
        val index = i
        val tabView = tabStrip.getChildAt(i)
        tabView.isFocusable = true
        tabView.isClickable = true
        if (disableDefaultFocusHighlight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The platform focus highlight would draw a grey rectangle behind the pill background.
            tabView.defaultFocusHighlightEnabled = false
        }
        tabView.stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.blbl_focus_scale)
        tabView.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (selectOnFocusProvider() && selectedTabPosition != index) {
                getTabAt(index)?.select()
            }
            onTabFocused?.invoke(index)
        }
    }
}

/** Keep section navigation inside the strip; selection still follows the user's focus preference. */
fun TabLayout.bindDpadSectionNavigation(onDown: () -> Boolean, onUp: () -> Boolean) {
    val strip = getChildAt(0) as? ViewGroup ?: return
    for (index in 0 until strip.childCount) {
        strip.getChildAt(index).setOnKeyListener { _, key, event ->
            val confirm = key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener confirm
            when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val next = (index + if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        .coerceIn(0 until strip.childCount)
                    strip.getChildAt(next).requestFocus()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> onUp()
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) getTabAt(index)?.select()
                    true
                }
                else -> false
            }
        }
    }
}
