package blbl.cat3399.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationKeysTest {
    private val keys = MainNavigationKeys

    @Test fun fixedMyDestinationSurvivesVisibilityAndStartupRestore() {
        assertEquals(keys.MY_ACCOUNT, keys.migrateStartupKey(keys.MY_ACCOUNT))
        assertEquals(keys.MY_ACCOUNT, keys.launchKey(keys.MY_ACCOUNT, null, listOf(keys.RECOMMEND)))
        assertEquals(keys.MY_ACCOUNT, keys.launchKey(keys.LAST_VISITED, keys.MY_ACCOUNT, listOf(keys.SEARCH)))
    }

    @Test fun defaultsHideHistoryAndFavoritesWithoutRemovingTheirRoutes() {
        assertEquals(setOf(keys.SEARCH, keys.RECOMMEND, keys.POPULAR, keys.CATEGORY, keys.LIVE, keys.MY_WATCH_LATER), keys.defaultVisible)
        assertEquals(keys.MY_HISTORY, keys.visibleFallback(keys.MY_HISTORY, listOf(keys.MY_HISTORY)))
        assertEquals(keys.MY_FAVORITES, keys.visibleFallback(keys.MY_FAVORITES, listOf(keys.MY_FAVORITES)))
    }

    @Test fun oldStartupValuesMigrateToSpecificPages() {
        assertEquals(keys.RECOMMEND, keys.migrateStartupKey("home"))
        assertEquals(keys.MY_HISTORY, keys.migrateStartupKey("my"))
        assertEquals(keys.RECOMMEND, keys.migrateStartupKey("dynamic"))
        assertEquals(keys.RECOMMEND, keys.migrateStartupKey("custom"))
        assertEquals(keys.RECOMMEND, keys.migrateStartupKey("unknown"))
        assertEquals(keys.MY_FAVORITES, keys.migrateStartupKey(keys.MY_FAVORITES))
    }

    @Test fun hiddenStartupAndLastPageShareTheSameFallback() {
        val visible = listOf(keys.SEARCH, keys.RECOMMEND, keys.MY_HISTORY)
        assertEquals(keys.RECOMMEND, keys.launchKey(keys.MY_FAVORITES, null, visible))
        assertEquals(keys.RECOMMEND, keys.launchKey(keys.LAST_VISITED, keys.MY_LIKES, visible))
        assertEquals(keys.MY_HISTORY, keys.launchKey(keys.LAST_VISITED, keys.MY_HISTORY, visible))
    }

    @Test fun fallbackUsesCanonicalOrderWhenRecommendIsHidden() {
        val visible = listOf(keys.MY_FAVORITES, keys.POPULAR, keys.LIVE)
        assertEquals(keys.POPULAR, keys.launchKey("dynamic", null, visible))
        assertEquals(keys.POPULAR, keys.launchKey(keys.LAST_VISITED, null, visible))
        assertEquals(keys.MY_FAVORITES, keys.launchKey(keys.MY_FAVORITES, null, visible))
    }
}
