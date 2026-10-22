package blbl.cat3399.feature.home

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.TabContentFocusTarget
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragmentAs
import blbl.cat3399.core.ui.postDelayedIfAlive
import blbl.cat3399.databinding.FragmentHomeBinding
import blbl.cat3399.feature.video.VideoGridTabSwitchFocusHost
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.MainNavigationHost
import blbl.cat3399.ui.RefreshKeyHandler

class HomeFragment : Fragment(), VideoGridTabSwitchFocusHost, BackPressHandler {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null
    private var pendingPageKey: String? = null
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToRecommend: Boolean = false
    private var pendingBackToRecommendToken: Int = 0
    private var pendingBackToRecommendAttempts: Int = 0

    val currentPageKey: String
        get() = HomeTabs.all.getOrNull(_binding?.viewPager?.currentItem ?: 0)?.key ?: HomeTabs.KEY_RECOMMEND

    private fun requestCurrentPageFocusFromContentSwitch(): Boolean {
        val b = _binding ?: return false
        val page = findCurrentViewPagerChildFragmentAs<TabContentFocusTarget>(b.viewPager) ?: return false
        return page.requestFocusPrimaryItemFromContentSwitch()
    }

    private fun maybeRequestRecommendFocusFromBack(): Boolean {
        val b = _binding ?: return false
        if (!pendingFocusFirstCardFromBackToRecommend) return false
        if (currentPageKey != HomeTabs.KEY_RECOMMEND) return false

        val page = findCurrentViewPagerChildFragmentAs<TabContentFocusTarget>(b.viewPager)
        if (page != null) {
            page.requestFocusPrimaryItemFromBackToTab0()
            pendingFocusFirstCardFromBackToRecommend = false
            pendingBackToRecommendAttempts = 0
            return true
        }

        if (pendingBackToRecommendAttempts <= 0) return false
        val token = pendingBackToRecommendToken
        pendingBackToRecommendAttempts--
        b.viewPager.postDelayedIfAlive(
            delayMillis = 16L,
            isAlive = { _binding === b && pendingFocusFirstCardFromBackToRecommend && pendingBackToRecommendToken == token },
        ) {
            maybeRequestRecommendFocusFromBack()
        }
        return true
    }

    private fun refreshCurrentPageFromTopReselect(): Boolean {
        val b = _binding ?: return false
        val page = findCurrentViewPagerChildFragmentAs<RefreshKeyHandler>(b.viewPager) ?: return false
        return page.handleRefreshKey()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding
        b.viewPager.adapter = HomePagerAdapter(this, HomeTabs.all)
        // These pages are selected from the global top navigation.
        b.viewPager.isUserInputEnabled = false
        val requested = pendingPageKey ?: arguments?.getString(ARG_PAGE_KEY)
        val initialPosition = HomeTabs.all.indexOfFirst { it.key == requested }.takeIf { it >= 0 } ?: 0
        if (initialPosition != 0) b.viewPager.setCurrentItem(initialPosition, false)

        pageCallback =
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val key = HomeTabs.all.getOrNull(position)?.key ?: return
                    AppLog.d("Home", "page selected pos=$position key=$key t=${SystemClock.uptimeMillis()}")
                    (activity as? MainNavigationHost)?.onHomePageSelected(key)
                    if (pendingFocusFirstCardFromBackToRecommend) {
                        maybeRequestRecommendFocusFromBack()
                    } else if (pendingFocusFirstCardFromContentSwitch && requestCurrentPageFocusFromContentSwitch()) {
                        pendingFocusFirstCardFromContentSwitch = false
                    }
                }
            }.also(b.viewPager::registerOnPageChangeCallback)

        pendingPageKey = null
        (activity as? MainNavigationHost)?.onHomePageSelected(currentPageKey)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainNavigationHost)?.onHomePageSelected(currentPageKey)
    }

    fun selectPage(pageKey: String): Boolean {
        if (HomeTabs.all.none { it.key == pageKey }) return false
        val b = _binding
        if (b == null) {
            pendingPageKey = pageKey
            return true
        }
        val position = HomeTabs.all.indexOfFirst { it.key == pageKey }
        if (position != b.viewPager.currentItem) b.viewPager.setCurrentItem(position, false)
        else (activity as? MainNavigationHost)?.onHomePageSelected(pageKey)
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
                if (currentPageKey != HomeTabs.KEY_RECOMMEND) {
                    pendingFocusFirstCardFromBackToRecommend = true
                    pendingFocusFirstCardFromContentSwitch = false
                    pendingBackToRecommendToken++
                    pendingBackToRecommendAttempts = 30
                    val recommendPosition = HomeTabs.all.indexOfFirst { it.key == HomeTabs.KEY_RECOMMEND }
                    b.viewPager.setCurrentItem(recommendPosition, false)
                    true
                } else {
                    (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
                }
            }

            else -> (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
        }
    }

    override fun requestFocusCurrentPageFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (requestCurrentPageFocusFromContentSwitch()) pendingFocusFirstCardFromContentSwitch = false
        return true
    }

    override fun onDestroyView() {
        pageCallback?.let { _binding?.viewPager?.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_PAGE_KEY = "home.pageKey"

        fun newInstance() = HomeFragment()

        fun newInstance(pageKey: String) = HomeFragment().apply {
            arguments = Bundle().apply { putString(ARG_PAGE_KEY, pageKey) }
        }
    }
}
