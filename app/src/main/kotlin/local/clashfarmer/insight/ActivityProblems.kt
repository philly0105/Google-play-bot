package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.WallPurchaseStore
import local.clashfarmer.recognition.PhoneProfile
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/**
 * The "Needs you" inbox as the app's Activity screen and the remote page both build it, so the two
 * cannot list different problems: [Problems.collect] plus the wall markers that will not read.
 */
internal object ActivityProblems {
    /** A wall marker that exists but will not read. Walls stay blocked, and only Check village clears it. */
    fun unreadableMarker(account: AccountRecord): Problem = Problem(
        key = "marker:${account.id}",
        tone = Tone.BAD,
        title = if (account.displayName.isBlank()) "Wall purchase not confirmed" else "${account.displayName}: wall purchase not confirmed",
        detail = "A wall purchase could not be confirmed, and its record could not be read. Look at the village before " +
            "more walls are bought, then confirm. Walls do not run on this account until then.",
        timeMs = null,
        accountId = account.id,
        action = Problem.Action.CHECK_VILLAGE,
        dismissible = false
    )

    /** The unreadable markers first, then [collected] without the log's "not confirmed" entries for those accounts. */
    fun withUnreadable(collected: List<Problem>, unreadable: List<AccountRecord>): List<Problem> {
        if (unreadable.isEmpty()) return collected
        val ids = unreadable.map { it.id }.toSet()
        return unreadable.map(::unreadableMarker) + collected.filterNot { it.key.startsWith("unknown:") && it.accountId in ids }
    }

    /**
     * The inbox from [events] (oldest first), reading the wall markers and profile save times under
     * [files]. [dismissed] entries are left out, as [Problems.collect] does.
     */
    fun inbox(
        files: File,
        accounts: List<AccountRecord>,
        profiles: Map<String, PhoneProfile?>,
        events: List<JSONObject>,
        dismissed: Map<String, Long>,
        zone: ZoneId
    ): List<Problem> {
        val walls = WallPurchaseStore(files)
        val read = accounts.map { it to runCatching { walls.pending(it.id) }.getOrNull() }
        val markers = read.mapNotNull { it.second }
        // A marker that exists but will not parse blocks walls just the same, so it must show too.
        val unreadable = read.filter { (a, m) -> m == null && runCatching { walls.isPending(a.id) }.getOrDefault(false) }.map { it.first }
        val accountStore = AccountStore(files)
        val savedAt = accounts.mapNotNull { account ->
            runCatching { accountStore.profileFile(account.id) }.getOrNull()?.takeIf { it.isFile }?.let { account.id to it.lastModified() }
        }.toMap()
        val health = CalibrationHealth.check(accounts, profiles, savedAt)
        return withUnreadable(Problems.collect(events, accounts, markers, health, dismissed, zone), unreadable)
    }
}
