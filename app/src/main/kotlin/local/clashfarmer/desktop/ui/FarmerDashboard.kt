package local.clashfarmer.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.clashfarmer.desktop.ui.screens.ActivityScreen
import local.clashfarmer.desktop.ui.screens.HomeScreen
import local.clashfarmer.desktop.ui.screens.RunScreen
import local.clashfarmer.desktop.ui.screens.SetupScreen
import local.clashfarmer.desktop.ui.screens.StatsScreen
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.Tab

/**
 * The shell (spec 2.1) without its drawing: one [DashboardScreen] per tab, the selected tab, and
 * the last [DashboardState], which the run strip reads. [FarmerWindowContent] draws it. The
 * service remains the owner of every run and game action; this only holds state and forwards.
 */
class FarmerDashboard(host: DashboardHost, initialTab: Int = 0) {

    val screens: List<DashboardScreen> = listOf(
        HomeScreen(host),
        RunScreen(host),
        StatsScreen(host),
        ActivityScreen(host),
        SetupScreen(host)
    )

    /** The state of the last tick, null until the first. */
    var state: DashboardState? by mutableStateOf(null)
        private set

    var selectedTab: Int by mutableStateOf(initialTab.coerceIn(0, Tab.entries.lastIndex))
        private set

    init {
        selectTab(selectedTab)
    }

    fun selectTab(index: Int) {
        selectedTab = index.coerceIn(0, Tab.entries.lastIndex)
        val screen = screens[selectedTab]
        state?.let { screen.bind(it) }
        screen.onShown()
    }

    /** Called on each controller tick (every second while busy, on change while idle). */
    fun bind(next: DashboardState) {
        state = next
        screens.forEach { it.bind(next) }
    }
}
