package local.clashfarmer.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpponentSelectorTest {

    private val next = Point(1440, 495)
    private val rules = OpponentRules(
        enabled = true, minimums = mapOf(Resource.GOLD to 500_000L),
        maxSkips = 10, maxSearchMs = 90_000, maxSearchGold = 20_000
    )

    private fun scout(gold: Long?, seconds: Int? = 28, fee: Long? = 1_500, button: Point? = next) = ScoutFrame(
        state = ScreenState.SCOUT, capturedAtMs = 0,
        loot = if (gold == null) emptyMap() else mapOf(Resource.GOLD to gold),
        fee = fee, secondsRemaining = seconds, nextButton = button
    )

    private fun screen(state: ScreenState) = ScoutFrame(state = state, capturedAtMs = 0)

    /** Plays frames back in order; each read stamps its capture time and then spends [ocrMs]. */
    private inner class Phone(frames: List<ScoutFrame>, private val ocrMs: Long = 300) {
        private val queue = ArrayDeque(frames)
        var now = 10_000L
        val taps = mutableListOf<Point>()
        val events = mutableListOf<String>()
        val logged = mutableListOf<Pair<String, Map<String, Any?>>>()

        fun read(): ScoutFrame {
            val frame = (queue.removeFirstOrNull() ?: error("read past the script")).copy(capturedAtMs = now)
            now += ocrMs
            return frame
        }

        fun selector(r: OpponentRules = rules) = OpponentSelector(
            rules = r,
            readScout = { read() },
            tapNext = { taps += it },
            clock = { now },
            pause = { now += it },
            log = { event, fields -> events += event; logged += event to fields }
        )

        fun lootOf(event: String): List<Any?> = logged.filter { it.first == event }.map { it.second["loot"] }

        suspend fun first(): ScoutFrame = queue.removeFirst().copy(capturedAtMs = now).also { now += ocrMs }
    }

    @Test fun aPoorBaseIsSkippedAndARichOneAccepted() = runTest {
        val phone = Phone(listOf(scout(1L), screen(ScreenState.SEARCHING), scout(900_000L)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(1, result.skips)
        assertEquals(1_500L, result.spentGold)
        assertEquals(listOf(next), phone.taps)
        assertEquals(listOf("opponent_skipped", "opponent_accepted"), phone.events)
    }

    @Test fun aBaseStillDrawingInIsWaitedFor() = runTest {
        // 06:26 on 24 September: the first scout frame after the clouds had no loot panel and no
        // Next yet, and the base was attacked unread as "next_not_found".
        val drawing = ScoutFrame(state = ScreenState.SCOUT, capturedAtMs = 0)
        val phone = Phone(listOf(scout(1L), screen(ScreenState.UNKNOWN), drawing, drawing, scout(900_000L)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(1, phone.taps.size)
    }

    @Test fun aBaseThatNeverFinishesDrawingIsAttackedAsItIs() = runTest {
        val drawing = ScoutFrame(state = ScreenState.SCOUT, capturedAtMs = 0)
        val phone = Phone(listOf(scout(1L), screen(ScreenState.UNKNOWN)) + List(40) { drawing })
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals(1, phone.taps.size)
    }

    @Test fun aNextThatChangesNothingIsPressedAgainOnlyAfterAFreshReadAndWithinTheBudget() = runTest {
        val phone = Phone(listOf(scout(1L)) + List(40) { scout(1L) })
        val result = phone.selector(rules.copy(maxSkips = 3)).select(phone.now, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals("skip_limit", result.reason)
        // Counted when sent, whether or not it took.
        assertEquals(3, result.skips)
        assertEquals(List(3) { next }, phone.taps)
        // Every press after the first follows a reread of the base that stayed.
        assertEquals(
            listOf(
                "opponent_skipped", "opponent_next_unconfirmed", "opponent_next_reread",
                "opponent_skipped", "opponent_next_unconfirmed", "opponent_next_reread",
                "opponent_skipped", "opponent_next_unconfirmed", "opponent_next_reread",
                "opponent_fallback"
            ),
            phone.events
        )
    }

    @Test fun anUnconfirmedNextWithTheSameLootIsRereadAndSkippedOnTheFreshRead() = runTest {
        // Device timing: one scout read takes about 4.5 s. The base stays on screen with the same
        // loot, the fresh read still fails the rules, and Next is pressed again on that read.
        val phone = Phone(
            listOf(
                scout(1L), // screened and skipped
                scout(1L), scout(1L), // same base after the press: unconfirmed
                scout(1L), // the reread, still short of the rules
                screen(ScreenState.SEARCHING), scout(900_000L)
            ),
            ocrMs = 4_500
        )
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(2, result.skips)
        assertEquals(listOf(next, next), phone.taps)
        assertEquals(mapOf(Resource.GOLD to 900_000L), result.loot)
        assertEquals(
            listOf(
                "opponent_skipped", "opponent_next_unconfirmed", "opponent_next_reread",
                "opponent_skipped", "opponent_accepted"
            ),
            phone.events
        )
        assertEquals(listOf<Any?>("gold=1"), phone.lootOf("opponent_next_reread"))
        assertEquals(listOf<Any?>("gold=900000"), phone.lootOf("opponent_accepted"))
    }

    @Test fun anUnconfirmedNextAttacksOnTheFreshReadAndLogsItsLoot() = runTest {
        // 06:28 on 8 October: rows unreadable on the frames after the press, then the old base's
        // loot was logged against a base nobody screened. The fresh read decides now.
        val drawing = scout(null, fee = null)
        val phone = Phone(
            listOf(scout(1L), drawing, drawing, scout(700_000L)),
            ocrMs = 4_500
        )
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals("loot_met", result.reason)
        assertEquals(mapOf(Resource.GOLD to 700_000L), result.loot)
        assertEquals(listOf(next), phone.taps)
        assertEquals(listOf<Any?>("gold=700000"), phone.lootOf("opponent_next_reread"))
        assertEquals(listOf<Any?>("gold=700000"), phone.lootOf("opponent_accepted"))
    }

    @Test fun theGoldBudgetBoundsRepeatedPressesOnAnUnchangedBase() = runTest {
        val phone = Phone(listOf(scout(1L)) + List(40) { scout(1L) })
        val result = phone.selector(rules.copy(maxSkips = 10, maxSearchGold = 3_000)).select(phone.now, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals("search_gold_limit", result.reason)
        assertEquals(listOf(next, next), phone.taps)
        assertEquals(3_000L, result.spentGold)
    }

    @Test fun aRereadThatFindsTheBattleDeploysIntoIt() = runTest {
        val phone = Phone(
            listOf(scout(1L), scout(1L), scout(1L), screen(ScreenState.BATTLE)),
            ocrMs = 4_500
        )
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.BATTLE_STARTED, result.end)
        assertEquals(listOf(next), phone.taps)
        assertEquals(
            listOf("opponent_skipped", "opponent_next_unconfirmed", "opponent_next_reread", "opponent_battle_started"),
            phone.events
        )
    }

    @Test fun oneUnreadableFrameAtTheEndOfTheRereadIsReadAgainNotStoppedOn() = runTest {
        val unknown = screen(ScreenState.UNKNOWN)
        val phone = Phone(
            listOf(
                scout(1L),
                scout(1L), scout(1L), // unconfirmed
                unknown, // the reread, still in flux
                scout(1L), scout(1L), // the second wait, unconfirmed again
                unknown, // its tail frame
                scout(700_000L) // read once more
            ),
            ocrMs = 4_500
        )
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(mapOf(Resource.GOLD to 700_000L), result.loot)
        assertEquals(listOf(next), phone.taps)
        assertEquals(listOf<Any?>("gold=700000"), phone.lootOf("opponent_next_reread"))
    }

    @Test fun aFrameStillDrawingRightAfterThePressDoesNotEndTheWait() = runTest {
        // The first frame is captured half a second after the press, while the panel draws in,
        // and its read finishes past the 4 s mark. Only its capture time counts.
        val drawing = scout(null, fee = null)
        val phone = Phone(listOf(scout(1L), drawing, scout(800_000L)), ocrMs = 4_500)
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(mapOf(Resource.GOLD to 800_000L), result.loot)
        assertEquals(listOf("opponent_skipped", "opponent_accepted"), phone.events)
    }

    @Test fun aChangedBaseWithoutASeenTransitionStillCounts() = runTest {
        val phone = Phone(listOf(scout(1L), scout(2L), scout(900_000L)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(2, phone.taps.size)
    }

    @Test fun unknownThenSameScoutDoesNotConfirmNext() = runTest {
        // An unreadable frame is not a seen transition; the same base coming back means
        // the press never landed. With no skip left, the base read again is the one attacked.
        val phone = Phone(listOf(scout(1L), screen(ScreenState.UNKNOWN)) + List(40) { scout(1L) })
        val result = phone.selector(rules.copy(maxSkips = 1)).select(phone.now, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals("skip_limit", result.reason)
        assertEquals(listOf(next), phone.taps)
        assertEquals(1, result.skips)
        assertEquals(
            listOf("opponent_skipped", "opponent_next_unconfirmed", "opponent_next_reread", "opponent_fallback"),
            phone.events
        )
    }

    @Test fun unknownThenAChangedBaseConfirmsNext() = runTest {
        val phone = Phone(listOf(scout(1L), screen(ScreenState.UNKNOWN), scout(900_000L)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(listOf(next), phone.taps)
    }

    @Test fun aBattleAlreadyRunningIsDeployedIntoAtOnce() = runTest {
        val phone = Phone(listOf(screen(ScreenState.BATTLE)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.BATTLE_STARTED, result.end)
        assertEquals(emptyList<Point>(), phone.taps)
    }

    @Test fun aBattleStartingDuringTheSearchIsDeployedInto() = runTest {
        val phone = Phone(listOf(scout(1L), screen(ScreenState.SEARCHING), screen(ScreenState.BATTLE)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.BATTLE_STARTED, result.end)
    }

    @Test fun theSkipLimitIsReachedAtDispatch() = runTest {
        val phone = Phone(listOf(scout(1L), screen(ScreenState.SEARCHING), scout(2L)))
        val result = phone.selector(rules.copy(maxSkips = 1)).select(phone.now, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals(1, phone.taps.size)
    }

    @Test fun theCountdownIsAgedByTheTimeSpentReadingIt() = runTest {
        // Nine seconds on the clock when captured, but reading it took two.
        val phone = Phone(listOf(scout(1L, seconds = 9)), ocrMs = 2_000)
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals(emptyList<Point>(), phone.taps)
    }

    @Test fun matchmakingTimeCountsAgainstTheSearch() = runTest {
        val phone = Phone(listOf(scout(1L)))
        val result = phone.selector().select(phone.now - 90_000, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals(emptyList<Point>(), phone.taps)
    }

    @Test fun anUnreadNextButtonIsNeverPressed() = runTest {
        val phone = Phone(listOf(scout(1L, button = null)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.FALLBACK, result.end)
        assertEquals(emptyList<Point>(), phone.taps)
    }

    @Test fun unreadLootIsReadOnceMore() = runTest {
        val phone = Phone(listOf(scout(null), scout(900_000L)))
        val result = phone.selector().select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(emptyList<Point>(), phone.taps)
    }

    @Test fun aWrongScreenAfterNextStopsTheRun() = runTest {
        val phone = Phone(listOf(scout(1L), screen(ScreenState.HOME), screen(ScreenState.HOME)))
        assertThrows(StopException::class.java) {
            kotlinx.coroutines.runBlocking { phone.selector().select(phone.now, phone.first()) }
        }
        assertEquals(1, phone.taps.size)
    }

    private fun heldOnce(phone: Phone): OpponentSelector {
        var held = false
        return OpponentSelector(
            rules = rules,
            readScout = { phone.read() },
            tapNext = {
                if (!held) {
                    held = true
                    throw StopException(TapAfterHold.HELD)
                }
                phone.taps += it
            },
            clock = { phone.now },
            pause = { phone.now += it },
            log = { event, fields -> phone.events += event; phone.logged += event to fields }
        )
    }

    @Test fun aNextHeldAcrossAPauseReadsTheScoutAgainAndCountsNothing() = runTest {
        // Decision 1 of UO-21: no look before Next; a held Next reads the scout again.
        val phone = Phone(listOf(scout(1L), scout(1L), screen(ScreenState.SEARCHING), scout(900_000L)))
        val result = heldOnce(phone).select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        // The held press is neither a skip nor a fee; the press on the fresh read is.
        assertEquals(1, result.skips)
        assertEquals(1_500L, result.spentGold)
        assertEquals(listOf(next), phone.taps)
        assertEquals(
            listOf("opponent_skipped", "opponent_next_held", "opponent_skipped", "opponent_accepted"),
            phone.events
        )
        assertEquals(listOf<Any?>(0), phone.logged.filter { it.first == "opponent_next_held" }.map { it.second["skips"] })
    }

    @Test fun aFreshBaseAfterAHeldNextIsDecidedOnItsOwnLoot() = runTest {
        val phone = Phone(listOf(scout(1L), scout(900_000L)))
        val result = heldOnce(phone).select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(0, result.skips)
        assertEquals(0L, result.spentGold)
        assertEquals(emptyList<Point>(), phone.taps)
        assertEquals(mapOf(Resource.GOLD to 900_000L), result.loot)
    }

    @Test fun aBattleThatBeganUnderThePauseIsDeployedInto() = runTest {
        val phone = Phone(listOf(scout(1L), screen(ScreenState.BATTLE)))
        val result = heldOnce(phone).select(phone.now, phone.first())
        assertEquals(SelectionEnd.BATTLE_STARTED, result.end)
        assertEquals(0, result.skips)
        assertEquals(emptyList<Point>(), phone.taps)
    }

    @Test fun anyOtherStopFromNextStillStops() = runTest {
        val phone = Phone(listOf(scout(1L)))
        val selector = OpponentSelector(
            rules = rules, readScout = { error("no read after a real stop") },
            tapNext = { throw StopException("Tap at $it was cancelled by a touch on the screen") },
            clock = { phone.now }, pause = { phone.now += it }
        )
        val first = phone.first()
        assertThrows(StopException::class.java) {
            kotlinx.coroutines.runBlocking { selector.select(phone.now, first) }
        }
    }

    @Test fun disabledSelectionReadsNothing() = runTest {
        val phone = Phone(listOf(scout(1L)))
        val result = phone.selector(rules.copy(enabled = false)).select(phone.now, phone.first())
        assertEquals(SelectionEnd.ACCEPTED, result.end)
        assertEquals(emptyList<Point>(), phone.taps)
    }
}
