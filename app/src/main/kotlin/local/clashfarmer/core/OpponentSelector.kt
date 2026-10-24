package local.clashfarmer.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import local.clashfarmer.recognition.LootPanelReader

/** One reading of the scout screen, or of whatever stood in its place. */
data class ScoutFrame(
    val state: ScreenState,
    /** On the selector's clock, taken before the frame was read. */
    val capturedAtMs: Long,
    val loot: Map<Resource, Long> = emptyMap(),
    val fee: Long? = null,
    val secondsRemaining: Int? = null,
    /** Null unless the Next button's own word was read where the button is drawn. */
    val nextButton: Point? = null
)

enum class SelectionEnd { ACCEPTED, FALLBACK, BATTLE_STARTED }

data class Selection(
    val end: SelectionEnd,
    val reason: String,
    val skips: Int,
    val spentGold: Long,
    val loot: Map<Resource, Long>
)

/**
 * Presses Next on scout screens [chooseOpponent] turns down, until one is accepted or a limit
 * says deploy anyway. Every way out of here deploys; none of them leaves the army unused.
 *
 * The clock is monotonic and the search deadline counts from before matchmaking began. A skip
 * is counted the moment Next is sent, whether or not it took, and Next is never sent twice
 * without a read in between: a press nothing confirms is followed by a fresh read of whatever
 * base is there, and that read alone decides whether it is attacked or skipped.
 */
