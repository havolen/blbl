package blbl.cat3399.feature.my

import androidx.fragment.app.Fragment
import blbl.cat3399.ui.MainNavigationHost

fun Fragment.focusSelectedMyTabIfAvailable(): Boolean {
    return (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true
}

fun Fragment.switchToNextMyTabFromContentEdge(): Boolean {
    return (activity as? MainNavigationHost)?.requestFocusTopNavigationAdjacent(1) == true
}

fun Fragment.switchToPrevMyTabFromContentEdge(): Boolean {
    return (activity as? MainNavigationHost)?.requestFocusTopNavigationAdjacent(-1) == true
}
