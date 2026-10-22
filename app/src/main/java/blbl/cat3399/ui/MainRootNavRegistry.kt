package blbl.cat3399.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import blbl.cat3399.R
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.navigation.MainNavigationKeys
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.category.CategoryFragment
import blbl.cat3399.feature.custom.CustomPageFragment
import blbl.cat3399.feature.custom.CustomPageTabRegistry
import blbl.cat3399.feature.dynamic.DynamicFragment
import blbl.cat3399.feature.home.HomeFragment
import blbl.cat3399.feature.live.LiveFragment
import blbl.cat3399.feature.my.MyAccountFragment
import blbl.cat3399.feature.my.MyFragment
import blbl.cat3399.feature.search.SearchFragment

data class RootNavSpec(
    val navId: Int,
    val key: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val startupPageKey: String? = null,
    val isEnabled: () -> Boolean = { true },
    val matchesFragment: (Fragment) -> Boolean,
    val fragmentFactory: () -> Fragment,
)

data class MainTopNavSpec(
    val key: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
)

object MainRootNavRegistry {
    private const val ROOT_TAG_PREFIX = "main_root_"

    fun accountItem(avatarUrl: String? = null) =
        MainTopNavAdapter.NavItem(
            key = MainNavigationKeys.MY_ACCOUNT,
            title = "我的",
            iconRes = R.drawable.ic_nav_my,
            avatarUrl = avatarUrl,
        )
    val settingsItem = MainTopNavAdapter.NavItem(MainTopNavAdapter.KEY_SETTINGS, "设置", R.drawable.ic_nav_settings)

    private val specs: List<RootNavSpec> =
        listOf(
            RootNavSpec(
                navId = MainRootNavIds.ACCOUNT,
                key = MainNavigationKeys.MY_ACCOUNT,
                titleRes = R.string.tab_my,
                iconRes = R.drawable.ic_nav_my,
                matchesFragment = { it is MyAccountFragment },
                fragmentFactory = { MyAccountFragment() },
            ),
            RootNavSpec(
                navId = MainRootNavIds.SEARCH,
                key = "search",
                titleRes = R.string.tab_search,
                iconRes = R.drawable.ic_nav_search,
                matchesFragment = { it is SearchFragment },
                fragmentFactory = { SearchFragment.newInstance() },
            ),
            RootNavSpec(
                navId = MainRootNavIds.HOME,
                key = "home",
                titleRes = R.string.tab_recommend,
                iconRes = R.drawable.ic_nav_home,
                startupPageKey = AppPrefs.STARTUP_PAGE_HOME,
                matchesFragment = { it is HomeFragment },
                fragmentFactory = { HomeFragment.newInstance() },
            ),
            RootNavSpec(
                navId = MainRootNavIds.CATEGORY,
                key = "category",
                titleRes = R.string.tab_category,
                iconRes = R.drawable.ic_nav_category,
                startupPageKey = AppPrefs.STARTUP_PAGE_CATEGORY,
                matchesFragment = { it is CategoryFragment },
                fragmentFactory = { CategoryFragment.newInstance() },
            ),
            RootNavSpec(
                navId = MainRootNavIds.DYNAMIC,
                key = "dynamic",
                titleRes = R.string.tab_dynamic,
                iconRes = R.drawable.ic_nav_dynamic,
                startupPageKey = AppPrefs.STARTUP_PAGE_DYNAMIC,
                matchesFragment = { it is DynamicFragment },
                fragmentFactory = { DynamicFragment.newInstance() },
            ),
            RootNavSpec(
                navId = MainRootNavIds.LIVE,
                key = "live",
                titleRes = R.string.tab_live,
                iconRes = R.drawable.ic_nav_live,
                startupPageKey = AppPrefs.STARTUP_PAGE_LIVE,
                matchesFragment = { it is LiveFragment },
                fragmentFactory = { LiveFragment.newInstance() },
            ),
            RootNavSpec(
                navId = MainRootNavIds.CUSTOM,
                key = "custom",
                titleRes = R.string.tab_custom,
                iconRes = R.drawable.ic_nav_custom,
                startupPageKey = AppPrefs.STARTUP_PAGE_CUSTOM,
                isEnabled = {
                    CustomPageTabRegistry.isEnabled(
                        config = BiliClient.prefs.customPageConfig,
                        isLoggedIn = BiliClient.cookies.hasSessData(),
                    )
                },
                matchesFragment = { it is CustomPageFragment },
                fragmentFactory = { CustomPageFragment.newInstance() },
            ),
            RootNavSpec(
                navId = MainRootNavIds.MY,
                key = "my",
                titleRes = R.string.tab_my,
                iconRes = R.drawable.ic_nav_my,
                startupPageKey = AppPrefs.STARTUP_PAGE_MY,
                matchesFragment = { it is MyFragment },
                fragmentFactory = { MyFragment.newInstance() },
            ),
        )

