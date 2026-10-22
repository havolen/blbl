package blbl.cat3399.core.ui

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.account.BiliSession
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.ui.MainNavigationHost

/** Presentation only: requests and pagination remain owned by the page. */
class GridLoadState(
    private val binding: FragmentVideoGridBinding,
    private val emptyMessage: String,
    private val navigationHost: MainNavigationHost?,
    private val isActive: () -> Boolean,
    private val onRetry: () -> Unit,
) {
    var hasFailure: Boolean = false
        private set
    private val adapter = checkNotNull(binding.recycler.adapter)
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = content()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = content()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = content()
    }

    init {
        adapter.registerAdapterDataObserver(observer)
        val returnToNavigation = View.OnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && adapter.itemCount == 0) {
                navigationHost?.requestFocusTopNavigationSelected() == true
            } else false
        }
        binding.btnRetry.setOnKeyListener(returnToNavigation)
        binding.loadStatePanel.setOnKeyListener(returnToNavigation)
        if (adapter.itemCount > 0) content()
    }

    fun loading() {
        hasFailure = false
        if (adapter.itemCount > 0) {
            if (binding.loadStatePanel.hasFocus()) show("正在加载…", loading = true)
            else dismiss()
            return
        }
        show("正在加载…", loading = true)
    }

    fun content() {
        hasFailure = false
        if (adapter.itemCount == 0) show(emptyMessage, action = "刷新", onAction = onRetry)
        else dismiss()
    }

    fun failure(error: Throwable, sessionRevision: Long = BiliSession.state.value.revision) {
        hasFailure = true
        val code = (error as? BiliApiException)?.apiCode
        if (code == -101) BiliSession.markExpired(sessionRevision)
        val message = when (code) {
            -101 -> "登录已失效，请重新登录"
            53013 -> "该列表未公开，请检查账号隐私设置"
            else -> if (adapter.itemCount > 0) "加载失败，已保留现有内容" else "加载失败，请检查网络后重试"
        }
        if (code == -101 && navigationHost != null) {
            show(message, action = "登录") { navigationHost.openLoginForCurrentPage() }
        } else show(message, action = "重试", onAction = onRetry)
    }

    fun release() = adapter.unregisterAdapterDataObserver(observer)

    private fun show(message: String, loading: Boolean = false, action: String = "", onAction: (() -> Unit)? = null) {
        val panel = binding.loadStatePanel
        val hadPanelFocus = panel.hasFocus()
        val hadButtonFocus = binding.btnRetry.hasFocus()
        panel.isFocusable = loading
        if (loading && hadButtonFocus) panel.requestFocus()
        (panel.layoutParams as FrameLayout.LayoutParams).let {
            it.gravity = if (adapter.itemCount == 0) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            panel.layoutParams = it
        }
        binding.tvLoadState.text = message
        binding.progressLoadState.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRetry.visibility = if (onAction != null) View.VISIBLE else View.GONE
        binding.btnRetry.text = action
        binding.btnRetry.setOnClickListener { onAction?.invoke() }
        panel.visibility = View.VISIBLE
        if (!loading && hadPanelFocus && isActive()) binding.btnRetry.requestFocus()
    }

    private fun dismiss() {
        val hadFocus = binding.loadStatePanel.hasFocus()
        if (hadFocus && isActive()) binding.recycler.requestFocus()
        binding.loadStatePanel.visibility = View.GONE
        if (hadFocus) binding.recycler.post {
            if (isActive() && binding.recycler.hasFocus()) {
                val lastVisible = binding.recycler.getChildAt(binding.recycler.childCount - 1)
                if (lastVisible != null) lastVisible.requestFocus()
                else binding.recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }
}
