package blbl.cat3399.core.navigation

/** Stable keys shared by the top navigation, settings, and saved navigation state. */
object MainNavigationKeys {
    const val LAST_VISITED = "last_visited"
    const val MY_ACCOUNT = "my_account"
    const val SEARCH = "search"
    const val RECOMMEND = "recommend"
    const val POPULAR = "popular"
    const val CATEGORY = "category"
    const val CINEMA = "cinema"
    const val BANGUMI = "bangumi"
    const val LIVE = "live"
    const val MY_HISTORY = "my_history"
    const val MY_FAVORITES = "my_favorites"
    const val MY_BANGUMI = "my_bangumi"
    const val MY_DRAMA = "my_drama"
    const val MY_WATCH_LATER = "my_watch_later"
    const val MY_LIKES = "my_likes"

    val all: List<String> =
        listOf(
            SEARCH,
            RECOMMEND,
            POPULAR,
            CATEGORY,
            CINEMA,
            BANGUMI,
            LIVE,
            MY_HISTORY,
            MY_FAVORITES,
            MY_BANGUMI,
            MY_DRAMA,
            MY_WATCH_LATER,
            MY_LIKES,
        )

    /** The fixed My destination is not controlled by the entry visibility setting. */
    val destinations: List<String> = all + MY_ACCOUNT

    val defaultVisible: Set<String> =
        setOf(
            SEARCH,
            RECOMMEND,
            POPULAR,
            CATEGORY,
            LIVE,
            MY_HISTORY,
            MY_FAVORITES,
            MY_WATCH_LATER,
        )

    fun migrateStartupKey(value: String): String =
        when (value) {
            "home" -> RECOMMEND
            "my" -> MY_HISTORY
            LAST_VISITED -> LAST_VISITED
            in destinations -> value
            else -> RECOMMEND
        }

    fun visibleFallback(requested: String?, visibleKeys: Collection<String>): String {
        if (requested == MY_ACCOUNT) return MY_ACCOUNT
        val visible = all.filter { it in visibleKeys }
        return requested?.takeIf { it in visible }
            ?: RECOMMEND.takeIf { it in visible }
            ?: visible.firstOrNull()
            ?: RECOMMEND
    }

    fun launchKey(startupKey: String, lastVisitedKey: String?, visibleKeys: Collection<String>): String {
        val startup = migrateStartupKey(startupKey)
        return visibleFallback(if (startup == LAST_VISITED) lastVisitedKey else startup, visibleKeys)
    }
}
