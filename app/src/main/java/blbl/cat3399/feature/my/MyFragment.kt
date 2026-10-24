package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import blbl.cat3399.core.account.BiliSession
import blbl.cat3399.core.navigation.MainNavigationKeys
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.PageSectionFocusHost
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.databinding.FragmentMyContainerBinding
import blbl.cat3399.ui.BackPressHandler
import kotlinx.coroutines.launch

class MyFragment : Fragment(), BackPressHandler, MyNavigator, TabContentSwitchFocusHost, PageSectionFocusHost {
    private var _binding: FragmentMyContainerBinding? = null
    private val binding get() = _binding!!
    private var requestedTopNavigationKey: String = blbl.cat3399.core.navigation.MainNavigationKeys.MY_HISTORY
    private var renderedRevision: Long? = null
    private var renderedMid: Long? = null

    fun requestedTopNavigationKey(): String = requestedTopNavigationKey

    fun selectTopNavigationKey(key: String): Boolean {
        if (MyTabs.tabKeyFor(key) == null) return false
        val changed = requestedTopNavigationKey != key
        requestedTopNavigationKey = key
        if (changed && childFragmentManager.backStackEntryCount > 0 && !childFragmentManager.isStateSaved) {
            childFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        if (changed && _binding != null && !childFragmentManager.isStateSaved) {
            showRootForLoginState(BiliSession.state.value)
        }
        (childFragmentManager.findFragmentById(R.id.my_container) as? MyTabsFragment)
            ?.selectTopNavigationKey(key)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (savedInstanceState?.getString("requested_navigation_key") ?: arguments?.getString("requested_navigation_key"))?.takeIf { MyTabs.tabKeyFor(it) != null }?.let {
            requestedTopNavigationKey = it
        }
        renderedMid = savedInstanceState?.getLong("rendered_account_mid")?.takeIf { it > 0 }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("requested_navigation_key", requestedTopNavigationKey)
        renderedMid?.let { outState.putLong("rendered_account_mid", it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyContainerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BiliSession.state.collect { session ->
                    view.post {
                        if (_binding?.root === view && session.revision == BiliSession.state.value.revision) {
                            showRootForLoginState(session)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val view = binding.root
        view.post {
            if (_binding?.root !== view || !isAdded) return@post
            showRootForLoginState(BiliSession.state.value)
            (childFragmentManager.findFragmentById(R.id.my_container) as? MyTabsFragment)
                ?.selectTopNavigationKey(requestedTopNavigationKey)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun handleBackPressed(): Boolean {
        if (childFragmentManager.popBackStackImmediate()) return true
        val current = childFragmentManager.findFragmentById(R.id.my_container)
        return (current as? BackPressHandler)?.handleBackPressed() == true
    }

    override fun requestFocusCurrentPagePrimaryItemFromContentSwitch(): Boolean {
        val current = childFragmentManager.findFragmentById(R.id.my_container) as? TabContentSwitchFocusHost ?: return false
        return current.requestFocusCurrentPagePrimaryItemFromContentSwitch()
    }

    override fun requestFocusPageSection(): Boolean {
        val current = childFragmentManager.findFragmentById(R.id.my_container) as? PageSectionFocusHost ?: return false
        return current.requestFocusPageSection()
    }

    override fun openFavFolder(mediaId: Long, title: String) {
        if (_binding == null || childFragmentManager.isStateSaved) return
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.my_container, MyFavFolderDetailFragment.newInstance(mediaId = mediaId, title = title))
            .addToBackStack(null)
            .commit()
    }

    override fun openBangumiDetail(seasonId: Long, isDrama: Boolean, continueEpId: Long?, continueEpIndex: Int?) {
        if (!isAdded) return
        startActivity(
            Intent(requireContext(), BangumiDetailActivity::class.java)
                .putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, seasonId)
                .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, isDrama)
                .apply { continueEpId?.let { putExtra(BangumiDetailActivity.EXTRA_CONTINUE_EP_ID, it) } }
                .apply { continueEpIndex?.let { putExtra(BangumiDetailActivity.EXTRA_CONTINUE_EP_INDEX, it) } },
        )
    }

    private fun showRootForLoginState(session: BiliSession.State) {
        if (_binding == null || childFragmentManager.isStateSaved) return
        val accountChanged = renderedMid != session.mid || (renderedRevision != null && renderedRevision != session.revision)
        renderedMid = session.mid
        renderedRevision = session.revision
        if (accountChanged) {
            childFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        // History includes Mango's independent account, so keep its source switch reachable
        // even when the Bilibili session is unavailable. Each source shows its own login prompt.
        val wantLogin = !session.canAccessPersonal && requestedTopNavigationKey != MainNavigationKeys.MY_HISTORY
        val current = childFragmentManager.findFragmentById(R.id.my_container)
        if (!accountChanged) {
            if (wantLogin && current is MyLoginFragment) return
            if (!wantLogin && current != null && current !is MyLoginFragment) return
        }

        val fragment = if (wantLogin) MyLoginFragment() else MyTabsFragment()
        AppLog.d("My", "showRoot loginRequired=$wantLogin")
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.my_container, fragment)
            .commitNow()
        (fragment as? MyTabsFragment)?.selectTopNavigationKey(requestedTopNavigationKey)
    }

    companion object {
        fun newInstance(key: String? = null) = MyFragment().apply {
            arguments = Bundle().apply { key?.let { putString("requested_navigation_key", it) } }
        }
    }
}
