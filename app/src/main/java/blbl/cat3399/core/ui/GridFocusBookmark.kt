package blbl.cat3399.core.ui

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference

/** Per-page scroll and item identity. No View references survive detach. */
class GridFocusBookmark {
    private var itemId: Long? = null
    private var position = 0
    private var scrollState: Parcelable? = null
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var requestOrigin: WeakReference<View>? = null

    fun beginFocusRequest(focused: View?) {
        requestOrigin = focused?.let { WeakReference(it) }
    }

    fun canApplyFocusRequest(recycler: RecyclerView): Boolean {
        val focused = recycler.rootView.findFocus()
        return focused == null || focused === requestOrigin?.get() || FocusTreeUtils.isDescendantOf(focused, recycler)
    }

    fun finishFocusRequest() { requestOrigin = null }

    fun attach(recycler: RecyclerView, savedState: Bundle?) {
        if (savedState?.containsKey("grid_focus_item_id") == true) itemId = savedState.getLong("grid_focus_item_id")
        position = savedState?.getInt("grid_focus_position", position) ?: position
        @Suppress("DEPRECATION")
        scrollState = savedState?.getParcelable("grid_scroll_state") ?: scrollState
        recycler.layoutManager?.onRestoreInstanceState(scrollState)
        focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
            if (focused == null) return@OnGlobalFocusChangeListener
            val holder = recycler.findContainingViewHolder(focused) ?: return@OnGlobalFocusChangeListener
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                position = holder.bindingAdapterPosition
                itemId = holder.itemId.takeIf { it != RecyclerView.NO_ID }
            }
        }.also { recycler.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
    }

    fun save(recycler: RecyclerView, outState: Bundle) {
        scrollState = recycler.layoutManager?.onSaveInstanceState()
        itemId?.let { outState.putLong("grid_focus_item_id", it) }
        outState.putInt("grid_focus_position", position)
        outState.putParcelable("grid_scroll_state", scrollState)
    }

    fun detach(recycler: RecyclerView) {
        scrollState = recycler.layoutManager?.onSaveInstanceState()
        focusListener?.let { if (recycler.viewTreeObserver.isAlive) recycler.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
        requestOrigin = null
    }

    fun requestFocus(recycler: RecyclerView, isAlive: () -> Boolean): Boolean {
        val adapter = recycler.adapter ?: return false
        val savedId = itemId ?: return false
        if (adapter.itemCount == 0) return false
        val target = (0 until adapter.itemCount).firstOrNull { adapter.getItemId(it) == savedId }
            ?: position.coerceIn(0, adapter.itemCount - 1)
        val originalFocus = recycler.rootView.findFocus()
        return recycler.requestFocusAdapterPositionReliable(
            position = target,
            smoothScroll = false,
            isAlive = {
                val focus = recycler.rootView.findFocus()
                isAlive() && (focus == null || focus === originalFocus || FocusTreeUtils.isDescendantOf(focus, recycler))
            },
            onFocused = {},
        )
    }
}
