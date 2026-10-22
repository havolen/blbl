package blbl.cat3399.feature.home

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.R
import blbl.cat3399.feature.video.VideoGridFragment

data class HomeTabSpec(
    val key: String,
    @StringRes val titleRes: Int,
    val createFragment: () -> Fragment,
)

object HomeTabs {
    const val KEY_RECOMMEND = "recommend"
    const val KEY_POPULAR = "popular"
    const val KEY_CINEMA = "cinema"
    const val KEY_BANGUMI = "bangumi"

    val all: List<HomeTabSpec> =
        listOf(
            HomeTabSpec(KEY_RECOMMEND, R.string.tab_recommend) { VideoGridFragment.newRecommend() },
            HomeTabSpec(KEY_POPULAR, R.string.tab_popular) { VideoGridFragment.newPopular() },
            HomeTabSpec(KEY_CINEMA, R.string.tab_cinema) { PgcRecommendGridFragment.newCinema() },
            HomeTabSpec(KEY_BANGUMI, R.string.tab_bangumi) { PgcRecommendGridFragment.newBangumi() },
        )
}

class HomePagerAdapter(
    fragment: Fragment,
    private val tabs: List<HomeTabSpec>,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        return tabs[position].createFragment()
    }

    override fun getItemId(position: Int): Long = tabs[position].key.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = tabs.any { it.key.hashCode().toLong() == itemId }
}
