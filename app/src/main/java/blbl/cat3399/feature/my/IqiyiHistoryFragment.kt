package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.api.IqiyiApi
import blbl.cat3399.core.api.IqiyiHistoryApi
import blbl.cat3399.core.api.IqiyiHistoryItem
import blbl.cat3399.core.api.IqiyiLoginRequiredException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridFocusBookmark
import blbl.cat3399.core.ui.GridLoadState
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.login.IqiyiLoginActivity
import blbl.cat3399.feature.player.IqiyiPlayerActivity
import blbl.cat3399.feature.player.IqiyiWebPlayerActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** iQiyi watch history using the same grid/focus pipeline as the Mango history page. */
class IqiyiHistoryFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!
    private lateinit var sessionStore: IqiyiSessionStore
    private lateinit var historyApi: IqiyiHistoryApi
    private lateinit var adapter: VideoCardAdapter
    private val items = ArrayList<IqiyiHistoryItem>()
    private val focusBookmark = GridFocusBookmark()
    private var gridLoadState: GridLoadState? = null
    private var loadJob: Job? = null
    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null
    private var nextPage: Int = 1
    private var hasMore: Boolean = false
    private var initialLoadTriggered = false
    private var pendingFocusFirstItem = false
    private var refreshAfterPlayback = false
    private var resolvingPlayback = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sessionStore = IqiyiSessionStore(requireContext())
        historyApi = IqiyiHistoryApi(sessionStore)
        adapter = VideoCardAdapter(onClick = { _, position -> openHistoryItem(position) })
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth(resources))
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        focusBookmark.attach(binding.recycler, savedInstanceState)
        gridLoadState = GridLoadState(
            binding = binding,
            emptyMessage = getString(R.string.iqiyi_history_empty),
            navigationHost = activity as? MainNavigationHost,
            isActive = { isResumed },
            onRetry = { loadHistory(reset = true) },
        )
        installDpadNavigation()
        installViewportFillMonitor()
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadJob?.isActive == true || !hasMore) return
                val layout = recyclerView.layoutManager as? GridLayoutManager ?: return
                val lastVisible = layout.findLastVisibleItemPosition()
                if (adapter.itemCount > 0 && adapter.itemCount - lastVisible - 1 <= PREFETCH_ITEMS) {
                    loadHistory(reset = false)
                }
            }
        })
        binding.swipeRefresh.setOnRefreshListener {
            focusBookmark.beginFocusRequest(activity?.currentFocus)
            dpadGridController?.parkFocusForDataSetReset()
            loadHistory(reset = true)
        }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        viewportFillMonitor?.scheduleCheck()
        if (refreshAfterPlayback) {
            refreshAfterPlayback = false
            loadHistory(reset = true)
        } else if (!initialLoadTriggered) {
            initialLoadTriggered = true
            loadHistory(reset = true)
        }
        consumePendingFocus()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        focusBookmark.beginFocusRequest(activity?.currentFocus)
        dpadGridController?.parkFocusForDataSetReset()
        b.swipeRefresh.isRefreshing = true
        loadHistory(reset = true)
        return true
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean {
        val b = _binding ?: return false
        focusBookmark.beginFocusRequest(activity?.currentFocus)
        if (isResumed) {
            if (adapter.itemCount == 0 && b.btnRetry.isShown && b.btnRetry.requestFocus()) {
                pendingFocusFirstItem = false
                focusBookmark.finishFocusRequest()
                return true
            }
            if (focusBookmark.requestFocus(b.recycler) { _binding === b && isResumed }) {
                pendingFocusFirstItem = false
                focusBookmark.finishFocusRequest()
                return true
            }
        }
        pendingFocusFirstItem = true
        consumePendingFocus()
        return true
    }

    private fun installDpadNavigation() {
        dpadGridController?.release()
        dpadGridController = DpadGridController(
            recyclerView = binding.recycler,
            callbacks = object : DpadGridController.Callbacks {
                override fun onTopEdge(): Boolean {
                    if ((parentFragment as? MyHistoryHostFragment)?.requestSourceButtonFocus() == true) return true
                    return (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
                }

                override fun onLeftEdge(): Boolean =
                    (activity as? MainNavigationHost)?.requestFocusTopNavigationAdjacent(-1) == true

                override fun onRightEdge() {
                    (activity as? MainNavigationHost)?.requestFocusTopNavigationAdjacent(1)
                }

                override fun canLoadMore(): Boolean = hasMore && gridLoadState?.hasFailure != true

                override fun loadMore() = loadHistory(reset = false)
            },
            config = DpadGridController.Config(
                isEnabled = { _binding != null && isResumed },
                enableCenterLongPressToLongClick = false,
            ),
        ).also { it.install() }
    }

    private fun installViewportFillMonitor() {
        viewportFillMonitor?.release()
        viewportFillMonitor = binding.recycler.installGridViewportFillMonitor(
            isEnabled = { _binding != null && isResumed },
            canLoadMore = { hasMore && loadJob?.isActive != true && gridLoadState?.hasFailure != true },
            loadMore = { loadHistory(reset = false) },
        )
    }

    private fun loadHistory(reset: Boolean) {
        val b = _binding ?: return
        if (loadJob?.isActive == true) return
        if (reset) {
            nextPage = 1
            hasMore = false
            if (adapter.itemCount == 0) items.clear()
        }
        if (!reset && !hasMore) return
        val pageNum = if (reset) 1 else nextPage
        gridLoadState?.loading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (sessionStore.currentAccount() == null) {
                    showIqiyiLoginPrompt()
                    return@launch
                }
                val page = historyApi.loadPage(pageNum)
                if (_binding !== b) return@launch
                if (reset) {
                    items.clear()
                    items.addAll(page.items)
                    adapter.submit(items.map { it.toVideoCard() })
                } else {
                    val existing = items.mapTo(HashSet()) { it.videoId }
                    val fresh = page.items.filter { existing.add(it.videoId) }
                    items.addAll(fresh)
                    if (fresh.isNotEmpty()) adapter.append(fresh.map { it.toVideoCard() })
                }
                nextPage = pageNum + 1
                hasMore = page.hasMore
                AppLog.d("IqiyiHistory", "page loaded count=${page.items.size}")
                gridLoadState?.content()
                viewportFillMonitor?.scheduleCheck()
                consumePendingFocus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (_binding !== b) return@launch
                if (error is IqiyiLoginRequiredException) showIqiyiLoginPrompt()
                else gridLoadState?.failure(error)
                AppLog.w("IqiyiHistory", "load failed type=${error.javaClass.simpleName}")
            } finally {
                if (_binding === b) b.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showIqiyiLoginPrompt() {
        val b = _binding ?: return
        b.loadStatePanel.isFocusable = false
        b.tvLoadState.text = getString(R.string.iqiyi_history_login_prompt)
        b.progressLoadState.visibility = View.GONE
        b.btnRetry.visibility = View.VISIBLE
        b.btnRetry.text = getString(R.string.mango_account_action_login)
        b.btnRetry.setOnClickListener {
            refreshAfterPlayback = true
            startActivity(Intent(requireContext(), IqiyiLoginActivity::class.java))
        }
        b.loadStatePanel.visibility = View.VISIBLE
    }

    private fun openHistoryItem(position: Int) {
        val item = items.getOrNull(position) ?: return
        if (resolvingPlayback) return
        resolvingPlayback = true
        refreshAfterPlayback = true
        AppToast.show(requireContext(), getString(R.string.iqiyi_search_resolving))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val stream = IqiyiApi(sessionStore.cookies).resolveStream(item.tvId, item.videoId)
                if (stream == null) {
                    if (item.pageUrl.isNotBlank()) {
                        startActivity(
                            IqiyiWebPlayerActivity.intent(
                                context = requireContext(),
                                url = item.pageUrl,
                                title = item.title,
                                desktop = false,
                            ),
                        )
                    } else {
                        AppToast.show(requireContext(), getString(R.string.iqiyi_search_play_failed))
                    }
                    return@launch
                }
                startActivity(
                    IqiyiPlayerActivity.intent(
                        context = requireContext(),
                        streamUrl = stream.url,
                        title = item.title,
                        startSec = item.watchedSec.toLong(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("IqiyiHistory", "resolve stream failed", error)
                AppToast.show(requireContext(), getString(R.string.iqiyi_search_play_failed))
            } finally {
                resolvingPlayback = false
            }
        }
    }

    private fun consumePendingFocus() {
        if (!pendingFocusFirstItem || !isResumed || _binding == null) return
        val b = binding
        val focused = activity?.currentFocus
        if (focused != null && focused != b.recycler && FocusTreeUtils.isDescendantOf(focused, b.recycler)) {
            pendingFocusFirstItem = false
            focusBookmark.finishFocusRequest()
            return
        }
        if (adapter.itemCount == 0) {
            if (b.btnRetry.isShown) b.btnRetry.requestFocus() else b.recycler.requestFocus()
            pendingFocusFirstItem = false
            focusBookmark.finishFocusRequest()
            return
        }
        b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
            if (!focusBookmark.requestFocus(b.recycler) { _binding === b && isResumed }) {
                b.recycler.requestFocusAdapterPositionReliable(
                    position = 0,
                    smoothScroll = false,
                    isAlive = { _binding === b && isResumed },
                    onFocused = {},
                )
            }
            pendingFocusFirstItem = false
            focusBookmark.finishFocusRequest()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        _binding?.recycler?.let { focusBookmark.save(it, outState) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        gridLoadState?.release()
        gridLoadState = null
        _binding = null
        super.onDestroyView()
    }

    private fun IqiyiHistoryItem.toVideoCard(): VideoCard = VideoCard(
        bvid = "iqiyi:$tvId:$videoId",
        cid = null,
        title = title,
        coverUrl = coverUrl,
        durationSec = durationSec,
        ownerName = "爱奇艺",
        ownerFace = null,
        view = null,
        danmaku = null,
        pubDate = null,
        pubDateText = buildString {
            if (albumTitle.isNotBlank() && albumTitle != title) append(albumTitle)
            if (progressPercent > 0) {
                if (isNotEmpty()) append(" · ")
                append("已观看 ").append(progressPercent).append("%")
            }
        }.ifBlank { "爱奇艺" },
        coverLeftBottomText = if (progressPercent in 1..99) "$progressPercent%" else null,
    )

    private companion object {
        const val PREFETCH_ITEMS = 8
    }
}
