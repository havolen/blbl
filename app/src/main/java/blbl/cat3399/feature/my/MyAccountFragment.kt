package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import blbl.cat3399.R
import blbl.cat3399.core.account.BiliSession
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.account.MangoSessionStore
import blbl.cat3399.core.account.PlatformVipStore
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.IqiyiApi
import blbl.cat3399.core.api.MangoAccountApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.navigation.MainNavigationKeys
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.core.ui.userScaledContext
import blbl.cat3399.core.ui.popup.PopupHandle
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.DialogAccountManageBinding
import blbl.cat3399.databinding.FragmentMyAccountBinding
import blbl.cat3399.feature.login.IqiyiLoginActivity
import blbl.cat3399.feature.login.MangoLoginActivity
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.feature.following.FollowingListActivity
import blbl.cat3399.ui.MainNavigationHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/** Account and application tools; personal pages reuse the same login gate and data fragments. */
class MyAccountFragment : Fragment(), BackPressHandler, TabContentSwitchFocusHost {
    private var _binding: FragmentMyAccountBinding? = null
    private val binding get() = _binding!!
    private var lastActionId = R.id.account_bilibili
    private var personalFocusRequestToken = 0
    private lateinit var mangoSessionStore: MangoSessionStore
    private lateinit var mangoAccountApi: MangoAccountApi
    private lateinit var iqiyiSessionStore: IqiyiSessionStore
    private lateinit var iqiyiApi: IqiyiApi
    private lateinit var vipStore: PlatformVipStore
    private var mangoRefreshJob: Job? = null
    private var iqiyiRefreshJob: Job? = null
    private var biliRefreshJob: Job? = null
    private var accountDialog: PopupHandle? = null
    private var accountDialogPlatform: AccountPlatform? = null
    private var dialogBinding: DialogAccountManageBinding? = null
    private var dialogLoadJob: Job? = null
    private var pendingLoginFocusPlatform: AccountPlatform? = null
    private var mangoLiveUid: String? = null
    private var mangoLiveVip: Boolean? = null
    private var iqiyiLiveUid: String? = null
    private var iqiyiLiveVip: Boolean? = null
    private val backStackListener = FragmentManager.OnBackStackChangedListener { updateVisibility() }

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val platform = pendingLoginFocusPlatform ?: return@registerForActivityResult
            val b = _binding ?: return@registerForActivityResult
            pendingLoginFocusPlatform = null
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                AppLog.d("My", "login returned platform=${platform.name.lowercase(Locale.ROOT)}")
            }
            initSessions()
            refreshPlatforms()
            b.root.post { focusPlatform(platform) }
            accountDialogPlatform?.let { open -> if (open == platform && accountDialog?.isShowing == true) refreshDialog(platform) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastActionId = savedInstanceState?.getInt("last_action", R.id.account_bilibili) ?: R.id.account_bilibili
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vipStore = PlatformVipStore(requireContext())
        initSessions()
        bindOverviewActions()
        bindTableFocus()
        renderTable()
        // Older saved focus ids may point at the removed Settings/About actions.
        if (view.findViewById<View>(lastActionId) == null) lastActionId = R.id.account_bilibili
        childFragmentManager.addOnBackStackChangedListener(backStackListener)
        updateVisibility()
        if (binding.personalContainer.visibility == View.VISIBLE) {
            requestPersonalPageFocus(++personalFocusRequestToken)
        } else {
            view.doOnPreDraw { requestOverviewFocusIfNeeded() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BiliSession.state.collect {
                    renderTable()
                    if (accountDialogPlatform == AccountPlatform.BILIBILI && accountDialog?.isShowing == true) {
                        refreshDialogHeader(AccountPlatform.BILIBILI)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        renderTable()
        refreshPlatforms()
        if (binding.personalContainer.visibility == View.VISIBLE) {
            if (binding.root.findFocus() == null) requestPersonalPageFocus(++personalFocusRequestToken)
        } else {
            requestOverviewFocusIfNeeded()
        }
    }

    override fun onDestroyView() {
        accountDialog?.dismiss()
        accountDialog = null
        accountDialogPlatform = null
        dialogBinding = null
        dialogLoadJob?.cancel()
        mangoRefreshJob?.cancel()
        iqiyiRefreshJob?.cancel()
        biliRefreshJob?.cancel()
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        _binding = null
        super.onDestroyView()
    }

    private fun initSessions() {
        mangoRefreshJob?.cancel()
        iqiyiRefreshJob?.cancel()
        mangoSessionStore = MangoSessionStore(requireContext())
        mangoAccountApi = MangoAccountApi(mangoSessionStore)
        iqiyiSessionStore = IqiyiSessionStore(requireContext())
        iqiyiApi = IqiyiApi(iqiyiSessionStore.cookies)
    }

    private fun bindOverviewActions() {
        val actions = listOf(
            binding.btnHistory to MainNavigationKeys.MY_HISTORY,
            binding.btnFavorites to MainNavigationKeys.MY_FAVORITES,
            binding.btnBangumi to MainNavigationKeys.MY_BANGUMI,
            binding.btnDrama to MainNavigationKeys.MY_DRAMA,
            binding.btnWatchLater to MainNavigationKeys.MY_WATCH_LATER,
            binding.btnLikes to MainNavigationKeys.MY_LIKES,
        )
        actions.forEach { (button, key) -> button.setOnClickListener { openPersonal(key) } }
        actions.forEach { (button, _) ->
            button.setOnFocusChangeListener { _, focused -> if (focused) lastActionId = button.id }
        }
    }

    private fun bindTableFocus() {
        val cells =
            listOf(
                binding.accountBilibili to binding.nameBilibili,
                binding.accountIqiyi to binding.nameIqiyi,
                binding.accountMango to binding.nameMango,
                binding.accountTencent to binding.nameTencent,
            )
        cells.forEach { (cell, name) ->
            cell.setOnFocusChangeListener { _, focused ->
                if (focused) lastActionId = cell.id
                name.isSelected = focused
            }
        }
        listOf(
            binding.removeBilibili,
            binding.removeIqiyi,
            binding.removeMango,
            binding.removeTencent,
        ).forEach { remove ->
            remove.setOnFocusChangeListener { _, focused -> if (focused) lastActionId = remove.id }
        }
    }

    private fun currentStates(): Map<AccountPlatform, PlatformState> {
        val bili = BiliSession.state.value
        val biliLoggedIn = bili.status != BiliSession.Status.SignedOut && bili.mid != null
        val biliKey = PlatformVipStore.bilibiliKey(bili.mid)
        val mango = mangoSessionStore.currentAccount()
        val mangoKey = PlatformVipStore.platformKey(mango?.uid)
        val iqiyi = iqiyiSessionStore.currentAccount()
        val iqiyiKey = PlatformVipStore.platformKey(iqiyi?.uid)
        val iqiyiWebsiteVip = if (iqiyi != null && iqiyi.uid == iqiyiLiveUid) iqiyiLiveVip else null
        val mangoWebsiteVip = if (mango != null && mango.uid == mangoLiveUid) mangoLiveVip else null
        return listOf(
            PlatformState(
                platform = AccountPlatform.BILIBILI,
                loggedIn = biliLoggedIn,
                name = bili.name,
                accountKey = biliKey,
                websiteVip = bili.vip,
                effectiveVip = biliLoggedIn && vipStore.effectiveVip(
                    PlatformVipStore.Platform.BILIBILI,
                    biliKey,
                    bili.vip,
                ),
            ),
            PlatformState(
                platform = AccountPlatform.IQIYI,
                loggedIn = iqiyi != null,
                name = iqiyi?.username,
                accountKey = iqiyiKey,
                websiteVip = iqiyiWebsiteVip,
                effectiveVip = iqiyi != null && vipStore.effectiveVip(
                    PlatformVipStore.Platform.IQIYI,
                    iqiyiKey,
                    iqiyiWebsiteVip,
                ),
            ),
            PlatformState(
                platform = AccountPlatform.MANGO,
                loggedIn = mango != null,
                name = mango?.username,
                accountKey = mangoKey,
                websiteVip = mangoWebsiteVip,
                effectiveVip = mango != null && vipStore.effectiveVip(
                    PlatformVipStore.Platform.MANGO,
                    mangoKey,
                    mangoWebsiteVip,
                ),
            ),
            PlatformState(
                platform = AccountPlatform.TENCENT,
                loggedIn = false,
                name = null,
                accountKey = PlatformVipStore.DEFAULT_ACCOUNT_KEY,
                websiteVip = null,
                effectiveVip = false,
            ),
        ).associateBy { it.platform }
    }

    private fun renderTable() {
        val b = _binding ?: return
        val states = currentStates()
        bindRow(
            state = states.getValue(AccountPlatform.BILIBILI),
            name = b.nameBilibili,
            logo = b.logoBilibili,
            badge = b.vipBilibili,
            remove = b.removeBilibili,
        )
        bindRow(
            state = states.getValue(AccountPlatform.IQIYI),
            name = b.nameIqiyi,
            logo = b.logoIqiyi,
            badge = b.vipIqiyi,
            remove = b.removeIqiyi,
        )
        bindRow(
            state = states.getValue(AccountPlatform.MANGO),
            name = b.nameMango,
            logo = b.logoMango,
            badge = b.vipMango,
            remove = b.removeMango,
        )
        bindRow(
            state = states.getValue(AccountPlatform.TENCENT),
            name = b.nameTencent,
            logo = b.logoTencent,
            badge = b.vipTencent,
            remove = b.removeTencent,
        )
    }

    private fun bindRow(
        state: PlatformState,
        name: TextView,
        logo: android.widget.ImageView,
        badge: View,
        remove: View,
    ) {
        name.text = state.name?.takeIf { it.isNotBlank() } ?: getString(R.string.mango_account_logged_out)
        name.contentDescription = if (state.loggedIn) {
            getString(R.string.account_row_description_fmt, platformTitle(state.platform), state.name.orEmpty())
        } else {
            getString(R.string.account_row_login_description_fmt, platformTitle(state.platform))
        }
        logo.setImageResource(if (state.loggedIn) state.platform.logoRes else R.drawable.ic_platform_add)
        logo.contentDescription = if (state.loggedIn) platformTitle(state.platform) else getString(R.string.account_row_login_description_fmt, platformTitle(state.platform))
        badge.visibility = if (state.loggedIn && state.effectiveVip) View.VISIBLE else View.GONE
        remove.visibility = if (state.loggedIn && state.platform != AccountPlatform.TENCENT) View.VISIBLE else View.GONE
        remove.contentDescription = getString(R.string.account_remove_description_fmt, platformTitle(state.platform))
        val cell = cellFor(state.platform)
        cell.setOnClickListener {
            if (state.loggedIn) showAccountDialog(state.platform) else launchLogin(state.platform)
        }
        remove.setOnClickListener {
            if (state.loggedIn && state.platform != AccountPlatform.TENCENT) confirmLogout(state.platform)
        }
    }

    private fun cellFor(platform: AccountPlatform): View = when (platform) {
        AccountPlatform.BILIBILI -> binding.accountBilibili
        AccountPlatform.IQIYI -> binding.accountIqiyi
        AccountPlatform.MANGO -> binding.accountMango
        AccountPlatform.TENCENT -> binding.accountTencent
    }

    private fun removeFor(platform: AccountPlatform): View = when (platform) {
        AccountPlatform.BILIBILI -> binding.removeBilibili
        AccountPlatform.IQIYI -> binding.removeIqiyi
        AccountPlatform.MANGO -> binding.removeMango
        AccountPlatform.TENCENT -> binding.removeTencent
    }

    private fun launchLogin(platform: AccountPlatform) {
        if (platform == AccountPlatform.TENCENT) return
        val intent = when (platform) {
            AccountPlatform.BILIBILI -> Intent(requireContext(), QrLoginActivity::class.java)
            AccountPlatform.IQIYI -> Intent(requireContext(), IqiyiLoginActivity::class.java)
            AccountPlatform.MANGO -> Intent(requireContext(), MangoLoginActivity::class.java)
            AccountPlatform.TENCENT -> return
        }
        pendingLoginFocusPlatform = platform
        loginLauncher.launch(intent)
    }

    private fun refreshPlatforms() {
        refreshBili()
        refreshMango()
        refreshIqiyi()
    }

    private fun refreshBili() {
        val session = BiliSession.state.value
        if (session.status == BiliSession.Status.SignedOut) return
        biliRefreshJob?.cancel()
        biliRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            runCatching { BiliSession.refresh() }
        }
    }

    private fun refreshMango() {
        val account = mangoSessionStore.currentAccount() ?: return
        mangoRefreshJob?.cancel()
        mangoRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = mangoAccountApi.fetchProfile()
                mangoSessionStore.saveAccount(profile.username, profile.uid, profile.vip)
                mangoLiveUid = profile.uid ?: account.uid
                mangoLiveVip = profile.vip
                renderTable()
                if (accountDialogPlatform == AccountPlatform.MANGO && accountDialog?.isShowing == true) {
                    refreshDialog(AccountPlatform.MANGO)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("My", "Mango profile unavailable: ${error.javaClass.simpleName}")
                mangoLiveUid = account.uid
                mangoLiveVip = null
                renderTable()
                if (accountDialogPlatform == AccountPlatform.MANGO && accountDialog?.isShowing == true) {
                    refreshDialog(AccountPlatform.MANGO)
                }
            }
        }
    }

    private fun refreshIqiyi() {
        val account = iqiyiSessionStore.currentAccount() ?: return
        iqiyiRefreshJob?.cancel()
        iqiyiRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val profile = iqiyiApi.fetchProfile()
            if (profile == null) {
                iqiyiLiveUid = account.uid
                iqiyiLiveVip = null
                renderTable()
                if (accountDialogPlatform == AccountPlatform.IQIYI && accountDialog?.isShowing == true) {
                    refreshDialog(AccountPlatform.IQIYI)
                }
                return@launch
            }
            runCatching { iqiyiSessionStore.saveAccount(profile.username, profile.uid, profile.vip) }
            iqiyiLiveUid = profile.uid ?: account.uid
            iqiyiLiveVip = profile.vip
            renderTable()
            if (accountDialogPlatform == AccountPlatform.IQIYI && accountDialog?.isShowing == true) {
                refreshDialog(AccountPlatform.IQIYI)
            }
        }
    }

    private fun showAccountDialog(platform: AccountPlatform) {
        if (platform == AccountPlatform.TENCENT) return
        accountDialog?.dismiss()
        accountDialogPlatform = platform
        val dialogContext = requireActivity().userScaledContext()
        val dialogInflater = LayoutInflater.from(dialogContext)
        val b = DialogAccountManageBinding.inflate(dialogInflater)
        dialogBinding = b
        b.btnLogout.setOnClickListener { confirmLogout(platform) }
        b.btnSwitchAccount.setOnClickListener { toggleSwitchPanel(true) }
        b.btnAddAccount.setOnClickListener { launchLogin(AccountPlatform.BILIBILI) }
        b.btnFollowing.setOnClickListener { startActivity(Intent(requireContext(), FollowingListActivity::class.java)) }
        b.btnFollower.setOnClickListener { AppToast.show(requireContext(), "粉丝列表未实现") }
        b.vipCheck.setOnCheckedChangeListener(null)
        b.btnRestoreVip.setOnClickListener {
            vipStore.setOverride(platform.vipPlatform, currentStates().getValue(platform).accountKey, null)
            refreshDialog(platform)
        }
        b.vipCheck.setOnCheckedChangeListener { _, checked ->
            val state = currentStates()[platform] ?: return@setOnCheckedChangeListener
            if (!state.loggedIn) return@setOnCheckedChangeListener
            vipStore.setOverride(platform.vipPlatform, state.accountKey, checked)
            renderTable()
            refreshDialogVip(platform)
        }
        accountDialog =
            PopupHost.from(requireActivity() as androidx.activity.ComponentActivity).showModal(
                title = getString(R.string.account_manage_title_fmt, platformTitle(platform)),
                contentView = b.root,
                cancelable = true,
                actions = emptyList(),
                autoFocus = true,
                onRestoreFocus = {
                    requestPlatformFocus(platform)
                    true
                },
                onDismiss = {
                    if (dialogBinding?.root === b.root) {
                        dialogLoadJob?.cancel()
                        dialogLoadJob = null
                        dialogBinding = null
                        accountDialog = null
                        accountDialogPlatform = null
                    }
                },
            )
        refreshDialog(platform)
    }

    private fun refreshDialog(platform: AccountPlatform) {
        val b = dialogBinding ?: return
        refreshDialogHeader(platform)
        if (platform == AccountPlatform.BILIBILI) {
            b.statsRow.visibility = View.VISIBLE
            b.expRow.visibility = View.VISIBLE
            b.btnSwitchAccount.visibility = View.VISIBLE
            b.tvSubtitle.text = getString(R.string.account_manage_subtitle)
            loadBiliDialogStats()
        } else {
            b.statsRow.visibility = View.GONE
            b.expRow.visibility = View.GONE
            b.progressExp.visibility = View.GONE
            b.btnSwitchAccount.visibility = View.GONE
            b.accountSwitchPanel.visibility = View.GONE
            b.tvSubtitle.text = getString(R.string.account_manage_subtitle)
        }
        refreshDialogVip(platform)
    }

    private fun refreshDialogHeader(platform: AccountPlatform) {
        val b = dialogBinding ?: return
        val state = currentStates()[platform] ?: return
        b.avatarLogo.setImageResource(if (state.loggedIn) platform.logoRes else R.drawable.ic_platform_add)
        b.tvName.text = state.name?.takeIf { it.isNotBlank() } ?: getString(R.string.mango_account_logged_out)
        b.vipBadge.visibility = if (state.loggedIn && state.effectiveVip) View.VISIBLE else View.GONE
    }

    private fun refreshDialogVip(platform: AccountPlatform) {
        val b = dialogBinding ?: return
        val state = currentStates()[platform] ?: return
        val site = state.websiteVip
        b.vipCheck.setOnCheckedChangeListener(null)
        b.vipCheck.isChecked = state.effectiveVip
        b.vipCheck.isEnabled = state.loggedIn
        b.vipCheck.alpha = if (state.loggedIn) 1f else 0.5f
        b.tvVipSite.text = getString(
            R.string.account_vip_status_fmt,
            when (site) {
                true -> getString(R.string.vip_status_vip)
                false -> getString(R.string.vip_status_not_vip)
                null -> getString(R.string.vip_status_unknown)
            },
        )
        b.btnRestoreVip.visibility =
            if (state.loggedIn && vipStore.override(platform.vipPlatform, state.accountKey) != null) View.VISIBLE else View.GONE
        b.vipCheck.setOnCheckedChangeListener { _, checked ->
            val current = currentStates()[platform] ?: return@setOnCheckedChangeListener
            if (!current.loggedIn) return@setOnCheckedChangeListener
            vipStore.setOverride(platform.vipPlatform, current.accountKey, checked)
            renderTable()
            refreshDialogVip(platform)
        }
        b.vipBadge.visibility = if (state.loggedIn && state.effectiveVip) View.VISIBLE else View.GONE
    }

    private fun loadBiliDialogStats() {
        val b = dialogBinding ?: return
        dialogLoadJob?.cancel()
        b.pbLoading.visibility = View.VISIBLE
        b.tvFollowing.text = "--"
        b.tvFollower.text = "--"
        b.tvCoins.text = "--"
        b.tvLevel.text = ""
        b.tvExp.text = getString(R.string.account_stats_loading)
        b.progressExp.visibility = View.GONE
        dialogLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nav = BiliSession.profileNav()
                val data = nav.optJSONObject("data")
                val mid = data?.optLong("mid") ?: 0L
                val stat = if (mid > 0) BiliApi.relationStat(mid) else null
                if (dialogBinding !== b) return@launch
                b.tvFollowing.text = (stat?.following ?: 0L).toString()
                b.tvFollower.text = (stat?.follower ?: 0L).toString()
                b.tvCoins.text = formatCoins(parseCoins(data))
                val levelInfo = data?.optJSONObject("level_info")
                val level = levelInfo?.optInt("current_level") ?: 0
                val currentExp = parseInt(levelInfo, "current_exp") ?: 0
                val nextExp = parseInt(levelInfo, "next_exp")
                b.tvLevel.text = getString(R.string.label_level_fmt, level)
                val expText = if (nextExp != null && nextExp > 0) "$currentExp/$nextExp" else "已满级"
                b.tvExp.text = getString(R.string.label_exp_fmt, expText)
                if (nextExp != null && nextExp > 0) {
                    b.progressExp.visibility = View.VISIBLE
                    b.progressExp.max = nextExp
                    b.progressExp.progress = currentExp.coerceIn(0, nextExp)
                } else {
                    b.progressExp.visibility = View.GONE
                }
                renderAccountSwitchRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (dialogBinding !== b) return@launch
                AppLog.w("My", "Bili account stats unavailable: ${error.javaClass.simpleName}")
                b.tvExp.text = getString(R.string.account_stats_failed)
            } finally {
                if (dialogBinding === b) b.pbLoading.visibility = View.GONE
            }
        }
    }

    private fun toggleSwitchPanel(show: Boolean) {
        val b = dialogBinding ?: return
        if (show) {
            BiliClient.accounts.saveCurrentSessionAsActive(
                appPrefs = BiliClient.prefs,
                cookies = BiliClient.cookies,
            )
            b.accountSwitchPanel.visibility = View.VISIBLE
            renderAccountSwitchRows()
        } else {
            b.accountSwitchPanel.visibility = View.GONE
        }
    }

    private fun renderAccountSwitchRows() {
        val b = dialogBinding ?: return
        val list = b.accountList
        list.removeAllViews()
        for (account in BiliClient.accounts.accounts()) {
            val row = LayoutInflater.from(requireActivity().userScaledContext())
                .inflate(R.layout.item_account_switch_row, list, false) as TextView
            row.text = account.name + if (account.isActive) "（当前）" else ""
            row.setOnClickListener {
                if (account.isActive) return@setOnClickListener
                val switched = BiliClient.accounts.switchToAccount(
                    accountId = account.id,
                    appPrefs = BiliClient.prefs,
                    cookies = BiliClient.cookies,
                )
                if (switched == null) {
                    AppToast.show(requireContext(), "帐号不存在")
                    return@setOnClickListener
                }
                AppToast.show(requireContext(), "已切换：${switched.name}")
                BiliSession.syncCredentials()
                refreshBili()
                refreshDialog(AccountPlatform.BILIBILI)
                renderTable()
            }
            list.addView(row)
        }
    }

    private fun confirmLogout(platform: AccountPlatform) {
        val dialogContext = requireActivity().userScaledContext()
        val view = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_confirm_danger, null, false)
        view.findViewById<TextView>(R.id.tv_message).text = getString(R.string.account_logout_confirm_message)
        var confirmed = false
        val handle =
            PopupHost.from(requireActivity() as androidx.activity.ComponentActivity).showModal(
                title = getString(R.string.account_logout_confirm_title_fmt, platformTitle(platform)),
                contentView = view,
                cancelable = true,
                actions = emptyList(),
                autoFocus = true,
                onRestoreFocus = {
                    if (confirmed) requestPlatformFocus(platform) else requestRemoveFocus(platform)
                    true
                },
            )
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { handle.dismiss() }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            confirmed = true
            handle.dismiss()
            accountDialog?.dismiss()
            performLogout(platform)
        }
    }

    private fun performLogout(platform: AccountPlatform) {
        when (platform) {
            AccountPlatform.BILIBILI -> {
                val mid = BiliSession.state.value.mid
                BiliClient.accounts.removeActiveAccountAndActivateNext(
                    appPrefs = BiliClient.prefs,
                    cookies = BiliClient.cookies,
                )
                vipStore.clearAccount(PlatformVipStore.Platform.BILIBILI, PlatformVipStore.bilibiliKey(mid))
                BiliSession.syncCredentials()
                refreshBili()
            }

            AccountPlatform.MANGO -> {
                val account = mangoSessionStore.currentAccount()
                vipStore.clearAccount(PlatformVipStore.Platform.MANGO, PlatformVipStore.platformKey(account?.uid))
                mangoSessionStore.clear()
                mangoLiveUid = null
                mangoLiveVip = null
            }

            AccountPlatform.IQIYI -> {
                val account = iqiyiSessionStore.currentAccount()
                vipStore.clearAccount(PlatformVipStore.Platform.IQIYI, PlatformVipStore.platformKey(account?.uid))
                iqiyiSessionStore.clear()
                iqiyiLiveUid = null
                iqiyiLiveVip = null
            }

            AccountPlatform.TENCENT -> return
        }
        renderTable()
        requestPlatformFocus(platform)
    }

    private fun requestOverviewFocusIfNeeded() {
        val b = _binding ?: return
        if (binding.personalContainer.visibility == View.VISIBLE) return
        if (accountDialog?.isShowing == true) return
        if (b.root.findFocus() != null) return
        val target = b.root.findViewById<View>(lastActionId) ?: b.accountBilibili
        target.post {
            if (_binding == null || !isResumed || binding.personalContainer.visibility == View.VISIBLE) return@post
            if (binding.overview.findFocus() == null) target.requestFocus()
        }
    }

    private fun focusPlatform(platform: AccountPlatform) {
        requestPlatformFocus(platform)
    }

    private fun requestPlatformFocus(platform: AccountPlatform, attemptsLeft: Int = 24) {
        val target = _binding?.let { cellFor(platform) } ?: return
        target.post {
            if (!isResumed || !target.isShown) return@post
            if (target.requestFocus()) return@post
            if (attemptsLeft > 0) target.postDelayed({ requestPlatformFocus(platform, attemptsLeft - 1) }, 32L)
        }
    }

    private fun requestRemoveFocus(platform: AccountPlatform, attemptsLeft: Int = 24) {
        val target = _binding?.let { removeFor(platform) } ?: return
        target.post {
            if (!isResumed || !target.isShown) return@post
            if (target.requestFocus()) return@post
            if (attemptsLeft > 0) target.postDelayed({ requestRemoveFocus(platform, attemptsLeft - 1) }, 32L)
        }
    }

    private fun parseCoins(data: JSONObject?): Double {
        if (data == null) return 0.0
        val money = data.optDouble("money", Double.NaN)
        if (!money.isNaN()) return money
        val coins = data.optDouble("coins", Double.NaN)
        if (!coins.isNaN()) return coins
        return 0.0
    }

    private fun formatCoins(value: Double): String {
        val v = value.coerceAtLeast(0.0)
        return if (v >= 1000) String.format(Locale.getDefault(), "%.0f", v) else String.format(Locale.getDefault(), "%.1f", v)
    }

    private fun parseInt(
        obj: JSONObject?,
        key: String,
    ): Int? {
        val any = obj?.opt(key) ?: return null
        return when (any) {
            is Number -> any.toInt()
            is String -> any.toIntOrNull()
            else -> null
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
        return b.root.findViewById<View>(lastActionId)?.requestFocus() == true || b.accountBilibili.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("last_action", lastActionId)
    }

    private data class PlatformState(
        val platform: AccountPlatform,
        val loggedIn: Boolean,
        val name: String?,
        val accountKey: String,
        val websiteVip: Boolean?,
        val effectiveVip: Boolean,
    )

    private enum class AccountPlatform(
        @StringRes val titleRes: Int,
        val logoRes: Int,
        val vipPlatform: PlatformVipStore.Platform,
    ) {
        BILIBILI(R.string.platform_bilibili, R.drawable.ic_platform_bilibili, PlatformVipStore.Platform.BILIBILI),
        IQIYI(R.string.platform_iqiyi, R.drawable.ic_platform_iqiyi, PlatformVipStore.Platform.IQIYI),
        MANGO(R.string.platform_mango, R.drawable.ic_platform_mango, PlatformVipStore.Platform.MANGO),
        TENCENT(R.string.platform_tencent, R.drawable.ic_platform_tencent, PlatformVipStore.Platform.TENCENT),
    }

    private fun platformTitle(platform: AccountPlatform): String = getString(platform.titleRes)
}