    private val topNavSpecs =
        listOf(
            MainTopNavSpec(MainNavigationKeys.SEARCH, R.string.tab_search, R.drawable.ic_nav_search),
            MainTopNavSpec(MainNavigationKeys.RECOMMEND, R.string.tab_recommend, R.drawable.ic_nav_home),
            MainTopNavSpec(MainNavigationKeys.POPULAR, R.string.tab_popular, R.drawable.ic_hot_fire),
            MainTopNavSpec(MainNavigationKeys.CATEGORY, R.string.tab_category, R.drawable.ic_nav_category),
            MainTopNavSpec(MainNavigationKeys.CINEMA, R.string.tab_cinema, R.drawable.ic_nav_cinema),
            MainTopNavSpec(MainNavigationKeys.BANGUMI, R.string.tab_bangumi, R.drawable.ic_nav_bangumi),
            MainTopNavSpec(MainNavigationKeys.LIVE, R.string.tab_live, R.drawable.ic_nav_live),
            MainTopNavSpec(MainNavigationKeys.MY_HISTORY, R.string.my_tab_history, R.drawable.ic_nav_history),
            MainTopNavSpec(MainNavigationKeys.MY_FAVORITES, R.string.my_tab_fav, R.drawable.ic_nav_favorite),
            MainTopNavSpec(MainNavigationKeys.MY_BANGUMI, R.string.my_tab_bangumi, R.drawable.ic_nav_follow),
            MainTopNavSpec(MainNavigationKeys.MY_DRAMA, R.string.my_tab_drama, R.drawable.ic_nav_drama),
            MainTopNavSpec(MainNavigationKeys.MY_WATCH_LATER, R.string.my_tab_toview, R.drawable.ic_nav_watch_later),
            MainTopNavSpec(MainNavigationKeys.MY_LIKES, R.string.my_tab_like, R.drawable.ic_nav_like),
        )

    fun allTopNavItems(context: Context): List<MainTopNavAdapter.NavItem> =
        topNavSpecs.map { spec ->
            MainTopNavAdapter.NavItem(
                key = spec.key,
                title = context.getString(spec.titleRes),
                iconRes = spec.iconRes,
            )
        }

    fun topNavItems(
        context: Context,
        visibleKeys: Collection<String> = BiliClient.prefs.topNavigationVisibleKeys,
        accountAvatarUrl: String? = null,
    ): List<MainTopNavAdapter.NavItem> {
        val visible = visibleKeys.toSet()
        return allTopNavItems(context).filter { it.key in visible } + listOf(accountItem(accountAvatarUrl), settingsItem)
    }

    fun isValidTopNavKey(key: String): Boolean = key in MainNavigationKeys.destinations

    fun normalizeVisibleTopNavKey(key: String?): String {
        return MainNavigationKeys.visibleFallback(key, BiliClient.prefs.topNavigationVisibleKeys)
    }

    fun rootNavIdForTopKey(key: String): Int? =
        when (key) {
            MainNavigationKeys.MY_ACCOUNT -> MainRootNavIds.ACCOUNT
            MainTopNavAdapter.KEY_SEARCH -> MainRootNavIds.SEARCH
            MainTopNavAdapter.KEY_RECOMMEND,
            MainTopNavAdapter.KEY_POPULAR,
            MainTopNavAdapter.KEY_CINEMA,
            MainTopNavAdapter.KEY_BANGUMI,
            -> MainRootNavIds.HOME

            MainTopNavAdapter.KEY_CATEGORY -> MainRootNavIds.CATEGORY
            MainTopNavAdapter.KEY_LIVE -> MainRootNavIds.LIVE
            MainTopNavAdapter.KEY_MY_HISTORY,
            MainTopNavAdapter.KEY_MY_FAVORITES,
            MainTopNavAdapter.KEY_MY_BANGUMI,
            MainTopNavAdapter.KEY_MY_DRAMA,
            MainTopNavAdapter.KEY_MY_WATCH_LATER,
            MainTopNavAdapter.KEY_MY_LIKES,
            -> MainRootNavIds.MY
            else -> null
        }

    fun homePageKeyForTopKey(key: String): String? =
        when (key) {
            MainTopNavAdapter.KEY_RECOMMEND -> blbl.cat3399.feature.home.HomeTabs.KEY_RECOMMEND
            MainTopNavAdapter.KEY_POPULAR -> blbl.cat3399.feature.home.HomeTabs.KEY_POPULAR
            MainTopNavAdapter.KEY_CINEMA -> blbl.cat3399.feature.home.HomeTabs.KEY_CINEMA
            MainTopNavAdapter.KEY_BANGUMI -> blbl.cat3399.feature.home.HomeTabs.KEY_BANGUMI
            else -> null
        }

