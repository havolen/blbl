package blbl.cat3399.feature.search

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.QrCodeBitmap
import blbl.cat3399.databinding.FragmentSearchBinding
import blbl.cat3399.feature.my.BangumiDetailActivity
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.ui.MainNavigationHost

class SearchFragment : Fragment(), BackPressHandler, RefreshKeyHandler {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val state = SearchState()
    private lateinit var adapters: SearchAdapters
    private var renderer: SearchRenderer? = null
    private var interactor: SearchInteractor? = null
    private var phoneSearchServer: PhoneSearchServer? = null
    private var phoneUrl: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ensureAdapters()
        val interactor = SearchInteractor(fragment = this, state = state)
        val renderer = SearchRenderer(fragment = this, binding = binding, state = state, interactor = interactor, adapters = adapters)
        interactor.renderer = renderer

        this.interactor = interactor
        this.renderer = renderer

        interactor.reloadHistory()
        renderer.setupInput()
        renderer.setupResults()
        renderer.applyUiScale()
        preparePhoneSearchPanel()
        interactor.loadHotAndDefault()
        interactor.scheduleMiddleList(state.query)

        if (state.resultsVisible) {
            renderer.showResults()
        } else {
            renderer.showInput()
        }
        if (savedInstanceState == null) {
            renderer.focusInitialInput()
        }
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        val r = renderer ?: return false
        val resultsVisible = b.panelResults.visibility == View.VISIBLE
        val focused = activity?.currentFocus
        if (focused == null || !FocusTreeUtils.isDescendantOf(focused, b.root)) return false
        AppLog.d("Back", "SearchFragment handleBackPressed resultsVisible=$resultsVisible")
        return if (resultsVisible) {
            r.showInput()
            r.focusInitialInput()
            true
        } else {
            // Search (input panel) behaves like Dynamic: Back in page content returns to the sidebar.
            (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
        }
    }

    override fun onResume() {
        super.onResume()
        renderer?.onResume()
        interactor?.renderInputPanelFromState()
        binding?.tvQuery?.post {
            val b = _binding ?: return@post
            val visible = b.tvQuery.text?.toString().orEmpty()
            if (visible != state.query) interactor?.setQuery(visible)
        }
        preparePhoneSearchPanel()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Returning from Bangumi/Media detail uses add+hide, so SearchFragment won't get onResume().
            // Re-run the minimal "shown again" hooks to restore focus and other pending UI actions.
            renderer?.onShown()
        }
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.panelResults.visibility != View.VISIBLE) return false
        if (b.swipeRefresh.isRefreshing) return true
        interactor?.resetAndLoad()
        return true
    }

    override fun onDestroyView() {
        phoneSearchServer?.stop()
        phoneSearchServer = null
        phoneUrl = null
        interactor?.release()
        interactor = null
        renderer?.release()
        renderer = null
        state.lastAppliedUiScale = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = SearchFragment()
    }

    private fun ensureAdapters() {
        if (::adapters.isInitialized) return
        adapters = SearchAdapters(this, state)
    }

    internal fun onSearchKeyClicked(key: String) {
        interactor?.onKeyClicked(key)
    }

    internal fun onSearchKeywordClicked(keyword: String) {
        interactor?.onKeywordClicked(keyword)
    }

    internal fun openMangoSearch() {
        openMangoSearch(state.query)
    }

    internal fun openIqiyiSearch() {
        openIqiyiSearch(state.query)
    }

    internal fun openIqiyiSearch(keyword: String) {
        val query = keyword.trim()
        if (query.isBlank() || !isAdded) return
        startActivity(
            Intent(requireContext(), IqiyiSearchActivity::class.java)
                .putExtra(IqiyiSearchActivity.EXTRA_QUERY, query),
        )
    }

    internal fun openTencentSearch() {
        AppToast.show(requireContext(), getString(R.string.tencent_search_unavailable))
    }

    internal fun openMangoSearch(keyword: String) {
        val query = keyword.trim()
        if (query.isBlank() || !isAdded) return
        startActivity(
            Intent(requireContext(), MangoSearchActivity::class.java)
                .putExtra(MangoSearchActivity.EXTRA_QUERY, query),
        )
    }

    /** Starts the LAN input server and binds it to the always-visible QR panel. */
    internal fun preparePhoneSearchPanel() {
        val b = _binding ?: return
        val server = phoneSearchServer ?: PhoneSearchServer().also { phoneSearchServer = it }
        val url = server.start()
        if (url.isNullOrBlank()) {
            b.ivPhoneQr.setImageDrawable(null)
            b.tvPhoneUrl.text = ""
            b.tvPhoneHint.setText(R.string.search_phone_start_failed)
            return
        }
        if (phoneUrl != url) {
            phoneUrl = url
            val sizePx = (resources.displayMetrics.density * 200f).toInt().coerceAtLeast(1)
            b.ivPhoneQr.setImageBitmap(QrCodeBitmap.encode(url, sizePx))
            b.ivPhoneQr.contentDescription = getString(R.string.search_phone_title)
            b.tvPhoneUrl.text = getString(R.string.search_phone_url, url)
        }
        b.tvPhoneHint.setText(R.string.search_phone_hint)
        b.tvPhoneQuery.text =
            getString(
                R.string.search_phone_query,
                state.query.ifBlank { getString(R.string.search_phone_empty) },
            )

        server.onQueryChanged = { text ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                interactor?.setQuery(text)
            }
        }
        server.onSearchRequested = { engine, text ->
            activity?.runOnUiThread {
                if (!isAdded || !isResumed) return@runOnUiThread
                interactor?.setQuery(text)
                when (engine) {
                    PhoneSearchServer.Engine.BILI -> interactor?.performSearch()
                    PhoneSearchServer.Engine.IQIYI -> openIqiyiSearch(text)
                    PhoneSearchServer.Engine.MANGO -> openMangoSearch(text)
                }
            }
        }
    }

    internal fun removeSearchHistoryAndRestoreFocus(keyword: String, position: Int) {
        interactor?.removeHistory(keyword, position)
    }

    internal fun openSearchVideoAt(position: Int) {
        renderer?.openVideoAt(position)
    }

    internal fun openSearchVideoDetailAt(position: Int) {
        renderer?.openDetailAt(position)
    }

    internal fun removeSearchVideoCardAndRestoreFocus(stableKey: String) {
        if (!::adapters.isInitialized) return
        _binding?.recyclerResults?.removeVideoCardAndRestoreFocus(
            adapter = adapters.videoAdapter,
            stableKey = stableKey,
            isAlive = { _binding != null && isResumed },
        )
    }

    internal fun openBangumiDetail(season: BangumiSeason, isDrama: Boolean) {
        if (!isAdded) return
        startActivity(
            Intent(requireContext(), BangumiDetailActivity::class.java)
                .putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, season.seasonId)
                .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, isDrama),
        )
    }
}
