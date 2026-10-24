package local.clashfarmer.core

import local.clashfarmer.accounts.AccountIdentityParser

/**
 * When a maintenance break may be called over. One frame is not enough: OCR can drop a letter
 * from "maintenance" or catch the dialog redrawing its countdown, and ending the wait on that
 * frame handed a screen that was still down to recovery, which presses Back at what it cannot
 * read. So the dialog must be gone on two reads at least [CONFIRM_GAP_MS] apart, with no
 * maintenance read between, and then the screen must be one the runner recognises before the
 * wait ends and any input may follow. The splash and the loading clouds read as nothing and are
 * waited on. Any maintenance read starts the count over. [timedOut] bounds the whole wait.
 */
class MaintenanceWait(private val startedAt: Long) {
    enum class Read {
        MAINTENANCE,
        /** Splash, loading clouds, or a dialog nobody reads; nothing to tap yet. */
        UNRECOGNISED,
        /** A screen recovery knows its way from. */
        RECOGNISED
    }

    sealed interface Decision {
        data class Wait(val pollMs: Long) : Decision
        object Over : Decision
    }

    private var goneSince: Long? = null

    fun timedOut(now: Long) = now - startedAt >= PopupGuard.MAINTENANCE_MAX_MS

    fun next(read: Read, now: Long): Decision {
        if (read == Read.MAINTENANCE) {
            goneSince = null
            return Decision.Wait(PopupGuard.MAINTENANCE_POLL_MS)
        }
        val since = goneSince ?: now.also { goneSince = it }
        val confirmed = now - since >= CONFIRM_GAP_MS
        return if (confirmed && read == Read.RECOGNISED) Decision.Over else Decision.Wait(CONFIRM_GAP_MS)
    }

    companion object {
        const val CONFIRM_GAP_MS = 5_000L

        /**
         * One frame during the wait. [verdict] is [PopupGuard.inspect]'s on an UNKNOWN frame and
         * [PopupGuard.Verdict.None] otherwise. Anything the runner or recovery acts on ends the
         * wait once the break is confirmed gone: a known screen, a reload or takeover dialog, a
         * forced update (which stops the run with its own reason), a popup with an X or Okay or a
         * premium one, the event-intro bubbles and Welcome Back. Nothing readable and a popup the
         * guard only waits on are still loading.
         */
        fun classify(
            state: ScreenState,
            verdict: PopupGuard.Verdict,
            rawText: String,
            reloadDialog: Boolean = false
        ): Read = when {
            verdict == PopupGuard.Verdict.Maintenance -> Read.MAINTENANCE
            state != ScreenState.UNKNOWN || reloadDialog -> Read.RECOGNISED
            verdict is PopupGuard.Verdict.ServerDialog || verdict == PopupGuard.Verdict.UpdateRequired ||
                verdict is PopupGuard.Verdict.Dismiss || verdict is PopupGuard.Verdict.RefusedPremium ||
                verdict is PopupGuard.Verdict.AdvanceDialogue -> Read.RECOGNISED
            Policy.villageTakenElsewhere(rawText) || AccountIdentityParser.isWelcomeBackDialog(rawText) -> Read.RECOGNISED
            else -> Read.UNRECOGNISED
        }
    }
}