    fun myPageKeyForTopKey(key: String): String? =
        when (key) {
            MainTopNavAdapter.KEY_MY_HISTORY -> blbl.cat3399.feature.my.MyTabs.KEY_HISTORY
            MainTopNavAdapter.KEY_MY_FAVORITES -> blbl.cat3399.feature.my.MyTabs.KEY_FAV
            MainTopNavAdapter.KEY_MY_BANGUMI -> blbl.cat3399.feature.my.MyTabs.KEY_BANGUMI
            MainTopNavAdapter.KEY_MY_DRAMA -> blbl.cat3399.feature.my.MyTabs.KEY_DRAMA
            MainTopNavAdapter.KEY_MY_WATCH_LATER -> blbl.cat3399.feature.my.MyTabs.KEY_TOVIEW
            MainTopNavAdapter.KEY_MY_LIKES -> blbl.cat3399.feature.my.MyTabs.KEY_LIKE
            else -> null
        }

    fun topKeyForRootNavId(navId: Int, homePageKey: String? = null): String =
        when (navId) {
            MainRootNavIds.ACCOUNT -> MainNavigationKeys.MY_ACCOUNT
            MainRootNavIds.SEARCH -> MainTopNavAdapter.KEY_SEARCH
            MainRootNavIds.HOME -> homePageKey?.takeIf(::isValidTopNavKey) ?: MainTopNavAdapter.KEY_RECOMMEND
            MainRootNavIds.CATEGORY -> MainTopNavAdapter.KEY_CATEGORY
            MainRootNavIds.LIVE -> MainTopNavAdapter.KEY_LIVE
            MainRootNavIds.MY -> homePageKey?.takeIf { it in MainNavigationKeys.all && it.startsWith("my_") }
                ?: MainTopNavAdapter.KEY_MY_HISTORY
            else -> MainTopNavAdapter.KEY_RECOMMEND
        }

    fun isAccessibleRootNavId(navId: Int): Boolean =
        navId in setOf(
            MainRootNavIds.ACCOUNT,
            MainRootNavIds.SEARCH,
            MainRootNavIds.HOME,
            MainRootNavIds.CATEGORY,
            MainRootNavIds.LIVE,
            MainRootNavIds.MY,
        ) && enabledSpecForNavId(navId) != null

    fun enabledSpecs(): List<RootNavSpec> = specs.filter { it.isEnabled() }

    fun startupSpecs(): List<RootNavSpec> =
        specs.filter {
            it.startupPageKey != null &&
                it.navId != MainRootNavIds.DYNAMIC &&
                it.navId != MainRootNavIds.CUSTOM
        }

    fun enabledStartupSpecs(): List<RootNavSpec> = enabledSpecs().filter { it.startupPageKey != null }

    fun resolveLaunchNavId(startupPageKey: String): Int {
        val key = resolveLaunchTopNavKey(startupPageKey)
        return rootNavIdForTopKey(key) ?: MainRootNavIds.HOME
    }

    fun resolveLaunchTopNavKey(startupPageKey: String): String {
        return MainNavigationKeys.launchKey(
            startupPageKey,
            BiliClient.prefs.lastVisitedTopNavigationKey,
            BiliClient.prefs.topNavigationVisibleKeys,
        )
    }

    fun startupTitle(context: Context, startupPageKey: String): String {
        val key = MainNavigationKeys.migrateStartupKey(startupPageKey)
        if (key == MainNavigationKeys.LAST_VISITED) return "恢复上次浏览页面"
        if (key == MainNavigationKeys.MY_ACCOUNT) return "我的"
        val title = allTopNavItems(context).firstOrNull { it.key == key }?.title ?: context.getString(R.string.tab_recommend)
        if (key in BiliClient.prefs.topNavigationVisibleKeys) return title
        val fallback = allTopNavItems(context).first { it.key == normalizeVisibleTopNavKey(key) }.title
        return "$title（已隐藏，将打开$fallback）"
    }

    fun specForNavId(navId: Int): RootNavSpec? = specs.firstOrNull { it.navId == navId }

    fun enabledSpecForNavId(navId: Int): RootNavSpec? = specForNavId(navId)?.takeIf { it.isEnabled() }

    fun rootNavIds(): IntArray =
        enabledSpecs().filter { isAccessibleRootNavId(it.navId) }.map { it.navId }.toIntArray()

    fun rootTagFor(navId: Int): String {
        val spec = specForNavId(navId) ?: throw IllegalArgumentException("Unknown root navId=$navId")
        return ROOT_TAG_PREFIX + spec.key
    }

    fun isRootTag(tag: String?): Boolean = tag?.startsWith(ROOT_TAG_PREFIX) == true

    fun navIdForFragment(fragment: Fragment?): Int? {
        if (fragment == null) return null
        val byTag = specs.firstOrNull { rootTagFor(it.navId) == fragment.tag }
        if (byTag != null) return byTag.navId
        return specs.firstOrNull { it.matchesFragment(fragment) }?.navId
    }
}
