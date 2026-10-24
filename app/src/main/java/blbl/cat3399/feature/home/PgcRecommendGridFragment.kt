package blbl.cat3399.feature.home

import android.os.Bundle
import android.content.Intent
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
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.TabSwitchFocusTarget
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.my.BangumiFollowAdapter
import blbl.cat3399.feature.my.BangumiDetailActivity
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PgcRecommendGridFragment : Fragment(), RefreshKeyHandler, TabSwitchFocusTarget {
    private data class PgcPagingKey(
        val cursor: String? = null,
        val page: Int = 1,
    )

    private data class FetchedPage(
        val items: List<BangumiSeason>,
        val nextKey: PgcPagingKey,
        val hasNext: Boolean,
    )

    private sealed interface PgcGridSource {
        val isDrama: Boolean
        val logName: String

        suspend fun fetch(key: PgcPagingKey): FetchedPage

        data object BangumiRecommend : PgcGridSource {
            override val isDrama: Boolean = false
            override val logName: String = "bangumi_recommend"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                val res = BiliApi.pgcBangumiPage(cursor = key.cursor)
                return FetchedPage(
                    items = res.items,
                    nextKey = key.copy(cursor = res.nextCursor),
                    hasNext = res.hasNext,
                )
            }
        }

        data object CinemaRecommend : PgcGridSource {
            override val isDrama: Boolean = true
            override val logName: String = "cinema_recommend"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                val res = BiliApi.pgcCinemaTabPage(cursor = key.cursor)
                return FetchedPage(
                    items = res.items,
                    nextKey = key.copy(cursor = res.nextCursor),
                    hasNext = res.hasNext,
                )
            }
        }

        data class BangumiSearch(private val keyword: String) : PgcGridSource {
            override val isDrama: Boolean = false
            override val logName: String = "bangumi_search"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                return fetchSearchPage(keyword = keyword, key = key, isDrama = false)
            }
        }

        data class CinemaSearch(private val keyword: String) : PgcGridSource {
            override val isDrama: Boolean = true
            override val logName: String = "cinema_search"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                return fetchSearchPage(keyword = keyword, key = key, isDrama = true)
            }
        }
    }

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!
    private var gridLoadState: GridLoadState? = null
    private val focusBookmark = GridFocusBookmark()
    private var loadJob: Job? = null
    private var contentSessionRevision = BiliSession.state.value.revision
    private var lastRequestWasRefresh = true
    private data class RefreshCheckpoint(val key: PgcPagingKey, val hasNext: Boolean, val ids: Set<Long>)
    private var refreshCheckpoint: RefreshCheckpoint? = null

    private val source: PgcGridSource by lazy { sourceFromArguments(requireArguments()) }

    private lateinit var adapter: BangumiFollowAdapter
    private val loadedSeasonIds = HashSet<Long>()

    private var nextKey: PgcPagingKey = PgcPagingKey()
    private var hasNext: Boolean = true
    private var isLoadingMore: Boolean = false
    private var requestToken: Int = 0
    private var initialLoadTriggered: Boolean = false

    private var pendingRestorePosition: Int? = null
    private var pendingFocusFirstCardAfterRefresh: Boolean = false

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                BangumiFollowAdapter { position, season ->
                    pendingRestorePosition = position
                    openBangumiDetail(season)
                }
        }

        binding.recycler.adapter = adapter
        gridLoadState = GridLoadState(binding, "暂无可显示的影视内容", activity as? MainNavigationHost, { isResumed }) {
            gridLoadState?.loading()
            if (lastRequestWasRefresh || adapter.itemCount == 0) resetAndLoad() else loadNextPage()
        }
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForPgc())
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
                            focusSelectedTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            // At the grid's left edge, open the sidebar on this page's entry.
                            // Moving to the previous global item can trigger focus-follow and
                            // silently replace the Cinema/Bangumi page during boundary navigation.
                            return focusSelectedTabIfAvailable()
                        }

                        override fun onRightEdge() {
                            switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = gridLoadState?.hasFailure != true && hasNext

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
                    if (isLoadingMore || !hasNext) return
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
        val revision = BiliSession.state.value.revision
        if (contentSessionRevision != revision) {
            contentSessionRevision = revision
            loadJob?.cancel()
            binding.swipeRefresh.isRefreshing = false
            adapter.submit(emptyList())
            initialLoadTriggered = false
            refreshCheckpoint = null
        }
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForPgc()
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstCard()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = true)
        return true
    }

    private fun spanCountForPgc(): Int = BiliClient.prefs.pgcGridSpanCount.coerceIn(1, 6)

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
        pendingRestorePosition = null
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
        pendingRestorePosition = null
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard(allowFromNavigation = true)
    }

    private fun maybeConsumePendingFocusFirstCard(allowFromNavigation: Boolean = false): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch && !pendingFocusFirstCardFromBackToTab0) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false
        if (pendingRestorePosition != null) return false

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

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val targetPosition = resolvePendingFocusTarget(itemCount = adapter.itemCount)
        val recycler = binding.recycler
        recycler.requestFocusAdapterPositionReliable(
            position = targetPosition,
            smoothScroll = false,
            isAlive = { _binding != null && isResumed },
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
        contentSessionRevision = BiliSession.state.value.revision
        loadJob?.cancel()
        refreshCheckpoint = if (adapter.itemCount > 0) RefreshCheckpoint(nextKey, hasNext, loadedSeasonIds.toSet()) else null
        isLoadingMore = false
        nextKey = PgcPagingKey()
        hasNext = true
        loadedSeasonIds.clear()
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        if (fromUserRefresh) {
            pendingFocusFirstCardAfterRefresh = true
            pendingRestorePosition = null
            clearPendingFocusFlags()
            dpadGridController?.parkFocusForDataSetReset()
        }
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (gridLoadState?.hasFailure == true && !isRefresh) return
        if (isLoadingMore || !hasNext) return
        val token = requestToken
        isLoadingMore = true
        val requestBinding = binding
        val sessionRevision = BiliSession.state.value.revision
        lastRequestWasRefresh = isRefresh
        gridLoadState?.loading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fetched = source.fetch(nextKey)
                if (token != requestToken || _binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch

                if (isRefresh) refreshCheckpoint = null
                nextKey = fetched.nextKey
                hasNext = fetched.hasNext

                val filtered = fetched.items.filter { loadedSeasonIds.add(it.seasonId) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)

                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (isRefresh && pendingFocusFirstCardAfterRefresh) {
                            pendingFocusFirstCardAfterRefresh = false
                            clearPendingFocusFlags()
                            pendingRestorePosition = null
                            lastFocusedAdapterPosition = adapter.itemCount.takeIf { it > 0 }?.let { 0 }

                            val recycler = b.recycler
                            val isUiAlive = { _binding === b && isResumed }
                            recycler.requestFocusFirstItemOrSelfAfterRefresh(
                                itemCount = adapter.itemCount,
                                smoothScroll = false,
                                isAlive = isUiAlive,
                                onDone = { focusedFirstItem ->
                                    if (focusedFirstItem) lastFocusedAdapterPosition = 0
                                    dpadGridController?.unparkFocusAfterDataSetReset()
                                },
                            )
                            return@postIfAlive
                        }
                        maybeConsumePendingFocusFirstCard()
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (_binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                if (isRefresh) restoreRefreshCheckpoint()
                pendingFocusFirstCardAfterRefresh = false
                AppLog.e("PgcRecommend", "load failed source=${source.logName} key=$nextKey", t)
                gridLoadState?.failure(t, sessionRevision)
            } finally {
                if (token == requestToken && _binding === requestBinding) _binding?.swipeRefresh?.isRefreshing = false
                if (token == requestToken) isLoadingMore = false
            }
        }
    }

    private fun restoreRefreshCheckpoint() {
        val previous = refreshCheckpoint ?: return
        nextKey = previous.key
        hasNext = previous.hasNext
        loadedSeasonIds.clear()
        loadedSeasonIds.addAll(previous.ids)
        refreshCheckpoint = null
    }

    private fun openBangumiDetail(season: BangumiSeason) {
        if (!isAdded) return
        startActivity(
            Intent(requireContext(), BangumiDetailActivity::class.java)
                .putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, season.seasonId)
                .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, source.isDrama),
        )
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

    private fun focusSelectedTabIfAvailable(): Boolean {
        captureCurrentFocusedAdapterPosition()
        return (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        captureCurrentFocusedAdapterPosition()
        return (activity as? MainNavigationHost)?.requestFocusTopNavigationAdjacent(1) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { focusBookmark.save(it.recycler, outState) }
    }

    override fun onDestroyView() {
        _binding?.let { focusBookmark.detach(it.recycler) }
        restoreRefreshCheckpoint()
        isLoadingMore = false
        loadJob?.cancel()
        loadJob = null
        gridLoadState?.release()
        gridLoadState = null
        requestToken++
        initialLoadTriggered = false
        pendingRestorePosition = null
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_SEARCH_KEYWORD = "search_keyword"

        private const val KIND_BANGUMI = 1
        private const val KIND_CINEMA = 2
        private const val KIND_SEARCH_BANGUMI = 3
        private const val KIND_SEARCH_CINEMA = 4

        private fun sourceFromArguments(args: Bundle): PgcGridSource {
            val keyword = args.getString(ARG_SEARCH_KEYWORD).orEmpty().trim()
            return when (args.getInt(ARG_KIND)) {
                KIND_BANGUMI -> PgcGridSource.BangumiRecommend
                KIND_SEARCH_BANGUMI -> PgcGridSource.BangumiSearch(keyword)
                KIND_SEARCH_CINEMA -> PgcGridSource.CinemaSearch(keyword)
                else -> PgcGridSource.CinemaRecommend
            }
        }

        private suspend fun fetchSearchPage(
            keyword: String,
            key: PgcPagingKey,
            isDrama: Boolean,
        ): FetchedPage {
            if (keyword.isBlank()) {
                return FetchedPage(
                    items = emptyList(),
                    nextKey = key.copy(page = key.page + 1),
                    hasNext = false,
                )
            }

            val res =
                if (isDrama) {
                    BiliApi.searchMediaFt(keyword = keyword, page = key.page, order = "totalrank")
                } else {
                    BiliApi.searchMediaBangumi(keyword = keyword, page = key.page, order = "totalrank")
                }
            return FetchedPage(
                items = res.items,
                nextKey = key.copy(page = key.page + 1),
                hasNext = res.items.isNotEmpty() && (res.pages <= 0 || res.page < res.pages),
            )
        }

        fun newBangumi(): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KIND, KIND_BANGUMI) }
        }

        fun newCinema(): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KIND, KIND_CINEMA) }
        }

        fun newSearchBangumi(keyword: String): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments =
                Bundle().apply {
                    putInt(ARG_KIND, KIND_SEARCH_BANGUMI)
                    putString(ARG_SEARCH_KEYWORD, keyword.trim())
                }
        }

        fun newSearchCinema(keyword: String): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments =
                Bundle().apply {
                    putInt(ARG_KIND, KIND_SEARCH_CINEMA)
                    putString(ARG_SEARCH_KEYWORD, keyword.trim())
                }
        }
    }
}
