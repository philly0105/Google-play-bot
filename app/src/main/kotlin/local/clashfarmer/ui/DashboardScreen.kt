package local.clashfarmer.ui

import local.clashfarmer.ui.components.Tone

/** What a screen may ask of the activity that hosts the dashboard (spec 2.1). */
interface DashboardHost {
    val activity: android.app.Activity
    val scope: kotlinx.coroutines.CoroutineScope
    fun act(action: String)        // existing MainActivity.handleAction vocabulary
    fun refresh()                  // re-binds every screen; do not call it from inside bind
    fun openTab(tab: Tab)          // switches the shell to [tab], as if its tab button was tapped
    fun openCalibration(accountId: String?)  // [accountId] null is the selected account; refused while busy
    fun badgesChanged()            // re-reads every tab's badge after an async load; do not call it from inside bind
    fun inspection(block: suspend () -> Unit): Boolean  // runs [block] on scope as an inspection; false while busy
}

/** One tab. The shell owns the tab bar and run strip; a screen owns everything between them. */
interface DashboardScreen {
    val view: android.view.View
    fun bind(state: DashboardState)   // called on each MainActivity tick, cheap, no file IO
    fun onShown() {}                  // load heavy data (history, captures) on host.scope + IO

    /**
     * The tab's badge, read by the shell after each [bind]: Activity's problem count (red),
     * Setup's failing-check dot (amber). Null shows none.
     */
    val badge: TabBadge? get() = null
}

/** A count badge ([count] > 0) or a plain dot ([count] == 0) in [tone] on a tab. */
data class TabBadge(val count: Int, val tone: Tone)

/** The five tabs, in tab bar order. */
enum class Tab(val title: String) {
    HOME("Home"), RUN("Run"), STATS("Stats"), ACTIVITY("Activity"), SETUP("Setup")
}
