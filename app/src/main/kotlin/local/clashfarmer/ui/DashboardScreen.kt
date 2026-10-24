package local.clashfarmer.ui

import local.clashfarmer.ui.components.Tone

/** A count badge ([count] > 0) or a plain dot ([count] == 0) in [tone] on a tab. */
data class TabBadge(val count: Int, val tone: Tone)

/** The five tabs, in tab bar order. */
enum class Tab(val title: String) {
    HOME("Home"), RUN("Run"), STATS("Stats"), ACTIVITY("Activity"), SETUP("Setup")
}
