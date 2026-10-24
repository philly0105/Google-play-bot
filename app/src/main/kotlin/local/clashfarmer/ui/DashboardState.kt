package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.log.RunContextSnapshot
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.FrameStore

/**
 * Everything MainActivity.refreshStatus reads on a tick, handed to every screen's bind. Anything
 * heavier (history, captures, stores) a screen loads itself in onShown().
 */
data class DashboardState(
    val snapshot: SessionSnapshot = SessionSnapshot(),
    val runContext: RunContextSnapshot = RunContextSnapshot(),
    val connected: Boolean = false,
    val busy: Boolean = false,
    val paused: Boolean = false,
    val accounts: List<AccountRecord> = emptyList(),
    val selectedId: String? = null,
    val profiles: Map<String, PhoneProfile?> = emptyMap(),
    /** Why the selected account's profile failed to load, when it did. */
    val profileError: String? = null,
    /** Raw JSON log lines, oldest first (AppLog.readRecentLogs). */
    val events: List<String> = emptyList(),
    val frame: FrameStore.Snapshot? = null
) {
    val selected: AccountRecord? get() = accounts.firstOrNull { it.id == selectedId }
    val profile: PhoneProfile? get() = selectedId?.let { profiles[it] }

    /** The account on screen: the one in the game while a run is active, else the selected one. */
    val shownAccountId: String? get() = runContext.activeAccountId.takeIf { busy } ?: selectedId
    val shownAccount: AccountRecord? get() = accounts.firstOrNull { it.id == shownAccountId }

    /** The account name the run strip shows: the one being farmed now. */
    val runAccountName: String?
        get() = runContext.currentAccountName ?: runContext.activeAccountName ?: shownAccount?.villageName
}
