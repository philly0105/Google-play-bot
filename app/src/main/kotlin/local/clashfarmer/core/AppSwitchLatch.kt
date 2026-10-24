package local.clashfarmer.core

/**
 * What a run does when the window in front changes. Leaving Clash ends a run, or for an
 * unattended run reopens the game, because a tap meant for the game would land on whatever
 * replaced it.
 *
 * The notification shade is the one exception on an unlocked phone. Pulling it down over Clash
 * ended Blood's run on 23 Sep at 05:05:44 and made the notification's own Pause and Stop
 * unreachable, so the shade now holds the run paused and closing it lets the run continue.
 * The lock screen is the same package and still ends the run. A pause only takes effect at the
 * runner's pause points, and the shade's window change can arrive after the shade is down, so
 * every capture and gesture asks [gate] first rather than meeting the shade in [RunGuard]'s check.
 */
object AppSwitchLatch {
    /** The lock screen and the notification shade. */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** How long the shade may hold a run. The guard gives paused time back to the deadline. */
    const val HOLD_CAP_MS = 10 * 60_000L

    /** When a hold that has not let go is logged again, with what the foreground read said. */
    const val STILL_HELD_AFTER_MS = 30_000L

    /** The stop at [HOLD_CAP_MS]. Not a failure of the run's, so it saves no failure capture. */
    val HOLD_TIMEOUT_REASON = "The notification shade stayed open for ${HOLD_CAP_MS / 60_000} minutes"

    fun isHoldTimeout(reason: String?): Boolean = reason?.contains(HOLD_TIMEOUT_REASON) == true

    enum class Action {
        /** Nothing to do. */
        IGNORE,
        /** Pause the run until Clash is back in front. */
        HOLD,
        /** Clash is back; lift the hold. */
        RELEASE,
        /** Stop the guard so the restart loop reopens Clash. */
        RECOVER,
        /** End the run. */
        STOP
    }

    /** What a capture or gesture does before the guard's check. */
    enum class Gate {
        /** Go on to [RunGuard.checkCanAct], which decides as it always has. */
        CHECK,
        /** Hold the run for the shade, then wait. */
        HOLD,
        /** Wait while the run is paused, then ask again. */
        WAIT
    }

    /** True when the shade, not the lock screen, is what covers Clash. */
    fun shadeOver(active: String?, unlocked: Boolean): Boolean = active == SYSTEM_UI_PACKAGE && unlocked

    /**
     * [held] is whether the current pause is the shade's own, not one a person pressed;
     * [own] is the helper's package, which may be in front without ending anything.
     */
    fun decide(
        active: String?,
        own: String,
        unlocked: Boolean,
        running: Boolean,
        paused: Boolean,
        held: Boolean,
        restartable: Boolean,
        waitingForVillage: Boolean
    ): Action = when {
        !running -> Action.IGNORE
        active == RunGuard.CLASH_PACKAGE -> if (held && paused) Action.RELEASE else Action.IGNORE
        // HOME is pressed on purpose while a taken village is waited out, and the takeover notice
        // invites a pull of the shade to read it; only the lock screen ends that wait.
        waitingForVillage && unlocked -> Action.IGNORE
        shadeOver(active, unlocked) && !waitingForVillage -> if (paused) Action.IGNORE else Action.HOLD
        active == null || active == own -> Action.IGNORE
        restartable && unlocked && active != SYSTEM_UI_PACKAGE -> Action.RECOVER
        else -> Action.STOP
    }

    /**
     * [decide] asked by a capture or gesture, with the same values. Under the shade it holds the
     * run before its window change arrives. A paused run with the shade, the helper or Clash in
     * front waits; the wait lets go of a hold once Clash is back. A person's Pause with Clash in
     * front waits too, so Pause stops every tap, swipe, pinch and Back where it stands rather than
     * at the runner's next pause point. The lock screen and other apps are left to the guard's
     * check, which stops or recovers as before, and so are a locked phone whose read found the
     * helper or nothing, the backgrounded takeover wait, and a run that has ended.
     */
    fun gate(
        active: String?,
        own: String,
        unlocked: Boolean,
        running: Boolean,
        paused: Boolean,
        held: Boolean,
        restartable: Boolean,
        waitingForVillage: Boolean
    ): Gate = when (decide(active, own, unlocked, running, paused, held, restartable, waitingForVillage)) {
        Action.HOLD -> Gate.HOLD
        Action.RELEASE -> Gate.WAIT
        Action.IGNORE -> if (running && paused && unlocked && (held || !waitingForVillage)) Gate.WAIT else Gate.CHECK
        Action.RECOVER, Action.STOP -> Gate.CHECK
    }

    /**
     * The clock on one of the shade's holds, asked about once a second. [next]'s [held] is whether
     * the pause is still the shade's: a Pause pressed during the hold makes it the person's, and a
     * person's pause has no cap.
     */
    class HoldTimer(private val startedAtMs: Long) {
        enum class Tick { WAIT, STILL_HELD, TIMEOUT, OVER }

        private var stillReported = false
        private var over = false

        fun next(nowMs: Long, held: Boolean): Tick {
            if (over || !held) {
                over = true
                return Tick.OVER
            }
            val heldMs = nowMs - startedAtMs
            return when {
                heldMs >= HOLD_CAP_MS -> {
                    over = true
                    Tick.TIMEOUT
                }
                !stillReported && heldMs >= STILL_HELD_AFTER_MS -> {
                    stillReported = true
                    Tick.STILL_HELD
                }
                else -> Tick.WAIT
            }
        }
    }
}
