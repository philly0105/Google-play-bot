package local.clashfarmer.core

import kotlinx.coroutines.delay
import local.clashfarmer.log.AppLog
import java.util.Locale

/**
 * Which stops an unattended run may pick itself up from, and what to do with the screen while it
 * finds its way back to the village.
 *
 * Only the game failing to show what was expected is recoverable: a timeout, an unreadable
 * screen, a screenshot the system refused; and another app taking the screen, after which the way
 * back starts by reopening Clash. Anything else a person did (Stop, Pause's touch, the screen going
 * off), anything the calibration or the account is wrong about,
 * and the session's own deadline all still end the run.
 * Village takeovers use a separate ten-minute backoff in [farmWithRestarts].
 */
object RecoveryPolicy {
    const val MAX_RESTARTS = 3

    /** How long the way back to the village may take; a battle left running lasts three minutes. */
    const val RECOVERY_TIMEOUT_MS = 240_000L

    /** How long an unreadable screen is waited on before Back is pressed at it. */
    const val UNKNOWN_BEFORE_BACK_MS = 20_000L

    private val RECOVERABLE = listOf(
        "screen transition timed out",
        "the screen stayed unrecognized",
        "unexpected transition screen",
        "matchmaking was still searching",
        "screenshot capture failed",
        "no return home button",
        "reward offers did not stop",
        "a reward banner stayed",
        "did not complete within",
        "cannot verify account identity because settings did not open",
        "timed out waiting for home village screen",
        ReturnHome.START_TIMEOUT_PREFIX.lowercase(Locale.ROOT),
        ReturnHome.MAINTENANCE_AT_START.lowercase(Locale.ROOT),
        // Only an unattended run leaves its guard stopped this way; see FarmerService.onAccessibilityEvent.
        "app switched to",
        "clash is not in foreground",
        // TapAfterHold.HELD from a tap no read loop encloses; every loop catches it first.
        "held a tap",
        // Neither spends the budget; see farmWithRestarts.
        PopupGuard.SERVER_DIALOG.lowercase(Locale.ROOT),
        PopupGuard.MAINTENANCE_OVER.lowercase(Locale.ROOT)
    )

    /**
     * Server or idle dialogs are counted from when each was first seen, over a sliding
     * [SERVER_WINDOW_MS]. The [SERVER_MAX_IN_WINDOW]th within one window stops the run; before
     * that, the nth is reloaded after [SERVER_BACKOFF_MS] at index n - 1. Measuring from the
     * sighting rather than from the end of the reload is what catches a drop that comes back
     * once per battle.
     */
    const val SERVER_WINDOW_MS = 30 * 60_000L
    const val SERVER_MAX_IN_WINDOW = 3
    val SERVER_BACKOFF_MS = listOf(0L, 60_000L)

    fun repeatedServerDisconnects(count: Int, minutes: Long) =
        "Repeated server disconnects: $count in $minutes min"

    fun isRecoverable(reason: String?): Boolean {
        val r = reason?.lowercase(Locale.ROOT) ?: return false
        return RECOVERABLE.any { r.startsWith(it) || r.contains(it) }
    }

    /** Stops that are the game's or the server's doing and do not spend a restart. */
    fun isServerDialog(reason: String?) = reason == PopupGuard.SERVER_DIALOG
    fun isMaintenanceOver(reason: String?) = reason == PopupGuard.MAINTENANCE_OVER

    enum class Step { DONE, WAIT, RETURN_HOME, BACK, LEAVE_TO_START }

    /**
     * What to do with one frame on the way back. A [reloadDialog] (idle, or the server dropped
     * the game) is left for the start's own account check, which already presses Reload game and
     * Try again. The caller ends the run on the takeover dialog before asking.
     */
    fun stepFor(state: ScreenState, unknownForMs: Long, reloadDialog: Boolean): Step = when {
        reloadDialog -> Step.LEAVE_TO_START
        state == ScreenState.HOME -> Step.DONE
        state == ScreenState.RESULT -> Step.RETURN_HOME
        // A battle ends itself, and nothing about a scout or a search needs a tap to move on.
        state in setOf(ScreenState.BATTLE, ScreenState.SCOUT, ScreenState.SEARCHING, ScreenState.REWARD) -> Step.WAIT
        state == ScreenState.UNKNOWN && unknownForMs < UNKNOWN_BEFORE_BACK_MS -> Step.WAIT
        // The attack menu, the army screen, a building panel or a window nothing reads.
        else -> Step.BACK
    }
}

