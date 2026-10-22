package blbl.cat3399.ui

/** Main screen navigation and focus hooks used by root page fragments. */
interface MainNavigationHost {
    /** Remember the current stable destination while the user completes or cancels login. */
    fun openAccountDetails()

    fun openLoginForCurrentPage()
    /** Request focus on the selected entry in the main top navigation. */
    fun requestFocusTopNavigationSelected(): Boolean

    /** Keep a temporary focus handoff from switching pages during a nested content transition. */
    fun suppressNextTopNavigationFocusFollow()

    /** Keep the global top navigation selection in sync with the Home pager's stable page key. */
    fun onHomePageSelected(pageKey: String)

    /** Keep the global top navigation selection in sync with the selected personal page. */
    fun onMyPageSelected(topNavigationKey: String)

    /** Move focus to the previous or next visible top-level navigation entry. */
    fun requestFocusTopNavigationAdjacent(direction: Int): Boolean
}
