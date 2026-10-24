package local.clashfarmer.accounts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.clashfarmer.core.CompletionReason
import local.clashfarmer.core.Policy
import local.clashfarmer.core.RunOutcome
import local.clashfarmer.core.StopException
import local.clashfarmer.log.AppLog

interface AccountRunnerDriver {
    /**
     * Verifies [account]'s identity, switching to it if need be, and farms it for at most
     * [maxMinutes]. Returns only on a normal finish, which includes running out of those minutes;
     * a failed identity check, a Stop or the hard deadline behind the time limit is thrown.
     */
    suspend fun farmAccount(account: AccountRecord, quota: Int, maxMinutes: Int): RunOutcome
}

/**
 * Runs two accounts one after the other. The second starts only when the first finished
 * normally - its quota, its goal (even one already met before any attack), a storage too full
 * to farm into or its time limit - and never after a device-guard finish, a Stop, a failure or a
 * cancellation, which all arrive as exceptions.
 *
 * Each account farms for its own maxMinutes, cut to what is left of [runCombined]'s global
 * deadline. With under [Policy.MIN_SECOND_ACCOUNT_MINUTES] left the second account is not started
 * and ends on the time limit.
 */
class AccountOrchestrator(
    private val driver: AccountRunnerDriver,
    /** Milliseconds on any monotonic clock; only differences are used. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Told why the first account finished, just before the second starts. */
    private val onHandoff: (from: AccountRecord, to: AccountRecord, outcome: RunOutcome) -> Unit = { from, to, outcome ->
        AppLog.log("account_handoff", "from" to from.villageName, "to" to to.villageName,
            "completed" to outcome.completedAttacks, "reason" to outcome.reason.key)
    },
    /** Told the attacks already done across the run each time an account starts. */
    private val onAccountStart: (completedBefore: Int, account: AccountRecord) -> Unit = { _, _ -> }
) {

    suspend fun runCombined(
        account1: AccountRecord,
        account2: AccountRecord,
        quota1: Int,
        quota2: Int,
        globalDeadlineMinutes: Int = 120
    ): Pair<RunOutcome, RunOutcome> {
        Policy.validateLimits(quota1, globalDeadlineMinutes)
        Policy.validateLimits(quota2, globalDeadlineMinutes)
        val startedAt = clock()

        onAccountStart(0, account1)
        val first = farm(account1, quota1, minOf(account1.settings.maxMinutes, globalDeadlineMinutes))

        currentCoroutineContext().ensureActive()

        // A flat or hot phone is the same phone for the second account.
        if (first.reason == CompletionReason.DEVICE_GUARD) {
            AppLog.log("account_skipped", "account" to account2.villageName,
                "reason" to CompletionReason.DEVICE_GUARD.key, "after" to account1.villageName)
            return first to RunOutcome(0, CompletionReason.DEVICE_GUARD, first.detail)
        }

        val leftMinutes = ((globalDeadlineMinutes * 60_000L - (clock() - startedAt)) / 60_000L).toInt()
        // A switch and a home read for no attack is all a few minutes would buy.
        if (leftMinutes < Policy.MIN_SECOND_ACCOUNT_MINUTES) {
            AppLog.log("account_skipped", "account" to account2.villageName,
                "reason" to CompletionReason.TIME_LIMIT.key, "after" to account1.villageName,
                "leftMinutes" to leftMinutes, "minMinutes" to Policy.MIN_SECOND_ACCOUNT_MINUTES)
            return first to RunOutcome(0, CompletionReason.TIME_LIMIT)
        }

        onHandoff(account1, account2, first)
        onAccountStart(first.completedAttacks, account2)
        val second = farm(account2, quota2, minOf(account2.settings.maxMinutes, leftMinutes))
        return first to second
    }

    private suspend fun farm(account: AccountRecord, quota: Int, maxMinutes: Int): RunOutcome {
        val outcome = driver.farmAccount(account, quota, maxMinutes)
        if (outcome.completedAttacks !in 0..quota) {
            throw StopException(
                "${account.villageName} reported ${outcome.completedAttacks} attacks against a quota of $quota"
            )
        }
        return outcome
    }
}
