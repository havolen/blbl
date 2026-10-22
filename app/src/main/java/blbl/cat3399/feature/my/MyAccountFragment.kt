package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import blbl.cat3399.R
import blbl.cat3399.core.account.AccountAvatar
import blbl.cat3399.core.account.BiliSession
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.navigation.MainNavigationKeys
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.databinding.FragmentMyAccountBinding
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.MainNavigationHost
import kotlinx.coroutines.launch

/** Account and application tools; personal pages reuse the same login gate and data fragments. */
class MyAccountFragment : Fragment(), BackPressHandler, TabContentSwitchFocusHost {
    private var _binding: FragmentMyAccountBinding? = null
    private val binding get() = _binding!!
    private var lastActionId = R.id.btn_history
    private var personalFocusRequestToken = 0
    private val backStackListener = FragmentManager.OnBackStackChangedListener { updateVisibility() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastActionId = savedInstanceState?.getInt("last_action", R.id.btn_history) ?: R.id.btn_history
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Older saved focus ids may point at the removed Settings/About actions.
        if (view.findViewById<View>(lastActionId) == null) lastActionId = R.id.btn_account
        val actions = listOf(
            binding.btnHistory to MainNavigationKeys.MY_HISTORY,
            binding.btnFavorites to MainNavigationKeys.MY_FAVORITES,
            binding.btnBangumi to MainNavigationKeys.MY_BANGUMI,
            binding.btnDrama to MainNavigationKeys.MY_DRAMA,
            binding.btnWatchLater to MainNavigationKeys.MY_WATCH_LATER,
            binding.btnLikes to MainNavigationKeys.MY_LIKES,
        )
        actions.forEach { (button, key) -> button.setOnClickListener { openPersonal(key) } }
        binding.btnAccount.setOnClickListener { (activity as? MainNavigationHost)?.openAccountDetails() }
        actions.forEach { (button, _) ->
            button.setOnFocusChangeListener { _, focused -> if (focused) lastActionId = button.id }
        }
        binding.btnAccount.setOnFocusChangeListener { _, focused ->
            if (focused) lastActionId = binding.btnAccount.id
            binding.btnAccount.isSelected = focused
            binding.accountName.isSelected = focused
            binding.accountStatus.isSelected = focused
            binding.accountAction.isSelected = focused
            binding.accountArrow.isSelected = focused
            binding.avatar.isSelected = focused
        }
        childFragmentManager.addOnBackStackChangedListener(backStackListener)
        updateVisibility()
        if (binding.personalContainer.visibility == View.VISIBLE) {
            requestPersonalPageFocus(++personalFocusRequestToken)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BiliSession.state.collect { renderAccount(it) }
            }
        }
    }

    private fun renderAccount(session: BiliSession.State) {
        val b = _binding ?: return
        b.accountName.text = session.name ?: getString(
            if (session.canAccessPersonal) R.string.my_account_title else R.string.my_account_signed_out_title,
        )
        b.accountStatus.text = when (session.status) {
            BiliSession.Status.SignedOut -> getString(R.string.my_account_signin_hint)
            BiliSession.Status.Unknown -> getString(R.string.my_account_state_unverified)
            BiliSession.Status.Authenticated -> session.mid?.let {
                getString(R.string.label_uid_fmt, it.toString())
            } ?: getString(R.string.my_account_logged_in)
        }
        b.accountAction.text = getString(
            if (session.canAccessPersonal) R.string.action_manage_account else R.string.action_scan_login,
        )
        val avatarUrl = AccountAvatar.resolve(session)
        if (avatarUrl == null) {
            b.avatar.setImageResource(R.drawable.ic_nav_my)
            b.avatar.imageTintList = ContextCompat.getColorStateList(
                requireContext(),
                R.color.blbl_my_account_avatar_tint,
            )
        } else {
            b.avatar.imageTintList = null
            ImageLoader.loadInto(b.avatar, ImageUrl.avatar(avatarUrl))
        }
    }

    private fun openPersonal(key: String) {
        if (childFragmentManager.isStateSaved) return
        AppLog.d("My", "account page open key=$key")
        val focusToken = ++personalFocusRequestToken
        childFragmentManager.beginTransaction().setReorderingAllowed(true)
            .replace(R.id.personal_container, MyFragment.newInstance(key))
            .addToBackStack(key).commit()
        childFragmentManager.executePendingTransactions()
        requestPersonalPageFocus(focusToken)
    }

    private fun requestPersonalPageFocus(token: Int, attemptsLeft: Int = 30) {
        val container = _binding?.personalContainer ?: return
        container.doOnPreDraw {
            if (_binding == null || token != personalFocusRequestToken || !isResumed) return@doOnPreDraw
            if (!requestFocusCurrentPagePrimaryItemFromContentSwitch() && attemptsLeft > 0) {
                container.postDelayed({ requestPersonalPageFocus(token, attemptsLeft - 1) }, 16L)
            }
        }
    }

    fun showOverview() {
        if (childFragmentManager.isStateSaved) return
        personalFocusRequestToken++
        childFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        updateVisibility()
    }

    private fun updateVisibility() {
        val b = _binding ?: return
        val personal = childFragmentManager.backStackEntryCount > 0
        b.overview.visibility = if (personal) View.GONE else View.VISIBLE
        b.personalContainer.visibility = if (personal) View.VISIBLE else View.GONE
    }

    override fun handleBackPressed(): Boolean {
        val current = childFragmentManager.findFragmentById(R.id.personal_container) ?: return false
        // A second Back from the global navigation returns to the account overview.
        if (!binding.personalContainer.hasFocus() || (current as? BackPressHandler)?.handleBackPressed() != true) {
            showOverview()
            requestOverviewFocus()
        }
        return true
    }

    private fun requestOverviewFocus(attemptsLeft: Int = 20, token: Int = personalFocusRequestToken) {
        if (token != personalFocusRequestToken) return
        val b = _binding ?: return
        b.root.post {
            if (_binding == null || token != personalFocusRequestToken) return@post
            val target = b.root.findViewById<View>(lastActionId)
            if (target?.requestFocus() == true) {
                AppLog.d("My", "overview focus restored id=$lastActionId")
                return@post
            }
            if (attemptsLeft > 0) {
                b.root.postDelayed({ requestOverviewFocus(attemptsLeft - 1, token) }, 16L)
            } else {
                AppLog.w(
                    "My",
                    "overview focus failed id=$lastActionId target=${target != null} " +
                        "shown=${target?.isShown} attached=${target?.isAttachedToWindow}",
                )
            }
        }
    }

    override fun requestFocusCurrentPagePrimaryItemFromContentSwitch(): Boolean {
        val b = _binding ?: return false
        if (b.personalContainer.visibility == View.VISIBLE) {
            val child = childFragmentManager.findFragmentById(R.id.personal_container)
            if ((child as? TabContentSwitchFocusHost)?.requestFocusCurrentPagePrimaryItemFromContentSwitch() == true) return true
            return child?.view?.findViewById<View>(R.id.btn_login)?.requestFocus() == true
        }
        return b.root.findViewById<View>(lastActionId)?.requestFocus() == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("last_action", lastActionId)
    }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        _binding = null
        super.onDestroyView()
    }
}
