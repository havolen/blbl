package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import blbl.cat3399.core.account.BiliSession
import blbl.cat3399.core.ui.GridLoadState
import blbl.cat3399.core.ui.GridFocusBookmark
import kotlinx.coroutines.Job
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyFavFoldersFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!
    private var gridLoadState: GridLoadState? = null
    private val focusBookmark = GridFocusBookmark()
    private var loadJob: Job? = null

    private lateinit var adapter: FavFolderAdapter
    private var initialLoadTriggered: Boolean = false
    private var requestToken: Int = 0
    private var pendingRestorePosition: Int? = null
    private var pendingFocusFirstItemFromTabSwitch: Boolean = false
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter = FavFolderAdapter { position, folder ->
                pendingRestorePosition = position
                val nav = findMyNavigator()
                nav?.openFavFolder(folder.mediaId, folder.title)
            }
        }

        binding.recycler.adapter = adapter
        gridLoadState = GridLoadState(binding, "还没有收藏夹", activity as? MainNavigationHost, { isResumed }) {
            gridLoadState?.loading()
            reload()
        }
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = StaggeredGridLayoutManager(spanCountForWidth(resources), StaggeredGridLayoutManager.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
        focusBookmark.attach(binding.recycler, savedInstanceState)
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            return focusSelectedMyTabIfAvailable()
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevMyTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextMyTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = false

                        override fun loadMore() = Unit
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                    ),
            ).also { it.install() }
        binding.swipeRefresh.setOnRefreshListener { reload() }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? StaggeredGridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        reload()
        return true
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean {
        focusBookmark.beginFocusRequest(activity?.currentFocus)
        val b = _binding
        if (b != null && isResumed) {
            if (adapter.itemCount == 0 && b.btnRetry.isShown && b.btnRetry.requestFocus()) {
                pendingFocusFirstItemFromTabSwitch = false
                focusBookmark.finishFocusRequest()
                return true
            }
            if (focusBookmark.requestFocus(b.recycler) { _binding === b && isResumed }) {
                pendingFocusFirstItemFromTabSwitch = false
                focusBookmark.finishFocusRequest()
                return true
            }
        }
        pendingFocusFirstItemFromTabSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstItemFromTabSwitch(allowFromNavigation = true)
    }

    private fun maybeConsumePendingFocusFirstItemFromTabSwitch(allowFromNavigation: Boolean = false): Boolean {
        if (!pendingFocusFirstItemFromTabSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false

        val focused = activity?.currentFocus
        if (!allowFromNavigation && !focusBookmark.canApplyFocusRequest(binding.recycler)) {
            pendingFocusFirstItemFromTabSwitch = false
            focusBookmark.finishFocusRequest()
            return false
        }
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItemFromTabSwitch = false
            focusBookmark.finishFocusRequest()
            return false
        }

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null && isResumed && focusBookmark.canApplyFocusRequest(recycler) }) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
                focusBookmark.finishFocusRequest()
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = { _binding != null && isResumed && focusBookmark.canApplyFocusRequest(recycler) }) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
                focusBookmark.finishFocusRequest()
            }
        }
        return true
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        reload()
        initialLoadTriggered = true
    }

    private fun reload() {
        loadJob?.cancel()
        val requestBinding = binding
        val sessionRevision = BiliSession.state.value.revision
        gridLoadState?.loading()
        val token = ++requestToken
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val mid = BiliSession.requireMid()
                val folders = BiliApi.favFolders(upMid = mid)
                if (token != requestToken || _binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                adapter.submit(folders)
                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) {
                    maybeConsumePendingFocusFirstItemFromTabSwitch()
                    dpadGridController?.consumePendingFocusAfterLoadMore()
                }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (_binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                AppLog.e("MyFav", "load failed", t)
                gridLoadState?.failure(t, sessionRevision)
            } finally {
                if (token == requestToken && _binding === requestBinding) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun restoreFocusIfNeeded() {
        val b = _binding ?: return
        if (!isResumed) return
        val focus = activity?.currentFocus
        if (focus != null && !FocusTreeUtils.isDescendantOf(focus, b.root)) return
        val pos = pendingRestorePosition ?: return
        if (_binding == null) return
        if (pos < 0 || pos >= adapter.itemCount) return
        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            recycler.scrollToPosition(pos)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                pendingRestorePosition = null
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { focusBookmark.save(it.recycler, outState) }
    }

    override fun onDestroyView() {
        _binding?.let { focusBookmark.detach(it.recycler) }
        loadJob?.cancel()
        loadJob = null
        gridLoadState?.release()
        gridLoadState = null
        requestToken++
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }
}
