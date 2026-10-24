package local.clashfarmer.core

/**
 * What a tap does when the frame it was decided on may no longer be what is on screen: the gate
 * before it (FarmerService.awaitClearToAct) waited out a pause, a pause came and went after the
 * capture it was built on (RunGuard.pauses), or the frame is older than [MAX_DECISION_AGE_MS].
 * Nothing is sent blind. A tap whose caller can look again looks, at most [MAX_LOOKS] times; one
 * that cannot is held with [HELD], and the nearest read loop reads again and decides afresh. The
 * press that accepts a wall purchase is refused after a pause rather than sent late. A run stopped
 * meanwhile goes on to RunGuard.checkCanAct, which throws with the stop's own reason.
 *
 * On 15 October at 05:31 UTC an Attack tap sent 19 s after the frame it was decided on landed on
 * the gem store, about 10 px under the $0.99 card (audit-evidence/shop/notes.md). [aged] is what
 * holds such a tap now.
 */
object TapAfterHold {
    enum class Next { SEND, LOOK, REFUSE }

    /** The reason AndroidWallDevice.tapConfirmation gives for a pause already up. */
    const val REFUSED = "Paused as the wall confirmation was about to be sent"

    /** A tap whose frame was stale or crossed by a pause, with no look to clear it. */
    const val HELD = "Held a tap: its frame was stale or a pause came between, and no look cleared it"

    /**
     * The oldest decision frame a tap is sent on without a look. The capture and the OCR of one
     * frame take about a second on the phone; see the UO-21 notes for the measured spans.
     */
    const val MAX_DECISION_AGE_MS = 2_000L

    /** How many looks one tap may take before it is held. */
    const val MAX_LOOKS = 2

    fun isHeld(e: Throwable): Boolean = e is StopException && e.message == HELD

    /**
     * Whether a tap decided on a frame taken at [decidedOn] is too old to send at [now]. Both are
     * on the capture's wall clock, so a clock that stepped back reads as aged too. A tap with no
     * decision frame is never aged.
     */
    fun aged(decidedOn: Long?, now: Long, max: Long = MAX_DECISION_AGE_MS): Boolean =
        decidedOn != null && (now - decidedOn) !in 0..max

    /**
     * [waited] is whether a pause came between the frame and now, [aged] whether the frame is
     * too old, [once] a press that must never be sent after a pause, [canLook] whether the caller
     * passed a look, and [looks] how many it has taken for this tap.
     */
    fun next(waited: Boolean, aged: Boolean, stopped: Boolean, once: Boolean, canLook: Boolean, looks: Int): Next = when {
        stopped -> Next.SEND
        !waited && !aged -> Next.SEND
        once && waited -> Next.REFUSE
        canLook && looks < MAX_LOOKS -> Next.LOOK
        else -> Next.REFUSE
    }

    enum class Battle { GO, LEFT }

    /**
     * Whether a battle is still up on a frame read between drops. An UNKNOWN frame mid-battle is
     * a spell's flash or a banner, but after a pause it may be anything a person opened.
     */
    fun battle(state: ScreenState, afterPause: Boolean): Battle = when (state) {
        ScreenState.BATTLE, ScreenState.SCOUT -> Battle.GO
        ScreenState.UNKNOWN -> if (afterPause) Battle.LEFT else Battle.GO
        else -> Battle.LEFT
    }
}

/**
 * Runs [send] and says whether it went out: false when it was held ([TapAfterHold.HELD]), which
 * the caller answers by reading the screen again. Any other stop still ends the run.
 */
inline fun sentOrHeld(send: () -> Unit): Boolean = try {
    send()
    true
} catch (e: StopException) {
    if (!TapAfterHold.isHeld(e)) throw e
    false
}

/**
 * The battle is no longer on screen. A stop in its own right, and recoverable if anything lets it
 * escape, but LocalRunner.executeDeployment catches it in battle and goes on to the result.
 */
class BattleLeft(val state: ScreenState) :
    StopException("Unexpected transition screen: $state after the battle left")