class OpponentSelector(
    private val rules: OpponentRules,
    private val readScout: suspend () -> ScoutFrame,
    private val tapNext: suspend (Point) -> Unit,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (event: String, fields: Map<String, Any?>) -> Unit = { _, _ -> }
) {

    companion object {
        /** No frame captured this long after Next proving a new base leaves the press unconfirmed. */
        const val NEXT_UNANSWERED_MS = 4_000L

        /** Clouds, search and the next base together; longer than this is a stuck search. */
        const val NEXT_TIMEOUT_MS = 45_000L

        /**
         * How long a scout showing neither loot nor Next is given to finish drawing in. At 06:26
         * on 24 September the first frame after the clouds had neither, and that base was
         * attacked unread.
         */
        const val DRAW_IN_MS = 3_000L

        const val POLL_MS = 500L
    }

    suspend fun select(searchStartedAtMs: Long, first: ScoutFrame): Selection {
        if (!rules.enabled) return Selection(SelectionEnd.ACCEPTED, "selection_disabled", 0, 0, first.loot)
        var skips = 0
        var spentGold = 0L
        var rereadUsed = false
        var frame = drawnIn(first)

        while (true) {
            currentCoroutineContext().ensureActive()
            if (frame.state == ScreenState.BATTLE) {
                return finish(SelectionEnd.BATTLE_STARTED, "battle_started", frame, skips, spentGold, searchStartedAtMs)
            }
            if (frame.state != ScreenState.SCOUT) {
                throw StopException("Opponent selection was handed ${frame.state}, not a scout screen")
            }

            val now = clock()
            val progress = SearchProgress(skips, now - searchStartedAtMs, spentGold)
            val fee = frame.fee.takeIf { frame.nextButton != null }
            // What the countdown said, less the time spent reading it.
            val seconds = frame.secondsRemaining?.let { it - ceilSeconds(now - frame.capturedAtMs) }
            when (chooseOpponent(rules, frame.loot, progress, fee, seconds, rereadUsed)) {
                ScoutDecision.ATTACK ->
                    return finish(SelectionEnd.ACCEPTED, "loot_met", frame, skips, spentGold, searchStartedAtMs)
                ScoutDecision.ATTACK_FALLBACK ->
                    return finish(
                        SelectionEnd.FALLBACK, fallbackReason(frame, progress, fee, seconds),
                        frame, skips, spentGold, searchStartedAtMs
                    )
                ScoutDecision.REREAD -> {
                    rereadUsed = true
                    log("opponent_reread", fields(frame, skips, spentGold, now - searchStartedAtMs))
                    frame = readScout()
                }
                ScoutDecision.SKIP -> {
                    val target = frame.nextButton!!
                    log("opponent_skipped", fields(frame, skips, spentGold, now - searchStartedAtMs) +
                        mapOf("fee" to fee, "seconds" to seconds))
                    try {
                        tapNext(target)
                    } catch (e: StopException) {
                        if (!TapAfterHold.isHeld(e)) throw e
                        // Held across a pause, Next was not sent: nothing is counted, and the
                        // screen the pause left decides afresh, a battle that began under it too.
                        log("opponent_next_held", fields(frame, skips, spentGold, clock() - searchStartedAtMs))
                        frame = drawnIn(readScout())
                        if (frame.state == ScreenState.UNKNOWN) {
                            pause(POLL_MS)
                            frame = drawnIn(readScout())
                        }
                        continue
                    }
                    skips++
                    spentGold += fee!!
                    frame = nextBase(frame) ?: rereadUnconfirmed(frame, skips, spentGold, searchStartedAtMs)
                    rereadUsed = false
                }
            }
        }
    }

    /**
     * Next went unconfirmed: whatever is on screen now is read afresh and decided on like any
     * other base, so the loot logged and attacked is that base's own. A screen still in flux gets
     * one more wait, and a single unreadable frame after it one more read; past that, the main
     * loop handles it as it handles any frame.
     */
    private suspend fun rereadUnconfirmed(before: ScoutFrame, skips: Int, spentGold: Long, startedAt: Long): ScoutFrame {
        log("opponent_next_unconfirmed", fields(before, skips, spentGold, clock() - startedAt))
        var fresh = drawnIn(readScout())
        if (fresh.state != ScreenState.SCOUT && fresh.state != ScreenState.BATTLE) {
            fresh = nextBase(before) ?: drawnIn(readScout())
            if (fresh.state == ScreenState.UNKNOWN) {
                pause(POLL_MS)
                fresh = drawnIn(readScout())
            }
        }
        log("opponent_next_reread", fields(fresh, skips, spentGold, clock() - startedAt))
        return fresh
    }

    /**
     * Waits for what Next brought: a new base, or a battle. Null when no scout frame captured in
     * the first [NEXT_UNANSWERED_MS] after the press proved a new base, which is unconfirmed, not
     * proof of the old base: the panel may still be drawing in. Any other screen, read twice
     * running, stops the run.
     */
    private suspend fun nextBase(before: ScoutFrame): ScoutFrame? {
        val pressedAt = clock()
        var transitionSeen = false
        var stray: ScreenState? = null
        while (clock() - pressedAt < NEXT_TIMEOUT_MS) {
            currentCoroutineContext().ensureActive()
            pause(POLL_MS)
            val frame = readScout()
            when (frame.state) {
                ScreenState.BATTLE -> return frame
                ScreenState.SEARCHING -> {
                    transitionSeen = true
                    stray = null
                }
                // An unreadable frame is not a transition: the same base may be under it.
                ScreenState.UNKNOWN -> stray = null
                ScreenState.SCOUT -> {
                    if (transitionSeen || LootPanelReader.differentBase(before.loot, frame.loot)) return drawnIn(frame)
                    // Judged by when the frame was taken: one read takes 4 to 5 s on the phone, so
                    // the clock after it was past the window on the first, half-drawn frame.
                    if (frame.capturedAtMs - pressedAt >= NEXT_UNANSWERED_MS) return null
                    stray = null
                }
                else -> {
                    if (frame.state == stray) {
                        throw StopException("Pressing Next led to ${frame.state} instead of another base")
                    }
                    stray = frame.state
                }
            }
        }
        throw StopException("No new base arrived within ${NEXT_TIMEOUT_MS / 1000}s of pressing Next")
    }

    /** [frame], or a later one if it is a scout whose panel has not drawn in yet. */
    private suspend fun drawnIn(frame: ScoutFrame): ScoutFrame {
        var current = frame
        val since = clock()
        while (current.state == ScreenState.SCOUT && current.nextButton == null && current.loot.isEmpty() &&
            clock() - since < DRAW_IN_MS
        ) {
            currentCoroutineContext().ensureActive()
            pause(POLL_MS)
            current = readScout()
        }
        return current
    }

    private fun fallbackReason(frame: ScoutFrame, progress: SearchProgress, fee: Long?, seconds: Int?): String = when {
        progress.skips >= rules.maxSkips -> "skip_limit"
        progress.elapsedMs >= rules.maxSearchMs -> "search_time_limit"
        frame.nextButton == null -> "next_not_found"
        fee == null -> "fee_unreadable"
        progress.spentGold > rules.maxSearchGold - fee -> "search_gold_limit"
        seconds == null -> "countdown_unreadable"
        seconds < MIN_SECONDS_FOR_NEXT -> "countdown_too_short"
        else -> "loot_unreadable"
    }

    private fun finish(
        end: SelectionEnd, reason: String, frame: ScoutFrame, skips: Int, spentGold: Long, startedAt: Long
    ): Selection {
        val event = when (end) {
            SelectionEnd.ACCEPTED -> "opponent_accepted"
            SelectionEnd.FALLBACK -> "opponent_fallback"
            SelectionEnd.BATTLE_STARTED -> "opponent_battle_started"
        }
        log(event, fields(frame, skips, spentGold, clock() - startedAt) + mapOf("reason" to reason))
        return Selection(end, reason, skips, spentGold, frame.loot)
    }

    private fun fields(frame: ScoutFrame, skips: Int, spentGold: Long, elapsedMs: Long): Map<String, Any?> = mapOf(
        "loot" to frame.loot.entries.joinToString(",") { "${it.key.key}=${it.value}" },
        "skips" to skips,
        "maxSkips" to rules.maxSkips,
        "spentGold" to spentGold,
        "maxSearchGold" to rules.maxSearchGold,
        "elapsedMs" to elapsedMs,
        "maxSearchMs" to rules.maxSearchMs
    )

    private fun ceilSeconds(ms: Long): Int = ((ms.coerceAtLeast(0L) + 999L) / 1000L).toInt()
}
