package blbl.cat3399.feature.video

import android.os.Bundle
import android.os.SystemClock
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
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.TabSwitchFocusTarget
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class VideoGridFragment : Fragment(), RefreshKeyHandler, TabSwitchFocusTarget {
    private data class PagingKey(
        val page: Int,
        val recommendFetchRow: Int,
    )

    private data class FetchedPage(
        val items: List<VideoCard>,
        val nextKey: PagingKey,
        val hasMore: Boolean,
    )

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!
    private var gridLoadState: GridLoadState? = null
    private val focusBookmark = GridFocusBookmark()
    private var loadJob: Job? = null
    private var contentSessionRevision = BiliSession.state.value.revision
    private var lastRequestWasRefresh = true
    private var refreshCheckpoint: PagedGridStateMachine.State<PagingKey>? = null

    private lateinit var adapter: VideoCardAdapter
    private var preDrawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var firstDrawLogged: Boolean = false
    private var initialLoadTriggered: Boolean = false

    private val source: String by lazy { requireArguments().getString(ARG_SOURCE) ?: SRC_POPULAR }
    private val rid: Int by lazy { requireArguments().getInt(ARG_RID, 0) }
    private val searchKeyword: String by lazy { requireArguments().getString(ARG_SEARCH_KEYWORD).orEmpty().trim() }

    private val loadedStableKeys = HashSet<String>()
    private val paging = PagedGridStateMachine(initialKey = PagingKey(page = 1, recommendFetchRow = 1))

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null
    private var pendingFocusFirstCardAfterRefresh: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        AppLog.d("VideoGrid", "onCreateView source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        AppLog.d("VideoGrid", "onViewCreated source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        if (!::adapter.isInitialized) {
            val actionController =
                VideoCardActionController(
                    context = requireContext(),
                    scope = viewLifecycleOwner.lifecycleScope,
                    dismissBehavior = VideoCardDismissBehavior.LocalNotInterested,
                    onOpenDetail = { _, pos -> openDetail(pos) },
                    onOpenUp = { card -> openUpDetailFromVideoCard(card) },
                    onCardRemoved = { stableKey ->
                        _binding?.recycler?.removeVideoCardAndRestoreFocus(
                            adapter = adapter,
                            stableKey = stableKey,
                            isAlive = { _binding != null && isResumed },
                        )
                    },
                )
            adapter =
                VideoCardAdapter(
                    onClick = { card, pos ->
                        AppLog.i("VideoGrid", "click bvid=${card.bvid} cid=${card.cid}")
                        requireContext().openVideoFromPlaybackHandle(
                            playbackHandle = playbackHandle(),
                            position = pos,
                            openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                        )
                    },
                    onLongClick = { card, _ ->
                        openUpDetailFromVideoCard(card)
                        true
                    },
                    actionDelegate = actionController,
                )
        }
        binding.recycler.adapter = adapter
        gridLoadState = GridLoadState(binding, "暂无可显示的视频", activity as? MainNavigationHost, { isResumed }) {
            gridLoadState?.loading()
            if (lastRequestWasRefresh || adapter.itemCount == 0) resetAndLoad() else loadNextPage()
        }
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        focusBookmark.attach(binding.recycler, savedInstanceState)
        (binding.recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val s = paging.snapshot()
                    if (s.isLoading || s.endReached) return

                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return

                    if (total - lastVisible - 1 <= 8) {
                        AppLog.d("VideoGrid", "near end source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
                        loadNextPage()
                    }
                }
            },
        )
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            focusSelectedTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = gridLoadState?.hasFailure != true && !paging.snapshot().endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        viewportFillMonitor?.release()
        viewportFillMonitor =
            binding.recycler.installGridViewportFillMonitor(
                isEnabled = { _binding != null && isResumed },
                canLoadMore = {
                    val s = paging.snapshot()
                    gridLoadState?.hasFailure != true && !s.isLoading && !s.endReached
                },
                loadMore = { loadNextPage() },
            )

        binding.swipeRefresh.setOnRefreshListener {
            pendingFocusFirstCardAfterRefresh = true
            dpadGridController?.parkFocusForDataSetReset()
            resetAndLoad()
        }

        if (preDrawListener == null) {
            preDrawListener =
                android.view.ViewTreeObserver.OnPreDrawListener {
                    if (!firstDrawLogged) {
                        firstDrawLogged = true
                        AppLog.d(
                            "VideoGrid",
                            "first preDraw source=$source rid=$rid t=${SystemClock.uptimeMillis()}",
                        )
                    }
                    true
                }
            binding.recycler.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }
    }

    override fun onResume() {
        super.onResume()
        val revision = BiliSession.state.value.revision
        if (contentSessionRevision != revision) {
            contentSessionRevision = revision
            loadJob?.cancel()
            binding.swipeRefresh.isRefreshing = false
            adapter.submit(emptyList())
            initialLoadTriggered = false
            refreshCheckpoint = null
        }
        AppLog.d("VideoGrid", "onResume source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        viewportFillMonitor?.scheduleCheck()
        maybeTriggerInitialLoad()
        maybeConsumePendingFocusFirstCard()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        pendingFocusFirstCardAfterRefresh = true
        dpadGridController?.parkFocusForDataSetReset()
        b.swipeRefresh.isRefreshing = true
        resetAndLoad()
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
        resetAndLoad()
        initialLoadTriggered = true
    }

    private fun resetAndLoad() {
        contentSessionRevision = BiliSession.state.value.revision
        loadJob?.cancel()
        AppLog.d("VideoGrid", "resetAndLoad source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        loadJob?.cancel()
        refreshCheckpoint = paging.snapshot().takeIf { adapter.itemCount > 0 }
        paging.reset()
        loadedStableKeys.clear()
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (gridLoadState?.hasFailure == true && !isRefresh) return
        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) return
        val startGen = startSnap.generation
        val startKey = startSnap.nextKey
        val startAt = SystemClock.uptimeMillis()
        AppLog.d(
            "VideoGrid",
            "loadNextPage start source=$source rid=$rid page=${startKey.page} refresh=$isRefresh t=$startAt",
        )
        val requestBinding = binding
        val sessionRevision = BiliSession.state.value.revision
        lastRequestWasRefresh = isRefresh
        gridLoadState?.loading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result =
                    paging.loadNextPage(
                        isRefresh = isRefresh,
                        fetch = ::fetchVisiblePage,
                        reduce = { _, fetched ->
                            PagedGridStateMachine.Update(
                                items = fetched.items,
                                nextKey = fetched.nextKey,
                                endReached = !fetched.hasMore,
                            )
                        },
                    )

                if (_binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                val applied = result.appliedOrNull() ?: return@launch
                if (isRefresh) refreshCheckpoint = null
                gridLoadState?.content()
                applied.items.forEach { loadedStableKeys.add(it.stableKey()) }
                if (applied.isRefresh) {
                    adapter.submit(applied.items)
                } else if (applied.items.isNotEmpty()) {
                    adapter.append(applied.items)
                }
                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (pendingFocusFirstCardAfterRefresh && applied.isRefresh) {
                            pendingFocusFirstCardAfterRefresh = false
                            clearPendingFocusFlags()
                            val recycler = b.recycler
                            val isUiAlive = { _binding === b && isResumed }
                            lastFocusedAdapterPosition = adapter.itemCount.takeIf { it > 0 }?.let { 0 }
                            recycler.requestFocusFirstItemOrSelfAfterRefresh(
                                itemCount = adapter.itemCount,
                                smoothScroll = false,
                                isAlive = isUiAlive,
                                onDone = { focusedFirstItem ->
                                    if (focusedFirstItem) lastFocusedAdapterPosition = 0
                                    dpadGridController?.unparkFocusAfterDataSetReset()
                                },
                            )
                            viewportFillMonitor?.scheduleCheck()
                            return@postIfAlive
                        }
                        maybeConsumePendingFocusFirstCard()
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                        viewportFillMonitor?.scheduleCheck()
                    }
                }
                AppLog.i(
                    "VideoGrid",
                    "load ok source=$source rid=$rid page=${startKey.page} add=${applied.items.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms",
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (_binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                if (isRefresh) {
                    refreshCheckpoint?.let { paging.restoreCursorAfterFailedRefresh(it, startGen) }
                    refreshCheckpoint = null
                    loadedStableKeys.clear()
                    loadedStableKeys.addAll(adapter.snapshot().map { it.stableKey() })
                }
                pendingFocusFirstCardAfterRefresh = false
                AppLog.e("VideoGrid", "load failed source=$source rid=$rid page=${startKey.page}", t)
                gridLoadState?.failure(t, sessionRevision)
            } finally {
                if (isRefresh && paging.snapshot().generation == startGen && _binding === requestBinding) _binding?.swipeRefresh?.isRefreshing = false
                AppLog.d(
                    "VideoGrid",
                    "loadNextPage end source=$source rid=$rid page=${paging.snapshot().nextKey.page} refresh=$isRefresh t=${SystemClock.uptimeMillis()}",
                )
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    override fun requestFocusFirstCardFromTab(): Boolean {
        focusBookmark.beginFocusRequest(activity?.currentFocus)
        val b = _binding
        if (b != null && isResumed) {
            if (adapter.itemCount == 0 && b.btnRetry.isShown && b.btnRetry.requestFocus()) {
                clearPendingFocusFlags()
                return true
            }
            if (focusBookmark.requestFocus(b.recycler) { _binding === b && isResumed }) {
                clearPendingFocusFlags()
                return true
            }
        }
        pendingFocusFirstCardFromTab = true
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard(allowFromNavigation = true)
    }

    override fun requestFocusFirstCardFromContentSwitch(): Boolean {
        focusBookmark.beginFocusRequest(activity?.currentFocus)
        val b = _binding
        if (b != null && isResumed) {
            if (adapter.itemCount == 0 && b.btnRetry.isShown && b.btnRetry.requestFocus()) {
                clearPendingFocusFlags()
                return true
            }
            if (focusBookmark.requestFocus(b.recycler) { _binding === b && isResumed }) {
                clearPendingFocusFlags()
                return true
            }
        }
        pendingFocusFirstCardFromContentSwitch = true
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromBackToTab0 = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard(allowFromNavigation = true)
    }

    override fun requestFocusFirstCardFromBackToTab0(): Boolean {
        focusBookmark.beginFocusRequest(activity?.currentFocus)
        pendingFocusFirstCardFromBackToTab0 = true
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard(allowFromNavigation = true)
    }

    private fun maybeConsumePendingFocusFirstCard(allowFromNavigation: Boolean = false): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch && !pendingFocusFirstCardFromBackToTab0) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false

        val focused = activity?.currentFocus
        if (!allowFromNavigation && !focusBookmark.canApplyFocusRequest(binding.recycler)) {
            clearPendingFocusFlags()
            return false
        }
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            // If we are already focused inside the grid, consider the request satisfied, except for
            // "Back -> tab0 content" which must deterministically land on the first card.
            val holder = binding.recycler.findContainingViewHolder(focused)
            val pos = holder?.bindingAdapterPosition?.takeIf { it != RecyclerView.NO_POSITION }
            if (pendingFocusFirstCardFromBackToTab0) {
                if (pos == 0) {
                    lastFocusedAdapterPosition = 0
                    clearPendingFocusFlags()
                    return false
                }
            } else {
                rememberFocusedAdapterPositionFromView(focused)
                clearPendingFocusFlags()
                return false
            }
        }

        if (pendingFocusFirstCardFromTab) {
            pendingFocusFirstCardFromTab = false
        }

        if (!this::adapter.isInitialized) return false
        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val targetPosition = resolvePendingFocusTarget(itemCount = adapter.itemCount)
        val b = _binding ?: return false
        val recycler = b.recycler
        val isUiAlive = { _binding === b && isResumed && focusBookmark.canApplyFocusRequest(recycler) }
        recycler.requestFocusAdapterPositionReliable(
            position = targetPosition,
            smoothScroll = false,
            isAlive = isUiAlive,
            onFocused = {
                lastFocusedAdapterPosition = targetPosition
                clearPendingFocusFlags()
            },
        )
        return true
    }

    private fun resolvePendingFocusTarget(itemCount: Int): Int {
        if (pendingFocusFirstCardFromTab || pendingFocusFirstCardFromBackToTab0) return 0
        if (!pendingFocusFirstCardFromContentSwitch) return 0
        val saved = lastFocusedAdapterPosition ?: return 0
        return saved.coerceIn(0, itemCount - 1)
    }

    private fun clearPendingFocusFlags() {
        focusBookmark.finishFocusRequest()
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
    }

    private fun captureCurrentFocusedAdapterPosition() {
        val recycler = _binding?.recycler ?: return
        val focused = activity?.currentFocus ?: return
        rememberFocusedAdapterPositionFromView(focused, recycler)
    }

    private fun rememberFocusedAdapterPositionFromView(
        focusedView: View,
        recycler: RecyclerView = binding.recycler,
    ) {
        val holder = recycler.findContainingViewHolder(focusedView) ?: return
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        lastFocusedAdapterPosition = position
    }

    private fun focusSelectedTabIfAvailable(): Boolean {
        captureCurrentFocusedAdapterPosition()
        var parent = parentFragment
        while (parent != null) {
            if ((parent as? blbl.cat3399.core.ui.PageSectionFocusHost)?.requestFocusPageSection() == true) return true
            parent = parent.parentFragment
        }
        return (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        captureCurrentFocusedAdapterPosition()
        return (activity as? MainNavigationHost)?.requestFocusTopNavigationAdjacent(1) == true
    }

    private fun switchToPrevTabFromContentEdge(): Boolean {
        captureCurrentFocusedAdapterPosition()
        return (activity as? MainNavigationHost)?.requestFocusTopNavigationAdjacent(-1) == true
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
        loadedStableKeys.clear()
        loadedStableKeys.addAll(adapter.snapshot().map { it.stableKey() })
        AppLog.d("VideoGrid", "onDestroyView source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        preDrawListener?.let { listener ->
            if (binding.recycler.viewTreeObserver.isAlive) {
                binding.recycler.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        preDrawListener = null
        firstDrawLogged = false
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        _binding = null
        super.onDestroyView()
    }

    private suspend fun fetchVisiblePage(key: PagingKey): FetchedPage {
        val ps = 24
        var currentKey = key
        while (true) {
            val rawPage = fetchRawPage(currentKey, ps)
            val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(rawPage.items, loadedStableKeys)
            if (visibleItems.isNotEmpty() || !rawPage.hasMore || rawPage.nextKey == currentKey || rawPage.items.isEmpty()) {
                return FetchedPage(
                    items = visibleItems,
                    nextKey = rawPage.nextKey,
                    hasMore = rawPage.hasMore,
                )
            }
            currentKey = rawPage.nextKey
        }
    }

    private suspend fun fetchRawPage(
        key: PagingKey,
        ps: Int,
    ): FetchedPage {
        return when (source) {
            SRC_RECOMMEND -> {
                val items = BiliApi.recommend(freshIdx = key.page, ps = ps, fetchRow = key.recommendFetchRow)
                FetchedPage(
                    items = items,
                    nextKey =
                        key.copy(
                            page = key.page + 1,
                            recommendFetchRow = key.recommendFetchRow + items.size,
                        ),
                    hasMore = items.isNotEmpty(),
                )
            }

            SRC_REGION -> {
                val res = BiliApi.regionRankPage(rid = rid, pn = key.page, ps = ps)
                FetchedPage(
                    items = res.items,
                    nextKey = key.copy(page = key.page + 1),
                    hasMore = res.hasMore,
                )
            }

            SRC_SEARCH -> {
                val keyword = searchKeyword
                if (keyword.isBlank()) {
                    FetchedPage(
                        items = emptyList(),
                        nextKey = key.copy(page = key.page + 1),
                        hasMore = false,
                    )
                } else {
                    val res = BiliApi.searchVideo(keyword = keyword, page = key.page, order = "totalrank")
                    FetchedPage(
                        items = res.items,
                        nextKey = key.copy(page = key.page + 1),
                        hasMore = res.items.isNotEmpty() && (res.pages <= 0 || res.page < res.pages),
                    )
                }
            }

            else -> {
                val res = BiliApi.popularPage(pn = key.page, ps = ps)
                FetchedPage(
                    items = res.items,
                    nextKey = key.copy(page = key.page + 1),
                    hasMore = res.hasMore,
                )
            }
        }
    }

    private fun openDetail(position: Int) {
        requireContext().openVideoDetailFromPlaybackHandle(playbackHandle(), position)
    }

    private fun playbackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "VideoGrid:$source/$rid",
            cardsProvider = adapter::snapshot,
            nextCursorProvider = { paging.snapshot().nextKey },
            hasMoreProvider = { !paging.snapshot().endReached },
        ) { key ->
            val page = fetchRawPage(key, ps = 24)
            VideoCardPlaylistPage(
                cards = page.items,
                nextCursor = page.nextKey,
                hasMore = page.hasMore,
                canAdvance = page.hasMore && page.nextKey != key && page.items.isNotEmpty(),
            )
        }

    companion object {
        private const val ARG_SOURCE = "source"
        private const val ARG_RID = "rid"
        private const val ARG_SEARCH_KEYWORD = "search_keyword"

        const val SRC_RECOMMEND = "recommend"
        const val SRC_POPULAR = "popular"
        const val SRC_REGION = "region"
        const val SRC_SEARCH = "search"

        fun newRecommend() = VideoGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_RECOMMEND) } }
        fun newPopular() = VideoGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_POPULAR) } }
        fun newRegion(rid: Int) = VideoGridFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE, SRC_REGION)
                putInt(ARG_RID, rid)
            }
        }

        fun newSearch(keyword: String) = VideoGridFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE, SRC_SEARCH)
                putString(ARG_SEARCH_KEYWORD, keyword.trim())
            }
        }
    }
}
