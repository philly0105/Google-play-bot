package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.BuilderStore
import local.clashfarmer.core.PendingWallPurchase
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.core.StorageReading
import local.clashfarmer.core.StorageStore
import local.clashfarmer.core.WallPurchaseStore
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.log.AppLog
import local.clashfarmer.log.RunContextSnapshot
import local.clashfarmer.recognition.PhoneProfile
import java.io.File

/**
 * Every file the dashboard reads. Each call does file IO, so the tick and the screens call it on
 * Dispatchers.IO; only a start's own click reads [accounts] directly, as the old starts did.
 */
class DashboardLoader(private val root: File, private val accountStore: AccountStore = AccountStore(root)) {

    /** The accounts, the selected one, and each account's calibration (null when missing or unreadable). */
    data class Accounts(
        val accounts: List<AccountRecord>,
        val selectedId: String?,
        val profiles: Map<String, PhoneProfile?>,
        /** Why the selected account's profile failed to load, when it did. */
        val profileError: String?
    ) {
        val selected: AccountRecord? get() = accounts.firstOrNull { it.id == selectedId }
    }

    /** What one MainActivity tick reads from disk and the log. */
    data class Tick(
        val accounts: Accounts,
        val snapshot: SessionSnapshot,
        val runContext: RunContextSnapshot,
        val events: List<String>
    )

    /**
     * What Home shows beyond the tick, but the battles, which [battles] reads. [markers] holds every
     * account with a wall marker; null means it would not parse.
     */
    data class Home(
        val accountId: String?,
        val storage: StorageReading?,
        val builders: BuilderReading?,
        val markers: Map<String, PendingWallPurchase?>,
        val last: RunPlan?
    )

    /** The composer's saved presets and the plan last started. */
    data class Presets(val presets: List<RunPlan>, val last: RunPlan?)

    fun accounts(): Accounts {
        val accounts = runCatching { accountStore.load() }.getOrDefault(emptyList())
        val selectedId = runCatching { accountStore.selectedId() }.getOrNull() ?: accounts.firstOrNull()?.id
        val profiles = mutableMapOf<String, PhoneProfile?>()
        var profileError: String? = null
        for (account in accounts) {
            val file = runCatching { accountStore.profileFile(account.id) }.getOrNull()
            profiles[account.id] = if (file != null && file.exists()) {
                try {
                    PhoneProfile.loadFromFile(file)
                } catch (e: Exception) {
                    if (account.id == selectedId) profileError = e.message ?: "Invalid profile"
                    null
                }
            } else {
                null
            }
        }
        return Accounts(accounts, selectedId, profiles, profileError)
    }

    fun tick(): Tick = Tick(accounts(), AppLog.sessionSnapshot(), AppLog.runContextSnapshot(), AppLog.readRecentLogs(150))

    fun home(accountId: String?, accountIds: List<String>): Home {
        val markerStore = WallPurchaseStore(root)
        val markers = accountIds.filter { runCatching { markerStore.isPending(it) }.getOrDefault(false) }
            .associateWith { markerStore.pending(it) }
        return Home(
            accountId = accountId,
            storage = accountId?.let { runCatching { StorageStore(root).latest(it) }.getOrNull() },
            builders = accountId?.let { runCatching { BuilderStore(root).latest(it) }.getOrNull() },
            markers = markers,
            last = runCatching { PresetStore(root).last() }.getOrNull()
        )
    }

    /**
     * Every account's battles, apart from [home]: up to [BattleHistoryStore.MAX_BATTLES] files take
     * seconds on the phone, and while they were read with the rest Home showed nothing it had saved.
     */
    fun battles(): List<BattleRecord> =
        runCatching { BattleHistoryStore(File(root, "history/battles")).load().records }.getOrDefault(emptyList())

    fun presets(): Presets {
        val store = PresetStore(root)
        return Presets(store.presets(), store.last())
    }
}
