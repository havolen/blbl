package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.bindDpadSectionNavigation
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.PageSectionFocusHost
import blbl.cat3399.core.ui.requestFocusSelectedTab
import blbl.cat3399.databinding.FragmentMyHistoryHostBinding
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler
import com.google.android.material.tabs.TabLayout

/** Keeps the global History entry stable while allowing its data source to change in-page. */
class MyHistoryHostFragment : Fragment(), MyTabSwitchFocusTarget, MyTabContentSwitchFocusHost, PageSectionFocusHost, RefreshKeyHandler, BackPressHandler {
    private var _binding: FragmentMyHistoryHostBinding? = null
    private val binding get() = _binding!!
    private var activeSource: String? = null
    private var updatingSourceTabs = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyHistoryHostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding
        val initialSource = BiliClient.prefs.historySource
        val tabs = b.historySourceTabs
        tabs.addTab(tabs.newTab().setText(R.string.history_source_bilibili), false)
        tabs.addTab(tabs.newTab().setText(R.string.history_source_iqiyi), false)
        tabs.addTab(tabs.newTab().setText(R.string.history_source_mango), false)
        tabs.addTab(tabs.newTab().setText(R.string.history_source_tencent), false)
        tabs.getTabAt(tabPositionFor(initialSource))?.select()
        tabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (!updatingSourceTabs) showSource(sourceForPosition(tab.position), persist = true)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
        tabs.enableDpadTabFocus(
            selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus },
            disableDefaultFocusHighlight = true,
        )
        tabs.bindDpadSectionNavigation(
            onDown = { activeFragmentAs<MyTabSwitchFocusTarget>()?.requestFocusFirstItemFromTabSwitch() == true },
            onUp = { (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true },
        )
        showSource(initialSource, persist = false)
        b.historySourceTabs.post { requestSourceButtonFocus() }
        (activity as? MainNavigationHost)?.onSectionTabVisibilityChanged()
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean = requestSourceButtonFocus()

    override fun requestFocusPageSection(): Boolean = requestSourceButtonFocus()

    override fun requestFocusCurrentPageFirstItemFromContentSwitch(): Boolean = requestFocusFirstItemFromTabSwitch()

    override fun handleRefreshKey(): Boolean = activeFragmentAs<RefreshKeyHandler>()?.handleRefreshKey() == true

    override fun handleBackPressed(): Boolean = activeFragmentAs<BackPressHandler>()?.handleBackPressed() == true

    fun requestSourceButtonFocus(): Boolean {
        val b = _binding ?: return false
        return b.historySourceTabs.requestFocusSelectedTab(fallbackPosition = tabPositionFor(activeSource ?: BiliClient.prefs.historySource)) {
            _binding === b
        }
    }

    private fun showSource(source: String, persist: Boolean) {
        if (childFragmentManager.isStateSaved) return
        val targetTag = tagFor(source)
        val target = childFragmentManager.findFragmentByTag(targetTag)
        if (activeSource == source && target != null) return

        val fragment = target ?: createFragment(source)
        val transaction = childFragmentManager.beginTransaction().setReorderingAllowed(true)
        if (target == null) {
            transaction.add(R.id.history_content, fragment, targetTag)
        } else {
            transaction.show(fragment)
        }
        childFragmentManager.fragments.filter { it !== fragment }.forEach { old ->
            transaction.hide(old).setMaxLifecycle(old, Lifecycle.State.STARTED)
        }
        transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED).commitNow()
        activeSource = source
        if (persist) BiliClient.prefs.historySource = source
        val b = _binding ?: return
        updatingSourceTabs = true
        b.historySourceTabs.getTabAt(tabPositionFor(source))?.select()
        updatingSourceTabs = false
    }

    private fun createFragment(source: String): Fragment =
        when (source) {
            AppPrefs.HISTORY_SOURCE_IQIYI -> IqiyiHistoryFragment()
            AppPrefs.HISTORY_SOURCE_MANGO -> MangoHistoryFragment()
            AppPrefs.HISTORY_SOURCE_TENCENT -> TencentHistoryFragment()
            else -> MyHistoryFragment()
        }

    private inline fun <reified T> activeFragmentAs(): T? =
        activeSource?.let { childFragmentManager.findFragmentByTag(tagFor(it)) as? T }

    private fun sourceForPosition(position: Int): String =
        when (position) {
            IQIYI_TAB_POSITION -> AppPrefs.HISTORY_SOURCE_IQIYI
            MANGO_TAB_POSITION -> AppPrefs.HISTORY_SOURCE_MANGO
            TENCENT_TAB_POSITION -> AppPrefs.HISTORY_SOURCE_TENCENT
            else -> AppPrefs.HISTORY_SOURCE_BILIBILI
        }

    private fun tabPositionFor(source: String): Int =
        when (source) {
            AppPrefs.HISTORY_SOURCE_IQIYI -> IQIYI_TAB_POSITION
            AppPrefs.HISTORY_SOURCE_MANGO -> MANGO_TAB_POSITION
            AppPrefs.HISTORY_SOURCE_TENCENT -> TENCENT_TAB_POSITION
            else -> BILIBILI_TAB_POSITION
        }

    private fun tagFor(source: String): String = "history-source:$source"

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        (activity as? MainNavigationHost)?.onSectionTabVisibilityChanged()
    }

    private companion object {
        const val BILIBILI_TAB_POSITION = 0
        const val IQIYI_TAB_POSITION = 1
        const val MANGO_TAB_POSITION = 2
        const val TENCENT_TAB_POSITION = 3
    }
}
