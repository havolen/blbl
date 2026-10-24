package blbl.cat3399.feature.my

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.R
import blbl.cat3399.core.navigation.MainNavigationKeys

data class MyTabSpec(
    val key: String,
    @StringRes val titleRes: Int,
    val createFragment: () -> Fragment,
)

object MyTabs {
    const val KEY_HISTORY = "history"
    const val KEY_FAV = "fav"
    const val KEY_BANGUMI = "bangumi"
    const val KEY_DRAMA = "drama"
    const val KEY_TOVIEW = "toview"
    const val KEY_LIKE = "like"

    val all: List<MyTabSpec> =
        listOf(
            MyTabSpec(KEY_HISTORY, R.string.my_tab_history) { MyHistoryHostFragment() },
            MyTabSpec(KEY_FAV, R.string.my_tab_fav) { MyFavFoldersFragment() },
            MyTabSpec(KEY_BANGUMI, R.string.my_tab_bangumi) { MyBangumiFollowFragment.newInstance(type = 1) },
            MyTabSpec(KEY_DRAMA, R.string.my_tab_drama) { MyBangumiFollowFragment.newInstance(type = 2) },
            MyTabSpec(KEY_TOVIEW, R.string.my_tab_toview) { MyToViewFragment() },
            MyTabSpec(KEY_LIKE, R.string.my_tab_like) { MyLikeFragment() },
        )

    fun topNavigationKeyFor(tabKey: String): String? =
        when (tabKey) {
            KEY_HISTORY -> MainNavigationKeys.MY_HISTORY
            KEY_FAV -> MainNavigationKeys.MY_FAVORITES
            KEY_BANGUMI -> MainNavigationKeys.MY_BANGUMI
            KEY_DRAMA -> MainNavigationKeys.MY_DRAMA
            KEY_TOVIEW -> MainNavigationKeys.MY_WATCH_LATER
            KEY_LIKE -> MainNavigationKeys.MY_LIKES
            else -> null
        }

    fun tabKeyFor(topNavigationKey: String): String? =
        all.firstOrNull { topNavigationKeyFor(it.key) == topNavigationKey }?.key
}

class MyPagerAdapter(
    fragment: Fragment,
    private val tabs: List<MyTabSpec>,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        return tabs[position].createFragment()
    }

    override fun getItemId(position: Int): Long = tabs[position].key.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = tabs.any { it.key.hashCode().toLong() == itemId }
}
