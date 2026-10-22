package blbl.cat3399.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.account.AccountAvatar
import blbl.cat3399.core.account.AccountSessionStore
import blbl.cat3399.core.account.BiliSession
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.log.CrashTracker
import blbl.cat3399.core.navigation.MainNavigationKeys
import blbl.cat3399.core.ui.PageSectionFocusHost
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.dispatchToAncestorDpadItemKeyHandler
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHandle
import blbl.cat3399.core.update.ApkUpdateFlow
import blbl.cat3399.core.update.ApkUpdater
import blbl.cat3399.databinding.ActivityMainBinding
import blbl.cat3399.databinding.DialogUserInfoBinding
import blbl.cat3399.feature.following.FollowingListActivity
import blbl.cat3399.feature.settings.SettingsActivity
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

class MainActivity : BaseActivity(), MainNavigationHost {
    private var focusRequestToken = 0
    private var topNavigationFocusReady = false
    private var suppressNextTopNavigationFocusFollow = false
    private var renderedVisibleKeys: List<String>? = null
    private var renderedSelectedKey: String? = null
    private var renderedAccountAvatarUrl: String? = null
    private val navigationHandler = Handler(Looper.getMainLooper())
    private var sidebarExpanded = false
    private var sidebarAutoCollapse: Runnable? = null
    private var sidebarBackdropJob: Job? = null
    private var sidebarBackdropBitmap: Bitmap? = null
    private var sidebarBackdropToken = 0
    private var pendingPageSelectionKey: String? = null
    private val pageFocusedViews = mutableMapOf<String, WeakReference<View>>()
    private var pendingLoginTargetKey: String? = null
    private var userRefreshJob: Job? = null
    private var observedSessionRevision: Long? = null
    private val qrLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pendingLoginTargetKey
        pendingLoginTargetKey = null
        if (result.resultCode == RESULT_OK && target != null) {
            navigateTopKey(MainRootNavRegistry.normalizeVisibleTopNavKey(target))
            binding.root.post { requestFocusTopNavigationSelected() }
        }
    }
    private enum class UserInfoOverlayMode {
        PROFILE,
        ACCOUNT_SWITCH,
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var navAdapter: MainTopNavAdapter
    private var needForceInitialTopNavigationFocus: Boolean = false
    private var launchNavId: Int = MainRootNavIds.HOME
    private var launchTopNavKey: String = MainTopNavAdapter.KEY_RECOMMEND
    private var currentRootNavId: Int? = null
    private var currentTopNavKey: String = MainTopNavAdapter.KEY_RECOMMEND
    private var lastMainFocusedView: WeakReference<View>? = null
    private var pausedFocusedView: WeakReference<View>? = null
    private var pausedFocusWasInMain: Boolean = false
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var disclaimerPopup: PopupHandle? = null
    private var crashPromptPopup: PopupHandle? = null
    private var ijkKernelPromptPopup: PopupHandle? = null
    private var autoUpdatePromptPopup: PopupHandle? = null
    private var ijkKernelPromptShown: Boolean = false
    private var autoUpdateCheckStarted: Boolean = false
    private var autoUpdatePromptShownVersion: String? = null
    private var pendingAutoUpdate: ApkUpdater.RemoteUpdate? = null
    private var autoUpdateCheckJob: Job? = null
    private lateinit var userInfoOverlay: DialogUserInfoBinding
    private val userInfoReturnFocus = FocusReturn()
    private var userInfoLoadJob: Job? = null
    private var userInfoOverlayMode: UserInfoOverlayMode = UserInfoOverlayMode.PROFILE
    private var restoreAccountFocusAfterQrLogin: Boolean = false
    private var lastBackAtMs: Long = 0L
    private var lastMainFocusAtMs: Long = 0L

    private data class FocusabilitySnapshot(
        val descendantFocusability: Int,
        val isFocusable: Boolean,
        val isFocusableInTouchMode: Boolean,
    )

    private var dpadDownFocusGuardActive: Boolean = false
    private var dpadDownFocusGuardReleaseToken: Int = 0
    private val dpadDownFocusGuardSnapshots = java.util.WeakHashMap<ViewGroup, FocusabilitySnapshot>()

    private var baseUserInfoCardWidth: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val safeState = restoredState
        needForceInitialTopNavigationFocus = safeState == null
        binding = ActivityMainBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        pendingLoginTargetKey = safeState?.getString(STATE_KEY_PENDING_LOGIN_TARGET)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        launchTopNavKey = MainRootNavRegistry.resolveLaunchTopNavKey(BiliClient.prefs.startupPage)
        launchNavId = resolveLaunchNavId()

        userInfoOverlay = binding.userInfoOverlay
        initUserInfoOverlay()

        val savedRootNavId = safeState?.getInt(STATE_KEY_ROOT_NAV_ID, -1)?.takeIf(::isValidRootNavId)
        val savedTopKey = safeState?.getString(STATE_KEY_TOP_NAV_KEY)?.takeIf(MainRootNavRegistry::isValidTopNavKey)
        val inferredTopKey = savedTopKey ?: savedRootNavId?.let { MainRootNavRegistry.topKeyForRootNavId(it) }
        val initialSelectedTopKey =
            if (safeState == null) launchTopNavKey else MainRootNavRegistry.normalizeVisibleTopNavKey(inferredTopKey)
        val initialSelectedNavId = MainRootNavRegistry.rootNavIdForTopKey(initialSelectedTopKey) ?: MainRootNavIds.HOME
        currentTopNavKey = initialSelectedTopKey
        pendingPageSelectionKey = initialSelectedTopKey.takeIf { MainRootNavRegistry.myPageKeyForTopKey(it) != null || MainRootNavRegistry.homePageKeyForTopKey(it) != null }

        navAdapter = MainTopNavAdapter(
            onClick = { item ->
                if (item.key == MainTopNavAdapter.KEY_SETTINGS) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else if (item.key == MainNavigationKeys.MY_ACCOUNT) {
                    navigateTopKey(item.key)
                    collapseSidebar()
                    binding.mainContainer.post {
                        if (currentTopNavKey == MainNavigationKeys.MY_ACCOUNT) {
                            (currentRootFragment() as? blbl.cat3399.feature.my.MyAccountFragment)?.showOverview()
                        }
                        focusPageContent()
                    }
                } else {
                    navigateTopKey(item.key, refreshIfReselected = true)
                    collapseSidebar()
                    binding.mainContainer.post { focusPageContent() }
                }
            },
            onFocus = { item ->
                navigateOnFocus(item.key)
            },
        )
        binding.recyclerTopNav.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.recyclerTopNav.adapter = navAdapter
        (binding.recyclerTopNav.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.navScrim.setOnClickListener { collapseSidebar() }
        configureTopNavigationItems(initialSelectedTopKey)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BiliSession.state.collect { session ->
                    if (observedSessionRevision != null && session.revision != observedSessionRevision) {
                        pageFocusedViews.clear()
                        lastMainFocusedView = null
                        userInfoLoadJob?.cancel()
                        if (isUserInfoOverlayVisible()) {
                            if (!session.canAccessPersonal) hideUserInfoOverlay()
                            else if (userInfoOverlayMode == UserInfoOverlayMode.PROFILE) {
                                resetUserInfoUi()
                                loadUserInfo()
                            }
                        }
                        binding.root.post {
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentRootNavId !in setOf(MainRootNavIds.MY, MainRootNavIds.ACCOUNT)) {
                                dispatchRefreshKeyToCurrentPage()
                            }
                        }
                    }
                    configureTopNavigationItems(currentTopNavKey)
                    observedSessionRevision = session.revision
                }
            }
        }

        if (safeState == null) {
            navigateTopKey(initialSelectedTopKey, refreshIfReselected = false)
        } else {
            restoreRootAfterRecreate(initialSelectedNavId)
            if (MainRootNavRegistry.homePageKeyForTopKey(initialSelectedTopKey) != null) {
                (currentRootFragment() as? blbl.cat3399.feature.home.HomeFragment)?.selectPage(
                    MainRootNavRegistry.homePageKeyForTopKey(initialSelectedTopKey)!!,
                )
            } else if (MainRootNavRegistry.myPageKeyForTopKey(initialSelectedTopKey) != null) {
                (currentRootFragment() as? blbl.cat3399.feature.my.MyFragment)?.selectTopNavigationKey(initialSelectedTopKey)
            }
        }

        focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null && isInMainContainer(newFocus)) {
                    pageFocusedViews[currentTopNavKey] = WeakReference(newFocus)
                    lastMainFocusedView = WeakReference(newFocus)
                    lastMainFocusAtMs = SystemClock.uptimeMillis()
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isUserInfoOverlayVisible()) {
                        hideUserInfoOverlay()
                        return
                    }
                    if (supportFragmentManager.popBackStackImmediate()) {
                        return
                    }
                    // The top navigation is the primary navigation layer on TV.
                    // When focus is already there, Back should enter the app-level exit flow
                    // (double press to exit) instead of jumping back to the startup page.
                    val focused = currentFocus
                    val accountPage = currentRootFragment() as? blbl.cat3399.feature.my.MyAccountFragment
                    if (accountPage?.handleBackPressed() == true) return
                    if (sidebarExpanded) {
                        collapseSidebar()
                        return
                    }
                    if (focused != null && isInTopNavigation(focused)) {
                        if (shouldFinishOnBackPress()) finish()
                        return
                    }
                    val current = currentRootFragment()
                    val handled = (current as? BackPressHandler)?.handleBackPressed() == true
                    AppLog.d("Back", "back current=${current?.javaClass?.simpleName} handled=$handled")
                    if (handled) return
                    if (!isAtLaunchRoot(current)) {
                        navigateTopKey(launchTopNavKey)
                        return
                    }
                    if (shouldFinishOnBackPress()) finish()
                }
            },
        )

        showFirstLaunchDisclaimerIfNeeded()
        maybeStartAutoUpdateCheck()
    }

    private fun resolveLaunchNavId(): Int {
        return MainRootNavRegistry.rootNavIdForTopKey(launchTopNavKey) ?: MainRootNavIds.HOME
    }

    private fun isAtLaunchRoot(fragment: Fragment?): Boolean {
        return navIdForRootFragment(fragment) == launchNavId && currentTopNavKey == launchTopNavKey
    }

    override fun onResume() {
        super.onResume()
        syncTopNavigationState()
        restoreFocusAfterResume()
        forceInitialTopNavigationFocusIfNeeded()
        ensureInitialFocus()
        if (sidebarExpanded) scheduleSidebarAutoCollapse()
        refreshSession()
        if (isUserInfoOverlayVisible()) {
            if (userInfoOverlayMode == UserInfoOverlayMode.ACCOUNT_SWITCH) {
                showAccountSwitchPanel(requestFocus = false)
                if (restoreAccountFocusAfterQrLogin) {
                    restoreAccountFocusAfterQrLogin = false
                    userInfoOverlay.root.post {
                        if (isUserInfoOverlayVisible() && userInfoOverlayMode == UserInfoOverlayMode.ACCOUNT_SWITCH) {
                            requestUserInfoFocus(userInfoOverlay.btnAddAccount)
                        }
                    }
                }
            } else {
                restoreAccountFocusAfterQrLogin = false
                loadUserInfo()
            }
        }
        showLastCrashPromptIfNeeded()
        showIjkKernelUpdatePromptIfNeeded()
        showAutoUpdatePromptIfReady()
    }

    override fun onPause() {
        cancelSidebarAutoCollapse()
        val focused = currentFocus
        pausedFocusedView = focused?.let { WeakReference(it) }
        pausedFocusWasInMain = focused != null && isInMainContainer(focused)
        releaseDpadDownFocusGuard()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentRootNavId?.let { outState.putInt(STATE_KEY_ROOT_NAV_ID, it) }
        outState.putString(STATE_KEY_TOP_NAV_KEY, currentTopNavKey)
        outState.putString(STATE_KEY_PENDING_LOGIN_TARGET, pendingLoginTargetKey)
    }

    override fun onDestroy() {
        cancelSidebarAutoCollapse()
        sidebarBackdropToken++
        sidebarBackdropJob?.cancel()
        sidebarBackdropJob = null
        binding.sidebarBackdrop.setImageDrawable(null)
        sidebarBackdropBitmap?.recycle()
        sidebarBackdropBitmap = null
        binding.navigationPanel.animate().cancel()
        binding.btnCollapsedNavigation.root.animate().cancel()
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
            if (sidebarExpanded) scheduleSidebarAutoCollapse()
        } else {
            cancelSidebarAutoCollapse()
        }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && sidebarExpanded) {
            scheduleSidebarAutoCollapse()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun expandSidebar(focusSelected: Boolean): Boolean {
        cancelSidebarAutoCollapse()
        if (!sidebarExpanded) {
            sidebarExpanded = true
            binding.navScrim.visibility = View.VISIBLE
            binding.navScrim.isClickable = true
            binding.navigationPanel.visibility = View.VISIBLE
            binding.btnCollapsedNavigation.root.visibility = View.GONE
            binding.navigationPanel.alpha = 0f
            binding.navigationPanel.translationX = -dp(8f).toFloat()
            binding.navigationPanel.animate().cancel()
            binding.navigationPanel.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(180L)
                .withEndAction {
                    if (sidebarExpanded) updateSidebarBackdrop()
                }
                .start()
        } else if (sidebarBackdropBitmap == null && sidebarBackdropJob == null) {
            updateSidebarBackdrop()
        }
        if (focusSelected) focusTopNavigationKey(MainRootNavRegistry.normalizeVisibleTopNavKey(currentTopNavKey))
        scheduleSidebarAutoCollapse()
        return true
    }

    private fun updateSidebarBackdrop() {
        if (!sidebarExpanded) return
        val image = binding.sidebarBackdrop
        image.visibility = View.VISIBLE
        image.post {
            if (isDestroyed || !sidebarExpanded) return@post
            val sample = SidebarBackdropBlur.capture(binding.mainContainer, image) ?: return@post
            val token = ++sidebarBackdropToken
            sidebarBackdropJob?.cancel()
            sidebarBackdropJob = lifecycleScope.launch {
                var blurred: Bitmap? = null
                try {
                    blurred = SidebarBackdropBlur.blur(sample)
                    if (token != sidebarBackdropToken || !sidebarExpanded) return@launch

                    val previous = sidebarBackdropBitmap
                    sidebarBackdropBitmap = blurred
                    image.setImageBitmap(blurred)
                    blurred = null
                    if (previous != null && previous !== sidebarBackdropBitmap) previous.recycle()
                } finally {
                    sample.recycle()
                    blurred?.recycle()
                }
            }
        }
    }

    private fun updateSidebarBackdropAfterPageChange() {
        if (!sidebarExpanded) return
        binding.mainContainer.doOnPreDraw {
            if (sidebarExpanded) updateSidebarBackdrop()
        }
    }

    private fun collapseSidebar(restoreMainFocus: Boolean = false): Boolean {
        if (!sidebarExpanded) return false
        val menuHadFocus = currentFocus?.let(::isInExpandedSidebar) == true
        sidebarExpanded = false
        sidebarBackdropToken++
        sidebarBackdropJob?.cancel()
        sidebarBackdropJob = null
        cancelSidebarAutoCollapse()
        binding.navScrim.visibility = View.GONE
        binding.navScrim.isClickable = false
        binding.btnCollapsedNavigation.root.visibility = View.VISIBLE
        binding.btnCollapsedNavigation.root.bringToFront()
        binding.btnCollapsedNavigation.root.elevation = dp(8f).toFloat()
        binding.btnCollapsedNavigation.root.alpha = 0f
        binding.btnCollapsedNavigation.root.animate().cancel()
        binding.btnCollapsedNavigation.root.animate().alpha(1f).setDuration(180L).start()
        binding.navigationPanel.animate().cancel()
        binding.navigationPanel.visibility = View.GONE
        binding.navigationPanel.alpha = 1f
        binding.navigationPanel.translationX = 0f
        if (restoreMainFocus) {
            focusPageContent()
        } else if (menuHadFocus) {
            binding.btnCollapsedNavigation.root.post {
                if (!sidebarExpanded && binding.btnCollapsedNavigation.root.isShown) binding.btnCollapsedNavigation.root.requestFocus()
            }
        }
        return true
    }

    private fun scheduleSidebarAutoCollapse() {
        cancelSidebarAutoCollapse()
        if (!sidebarExpanded || isUserInfoOverlayVisible()) return
        val task = Runnable {
            sidebarAutoCollapse = null
            collapseSidebar()
        }
        sidebarAutoCollapse = task
        navigationHandler.postDelayed(task, 4_000L)
    }

    private fun cancelSidebarAutoCollapse() {
        sidebarAutoCollapse?.let(navigationHandler::removeCallbacks)
        sidebarAutoCollapse = null
    }

    private fun moveSidebarFocus(focused: View, direction: Int): Boolean {
        val holder = binding.recyclerTopNav.findContainingViewHolder(focused) ?: return true
        val count = navAdapter.itemCount
        if (count == 0) return true
        val target = (holder.bindingAdapterPosition + direction).coerceIn(0, count - 1)
        val key = navAdapter.itemAt(target)?.key ?: return true
        focusTopNavigationKey(key)
        return true
    }


    private fun shouldStartDpadDownFocusGuard(focused: View?): Boolean {
        if (isUserInfoOverlayVisible()) return false
        if (focused != null) {
            if (isInTopNavigation(focused)) return false
            return isInMainContainer(focused)
        }
        val now = SystemClock.uptimeMillis()
        return now - lastMainFocusAtMs <= 1_000L
    }

    private fun syncTopNavigationState() {
        launchTopNavKey = MainRootNavRegistry.resolveLaunchTopNavKey(BiliClient.prefs.startupPage)
        launchNavId = resolveLaunchNavId()

        val currentNavId = currentRootNavId?.takeIf { isValidRootNavId(it) }
            ?: inferCurrentRootNavIdFromFragments()?.takeIf { isValidRootNavId(it) }
        val root = currentRootFragment()
        val candidateKey =
            when (currentNavId) {
                MainRootNavIds.HOME -> (root as? blbl.cat3399.feature.home.HomeFragment)?.currentPageKey ?: currentTopNavKey
                MainRootNavIds.ACCOUNT -> MainNavigationKeys.MY_ACCOUNT
                MainRootNavIds.CATEGORY -> MainTopNavAdapter.KEY_CATEGORY
                MainRootNavIds.LIVE -> MainTopNavAdapter.KEY_LIVE
                MainRootNavIds.SEARCH -> MainTopNavAdapter.KEY_SEARCH
                MainRootNavIds.MY -> currentTopNavKey.takeIf { MainRootNavRegistry.myPageKeyForTopKey(it) != null }
                    ?: (root as? blbl.cat3399.feature.my.MyFragment)?.requestedTopNavigationKey()
                    ?: MainTopNavAdapter.KEY_MY_HISTORY
                else -> currentTopNavKey
            }
        val selectedKey = MainRootNavRegistry.normalizeVisibleTopNavKey(pendingPageSelectionKey ?: candidateKey)
        pendingPageSelectionKey = selectedKey.takeIf { MainRootNavRegistry.myPageKeyForTopKey(it) != null || MainRootNavRegistry.homePageKeyForTopKey(it) != null }
        val desiredNavId = MainRootNavRegistry.rootNavIdForTopKey(selectedKey) ?: launchNavId
        if (currentNavId != desiredNavId) switchRoot(desiredNavId, clearBackStack = true)
        currentTopNavKey = selectedKey
        configureTopNavigationItems(selectedKey)

        applySelectedPageToRoot(selectedKey, desiredNavId)
        binding.mainContainer.post { applySelectedPageToRoot(selectedKey, desiredNavId) }
        scheduleSectionTabRowLayout()
    }

    override fun onSectionTabVisibilityChanged() {
        scheduleSectionTabRowLayout()
    }

    /**
     * Category and Live keep their section tabs in the same row as the collapsed navigation
     * capsule: the content moves up by one capsule row and the tab strip starts to its right.
     */
    private fun scheduleSectionTabRowLayout() {
        if (isDestroyed) return
        binding.mainContainer.post { applySectionTabRowLayout() }
        binding.mainContainer.doOnPreDraw { applySectionTabRowLayout() }
    }

    private fun applySectionTabRowLayout() {
        if (isDestroyed || binding.mainContainer.width == 0) return
        val root = currentRootFragment()
        val tabLayout = root?.view?.findViewById<View>(R.id.tab_layout)
        val hasTopTabs = root is PageSectionFocusHost && tabLayout?.isShown == true

        val containerLp = binding.mainContainer.layoutParams as? MarginLayoutParams
        if (containerLp != null) {
            val desiredTop = if (hasTopTabs) dp(16f) else resources.getDimensionPixelSize(R.dimen.main_content_top_inset)
            if (containerLp.topMargin != desiredTop) {
                containerLp.topMargin = desiredTop
                binding.mainContainer.layoutParams = containerLp
            }
        }

        if (tabLayout == null) return
        val capsule = binding.btnCollapsedNavigation.root
        if (hasTopTabs && capsule.width == 0) {
            // Capsule not measured yet; retry on the next frame.
            binding.mainContainer.post { applySectionTabRowLayout() }
            return
        }
        // Capsule starts at 20dp; leave a 16dp gap before the first tab.
        val startMargin = if (hasTopTabs) capsule.width + dp(36f) else 0
        val tabLp = tabLayout.layoutParams as? MarginLayoutParams
        if (tabLp != null && tabLp.marginStart != startMargin) {
            tabLp.marginStart = startMargin
            tabLayout.layoutParams = tabLp
        }
    }

    private fun applySelectedPageToRoot(key: String, expectedRootId: Int) {
        if (currentRootNavId != expectedRootId || currentTopNavKey != key) return
        MainRootNavRegistry.homePageKeyForTopKey(key)?.let { pageKey ->
            (currentRootFragment() as? blbl.cat3399.feature.home.HomeFragment)?.selectPage(pageKey)
            return
        }
        if (MainRootNavRegistry.myPageKeyForTopKey(key) != null) {
            (currentRootFragment() as? blbl.cat3399.feature.my.MyFragment)?.selectTopNavigationKey(key)
        }
    }

    private fun configureTopNavigationItems(selectedKey: String) {
        val visibleKeys = BiliClient.prefs.topNavigationVisibleKeys
        val accountAvatarUrl = AccountAvatar.resolve()
        val visibilityChanged = renderedVisibleKeys != visibleKeys
        val avatarChanged = renderedAccountAvatarUrl != accountAvatarUrl
        val items = MainRootNavRegistry.topNavItems(this, visibleKeys, accountAvatarUrl)
        if (visibilityChanged) {
            navAdapter.submit(items, selectedKey)
            renderedVisibleKeys = visibleKeys
        } else {
            navAdapter.setSelectedKey(selectedKey)
            if (avatarChanged) navAdapter.updateAccountAvatar(accountAvatarUrl)
        }
        renderedAccountAvatarUrl = accountAvatarUrl
        if (visibilityChanged || avatarChanged || renderedSelectedKey != selectedKey) {
            val selectedItem = items.firstOrNull { it.key == selectedKey }
                ?: MainRootNavRegistry.allTopNavItems(this).firstOrNull { it.key == selectedKey }
                ?: MainRootNavRegistry.accountItem(accountAvatarUrl)
            MainTopNavAdapter.bindCard(binding.btnCollapsedNavigation, selectedItem, true,
                onClick = { expandSidebar(focusSelected = true) }, onFocus = {})
            renderedSelectedKey = selectedKey
        }
    }

    private fun snapshotAndBlockFocus(container: ViewGroup) {
        if (!dpadDownFocusGuardSnapshots.containsKey(container)) {
            dpadDownFocusGuardSnapshots[container] =
                FocusabilitySnapshot(
                    descendantFocusability = container.descendantFocusability,
                    isFocusable = container.isFocusable,
                    isFocusableInTouchMode = container.isFocusableInTouchMode,
                )
        }
        container.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        container.isFocusable = false
        container.isFocusableInTouchMode = false
    }

    private fun beginOrKeepDpadDownFocusGuard(focused: View?) {
        if (!shouldStartDpadDownFocusGuard(focused)) return
        dpadDownFocusGuardActive = true
        dpadDownFocusGuardReleaseToken++

        // Keep framework fallback from escaping into the top navigation while content is loading.
        snapshotAndBlockFocus(binding.topNavigation)

        val rootView = currentRootFragment()?.view
        val tabLayout = rootView?.findViewById<ViewGroup?>(R.id.tab_layout)
        if (tabLayout != null && (focused == null || !FocusTreeUtils.isDescendantOf(focused, tabLayout))) {
            snapshotAndBlockFocus(tabLayout)
        }

        val recyclerFollowing = rootView?.findViewById<ViewGroup?>(R.id.recycler_following)
        if (recyclerFollowing != null && (focused == null || !FocusTreeUtils.isDescendantOf(focused, recyclerFollowing))) {
            snapshotAndBlockFocus(recyclerFollowing)
        }
    }

    private fun scheduleReleaseDpadDownFocusGuard() {
        if (!dpadDownFocusGuardActive) return
        val token = ++dpadDownFocusGuardReleaseToken
        binding.root.postDelayed(
            {
                if (!dpadDownFocusGuardActive) return@postDelayed
                if (dpadDownFocusGuardReleaseToken != token) return@postDelayed
                releaseDpadDownFocusGuard()
            },
            120L,
        )
    }

    private fun releaseDpadDownFocusGuard() {
        if (!dpadDownFocusGuardActive) return
        dpadDownFocusGuardActive = false
        val snapshots = dpadDownFocusGuardSnapshots.toMap()
        dpadDownFocusGuardSnapshots.clear()
        snapshots.forEach { (container, snap) ->
            container.descendantFocusability = snap.descendantFocusability
            container.isFocusable = snap.isFocusable
            container.isFocusableInTouchMode = snap.isFocusableInTouchMode
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isUserInfoOverlayVisible()) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && isNavKey(event.keyCode)) {
            suppressNextTopNavigationFocusFollow = false
            if (sidebarExpanded) scheduleSidebarAutoCollapse()
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> beginOrKeepDpadDownFocusGuard(currentFocus)
                KeyEvent.ACTION_UP,
                -> scheduleReleaseDpadDownFocusGuard()
            }
        } else if (event.action == KeyEvent.ACTION_DOWN && dpadDownFocusGuardActive) {
            // The user pressed another key: stop guarding so normal content navigation is not blocked.
            releaseDpadDownFocusGuard()
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
                if (dispatchRefreshKeyToCurrentPage()) return true
            }

            val focused = currentFocus
            if (sidebarExpanded && isNavKey(event.keyCode) && focused?.let(::isInExpandedSidebar) != true) {
                // A touch-opened sidebar can be visible before RecyclerView children accept focus.
                // Keep D-pad input on the overlay instead of letting it reach covered page content.
                focusTopNavigationKey(MainRootNavRegistry.normalizeVisibleTopNavKey(currentTopNavKey))
                return true
            }

            if (focused == null && isNavKey(event.keyCode)) {
                // During adapter updates, focus can be temporarily lost. Keep the event consumed
                // when content had focus recently instead of moving to an unrelated target.
                val now = SystemClock.uptimeMillis()
                if (now - lastMainFocusAtMs <= 1_000L) {
                    return true
                }
                if (event.repeatCount > 0) return true
                ensureInitialFocus()
                return true
            }

            if (focused != null && isInTopNavigation(focused)) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> return if (isInExpandedSidebar(focused)) collapseSidebar(restoreMainFocus = true) else expandSidebar(true)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> return if (isInExpandedSidebar(focused)) collapseSidebar(restoreMainFocus = true) else focusMainFromTopNavigation()
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isInExpandedSidebar(focused)) return focusMainFromTopNavigation()
                        return moveSidebarFocus(focused, 1)
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isInExpandedSidebar(focused)) return expandSidebar(true)
                        return moveSidebarFocus(focused, -1)
                    }
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    -> if (!isInExpandedSidebar(focused)) return expandSidebar(true)
                }
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focused != null && isInMainContainer(focused)) {
                        if (tryMoveDynamicVideoToFollowing(focused)) return true
                        if (focused.dispatchToAncestorDpadItemKeyHandler(event.keyCode, event)) {
                            return true
                        }
                        // Preserve page-local horizontal movement (search history, following, and grids).
                        val next = focused.focusSearch(View.FOCUS_LEFT)
                        if (next != null && isInMainContainer(next)) {
                            return super.dispatchKeyEvent(event)
                        }
                        if (expandSidebar(focusSelected = true)) return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focused != null && isInMainContainer(focused) && !hasPageSectionAbove(focused) && canEnterTopNavigationFrom(focused)) {
                        releaseDpadDownFocusGuard()
                        if (expandSidebar(focusSelected = true)) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchRefreshKeyToCurrentPage(): Boolean {
        val root = currentRootFragment() ?: return false
        val handler = findRefreshKeyHandler(root) ?: return false
        return handler.handleRefreshKey()
    }

    private fun findRefreshKeyHandler(fragment: Fragment): RefreshKeyHandler? {
        return findRefreshKeyHandler(fragment, preferResumed = true) ?: findRefreshKeyHandler(fragment, preferResumed = false)
    }

    private fun findRefreshKeyHandler(fragment: Fragment, preferResumed: Boolean): RefreshKeyHandler? {
        if (!fragment.isAdded) return null

        fragment.childFragmentManager.fragments.asReversed().forEach { child ->
            val found = findRefreshKeyHandler(child, preferResumed)
            if (found != null) return found
        }

        val active = if (preferResumed) fragment.isResumed else fragment.isVisible
        return if (active && fragment is RefreshKeyHandler) fragment else null
    }

    private fun initUserInfoOverlay() {
        userInfoOverlay.root.visibility = View.GONE

        userInfoOverlay.root.setOnClickListener { hideUserInfoOverlay() }
        userInfoOverlay.card.setOnClickListener { /* consume */ }
        userInfoOverlay.btnFollowing.setOnClickListener {
            hideUserInfoOverlay()
            startActivity(Intent(this, FollowingListActivity::class.java))
        }
        userInfoOverlay.btnFollower.setOnClickListener {
            AppToast.show(this, "粉丝列表未实现")
        }
        userInfoOverlay.btnSwitchAccount.setOnClickListener { showAccountSwitchPanel() }
        userInfoOverlay.btnAddAccount.setOnClickListener { openQrLogin() }
        userInfoOverlay.btnLogout.setOnClickListener { showLogoutConfirm() }

        val updateOverlayFocus = View.OnFocusChangeListener { focusedView, hasFocus ->
            focusedView.isSelected = hasFocus
            when (focusedView.id) {
                R.id.btn_following -> {
                    userInfoOverlay.tvFollowing.isSelected = hasFocus
                    userInfoOverlay.tvFollowingLabel.isSelected = hasFocus
                }
                R.id.btn_follower -> {
                    userInfoOverlay.tvFollower.isSelected = hasFocus
                    userInfoOverlay.tvFollowerLabel.isSelected = hasFocus
                }
            }
            userInfoOverlay.card.invalidate()
            userInfoOverlay.root.invalidate()
        }
        userInfoOverlay.btnFollowing.onFocusChangeListener = updateOverlayFocus
        userInfoOverlay.btnFollower.onFocusChangeListener = updateOverlayFocus
        userInfoOverlay.btnSwitchAccount.onFocusChangeListener = updateOverlayFocus
        userInfoOverlay.btnAddAccount.onFocusChangeListener = updateOverlayFocus
        userInfoOverlay.btnLogout.onFocusChangeListener = updateOverlayFocus
    }

    private fun isUserInfoOverlayVisible(): Boolean = userInfoOverlay.root.visibility == View.VISIBLE

    private fun clampUserInfoOverlayCardWidth() {
        val lp = userInfoOverlay.card.layoutParams as? MarginLayoutParams ?: return
        val baseWidth =
            baseUserInfoCardWidth
                ?: lp.width.takeIf { it > 0 }?.also { baseUserInfoCardWidth = it }
                ?: return

        val maxWidth = (resources.displayMetrics.widthPixels - lp.leftMargin - lp.rightMargin).coerceAtLeast(1)
        val clamped = baseWidth.coerceAtMost(maxWidth)
        if (lp.width != clamped) {
            lp.width = clamped
            userInfoOverlay.card.layoutParams = lp
        }
    }

    private fun showUserInfoOverlay() {
        if (isUserInfoOverlayVisible()) return
        cancelSidebarAutoCollapse()
        userInfoReturnFocus.capture(currentFocus)
        clampUserInfoOverlayCardWidth()

        binding.topNavigation.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.mainContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        userInfoOverlay.root.visibility = View.VISIBLE
        showProfilePanel(requestFocus = false)

        userInfoOverlay.root.post {
            requestUserInfoFocus(userInfoOverlay.btnFollowing)
        }
    }

    private fun requestUserInfoFocus(view: View): Boolean {
        if (!view.isShown || !view.isFocusable) return false
        return view.requestFocusFromTouch() || view.requestFocus()
    }

    private fun showProfilePanel(requestFocus: Boolean = true) {
        userInfoOverlayMode = UserInfoOverlayMode.PROFILE
        userInfoOverlay.profileStatsRow.visibility = View.VISIBLE
        userInfoOverlay.profileExpRow.visibility = View.VISIBLE
        userInfoOverlay.btnSwitchAccount.visibility = View.VISIBLE
        userInfoOverlay.btnLogout.visibility = View.VISIBLE
        userInfoOverlay.accountSwitchPanel.visibility = View.GONE
        userInfoOverlay.accountList.removeAllViews()
        resetUserInfoUi()
        loadUserInfo()
        if (requestFocus) {
            userInfoOverlay.btnFollowing.post { requestUserInfoFocus(userInfoOverlay.btnFollowing) }
        }
    }

    private fun showAccountSwitchPanel(requestFocus: Boolean = true) {
        userInfoOverlayMode = UserInfoOverlayMode.ACCOUNT_SWITCH
        userInfoLoadJob?.cancel()
        userInfoLoadJob = null
        BiliClient.accounts.saveCurrentSessionAsActive(
            appPrefs = BiliClient.prefs,
            cookies = BiliClient.cookies,
        )
        userInfoOverlay.profileStatsRow.visibility = View.GONE
        userInfoOverlay.profileExpRow.visibility = View.GONE
        userInfoOverlay.progressExp.visibility = View.GONE
        userInfoOverlay.btnSwitchAccount.visibility = View.GONE
        userInfoOverlay.btnLogout.visibility = View.GONE
        userInfoOverlay.pbLoading.visibility = View.GONE
        userInfoOverlay.accountSwitchPanel.visibility = View.VISIBLE
        renderAccountSwitchRows()
        if (requestFocus) {
            val first = userInfoOverlay.accountList.getChildAt(0) ?: userInfoOverlay.btnAddAccount
            first.post { requestUserInfoFocus(first) }
        }
    }

    private fun renderAccountSwitchRows() {
        val list = userInfoOverlay.accountList
        list.removeAllViews()
        val accounts = BiliClient.accounts.accounts()
        for (account in accounts) {
            val row = buildAccountSwitchRow(account)
            list.addView(row)
        }
    }

    private fun buildAccountSwitchRow(account: AccountSessionStore.AccountSummary): TextView {
        val row =
            layoutInflater.inflate(
                R.layout.item_account_switch_row,
                userInfoOverlay.accountList,
                false,
            ) as TextView
        row.text = accountSwitchRowText(account)
        row.onFocusChangeListener =
            View.OnFocusChangeListener { _, _ ->
                userInfoOverlay.card.invalidate()
                userInfoOverlay.root.invalidate()
            }
        row.setOnClickListener {
            if (account.isActive) {
                showProfilePanel()
                return@setOnClickListener
            }
            val switched =
                BiliClient.accounts.switchToAccount(
                    accountId = account.id,
                    appPrefs = BiliClient.prefs,
                    cookies = BiliClient.cookies,
                )
            if (switched == null) {
                AppToast.show(this, "帐号不存在")
                showAccountSwitchPanel()
                return@setOnClickListener
            }
            AppToast.show(this, "已切换：${switched.name}")
            syncTopNavigationState()
            refreshSession()
            showProfilePanel()
        }
        return row
    }

    private fun accountSwitchRowText(account: AccountSessionStore.AccountSummary): String {
        val suffix = if (account.isActive) "（当前）" else ""
        return account.name + suffix
    }

    private fun hideUserInfoOverlay() {
        if (!isUserInfoOverlayVisible()) return
        userInfoLoadJob?.cancel()
        userInfoLoadJob = null
        userInfoOverlayMode = UserInfoOverlayMode.PROFILE
        userInfoOverlay.root.visibility = View.GONE

        binding.topNavigation.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.mainContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        userInfoReturnFocus.restoreAndClear(postOnFail = true)
        if (sidebarExpanded) scheduleSidebarAutoCollapse()
    }

    private fun resetUserInfoUi() {
        userInfoOverlay.tvName.text = ""
        userInfoOverlay.tvMid.text = ""
        userInfoOverlay.tvFollowing.text = "--"
        userInfoOverlay.tvFollower.text = "--"
        userInfoOverlay.tvCoins.text = "--"
        userInfoOverlay.tvLevel.text = ""
        userInfoOverlay.tvExp.text = ""
        userInfoOverlay.progressExp.visibility = View.GONE
        userInfoOverlay.pbLoading.visibility = View.GONE
    }

    private fun loadUserInfo() {
        userInfoLoadJob?.cancel()
        userInfoOverlay.pbLoading.visibility = View.VISIBLE
        userInfoLoadJob =
            lifecycleScope.launch {
                val sessionRevision = BiliSession.state.value.revision
                runCatching {
                    val nav = BiliSession.profileNav()
                    val data = nav.optJSONObject("data")
                    val isLogin = data?.optBoolean("isLogin") ?: false
                    if (!isLogin) {
                        hideUserInfoOverlay()
                        return@launch
                    }

                    val mid = data?.optLong("mid") ?: 0L
                    val name = data?.optString("uname", "").orEmpty()
                    val avatarUrl = data?.optString("face")?.takeIf { it.isNotBlank() }
                    BiliClient.accounts.saveCurrentSessionAsActive(
                        appPrefs = BiliClient.prefs,
                        cookies = BiliClient.cookies,
                        name = name,
                        avatarUrl = avatarUrl,
                        mid = mid,
                    )

                    val coins = parseCoins(data)
                    val levelInfo = data?.optJSONObject("level_info")
                    val level = levelInfo?.optInt("current_level") ?: 0
                    val currentExp = parseInt(levelInfo, "current_exp") ?: 0
                    val nextExp = parseInt(levelInfo, "next_exp")

                    val stat = if (mid > 0) BiliApi.relationStat(mid) else null
                    if (sessionRevision != BiliSession.state.value.revision) return@launch

                    userInfoOverlay.tvName.text = name
                    userInfoOverlay.tvMid.text = getString(R.string.label_uid_fmt, mid.toString())
                    val normalizedUrl = blbl.cat3399.core.image.ImageUrl.avatar(avatarUrl)
                    blbl.cat3399.core.image.ImageLoader.loadInto(userInfoOverlay.ivAvatar, normalizedUrl)

                    userInfoOverlay.tvFollowing.text = (stat?.following ?: 0L).toString()
                    userInfoOverlay.tvFollower.text = (stat?.follower ?: 0L).toString()
                    userInfoOverlay.tvCoins.text = formatCoins(coins)

                    userInfoOverlay.tvLevel.text = getString(R.string.label_level_fmt, level)
                    val expText = if (nextExp != null && nextExp > 0) "$currentExp/$nextExp" else "已满级"
                    userInfoOverlay.tvExp.text = getString(R.string.label_exp_fmt, expText)

                    if (nextExp != null && nextExp > 0) {
                        userInfoOverlay.progressExp.visibility = View.VISIBLE
                        userInfoOverlay.progressExp.max = nextExp
                        userInfoOverlay.progressExp.progress = currentExp.coerceIn(0, nextExp)
                    } else {
                        userInfoOverlay.progressExp.visibility = View.GONE
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    if (sessionRevision != BiliSession.state.value.revision) return@launch
                    AppLog.w("MainActivity", "loadUserInfo unavailable: ${it.javaClass.simpleName}")
                    if (!isUserInfoOverlayVisible()) return@launch
                    userInfoOverlay.tvName.text = "加载失败"
                    userInfoOverlay.tvMid.text = "网络暂时不可用，请稍后重试"
                }
                userInfoOverlay.pbLoading.visibility = View.GONE
            }
    }

    private fun showLogoutConfirm() {
        val accountCount = BiliClient.accounts.accounts().size
        AppPopup.confirm(
            context = this,
            title = "退出登录",
            message =
                if (accountCount > 1) {
                    "将移除当前帐号，并自动切换到其他已保存帐号。确定继续吗？"
                } else {
                    "将清除当前登录状态，需要重新登录。确定继续吗？"
                },
            positiveText = "确定退出",
            negativeText = "取消",
            onPositive = {
                val next =
                    BiliClient.accounts.removeActiveAccountAndActivateNext(
                        appPrefs = BiliClient.prefs,
                        cookies = BiliClient.cookies,
                    )
                AppToast.show(this, next?.let { "已退出当前帐号，已切换：${it.name}" } ?: "已退出登录")
                hideUserInfoOverlay()
                syncTopNavigationState()
                refreshSession()
            },
        )
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

    private fun parseInt(obj: JSONObject?, key: String): Int? {
        val any = obj?.opt(key) ?: return null
        return when (any) {
            is Number -> any.toInt()
            is String -> any.toIntOrNull()
            else -> null
        }
    }

    private fun navigateOnFocus(key: String) {
        // Settings is a sidebar tool, not a destination; focus alone must not open it or alter the page.
        if (key == MainTopNavAdapter.KEY_SETTINGS) return
        if (suppressNextTopNavigationFocusFollow && key != currentTopNavKey) {
            suppressNextTopNavigationFocusFollow = false
            requestFocusTopNavigationSelected()
            return
        }
        if (topNavigationFocusReady && BiliClient.prefs.tabSwitchFollowsFocus && key != currentTopNavKey) {
            navigateTopKey(key, refreshIfReselected = false)
        }
    }

    override fun suppressNextTopNavigationFocusFollow() {
        suppressNextTopNavigationFocusFollow = true
    }

    private fun navigateTopKey(key: String, refreshIfReselected: Boolean = false): Boolean {
        if (MainRootNavRegistry.normalizeVisibleTopNavKey(key) != key) return false
        val targetNavId = MainRootNavRegistry.rootNavIdForTopKey(key) ?: return false
        if (!isValidRootNavId(targetNavId)) return false
        val startedAt = SystemClock.uptimeMillis()
        val wasSamePage = currentRootNavId == targetNavId && currentTopNavKey == key
        if (wasSamePage) {
            if (refreshIfReselected) dispatchRefreshKeyToCurrentPage()
            return true
        }
        currentFocus?.takeIf(::isInMainContainer)?.let { pageFocusedViews[currentTopNavKey] = WeakReference(it) }
        currentTopNavKey = key
        pendingPageSelectionKey = key.takeIf { MainRootNavRegistry.myPageKeyForTopKey(it) != null || MainRootNavRegistry.homePageKeyForTopKey(it) != null }
        BiliClient.prefs.lastVisitedTopNavigationKey = key
        configureTopNavigationItems(key)
        if (currentRootNavId != targetNavId && !switchRoot(targetNavId, clearBackStack = true)) return false
        applySelectedPageToRoot(key, targetNavId)
        binding.mainContainer.post {
            applySelectedPageToRoot(key, targetNavId)
            applySectionTabRowLayout()
            updateSidebarBackdropAfterPageChange()
        }
        AppLog.d("Nav", "selected key=$key dispatchMs=${SystemClock.uptimeMillis() - startedAt}")
        return true
    }

    override fun onHomePageSelected(pageKey: String) {
        val topKey = MainRootNavRegistry.topKeyForRootNavId(MainRootNavIds.HOME, pageKey)
        if (currentRootNavId != MainRootNavIds.HOME || !MainRootNavRegistry.isValidTopNavKey(topKey)) return
        if (pendingPageSelectionKey != null && pendingPageSelectionKey != topKey) return
        pendingPageSelectionKey = null
        if (topKey !in BiliClient.prefs.topNavigationVisibleKeys) {
            navigateTopKey(MainRootNavRegistry.normalizeVisibleTopNavKey(topKey), refreshIfReselected = false)
            return
        }
        currentTopNavKey = topKey
        BiliClient.prefs.lastVisitedTopNavigationKey = topKey
        navAdapter.setSelectedKey(topKey)
        configureTopNavigationItems(topKey)
        updateSidebarBackdropAfterPageChange()
    }

    override fun onMyPageSelected(topNavigationKey: String) {
        if (currentRootNavId != MainRootNavIds.MY || !topNavigationKey.startsWith("my_")) return
        if (pendingPageSelectionKey != null && pendingPageSelectionKey != topNavigationKey) return
        pendingPageSelectionKey = null
        if (topNavigationKey !in BiliClient.prefs.topNavigationVisibleKeys) {
            navigateTopKey(MainRootNavRegistry.normalizeVisibleTopNavKey(topNavigationKey), refreshIfReselected = false)
            return
        }
        currentTopNavKey = topNavigationKey
        BiliClient.prefs.lastVisitedTopNavigationKey = topNavigationKey
        navAdapter.setSelectedKey(topNavigationKey)
        configureTopNavigationItems(topNavigationKey)
        updateSidebarBackdropAfterPageChange()
    }

    private fun restoreRootAfterRecreate(initialSelectedNavId: Int) {
        if (!isValidRootNavId(initialSelectedNavId)) return

        // If we have cached (tagged) roots, ensure only the selected one is visible/resumed.
        if (hasAnyTaggedRootFragments()) {
            switchRoot(initialSelectedNavId, clearBackStack = false)
            return
        }

        // Legacy restore path (before root caching existed): keep the restored fragment as-is.
        val current = supportFragmentManager.findFragmentById(R.id.main_container)
        currentRootNavId = navIdForRootFragment(current)
        if (currentRootNavId == null) {
            switchRoot(initialSelectedNavId, clearBackStack = false)
        }
    }

    private fun switchRoot(navId: Int, clearBackStack: Boolean): Boolean {
        if (!isValidRootNavId(navId)) return false

        AppLog.d("MainActivity", "switchRoot navId=$navId clearBackStack=$clearBackStack t=${SystemClock.uptimeMillis()}")
        val fm = supportFragmentManager
        if (clearBackStack) {
            runCatching { fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE) }
        }

        val targetTag = rootTagFor(navId)
        var target = fm.findFragmentByTag(targetTag)
        val tx = fm.beginTransaction().setReorderingAllowed(true)

        if (target == null) {
            target = createRootFragment(navId)
            tx.add(R.id.main_container, target, targetTag)
        }

        // Keep cached roots, but remove unrelated fragments when switching roots
        // (equivalent to the old replace() behavior).
        fm.fragments
            .filter { it.isAdded && it.id == R.id.main_container && it !== target }
            .forEach { other ->
                val isCachedRoot = MainRootNavRegistry.isRootTag(other.tag)
                if (!clearBackStack || isCachedRoot) {
                    tx.hide(other)
                    tx.setMaxLifecycle(other, Lifecycle.State.STARTED)
                } else {
                    tx.remove(other)
                }
            }

        if (target.isHidden) {
            tx.show(target)
        }
        tx.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        tx.setPrimaryNavigationFragment(target)
        tx.commitAllowingStateLoss()

        currentRootNavId = navId
        return true
    }

    private fun currentRootFragment(): Fragment? {
        val fm = supportFragmentManager
        val id = currentRootNavId
        if (id != null) {
            fm.findFragmentByTag(rootTagFor(id))?.let { return it }
        }
        fm.primaryNavigationFragment?.let { return it }
        return fm.findFragmentById(R.id.main_container)
    }

    private fun inferCurrentRootNavIdFromFragments(): Int? {
        val fm = supportFragmentManager
        navIdForRootFragment(fm.primaryNavigationFragment)?.let { return it }

        MainRootNavRegistry.rootNavIds().forEach { navId ->
            val fragment = fm.findFragmentByTag(rootTagFor(navId)) ?: return@forEach
            if (fragment.isAdded && !fragment.isHidden) return navId
        }

        return navIdForRootFragment(fm.findFragmentById(R.id.main_container))
    }

    private fun hasAnyTaggedRootFragments(): Boolean {
        val fm = supportFragmentManager
        return MainRootNavRegistry.rootNavIds().any { fm.findFragmentByTag(rootTagFor(it)) != null }
    }

    private fun createRootFragment(navId: Int): Fragment {
        val spec = MainRootNavRegistry.enabledSpecForNavId(navId)
            ?: throw IllegalArgumentException("Unknown root navId=$navId")
        return spec.fragmentFactory()
    }

    private fun rootTagFor(navId: Int): String {
        return MainRootNavRegistry.rootTagFor(navId)
    }

    private fun navIdForRootFragment(fragment: Fragment?): Int? {
        val navId = MainRootNavRegistry.navIdForFragment(fragment) ?: return null
        return navId.takeIf { isValidRootNavId(it) }
    }

    private fun isValidRootNavId(id: Int): Boolean {
        return MainRootNavRegistry.isAccessibleRootNavId(id)
    }

    private fun openQrLogin() {
        AppLog.i("MainActivity", "openQrLogin")
        restoreAccountFocusAfterQrLogin =
            isUserInfoOverlayVisible() && userInfoOverlayMode == UserInfoOverlayMode.ACCOUNT_SWITCH
        qrLoginLauncher.launch(Intent(this, QrLoginActivity::class.java))
    }

    override fun openLoginForCurrentPage() {
        pendingLoginTargetKey = currentTopNavKey
        openQrLogin()
    }

    private fun shouldFinishOnBackPress(): Boolean {
        val now = SystemClock.uptimeMillis()
        val isSecond = now - lastBackAtMs <= BACK_DOUBLE_PRESS_WINDOW_MS
        if (isSecond) return true
        lastBackAtMs = now
        AppToast.show(this, "再按一次退出应用")
        return false
    }

    private fun showFirstLaunchDisclaimerIfNeeded() {
        if (BiliClient.prefs.disclaimerAccepted) return
        if (disclaimerPopup?.isShowing == true) return

        disclaimerPopup =
            AppPopup.confirm(
                context = this,
                title = getString(R.string.disclaimer_title),
                message = getString(R.string.disclaimer_message),
                positiveText = getString(R.string.disclaimer_accept),
                negativeText = getString(R.string.disclaimer_exit),
                cancelable = false,
                onPositive = { BiliClient.prefs.disclaimerAccepted = true },
                onNegative = { finish() },
                onDismiss = {
                    disclaimerPopup = null
                    if (!BiliClient.prefs.disclaimerAccepted && !isChangingConfigurations) finish()
                    maybeStartAutoUpdateCheck()
                    showIjkKernelUpdatePromptIfNeeded()
                },
            )
    }

    private fun showLastCrashPromptIfNeeded() {
        if (!BiliClient.prefs.disclaimerAccepted) return
        if (disclaimerPopup?.isShowing == true) return
        if (crashPromptPopup?.isShowing == true) return

        val crash = CrashTracker.loadLastCrash(this) ?: return
        if (CrashTracker.wasPrompted(this, crash.crashAtMs)) return

        CrashTracker.markPrompted(this, crash.crashAtMs)
        crashPromptPopup =
            AppPopup.confirm(
                context = this,
                title = "检测到上次异常退出",
                message = "为了帮助开发者定位问题，请到「设置 - 关于应用」导出或上传日志。",
                positiveText = "打开设置",
                negativeText = "知道了",
                onPositive = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onDismiss = {
                    crashPromptPopup = null
                    showIjkKernelUpdatePromptIfNeeded()
                },
            )
    }

    private fun showIjkKernelUpdatePromptIfNeeded() {
        if (ijkKernelPromptShown) {
            showAutoUpdatePromptIfReady()
            return
        }
        if (!BiliClient.prefs.disclaimerAccepted) return
        if (BiliClient.prefs.playerEngineKind != AppPrefs.PLAYER_ENGINE_IJK) {
            showAutoUpdatePromptIfReady()
            return
        }
        if (disclaimerPopup?.isShowing == true) return
        if (crashPromptPopup?.isShowing == true) return
        if (ijkKernelPromptPopup?.isShowing == true) return

        val status = IjkPlayerPlugin.status(this)
        val (title, message) =
            when (status) {
                IjkPlayerPlugin.InstallStatus.NeedsUpdate ->
                    "播放器内核需要更新" to "当前默认播放器内核是 IjkPlayer，本地内核不是当前应用要求的最新版。更新前不会加载旧 IjkPlayer 内核。"

                IjkPlayerPlugin.InstallStatus.NotInstalled ->
                    "播放器内核未安装" to "当前默认播放器内核是 IjkPlayer，需要先下载播放器内核。"

                else -> {
                    showAutoUpdatePromptIfReady()
                    return
                }
            }

        ijkKernelPromptShown = true
        ijkKernelPromptPopup =
            AppPopup.confirm(
                context = this,
                title = title,
                message = message,
                positiveText = "立即更新",
                negativeText = "稍后",
                onPositive = {
                    IjkPlayerPluginUi.ensureInstalled(this) {}
                },
                onDismiss = {
                    ijkKernelPromptPopup = null
                    showAutoUpdatePromptIfReady()
                },
            )
    }

    private fun maybeStartAutoUpdateCheck() {
        if (!BiliClient.prefs.disclaimerAccepted) return
        if (!BiliClient.prefs.autoUpdateCheckEnabled) return
        if (autoUpdateCheckStarted || autoUpdateCheckJob?.isActive == true) return

        autoUpdateCheckStarted = true
        autoUpdateCheckJob =
            lifecycleScope.launch {
                try {
                    val update = ApkUpdater.fetchLatestUpdate()
                    if (!ApkUpdater.isRemoteNewer(update.versionName, BuildConfig.VERSION_NAME)) return@launch
                    if (BiliClient.prefs.autoUpdateIgnoredVersionName == update.versionName) return@launch
                    pendingAutoUpdate = update
                    showAutoUpdatePromptIfReady()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLog.w("Update", "auto update check failed", t)
                }
            }
    }

    private fun showAutoUpdatePromptIfReady() {
        if (!BiliClient.prefs.autoUpdateCheckEnabled) return
        if (!BiliClient.prefs.disclaimerAccepted) return
        if (disclaimerPopup?.isShowing == true) return
        if (crashPromptPopup?.isShowing == true) return
        if (ijkKernelPromptPopup?.isShowing == true) return
        if (autoUpdatePromptPopup?.isShowing == true) return

        val update = pendingAutoUpdate ?: return
        if (!ApkUpdater.isRemoteNewer(update.versionName, BuildConfig.VERSION_NAME)) return
        if (BiliClient.prefs.autoUpdateIgnoredVersionName == update.versionName) return
        if (autoUpdatePromptShownVersion == update.versionName) return
        autoUpdatePromptShownVersion = update.versionName

        autoUpdatePromptPopup =
            ApkUpdateFlow.showUpdatePrompt(
                activity = this,
                update = update,
                onSkipVersion = { pendingAutoUpdate = null },
                onDismiss = {
                    autoUpdatePromptPopup = null
                },
            ) { selectedUpdate ->
                ApkUpdateFlow.startDownloadAndInstall(
                    activity = this,
                    latestVersionHint = selectedUpdate.versionName,
                    apkUrl = ApkUpdater.apkUrlFor(selectedUpdate.versionName),
                )
            }
    }

    private fun refreshSession() {
        BiliSession.syncCredentials()
        userRefreshJob?.cancel()
        userRefreshJob = lifecycleScope.launch { BiliSession.refresh() }
    }

    override fun openAccountDetails() {
        if (BiliSession.state.value.canAccessPersonal) showUserInfoOverlay() else openQrLogin()
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        if (!focusPageContent()) binding.btnCollapsedNavigation.root.requestFocus()
    }

    private fun forceInitialTopNavigationFocusIfNeeded() {
        if (topNavigationFocusReady) return
        binding.root.doOnPreDraw {
            if (needForceInitialTopNavigationFocus) {
                if (!focusPageContent()) binding.btnCollapsedNavigation.root.requestFocus()
                needForceInitialTopNavigationFocus = false
            }
            topNavigationFocusReady = true
        }
    }

    private fun restoreFocusAfterResume() {
        if (pausedFocusWasInMain) {
            binding.root.post { focusMainFromTopNavigation() }
            return
        }
        val desired = pausedFocusedView?.get()
        if (desired != null && desired.isAttachedToWindow && desired.isShown) {
            binding.root.post { desired.requestFocus() }
            return
        }
        if (!pausedFocusWasInMain) return

        val lastMain = lastMainFocusedView?.get()
        if (lastMain != null && lastMain.isAttachedToWindow && lastMain.isShown && isInMainContainer(lastMain)) {
            binding.root.post { lastMain.requestFocus() }
            return
        }

        if (currentFocus?.let(::isInTopNavigation) == true) {
            binding.root.post { focusMainFromTopNavigation() }
        }
    }

    private fun isInTopNavigation(view: View): Boolean = FocusTreeUtils.isDescendantOf(view, binding.topNavigation)

    private fun isInExpandedSidebar(view: View): Boolean = FocusTreeUtils.isDescendantOf(view, binding.navigationPanel)

    private fun isInMainContainer(view: View): Boolean = FocusTreeUtils.isDescendantOf(view, binding.mainContainer)

    override fun requestFocusTopNavigationSelected(): Boolean {
        val key = MainRootNavRegistry.normalizeVisibleTopNavKey(currentTopNavKey)
        expandSidebar(focusSelected = false)
        return focusTopNavigationKey(key)
    }

    private fun focusTopNavigationKey(key: String): Boolean {
        val token = ++focusRequestToken
        if (!sidebarExpanded) expandSidebar(focusSelected = false)
        if (key != MainNavigationKeys.MY_ACCOUNT &&
            key != MainTopNavAdapter.KEY_SETTINGS &&
            key !in BiliClient.prefs.topNavigationVisibleKeys
        ) return false
        val position = navAdapter.positionOf(key)
        if (position < 0) return false
        val recycler = binding.recyclerTopNav
        fun focusAttached(): Boolean {
            val item = recycler.findViewHolderForAdapterPosition(position)?.itemView ?: return false
            val focused = item.requestFocus() || item.requestFocusFromTouch()
            item.requestRectangleOnScreen(android.graphics.Rect(0, 0, item.width, item.height), true)
            return focused
        }
        if (focusAttached()) return true
        recycler.scrollToPosition(position)
        recycler.doOnPreDraw {
            if (focusRequestToken == token && navAdapter.positionOf(key) == position) focusAttached()
        }
        return true
    }

    override fun requestFocusTopNavigationAdjacent(direction: Int): Boolean {
        if (direction == 0) return requestFocusTopNavigationSelected()
        val sequence = topNavigationFocusSequence()
        val index = sequence.indexOf(currentTopNavKey).takeIf { it >= 0 } ?: 0
        if (index < 0) return requestFocusTopNavigationSelected()
        val target = (index + if (direction < 0) -1 else 1).coerceIn(sequence.indices)
        return focusTopNavigationSequenceIndex(sequence, target)
    }

    private fun focusMainFromTopNavigation(): Boolean {
        if (sidebarExpanded) return collapseSidebar(restoreMainFocus = true)
        return focusPageContent()
    }

    private fun focusPageContent(): Boolean {
        val rootFragment = currentRootFragment() ?: return false
        val fragmentView = rootFragment.view ?: return false

        if ((rootFragment as? PageSectionFocusHost)?.requestFocusPageSection() == true) return true

        val host = rootFragment as? TabContentSwitchFocusHost
        if (host != null && host.requestFocusCurrentPagePrimaryItemFromContentSwitch()) return true

        val lastMain = pageFocusedViews[currentTopNavKey]?.get()
        if (lastMain != null && lastMain.isAttachedToWindow && lastMain.isShown && FocusTreeUtils.isDescendantOf(lastMain, fragmentView)) {
            lastMain.requestFocus()
            return true
        }

        val recycler =
            fragmentView.findViewById<RecyclerView?>(R.id.recycler_following)
                ?: fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic)
                ?: fragmentView.findViewById<RecyclerView?>(R.id.recycler)
        if (recycler != null) {
            recycler.post {
                val current = currentFocus
                if (current != null && !isInTopNavigation(current) && !isInMainContainer(current)) return@post
                val selectedChild = (0 until recycler.childCount)
                    .map { recycler.getChildAt(it) }
                    .firstOrNull { it?.isSelected == true }
                if (selectedChild != null && selectedChild.requestFocus()) return@post
                val first = recycler.findViewHolderForAdapterPosition(0)?.itemView
                if (first != null) {
                    first.requestFocus()
                } else if ((recycler.adapter?.itemCount ?: 0) == 0) {
                    recycler.requestFocus()
                } else {
                    recycler.scrollToPosition(0)
                    recycler.post {
                        recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                    }
                }
            }
            return true
        }

        val firstFocusable = findFirstFocusableView(fragmentView)
        if (firstFocusable != null) {
            firstFocusable.requestFocus()
            return true
        }
        fragmentView.isFocusable = true
        return fragmentView.requestFocus()
    }

    private fun findFirstFocusableView(root: View): View? {
        if (root.visibility != View.VISIBLE || !root.isShown) return null
        if (root.isFocusable && root.isEnabled) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findFirstFocusableView(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun topNavigationFocusSequence(): List<String> =
        BiliClient.prefs.topNavigationVisibleKeys +
            MainNavigationKeys.MY_ACCOUNT +
            MainTopNavAdapter.KEY_SETTINGS

    private fun focusTopNavigationSequenceIndex(sequence: List<String>, index: Int): Boolean {
        val key = sequence.getOrNull(index) ?: return false
        return focusTopNavigationKey(key)
    }

    private fun hasPageSectionAbove(view: View): Boolean {
        val root = currentRootFragment() ?: return false
        if (root !is PageSectionFocusHost) return false
        val strip = root.view?.findViewById<View>(R.id.tab_layout) ?: return false
        return strip.isShown && !FocusTreeUtils.isDescendantOf(view, strip)
    }

    private fun canEnterTopNavigationFrom(view: View): Boolean {
        val next = view.focusSearch(View.FOCUS_UP)
        if (next != null && isInTopNavigation(next)) return true
        if (next != null && isInMainContainer(next)) return false
        val focusLoc = IntArray(2)
        val containerLoc = IntArray(2)
        view.getLocationOnScreen(focusLoc)
        binding.mainContainer.getLocationOnScreen(containerLoc)
        return focusLoc[1] - containerLoc[1] <= dp(32f)
    }

    private fun tryMoveDynamicVideoToFollowing(focused: View): Boolean {
        val fragmentView = currentRootFragment()?.view ?: return false
        val recyclerFollowing = fragmentView.findViewById<RecyclerView?>(R.id.recycler_following) ?: return false
        val recyclerDynamic = fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic) ?: return false
        if (!FocusTreeUtils.isDescendantOf(focused, recyclerDynamic)) return false
        if (!isStaggeredGridLeftEdge(focused, recyclerDynamic)) return false

        recyclerFollowing.post {
            val selectedChild = (0 until recyclerFollowing.childCount)
                .map { recyclerFollowing.getChildAt(it) }
                .firstOrNull { it?.isSelected == true }
            if (selectedChild != null) {
                selectedChild.requestFocus()
                return@post
            }

            val vh = recyclerFollowing.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            if (recyclerFollowing.adapter?.itemCount == 0) {
                recyclerFollowing.requestFocus()
                return@post
            }
            recyclerFollowing.scrollToPosition(0)
            recyclerFollowing.post {
                recyclerFollowing.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recyclerFollowing.requestFocus()
            }
        }
        return true
    }

    private fun isStaggeredGridLeftEdge(view: View, recyclerView: RecyclerView): Boolean {
        recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return false
        val itemView = recyclerView.findContainingItemView(view) ?: return false
        val lp = itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams ?: return false
        return lp.spanIndex == 0
    }

    private fun dp(valueDp: Float): Int {
        val dm = resources.displayMetrics
        return (valueDp * dm.density).toInt()
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    companion object {
        private const val STATE_KEY_ROOT_NAV_ID = "MainActivity.rootNavId"
        private const val STATE_KEY_TOP_NAV_KEY = "MainActivity.topNavKey"
        private const val STATE_KEY_PENDING_LOGIN_TARGET = "MainActivity.pendingLoginTarget"
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 1_500L
    }
}
