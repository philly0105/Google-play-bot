package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox

/**
 * The way from wherever Clash stands to the home village, one frame at a time. A start walks it
 * before the account check, which can only read the account over the village; recovery walks it
 * after a stop the run can pick itself up from.
 *
 * Both follow [RecoveryPolicy.stepFor] for screens the reader knows and ask [PopupGuard] about a
 * frame it does not. Only the guard's own tap closes a popup, and maintenance is never touched.
 * A start differs from recovery in three ways: it never presses Back at a screen nothing reads
 * (the store and a priced offer aside, which both walks Back out of at once), it refuses a battle or scout it did not start, and it gives up after [START_BOUND_MS].
 */
object ReturnHome {
    const val START_BOUND_MS = 90_000L
    const val MAX_POPUP_TAPS = 3
    /** Backs a walk gives the store or a priced offer before treating it as any unread screen. */
    const val MAX_SHOP_BACKS = 3

    /** Two readings running, so a frame caught mid-fade does not refuse a start. */
    private const val BATTLE_READINGS_TO_REFUSE = 2

    const val BATTLE_REFUSED =
        "Clash is in a battle or scout the bot did not start. The run did not start and nothing was tapped"

    /** Recoverable: unattended farming waits the break out on its way back and starts again. */
    const val MAINTENANCE_AT_START =
        "Clash was in a maintenance break at the start; nothing was tapped"

    /** Matched by [RecoveryPolicy.isRecoverable]. */
    const val START_TIMEOUT_PREFIX = "Could not reach the village home screen before the account check"

    fun startTimedOut(boundMs: Long, state: ScreenState, text: String, popup: String?): String =
        "$START_TIMEOUT_PREFIX within ${boundMs / 1000}s (state: ${state.name}" +
            (popup?.let { ", popup: $it" } ?: "") + ", read: ${text.take(160)})"

    /**
     * Back goes to whatever app is in front and cannot be checked by the run's guard, so it is
     * sent only while the run may act: not stopped, not paused, Clash in front, phone unlocked.
     */
    fun mayPressBack(tokenValid: Boolean, paused: Boolean, clashForeground: Boolean, unlocked: Boolean) =
        tokenValid && !paused && clashForeground && unlocked

    const val QUIT_PROMPT = "quit_prompt"

    /** Clash's quit prompt on a frame; [cancel] is its Cancel's centre, null when Cancel was not read. */
    data class QuitPrompt(val cancel: Point?)

    /**
     * [Policy.showsQuitPrompt] with the one button the prompt is ever given: Cancel, found by
     * [WallPanel.findCancelButton] as the wall phase finds it. Okay, at (938, 462), quits Clash.
     * Null when the frame is not the prompt.
     */
    fun quitPrompt(rawText: String, boxes: List<RecognizedBox>): QuitPrompt? =
        if (Policy.showsQuitPrompt(rawText)) QuitPrompt(WallPanel.findCancelButton(boxes)?.center) else null

    sealed class Step(val name: String) {
        object Done : Step("DONE")
        object Wait : Step("WAIT")
        object ReturnHome : Step("RETURN_HOME")
        object Back : Step("BACK")
        /** A reload or idle dialog, left for the start's account check, which presses Reload once. */
        object LeaveToStart : Step("LEAVE_TO_START")
        /** Waits without any input until the break is over, then the walk carries on. */
        object WaitOutMaintenance : Step("WAIT_OUT_MAINTENANCE")
        data class ClosePopup(val popup: String, val tap: Point) : Step("CLOSE_POPUP")
        /** A character's speech bubble; tapping it brings the next one. */
        data class AdvanceDialogue(val text: String, val tap: Point) : Step("ADVANCE_DIALOGUE")
        /** Clash's quit prompt: a tap on Cancel's box, never Okay, or null to wait on it. */
        data class CancelQuitPrompt(val tap: Point?) : Step("CANCEL_QUIT_PROMPT")
        data class Stop(val reason: String) : Step("STOP")
    }

    /**
     * One reading. [popup] is the guard's verdict on it; [settingsCanHandle] is a frame the
     * account check deals with itself (Settings already open, Welcome Back, the Layout Editor).
     * [boxes] are its words, where Clash's quit prompt has its Cancel.
     */
    data class Frame(
        val state: ScreenState,
        val popup: PopupGuard.Verdict = PopupGuard.Verdict.None,
        val reloadDialog: Boolean = false,
        val settingsCanHandle: Boolean = false,
        val text: String = "",
        val boxes: List<RecognizedBox> = emptyList()
    )

    /** [ownBattle] is a restart after the bot's own attempt, whose battle may still be running. */
    fun forStart(startedAt: Long, ownBattle: Boolean) =
        Walk(starting = true, ownBattle = ownBattle, startedAt = startedAt, boundMs = START_BOUND_MS) { frame, popup ->
            startTimedOut(START_BOUND_MS, frame.state, frame.text, popup)
        }

    fun forRecovery(startedAt: Long, reason: String) =
        Walk(starting = false, ownBattle = true, startedAt = startedAt, boundMs = RecoveryPolicy.RECOVERY_TIMEOUT_MS) { _, _ ->
            "Could not get back to the village after: $reason"
        }

