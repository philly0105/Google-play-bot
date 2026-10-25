package local.clashfarmer.desktop.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.Tab
import local.clashfarmer.ui.TabBadge
import java.io.File

/**
 * What a screen may ask of the controller that hosts the dashboard (spec 2.1): the phone's
 * DashboardHost, with the Activity replaced by the few things the screens took from it.
 */
interface DashboardHost {
    val scope: CoroutineScope
    /** The app's files folder, the phone's Context.filesDir. */
    val filesDir: File
    /** The Clash window as the watcher last saw it; Setup's Game window line reads it. */
    val windowStatus: StateFlow<WindowStatus>
    fun act(action: String)        // MainActivity.handleAction's vocabulary, plus launch_clash
    fun refresh()                  // re-binds every screen; do not call it from inside bind
    fun openTab(tab: Tab)          // switches the shell to [tab], as if its tab was clicked
    fun openCalibration(accountId: String?)  // [accountId] null is the selected account; refused while busy
    fun inspection(block: suspend () -> Unit): Boolean  // runs [block] on scope as an inspection; false while busy
    /** A short message at the foot of the window, the phone's Toast: 2 s, or 3.5 s when [long]. */
    fun toast(text: String, long: Boolean = false)
}

/**
 * One tab. The shell owns the tab rail and the run strip; a screen owns everything between them.
 * A screen keeps its state in Compose state, so the rail and [Content] follow a change made by an
 * async load without the phone's badgesChanged.
 */
interface DashboardScreen {
    fun bind(state: DashboardState)   // called on each controller tick, cheap, no file IO
    fun onShown() {}                  // load heavy data (history, captures) on host.scope + IO

    /**
     * The tab's badge: Activity's problem count (red), Setup's failing-check dot (amber). Null
     * shows none.
     */
    val badge: TabBadge? get() = null

    /** The tab's page. */
    @Composable
    fun Content()
}

/**
 * The phone's "Enable Accessibility Service first!" and "Turn on the accessibility service in
 * Setup", where the UI says it: the PC service has no switch; it runs from the app's start, so
 * it is missing only while the app starts or after it failed to.
 */
const val NOT_RUNNING = "Clash Farmer is still starting. If this stays, quit it from the tray icon and open it again."