/**
 * Farms [quota] attacks, starting over from the village after a recoverable stop until the quota
 * or the goal is reached or [maxRestarts] are spent. Takeovers do not spend that budget; [recover]
 * must wait before reclaiming the village, under the caller's session deadline.
 * A server or idle dialog does not spend it either: the first is reloaded at once, a second within
 * [RecoveryPolicy.SERVER_WINDOW_MS] after a backoff, and the third within that window stops the
 * run with [RecoveryPolicy.repeatedServerDisconnects]. The end of a maintenance break, waited
 * out by the runner, restarts for free up to [RecoveryPolicy.MAX_RESTARTS] times.
 * [attempt] runs with the attacks still owed;
 * [completedIn] says how many the failed attempt finished; [recover] brings the game back to
 * somewhere a start can begin and may throw to give up. [serverSightings] is shared by every call
 * in one run, so a combined run counts both accounts' dialogs in one window.
 */
suspend fun farmWithRestarts(
    quota: Int,
    maxRestarts: Int = RecoveryPolicy.MAX_RESTARTS,
    attempt: suspend (remaining: Int) -> RunOutcome,
    completedIn: () -> Int,
    recover: suspend (reason: String, restart: Int, remaining: Int) -> Unit,
    now: () -> Long = System::currentTimeMillis,
    serverSightings: ArrayDeque<Long> = ArrayDeque()
): RunOutcome {
    var done = 0
    var restarts = 0
    var maintenanceRestarts = 0
    while (true) {
        try {
            val outcome = attempt(quota - done)
            return outcome.copy(completedAttacks = done + outcome.completedAttacks)
        } catch (e: StopException) {
            val takeover = e.message == Policy.VILLAGE_TAKEN
            val server = RecoveryPolicy.isServerDialog(e.message)
            val maintenance = RecoveryPolicy.isMaintenanceOver(e.message)
            val free = takeover || server || maintenance
            if (!free && (restarts >= maxRestarts || !RecoveryPolicy.isRecoverable(e.message))) throw e
            if (maintenance && maintenanceRestarts >= RecoveryPolicy.MAX_RESTARTS) throw e
            if (server) {
                // The attempt throws on the frame that first shows the dialog, so this is its sighting.
                val seenAt = now()
                while (serverSightings.isNotEmpty() && seenAt - serverSightings.first() >= RecoveryPolicy.SERVER_WINDOW_MS) {
                    serverSightings.removeFirst()
                }
                serverSightings.addLast(seenAt)
                if (serverSightings.size >= RecoveryPolicy.SERVER_MAX_IN_WINDOW) {
                    val spanMs = seenAt - serverSightings.first()
                    val minutes = maxOf(1L, (spanMs + 59_999L) / 60_000L)
                    AppLog.log("server_dialog_repeated", "count" to serverSightings.size, "spanMs" to spanMs)
                    throw StopException(RecoveryPolicy.repeatedServerDisconnects(serverSightings.size, minutes))
                }
            }
            done += completedIn().coerceIn(0, quota - done)
            if (done >= quota) return RunOutcome(done, CompletionReason.QUOTA_REACHED)
            if (!free) restarts++
            if (maintenance) maintenanceRestarts++
            if (server) {
                val repeat = serverSightings.size - 1
                val waitMs = RecoveryPolicy.SERVER_BACKOFF_MS[repeat]
                AppLog.log("server_dialog_backoff", "repeat" to repeat, "waitMs" to waitMs)
                if (waitMs > 0) delay(waitMs)
            }
            var reason = e.message ?: "Run stopped"
            while (true) {
                try {
                    recover(reason, restarts, quota - done)
                    break
                } catch (recoveryFailure: StopException) {
                    // A takeover while recovering gets its own wait. No attempt ran here, so
                    // completedIn must not be added again and no restart is spent.
                    if (recoveryFailure.message != Policy.VILLAGE_TAKEN) throw recoveryFailure
                    reason = Policy.VILLAGE_TAKEN
                }
            }
        }
    }
}
