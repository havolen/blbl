package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import blbl.cat3399.core.account.BiliSession
import blbl.cat3399.core.ui.GridLoadState
import blbl.cat3399.core.ui.GridFocusBookmark
import blbl.cat3399.ui.MainNavigationHost
import kotlinx.coroutines.Job
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.core.ui.setTextSizePxIfChanged
import blbl.cat3399.core.ui.uiScaler
import blbl.cat3399.databinding.FragmentMyFavFolderDetailBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoCardDismissBehavior
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import blbl.cat3399.feature.video.buildPagedVideoCardPlaybackHandle
import blbl.cat3399.feature.video.defaultVideoCardPlaylistItem
import blbl.cat3399.feature.video.openVideoDetailFromPlaybackHandle
import blbl.cat3399.feature.video.openVideoFromPlaybackHandle
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyFavFolderDetailFragment : Fragment(), RefreshKeyHandler, blbl.cat3399.core.ui.TabContentSwitchFocusHost {
    private var _binding: FragmentMyFavFolderDetailBinding? = null
    private val binding get() = _binding!!
    private val gridBinding get() = binding.gridContent
    private var gridLoadState: GridLoadState? = null
    private var loadJob: Job? = null
    private val focusBookmark = GridFocusBookmark()
    private var lastRequestWasRefresh = true
    private data class RefreshCheckpoint(val page: Int, val endReached: Boolean, val keys: Set<String>)
    private var refreshCheckpoint: RefreshCheckpoint? = null

    private lateinit var adapter: VideoCardAdapter
    private var lastAppliedHeaderSizingScale: Float? = null
    private var baseTitleTextSizePx: Float? = null

    private val mediaId: Long by lazy { requireArguments().getLong(ARG_MEDIA_ID) }
    private val title: String by lazy { requireArguments().getString(ARG_TITLE).orEmpty() }

    private val loadedStableKeys = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var pendingFocusFirstItem: Boolean = false
    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyFavFolderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStackImmediate() }
        binding.tvTitle.text = title.ifBlank { getString(R.string.my_fav_default_title) }
        applyHeaderSizing(uiScale = UiScale.factor(requireContext()))

        if (!::adapter.isInitialized) {
            val actionController =
                VideoCardActionController(
                    context = requireContext(),
                    scope = viewLifecycleOwner.lifecycleScope,
                    dismissBehavior = VideoCardDismissBehavior.DeleteFavFolderItem(mediaId = mediaId),
                    onOpenDetail = { _, pos -> openDetail(pos) },
                    onOpenUp = { card -> openUpDetailFromVideoCard(card) },
                    onCardRemoved = { stableKey ->
                        _binding?.gridContent?.recycler?.removeVideoCardAndRestoreFocus(
                            adapter = adapter,
                            stableKey = stableKey,
                            isAlive = { _binding != null && isResumed },
                        )
                    },
                )
            adapter =
                VideoCardAdapter(
                    onClick = { _, pos ->
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
        gridBinding.recycler.adapter = adapter
        gridLoadState = GridLoadState(gridBinding, "收藏夹内暂无视频", activity as? MainNavigationHost, { _binding != null && isResumed }) {
            gridLoadState?.loading()
            if (lastRequestWasRefresh || adapter.itemCount == 0) resetAndLoad() else loadNextPage()
        }
        gridBinding.recycler.setHasFixedSize(true)
        gridBinding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth(resources))
        focusBookmark.attach(gridBinding.recycler, savedInstanceState)
        (gridBinding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        gridBinding.recycler.clearOnScrollListeners()
        gridBinding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = gridBinding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = gridLoadState?.hasFailure != true && !endReached

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
            gridBinding.recycler.installGridViewportFillMonitor(
                isEnabled = { _binding != null && isResumed },
                canLoadMore = { gridLoadState?.hasFailure != true && !isLoadingMore && !endReached },
                loadMore = { loadNextPage() },
            )
        gridBinding.swipeRefresh.setOnRefreshListener {
            pendingFocusFirstItem = true
            dpadGridController?.parkFocusForDataSetReset()
            resetAndLoad()
        }

        if (savedInstanceState == null) {
            pendingFocusFirstItem = true
            gridBinding.recycler.requestFocus()
            gridBinding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }

    }

    override fun onResume() {
        super.onResume()
        applyBackButtonSizing()
        (gridBinding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        viewportFillMonitor?.scheduleCheck()
    }

    private fun applyHeaderSizing(uiScale: Float) {
        val b = _binding ?: return
        val scale = uiScale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        if (lastAppliedHeaderSizingScale == scale) return

        val scaler = requireContext().uiScaler(scale)
        val baseTs = baseTitleTextSizePx ?: b.tvTitle.textSize.also { baseTitleTextSizePx = it }
        b.tvTitle.setTextSizePxIfChanged(scaler.scaledPxF(baseTs, minPx = 1f))

        lastAppliedHeaderSizingScale = scale
    }

    private fun applyBackButtonSizing() {
        val sidebarScale = UiScale.factor(requireContext())
        BackButtonSizingHelper.applySidebarSizing(
            view = binding.btnBack,
            resources = resources,
            sidebarScale = sidebarScale,
        )
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.gridContent.swipeRefresh.isRefreshing) return true
        b.gridContent.swipeRefresh.isRefreshing = true
        pendingFocusFirstItem = true
        dpadGridController?.parkFocusForDataSetReset()
        resetAndLoad()
        return true
    }

    override fun requestFocusCurrentPagePrimaryItemFromContentSwitch(): Boolean {
        if (_binding == null || !isResumed) return false
        val recycler = gridBinding.recycler
        if (adapter.itemCount == 0 && gridBinding.btnRetry.isShown) return gridBinding.btnRetry.requestFocus()
        if (focusBookmark.requestFocus(recycler) { _binding != null && isResumed }) return true
        return recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
    }

    private fun resetAndLoad() {
        loadJob?.cancel()
        refreshCheckpoint = if (adapter.itemCount > 0) RefreshCheckpoint(page, endReached, loadedStableKeys.toSet()) else null
        pendingFocusFirstItem = true
        dpadGridController?.parkFocusForDataSetReset()
        loadedStableKeys.clear()
        isLoadingMore = false
        endReached = false
        page = 1
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (gridLoadState?.hasFailure == true && !isRefresh) return
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        val requestBinding = binding
        val sessionRevision = BiliSession.state.value.revision
        lastRequestWasRefresh = isRefresh
        gridLoadState?.loading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                var targetPage = page
                var loadedPage: Pair<List<blbl.cat3399.core.model.VideoCard>, Boolean>? = null
                while (loadedPage == null) {
                    val res = BiliApi.favFolderResources(mediaId = mediaId, pn = targetPage, ps = 20)
                    if (token != requestToken || _binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                    val hasMore = res.hasMore
                    val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(res.items, loadedStableKeys)
                    targetPage++
                    if (visibleItems.isNotEmpty() || !hasMore) {
                        loadedPage = visibleItems to hasMore
                    }
                }
                val (visibleItems, hasMore) = checkNotNull(loadedPage)
                if (token != requestToken || _binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                if (isRefresh) refreshCheckpoint = null
                visibleItems.forEach { loadedStableKeys.add(it.stableKey()) }
                if (isRefresh) adapter.submit(visibleItems) else adapter.append(visibleItems)
                maybeFocusFirstItem()
                _binding?.gridContent?.recycler?.postIfAlive(isAlive = { _binding != null }) { dpadGridController?.consumePendingFocusAfterLoadMore() }
                endReached = !hasMore
                page = targetPage
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (token != requestToken || _binding !== requestBinding || sessionRevision != BiliSession.state.value.revision) return@launch
                if (isRefresh) restoreRefreshCheckpoint()
                pendingFocusFirstItem = false
                AppLog.e("MyFavDetail", "load failed mediaId=$mediaId", t)
                gridLoadState?.failure(t, sessionRevision)
            } finally {
                if (token == requestToken) _binding?.gridContent?.swipeRefresh?.isRefreshing = false
                if (token == requestToken) isLoadingMore = false
                viewportFillMonitor?.scheduleCheck()
            }
        }
    }

    private fun maybeFocusFirstItem() {
        if (!pendingFocusFirstItem) return
        if (_binding == null) return
        val recycler = gridBinding.recycler
        val isUiAlive = { _binding != null && isResumed }
        recycler.requestFocusFirstItemOrSelfAfterRefresh(
            itemCount = adapter.itemCount,
            smoothScroll = false,
            isAlive = isUiAlive,
            onDone = { pendingFocusFirstItem = false },
        )
    }

    private fun restoreRefreshCheckpoint() {
        val previous = refreshCheckpoint ?: return
        page = previous.page
        endReached = previous.endReached
        loadedStableKeys.clear()
        loadedStableKeys.addAll(previous.keys)
        refreshCheckpoint = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { focusBookmark.save(it.gridContent.recycler, outState) }
    }

    override fun onDestroyView() {
        _binding?.let { focusBookmark.detach(it.gridContent.recycler) }
        loadJob?.cancel()
        loadJob = null
        restoreRefreshCheckpoint()
        requestToken++
        isLoadingMore = false
        gridLoadState?.release()
        gridLoadState = null
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        _binding = null
        super.onDestroyView()
    }

    private fun openDetail(position: Int) {
        requireContext().openVideoDetailFromPlaybackHandle(playbackHandle(), position)
    }

    private fun playbackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "MyFavFolderDetail:$mediaId",
            cardsProvider = adapter::snapshot,
            nextCursorProvider = { page },
            hasMoreProvider = { !endReached },
        ) { targetPage ->
            val pageNum = targetPage.coerceAtLeast(1)
            val res = BiliApi.favFolderResources(mediaId = mediaId, pn = pageNum, ps = 20)
            VideoCardPlaylistPage(
                cards = res.items,
                nextCursor = pageNum + 1,
                hasMore = res.hasMore,
                canAdvance = res.hasMore && res.items.isNotEmpty(),
            )
        }

    companion object {
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_TITLE = "title"

        fun newInstance(mediaId: Long, title: String): MyFavFolderDetailFragment =
            MyFavFolderDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                    putString(ARG_TITLE, title)
                }
            }
    }
}
