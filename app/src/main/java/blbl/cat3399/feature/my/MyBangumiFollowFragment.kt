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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyBangumiFollowFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!
    private var gridLoadState: GridLoadState? = null
    private val focusBookmark = GridFocusBookmark()
    private var loadJob: Job? = null
    private var lastRequestWasRefresh = true
    private var refreshCheckpoint: PagedGridStateMachine.State<Int>? = null

    private val type: Int by lazy { requireArguments().getInt(ARG_TYPE) }
    private lateinit var adapter: BangumiFollowAdapter

    private val paging = PagedGridStateMachine(initialKey = 1)
    private var initialLoadTriggered: Boolean = false
    private var pendingRestorePosition: Int? = null
    private var pendingFocusFirstItemFromTabSwitch: Boolean = false
    private var pendingFocusFirstItemAfterRefresh: Boolean = false
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter = BangumiFollowAdapter { position, season ->
                val nav = findMyNavigator()
                if (nav != null) {
                    pendingRestorePosition = position
                    nav.openBangumiDetail(
                        seasonId = season.seasonId,
                        isDrama = type == 2,
                        continueEpId = season.lastEpId,
                        continueEpIndex = season.lastEpIndex,
                    )
                }
            }
        }
        binding.recycler.adapter = adapter
        gridLoadState = GridLoadState(binding, "还没有追番或追剧记录", activity as? MainNavigationHost, { isResumed }) {
            gridLoadState?.loading()
            if (lastRequestWasRefresh || adapter.itemCount == 0) resetAndLoad() else loadNextPage()
        }
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForBangumi())
        focusBookmark.attach(binding.recycler, savedInstanceState)
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            focusSelectedMyTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevMyTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextMyTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = gridLoadState?.hasFailure != true && !paging.snapshot().endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                    ),
            ).also { it.install() }
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val s = paging.snapshot()
                    if (s.isLoading || s.endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - last - 1 <= 8) loadNextPage()
                }
            },
        )
        binding.swipeRefresh.setOnRefreshListener { resetAndLoad(fromUserRefresh = true) }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForBangumi()
        maybeTriggerInitialLoad()
        val refreshed = silentRefreshForProgressDataSource()
        if (!refreshed) restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = true)
        return true
    }

    private fun spanCountForBangumi(): Int {
        val prefs = BiliClient.prefs
        return prefs.pgcGridSpanCount.coerceIn(1, 6)
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
        if (pendingRestorePosition != null) return false

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

        val b = _binding ?: return false
        val recycler = b.recycler
        val isUiAlive = { _binding === b && isResumed && focusBookmark.canApplyFocusRequest(recycler) }
        recycler.postIfAlive(isAlive = isUiAlive) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
                focusBookmark.finishFocusRequest()
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = isUiAlive) {
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
        resetAndLoad(fromUserRefresh = false)
        initialLoadTriggered = true
    }

    private fun resetAndLoad(fromUserRefresh: Boolean = false) {
        loadJob?.cancel()
        if (fromUserRefresh) {
            pendingFocusFirstItemAfterRefresh = true
            pendingRestorePosition = null
            pendingFocusFirstItemFromTabSwitch = false
            focusBookmark.finishFocusRequest()
        }
        loadJob?.cancel()
        refreshCheckpoint = paging.snapshot().takeIf { adapter.itemCount > 0 }
        paging.reset()
        dpadGridController?.clearPendingFocusAfterLoadMore()
        dpadGridController?.parkFocusForDataSetReset()
        loadNextPage(isRefresh = true)
    }

    private fun silentRefreshForProgressDataSource(): Boolean {
        val b = _binding ?: return false
        if (!initialLoadTriggered) return false
        if (adapter.itemCount <= 0) return false
        if (b.swipeRefresh.isRefreshing) return false
        if (paging.snapshot().isLoading) return false
        loadJob?.cancel()
        refreshCheckpoint = paging.snapshot().takeIf { adapter.itemCount > 0 }
        paging.reset()
        dpadGridController?.clearPendingFocusAfterLoadMore()
        loadNextPage(isRefresh = true)
        return true
    }

    private data class FetchedPage(
        val items: List<blbl.cat3399.core.model.BangumiSeason>,
        val pages: Int,
    )

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (gridLoadState?.hasFailure == true && !isRefresh) return
        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) return
        val startGen = startSnap.generation
        val requestBinding = binding
        val sessionRevision = BiliSession.state.value.revision
        lastRequestWasRefresh = isRefresh
        gridLoadState?.loading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result =
                    paging.loadNextPage(
                        isRefresh = isRefresh,
                        fetch = { page ->
                            val mid = BiliSession.requireMid()

                            val res = BiliApi.bangumiFollowList(vmid = mid, type = type, pn = page, ps = 15)
                            FetchedPage(items = res.items, pages = res.pages)
                        },
                        reduce = { page, fetched ->
                            if (fetched.items.isEmpty()) {
                                PagedGridStateMachine.Update(
                                    items = emptyList<blbl.cat3399.core.model.BangumiSeason>(),
                                    nextKey = page,
                                    endReached = true,
                                )
                            } else {
                                val nextPage = page + 1
                                val endReached = fetched.pages > 0 && nextPage > fetched.pages
                                PagedGridStateMachine.Update(
                                    items = fetched.items,
                                    nextKey = nextPage,
                                    endReached = endReached,
                                )
                            }
                        },
                    )

                if (_binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                val applied = result.appliedOrNull() ?: return@launch
                if (isRefresh) refreshCheckpoint = null
                gridLoadState?.content()
                if (applied.isRefresh) {
                    adapter.submit(applied.items)
                } else if (applied.items.isNotEmpty()) {
                    adapter.append(applied.items)
                }
                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (isRefresh && pendingFocusFirstItemAfterRefresh) {
                            pendingFocusFirstItemAfterRefresh = false
                            pendingFocusFirstItemFromTabSwitch = false
                            pendingRestorePosition = null

                            val recycler = b.recycler
                            val isUiAlive = { _binding === b && isResumed }
                            recycler.requestFocusFirstItemOrSelfAfterRefresh(
                                itemCount = adapter.itemCount,
                                smoothScroll = false,
                                isAlive = isUiAlive,
                                onDone = { dpadGridController?.unparkFocusAfterDataSetReset() },
                            )
                            return@postIfAlive
                        }
                        maybeConsumePendingFocusFirstItemFromTabSwitch()
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (_binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                if (isRefresh) {
                    refreshCheckpoint?.let { paging.restoreCursorAfterFailedRefresh(it, startGen) }
                    refreshCheckpoint = null
                }
                pendingFocusFirstItemAfterRefresh = false
                AppLog.e("MyBangumi", "load failed type=$type", t)
                gridLoadState?.failure(t, sessionRevision)
            } finally {
                if (paging.snapshot().generation == startGen && _binding === requestBinding) _binding?.swipeRefresh?.isRefreshing = false
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
        paging.invalidatePendingLoads()
        refreshCheckpoint?.let { paging.restoreCursorAfterFailedRefresh(it, paging.snapshot().generation) }
        refreshCheckpoint = null
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TYPE = "type"

        fun newInstance(type: Int): MyBangumiFollowFragment =
            MyBangumiFollowFragment().apply {
                arguments = Bundle().apply { putInt(ARG_TYPE, type) }
            }
    }
}