    class Walk internal constructor(
        private val starting: Boolean,
        private val ownBattle: Boolean,
        private var startedAt: Long,
        private val boundMs: Long,
        private val timedOut: (Frame, String?) -> String
    ) {
        private var unknownSince: Long? = null
        private var popupTaps = 0
        private var shopBacks = 0
        private var battleReadings = 0
        private var lastPopup: String? = null
        /** Bubbles tapped over the whole walk; a known screen never gives any back. */
        private var dialogueAdvances = 0
        private var advancesSinceKnown = 0
        private var quitCancels = 0

        /**
         * Bubbles tapped since the last known screen, set on the frame that reads as one again;
         * zero on any other frame. The caller logs `popup_dialogue_cleared` when it is set.
         */
        var clearedAfter = 0
            private set

        /** A maintenance break is the game's time, not the walk's. */
        fun restartClock(now: Long) {
            startedAt = now
            unknownSince = null
        }

        fun next(frame: Frame, now: Long): Step {
            val unknown = frame.state == ScreenState.UNKNOWN
            clearedAfter = 0
            if (!unknown && advancesSinceKnown > 0) {
                clearedAfter = advancesSinceKnown
                advancesSinceKnown = 0
            }
            // As in LocalRunner, the guard is only asked about a frame nothing else reads.
            val verdict = if (unknown) frame.popup else PopupGuard.Verdict.None
            if (frame.reloadDialog || verdict is PopupGuard.Verdict.ServerDialog) {
                return if (starting) Step.Stop(PopupGuard.SERVER_DIALOG) else Step.LeaveToStart
            }
            if (verdict == PopupGuard.Verdict.UpdateRequired) return Step.Stop(PopupGuard.UPDATE_REQUIRED)
            if (verdict == PopupGuard.Verdict.Maintenance) {
                return if (starting) Step.Stop(MAINTENANCE_AT_START) else Step.WaitOutMaintenance
            }
            // Clash's quit prompt reads as the village, Battle and Build legible behind it, and Back
            // on the bare village opens it (UO-21 review, 15 October). It is not the village: it
            // gets Cancel, MAX_POPUP_TAPS times at most, and is read again within the walk's bound.
            val quit = quitPrompt(frame.text, frame.boxes)
            if (quit != null) {
                unknownSince = null
                if (now - startedAt >= boundMs) return Step.Stop(timedOut(frame, QUIT_PROMPT))
                if (quit.cancel == null) return Step.CancelQuitPrompt(null)
                if (quitCancels >= MAX_POPUP_TAPS) return Step.Wait
                quitCancels++
                return Step.CancelQuitPrompt(quit.cancel)
            }
            if (frame.state == ScreenState.HOME || (starting && frame.settingsCanHandle)) return Step.Done

            val refusable = starting && !ownBattle &&
                (frame.state == ScreenState.BATTLE || frame.state == ScreenState.SCOUT)
            battleReadings = if (refusable) battleReadings + 1 else 0
            if (battleReadings >= BATTLE_READINGS_TO_REFUSE) return Step.Stop(BATTLE_REFUSED)

            when (verdict) {
                is PopupGuard.Verdict.Dismiss -> lastPopup = verdict.popup
                is PopupGuard.Verdict.Wait -> lastPopup = verdict.popup
                is PopupGuard.Verdict.RefusedPremium -> lastPopup = verdict.popup
                is PopupGuard.Verdict.AdvanceDialogue -> lastPopup = "character_dialogue"
                PopupGuard.Verdict.Shop -> lastPopup = PopupGuard.Verdict.Shop.POPUP
                else -> {}
            }
            // The store and a priced offer get Back at once, a start's included: recovery spent 25 s
            // waiting on the store on 15 October. Back is the only input either is ever given.
            if ((verdict == PopupGuard.Verdict.Shop || verdict is PopupGuard.Verdict.RefusedPremium) &&
                shopBacks < MAX_SHOP_BACKS) {
                shopBacks++
                unknownSince = null
                return Step.Back
            }
            if (now - startedAt >= boundMs) return Step.Stop(timedOut(frame, lastPopup))

            if (verdict is PopupGuard.Verdict.Dismiss && popupTaps < MAX_POPUP_TAPS) {
                popupTaps++
                return Step.ClosePopup(verdict.popup, verdict.tap)
            }
            // Spent, the dialogue is treated like any screen nothing reads.
            if (verdict is PopupGuard.Verdict.AdvanceDialogue &&
                dialogueAdvances < PopupGuard.MAX_DIALOGUE_ADVANCES) {
                dialogueAdvances++
                advancesSinceKnown++
                return Step.AdvanceDialogue(verdict.text, verdict.tap)
            }
            unknownSince = if (unknown) unknownSince ?: now else null
            // A start never presses Back at a screen nothing reads; recovery does after a wait.
            val unknownForMs = if (starting) 0L else now - (unknownSince ?: now)
            val step = when (RecoveryPolicy.stepFor(frame.state, unknownForMs, reloadDialog = false)) {
                RecoveryPolicy.Step.DONE -> Step.Done
                RecoveryPolicy.Step.WAIT -> Step.Wait
                RecoveryPolicy.Step.RETURN_HOME -> Step.ReturnHome
                RecoveryPolicy.Step.BACK -> Step.Back
                RecoveryPolicy.Step.LEAVE_TO_START -> Step.LeaveToStart
            }
            if (step == Step.Back) unknownSince = null
            return step
        }
    }
}
