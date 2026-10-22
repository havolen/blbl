package blbl.cat3399.feature.my

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragment
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragmentAs
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentMyTabsBinding
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler

class MyTabsFragment : Fragment(), MyTabContentSwitchFocusHost, BackPressHandler {
    private var _binding: FragmentMyTabsBinding? = null
    private val binding get() = _binding!!

    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private val tabs: List<MyTabSpec> = MyTabs.all
    private var pendingFocusFirstItemFromContentSwitch = false

    fun currentTopNavigationKey(): String? = MyTabs.topNavigationKeyFor(tabs.getOrNull(_binding?.viewPager?.currentItem ?: 0)?.key.orEmpty())

    fun selectTopNavigationKey(key: String): Boolean {
        val tabKey = MyTabs.tabKeyFor(key) ?: return false
        val position = tabs.indexOfFirst { it.key == tabKey }
        if (position < 0) return false
        val pager = _binding?.viewPager ?: return false
        if (position != pager.currentItem) pager.setCurrentItem(position, false)
        else currentTopNavigationKey()?.let { (activity as? MainNavigationHost)?.onMyPageSelected(it) }
        return true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.viewPager.adapter = MyPagerAdapter(this, tabs)
        // Category switching now belongs to the global top navigation.
        binding.viewPager.isUserInputEnabled = false
        pageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val tab = tabs.getOrNull(position)
                    AppLog.d("My", "page selected pos=$position key=${tab?.key} t=${SystemClock.uptimeMillis()}")
                    MyTabs.topNavigationKeyFor(tab?.key.orEmpty())?.let {
                        (activity as? MainNavigationHost)?.onMyPageSelected(it)
                    }
                    if (pendingFocusFirstItemFromContentSwitch && focusCurrentPageFirstItemFromContentSwitch()) {
                        pendingFocusFirstItemFromContentSwitch = false
                    }
                }
            }.also(binding.viewPager::registerOnPageChangeCallback)

        val requestedKey = (parentFragment as? MyFragment)?.requestedTopNavigationKey()
        if (requestedKey != null) selectTopNavigationKey(requestedKey)
        else currentTopNavigationKey()?.let { (activity as? MainNavigationHost)?.onMyPageSelected(it) }
    }

    private fun focusCurrentPageFirstItem(): Boolean {
        val byTag = findCurrentViewPagerChildFragment(binding.viewPager)
        val target = byTag as? MyTabSwitchFocusTarget
        if (target != null) return target.requestFocusFirstItemFromTabSwitch()

        val pageFragment =
            if (byTag?.view?.findViewById<RecyclerView?>(R.id.recycler) != null) {
                byTag
            } else {
                childFragmentManager.fragments.firstOrNull {
                    it.isVisible && it.view?.findViewById<RecyclerView?>(R.id.recycler) != null
                }
            } ?: return false
        val recycler = pageFragment.view?.findViewById<RecyclerView?>(R.id.recycler) ?: return false

        recycler.postIfAlive(isAlive = { _binding != null }) {
            val first = recycler.findViewHolderForAdapterPosition(0)?.itemView
            when {
                first != null -> first.requestFocus()
                recycler.adapter?.itemCount == 0 -> recycler.requestFocus()
                else -> {
                    recycler.scrollToPosition(0)
                    recycler.postIfAlive(isAlive = { _binding != null }) {
                        recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                    }
                }
            }
        }
        return true
    }

    private fun focusCurrentPageFirstItemFromContentSwitch(): Boolean {
        val target = findCurrentViewPagerChildFragmentAs<MyTabSwitchFocusTarget>(binding.viewPager) ?: return false
        return target.requestFocusFirstItemFromTabSwitch()
    }

    override fun requestFocusCurrentPageFirstItemFromContentSwitch(): Boolean {
        pendingFocusFirstItemFromContentSwitch = true
        if (focusCurrentPageFirstItemFromContentSwitch()) pendingFocusFirstItemFromContentSwitch = false
        return true
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        if (!b.viewPager.hasFocus()) return false

        return when (BiliClient.prefs.mainBackFocusScheme) {
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_A,
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_C,
            -> (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true

            AppPrefs.MAIN_BACK_FOCUS_SCHEME_B -> {
                val firstVisibleMyKey = MyTabs.all
                    .mapNotNull { MyTabs.topNavigationKeyFor(it.key) }
                    .firstOrNull { it in BiliClient.prefs.topNavigationVisibleKeys }
                if (firstVisibleMyKey != null && currentTopNavigationKey() != firstVisibleMyKey) {
                    pendingFocusFirstItemFromContentSwitch = true
                    selectTopNavigationKey(firstVisibleMyKey)
                } else {
                    (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
                }
            }

            else -> (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
        }
    }

    override fun onDestroyView() {
        pageCallback?.let { _binding?.viewPager?.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        _binding = null
        super.onDestroyView()
    }
}
