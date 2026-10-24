package local.clashfarmer.core

import local.clashfarmer.recognition.ScreenReader
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/**
 * The readings are the settled digits storage_read logged on 8 October (baseline-log.jsonl); the
 * log keeps no raw OCR text for a balance that read, so the digits are what there is.
 */
class HomeBalanceGuardTest {
    private val capacities = mapOf(Resource.GOLD to 29_000_000L, Resource.ELIXIR to 29_000_000L)
    private val elixir = 21_263_843L

    /** Feeds frames one at a time, as the runner reads them, until the guard settles. */
    private fun settle(guard: HomeBalanceGuard, vararg gold: Long?): Pair<Int, BalanceSettlement.Settled> =
        settleAs(guard, Resource.GOLD, *gold)

    private fun settleAs(guard: HomeBalanceGuard, resource: Resource, vararg readings: Long?): Pair<Int, BalanceSettlement.Settled> {
        val frames = mutableListOf<ResourceSample>()
        for ((i, g) in readings.withIndex()) {
            val values = buildMap {
                if (g != null) put(resource, g)
                put(Resource.ELIXIR, elixir)
            }
            frames += ResourceSample(1_000L + i * 600L, values)
            if (frames.size < 2) continue
            val result = guard.settle(frames)
            if (result is BalanceSettlement.Settled) return frames.size to result
        }
        throw AssertionError("guard still wanted a frame after ${frames.size}")
    }

    private val masks = listOf("215", "230", "240", "w150g2", "w150g3", "w180g2", "w180g3", "w210g2", "w210g3")

    /** One frame's cuts as balance_implausible logs them: every mask read [text], or [odd] for some. */
    private fun cuts(text: String, odd: Map<String, String> = emptyMap()): List<String> =
        masks.map { "$it:${odd[it] ?: text}" }

    /**
     * Feeds frames one at a time, as the runner reads them, each a value and its cuts, with the
     * resources [ScreenReader.everyMaskRead] finds every mask read alike.
     */
    private fun settleCuts(
        guard: HomeBalanceGuard,
        vararg frames: Map<Resource, Pair<Long, List<String>>>
    ): Pair<Int, BalanceSettlement.Settled> {
        val samples = mutableListOf<ResourceSample>()
        val everyMask = mutableListOf<Set<Resource>>()
        for ((i, frame) in frames.withIndex()) {
            samples += ResourceSample(1_000L + i * 600L, frame.mapValues { it.value.first })
            everyMask += frame.filterValues { (v, raw) -> ScreenReader.everyMaskRead(raw, v) }.keys
            if (samples.size < 2) continue
            val result = guard.settle(samples, everyMask)
            if (result is BalanceSettlement.Settled) return samples.size to result
        }
        throw AssertionError("guard still wanted a frame after ${samples.size}")
    }

    private fun gold(settled: BalanceSettlement.Settled) =
        agreedHomeBalances(settled.samples, capacities)[Resource.GOLD]

    private fun dark(settled: BalanceSettlement.Settled) =
        agreedHomeBalances(settled.samples, capacities)[Resource.DARK_ELIXIR]

    private fun trusting(value: Long): HomeBalanceGuard =
        HomeBalanceGuard(capacities).also { settle(it, value, value) }

    @Test fun blood0816DroppedOneIsReReadAndNotTrusted() {
        // 08:13 23 429 258, one attack, then 2 403 171 in both frames and again in a third.
        val guard = trusting(23_429_258L)
        guard.attackCompleted()
        val (frames, settled) = settle(guard, 2_403_171L, 2_403_171L, 2_403_171L)
        assertEquals(3, frames)
        assertNull(gold(settled))
        assertEquals(elixir, agreedHomeBalances(settled.samples, capacities)[Resource.ELIXIR])
        val flag = settled.flagged.single()
        assertEquals(Resource.GOLD, flag.resource)
        assertEquals(listOf<Long?>(2_403_171L, 2_403_171L, 2_403_171L), flag.readings)
        assertEquals(23_429_258L, flag.trusted)
        assertNull(flag.accepted)
    }

    @Test fun storageFullAndWallTriggerDoNotFireOnTheUnreadableValue() {
        val guard = trusting(23_429_258L)
        guard.attackCompleted()
        val (_, settled) = settle(guard, 2_403_171L, 2_403_171L, 2_403_171L)
        val agreed = agreedHomeBalances(settled.samples, capacities)
        assertFalse(storageFull(Resource.GOLD, agreed, capacities))
        assertFalse(Resource.GOLD in agreed)
    }

    @Test fun aLaterFrameWithTheTrueValueWins() {
        val guard = trusting(23_429_258L)
        guard.attackCompleted()
        val (frames, settled) = settle(guard, 2_403_171L, 2_403_171L, 24_031_171L, 24_031_171L)
        assertEquals(4, frames)
        assertEquals(24_031_171L, gold(settled))
        assertEquals(24_031_171L, settled.flagged.single().accepted)
    }

    @Test fun tenfoldDisagreementPrefersNeitherUntilTwoFramesAgree() {
        // Session start, nothing trusted yet: 2 403 171 against 24 636 572.
        val guard = HomeBalanceGuard(capacities)
        val (frames, settled) = settle(guard, 2_403_171L, 24_636_572L, 24_636_572L)
        assertEquals(3, frames)
        assertEquals(24_636_572L, gold(settled))
    }

    @Test fun tenfoldDisagreementWithNoTwoFramesAgreeingIsUnreadable() {
        val guard = HomeBalanceGuard(capacities)
        val (frames, settled) = settle(guard, 2_403_171L, 24_636_572L, null, 24_031_171L)
        assertEquals(4, frames)
        assertNull(gold(settled))
        assertNull(settled.flagged.single().accepted)
    }

    @Test fun anAgreedMisreadIsNotTakenOverItsTenfoldAgainstTheStoredBalance() {
        // With the last stored balance to go on, two frames of the misread do not settle it.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.GOLD to 23_429_258L))
        val (frames, settled) = settle(guard, 2_403_171L, 24_636_572L, 2_403_171L, 24_636_572L)
        assertEquals(4, frames)
        assertEquals(24_636_572L, gold(settled))
    }

    @Test fun aPlainCorrectReadingIsTakenFromTwoFrames() {
        // 09:03 24 636 572, one attack, 09:05 25 365 846.
        val guard = trusting(24_636_572L)
        guard.attackCompleted()
        val (frames, settled) = settle(guard, 25_365_846L, 25_365_846L)
        assertEquals(2, frames)
        assertEquals(25_365_846L, gold(settled))
        assertTrue(settled.flagged.isEmpty())
    }

    @Test fun capacityReadsAreTrustedAndFillTheStorage() {
        // 09:12 28 863 478, one attack, 09:16 29 000 000, then 29 000 000 again.
        val guard = trusting(28_863_478L)
        guard.attackCompleted()
        val (_, first) = settle(guard, 29_000_000L, 29_000_000L)
        assertEquals(29_000_000L, gold(first))
        assertTrue(storageFull(Resource.GOLD, agreedHomeBalances(first.samples, capacities), capacities))
        guard.attackCompleted()
        val (frames, second) = settle(guard, 29_000_000L, 29_000_000L)
        assertEquals(2, frames)
        assertEquals(29_000_000L, gold(second))
    }

    @Test fun sweatElixirDroppedOneIsNotTrusted() {
        val guard = HomeBalanceGuard(capacities)
        val frames = { e: Long -> List(2) { ResourceSample(1_000L + it * 600L, mapOf(Resource.ELIXIR to e)) } }
        assertTrue(guard.settle(frames(18_255_734L)) is BalanceSettlement.Settled)
        guard.attackCompleted()
        val three = frames(1_912_354L) + ResourceSample(2_200L, mapOf(Resource.ELIXIR to 1_912_354L))
        assertEquals(BalanceSettlement.NeedFrame, guard.settle(three.take(2)))
        val settled = guard.settle(three) as BalanceSettlement.Settled
        assertTrue(agreedHomeBalances(settled.samples, capacities).isEmpty())
    }

    @Test fun aSessionStartMisreadAgainstTheLastStoredBalanceIsNotTrusted() {
        // 08:59: a new session, the last stored gold 23 429 258, and 2 403 171 read.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.GOLD to 23_429_258L))
        val (_, start) = settle(guard, 2_403_171L, 2_403_171L, 2_403_171L)
        assertNull(gold(start))
        guard.attackCompleted()
        val (frames, next) = settle(guard, 24_636_572L, 24_636_572L)
        assertEquals(2, frames)
        assertEquals(24_636_572L, gold(next))
    }

    @Test fun aMisreadBaselineDoesNotLockTheTrueValueOut() {
        // No prior: 2 403 171 became the baseline at 08:59. 09:03's true 24 636 572 is held back
        // once, and 09:05's 25 365 846, consistent with it, is taken.
        val guard = trusting(2_403_171L)
        guard.attackCompleted()
        val (_, held) = settle(guard, 24_636_572L, 24_636_572L, 24_636_572L)
        assertNull(gold(held))
        guard.attackCompleted()
        val (_, taken) = settle(guard, 25_365_846L, 25_365_846L)
        assertEquals(25_365_846L, gold(taken))
        guard.attackCompleted()
        val (_, after) = settle(guard, 26_191_310L, 26_191_310L)
        assertEquals(26_191_310L, gold(after))
    }

    @Test fun aWallPhaseMayLowerTheBalance() {
        val guard = trusting(29_000_000L)
        guard.spendingPossible()
        val (frames, settled) = settle(guard, 21_000_000L, 21_000_000L)
        assertEquals(2, frames)
        assertEquals(21_000_000L, gold(settled))
    }

    @Test fun aHeldMisreadKeepsTheStoredBalanceForTheNextRun() {
        // 08:13 and 08:16 in one run, stored as the runner stores them; 08:59 in a new run.
        val store = StorageStore(Files.createTempDirectory("storage").toFile())
        val id = "0130d53a-41a1-38cf-9069-f88b32445475"
        fun recordAgreed(settled: BalanceSettlement.Settled, atMs: Long) =
            store.recordKeepingUnread(id, StorageReading(atMs, agreedHomeBalances(settled.samples, capacities)))

        val first = HomeBalanceGuard(capacities, homeBalancePrior(false, id, store))
        recordAgreed(settle(first, 23_429_258L, 23_429_258L).second, 1L)
        first.attackCompleted()
        val (_, held) = settle(first, 2_403_171L, 2_403_171L, 2_403_171L)
        assertNull(gold(held))
        recordAgreed(held, 2L)
        assertEquals(23_429_258L, store.latest(id)!!.values[Resource.GOLD])
        assertEquals(elixir, store.latest(id)!!.values[Resource.ELIXIR])

        val next = HomeBalanceGuard(capacities, homeBalancePrior(false, id, store))
        val (_, start) = settle(next, 2_403_171L, 2_403_171L, 2_403_171L)
        assertNull(gold(start))
    }

    @Test fun aPurchaseStoredAfterThePhaseIsTheRestartsPrior() {
        // Blood, 14 October: 26 232 522 elixir at 16:17, 24M of walls at 16:18, the village taken
        // at 16:21, and 5 119 558 read three times when the restarted runner had it back at 16:32.
        val store = StorageStore(Files.createTempDirectory("storage").toFile())
        val id = "0130d53a-41a1-38cf-9069-f88b32445475"
        fun frames(vararg e: Long) =
            e.mapIndexed { i, v -> ResourceSample(1_000L + i * 600L, mapOf(Resource.GOLD to 21_826_570L, Resource.ELIXIR to v)) }
        fun settleAll(guard: HomeBalanceGuard, frames: List<ResourceSample>): BalanceSettlement.Settled {
            for (n in 2..frames.size) (guard.settle(frames.take(n)) as? BalanceSettlement.Settled)?.let { return it }
            throw AssertionError("guard still wanted a frame after ${frames.size}")
        }
        fun record(settled: BalanceSettlement.Settled, atMs: Long) =
            store.recordKeepingUnread(id, StorageReading(atMs, agreedHomeBalances(settled.samples, capacities)))
        fun elixirOf(settled: BalanceSettlement.Settled) = agreedHomeBalances(settled.samples, capacities)[Resource.ELIXIR]

        val first = HomeBalanceGuard(capacities, homeBalancePrior(false, id, store))
        record(settleAll(first, frames(26_232_522L, 26_232_522L)), 1L)
        first.spendingPossible()
        val afterPhase = settleAll(first, frames(2_232_522L, 2_232_522L))
        assertEquals(2_232_522L, elixirOf(afterPhase))

        // Only the balance from before the phase stored: the restart holds the true value back.
        val stale = HomeBalanceGuard(capacities, homeBalancePrior(false, id, store))
        assertNull(elixirOf(settleAll(stale, frames(5_119_558L, 5_119_558L, 5_119_558L))))

        record(afterPhase, 2L)
        val restarted = HomeBalanceGuard(capacities, homeBalancePrior(false, id, store))
        val settled = settleAll(restarted, frames(5_119_558L, 5_119_558L, 5_119_558L))
        assertEquals(5_119_558L, elixirOf(settled))
        assertTrue(settled.flagged.isEmpty())
    }

    @Test fun aWallOnlyRunIsNotHeldAgainstTheStoredBalance() {
        // Stored at 4M, farmed to 22M by hand, then walls asked for: no attack will ever come.
        val store = StorageStore(Files.createTempDirectory("storage").toFile())
        store.recordKeepingUnread("a", StorageReading(1L, mapOf(Resource.GOLD to 4_000_000L)))
        val guard = HomeBalanceGuard(capacities, homeBalancePrior(true, "a", store))
        val (frames, settled) = settle(guard, 22_000_000L, 22_000_000L)
        assertEquals(2, frames)
        assertEquals(22_000_000L, gold(settled))
        assertEquals(mapOf(Resource.GOLD to 4_000_000L), homeBalancePrior(false, "a", store))
    }

    @Test fun aHeldMisreadDoesNotVouchForALowerOne() {
        // Sweat gold, 5 October: trusted 18 418 438, then misreads 100 089 and 5 010, then the
        // true 21 071 946.
        val guard = trusting(18_418_438L)
        guard.attackCompleted()
        assertNull(gold(settle(guard, 100_089L, 100_089L, 100_089L).second))
        guard.attackCompleted()
        assertNull(gold(settle(guard, 5_010L, 5_010L, 5_010L).second))
        guard.attackCompleted()
        val (frames, settled) = settle(guard, 21_071_946L, 21_071_946L)
        assertEquals(2, frames)
        assertEquals(21_071_946L, gold(settled))
    }

    @Test fun smallDisagreementStaysUnreadableWithoutExtraFrames() {
        val guard = trusting(23_429_258L)
        val (frames, settled) = settle(guard, 23_429_258L, 23_429_358L)
        assertEquals(2, frames)
        assertNull(gold(settled))
        assertTrue(settled.flagged.isEmpty())
    }

    @Test fun blood0141DarkElixirMisreadIsNotTrustedAndTheTruthIsTakenNextTime() {
        // Blood, 15 October, with capacities for gold and elixir only: 291 790 at 01:39, an attack,
        // 2 984 008 at 01:41, an attack, and 309 456 on every mask of three frames at 01:43.
        val guard = HomeBalanceGuard(capacities).also { settleAs(it, Resource.DARK_ELIXIR, 291_790L, 291_790L) }
        guard.attackCompleted()
        val (_, misread) = settleAs(guard, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L, 2_984_008L)
        assertNull(dark(misread))
        assertEquals(elixir, agreedHomeBalances(misread.samples, capacities)[Resource.ELIXIR])
        val flag = misread.flagged.single()
        assertEquals(291_790L, flag.trusted)
        assertNull(flag.accepted)
        guard.attackCompleted()
        val (frames, truth) = settleAs(guard, Resource.DARK_ELIXIR, 309_456L, 309_456L)
        assertEquals(2, frames)
        assertEquals(309_456L, dark(truth))
        assertTrue(truth.flagged.isEmpty())
    }

    @Test fun aJumpTakenOnACandidateIsReplacedByThreeFramesEveryMaskRead() {
        // Had 2 984 008 come back after the next attack, its candidate would have taken it. The
        // true 309 456 on three frames, read alike by all nine masks as on 15 October, agrees with
        // the balance before that jump, and replaces it.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 282_168L))
        settleAs(guard, Resource.DARK_ELIXIR, 291_790L, 291_790L)
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L, 2_984_008L).second))
        guard.attackCompleted()
        assertEquals(2_984_008L, dark(settleAs(guard, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L).second))
        guard.attackCompleted()
        val truth = mapOf(Resource.DARK_ELIXIR to (309_456L to cuts("309 456")))
        val (frames, settled) = settleCuts(guard, truth, truth, truth)
        assertEquals(3, frames)
        assertEquals(309_456L, dark(settled))
        val flag = settled.flagged.single()
        assertEquals(2_984_008L, flag.trusted)
        assertEquals(309_456L, flag.accepted)
        // Back from the jump, the misread is a jump again, not a return.
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L, 2_984_008L).second))
        // Two frames are not a run.
        val two = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 282_168L))
        settleAs(two, Resource.DARK_ELIXIR, 291_790L, 291_790L)
        two.attackCompleted()
        settleAs(two, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L, 2_984_008L)
        two.attackCompleted()
        settleAs(two, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L)
        two.attackCompleted()
        assertNull(dark(settleAs(two, Resource.DARK_ELIXIR, 309_456L, 309_456L, null).second))
    }

    @Test fun aFirstBalanceNothingVouchedForIsNotBroughtBackOverItsReplacement() {
        // With nothing stored, a dropped digit was the run's first dark elixir and the truth replaced
        // it on a candidate. The misread again, on three frames, does not take over: nothing said it
        // was true in the first place.
        val guard = HomeBalanceGuard(capacities).also { settleAs(it, Resource.DARK_ELIXIR, 29_179L, 29_179L) }
        guard.attackCompleted()
        settleAs(guard, Resource.DARK_ELIXIR, 291_790L, 291_790L, 291_790L)
        guard.attackCompleted()
        assertEquals(295_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 295_000L, 295_000L).second))
        guard.attackCompleted()
        val (frames, settled) = settleAs(guard, Resource.DARK_ELIXIR, 29_500L, 29_500L, 29_500L)
        assertEquals(3, frames)
        assertNull(dark(settled))
    }

    @Test fun darkElixirReadAfterAWallPhaseIsHeldSinceWallsNeverSpendIt() {
        // Walls are bought with gold or elixir, never dark elixir, so a wall phase leaves its floor
        // where it was: a dropped digit after the phase is held, and the truth after the next attack
        // is taken on two frames.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 300_000L))
        settleAs(guard, Resource.DARK_ELIXIR, 309_456L, 309_456L)
        guard.spendingPossible()
        val (frames, held) = settleAs(guard, Resource.DARK_ELIXIR, 30_945L, 30_945L, 30_945L)
        assertEquals(3, frames)
        assertNull(dark(held))
        assertEquals(309_456L, held.flagged.single().trusted)
        guard.attackCompleted()
        val (two, truth) = settleAs(guard, Resource.DARK_ELIXIR, 312_000L, 312_000L)
        assertEquals(2, two)
        assertEquals(312_000L, dark(truth))
    }

    @Test fun aMisreadReadAgainInsideItsOwnReachKeepsTheBalanceItJumpedFrom() {
        // 470 000 trusted; 4 700 000 held, then taken on its candidate as a jump, then read once
        // more. That last read used to drop the 470 000, so the truth on three frames stayed unread.
        val misread = 4_700_000L
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 470_000L))
        assertEquals(470_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 470_000L, 470_000L).second))
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, misread, misread, misread).second))
        guard.attackCompleted()
        assertEquals(misread, dark(settleAs(guard, Resource.DARK_ELIXIR, misread, misread).second))
        guard.attackCompleted()
        assertEquals(misread, dark(settleAs(guard, Resource.DARK_ELIXIR, misread, misread).second))
        guard.attackCompleted()
        val truth = mapOf(Resource.DARK_ELIXIR to (470_000L to cuts("470 000")))
        val (frames, settled) = settleCuts(guard, truth, truth, truth)
        assertEquals(3, frames)
        assertEquals(470_000L, dark(settled))
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, misread, misread, misread).second))
    }

    @Test fun aReturnNotEveryMaskReadComesBackOnItsCandidate() {
        // The true 309 456 after the jump on three frames with no cuts to go on is held, and taken
        // on its candidate one checkpoint later as a return to the balance before the jump.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 282_168L))
        settleAs(guard, Resource.DARK_ELIXIR, 291_790L, 291_790L)
        guard.attackCompleted()
        settleAs(guard, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L, 2_984_008L)
        guard.attackCompleted()
        assertEquals(2_984_008L, dark(settleAs(guard, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L).second))
        guard.attackCompleted()
        val (frames, held) = settleAs(guard, Resource.DARK_ELIXIR, 309_456L, 309_456L, 309_456L)
        assertEquals(3, frames)
        assertNull(dark(held))
        guard.attackCompleted()
        assertEquals(312_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 312_000L, 312_000L).second))
        // A return, so the misread after it is a jump again.
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 2_984_008L, 2_984_008L, 2_984_008L).second))
    }

    @Test fun aDroppedDigitAJumpKeptLetsNoTenthOfTheTruthBackIn() {
        // UO-19 round 2 review, 15 October (L1): 33 000 trusted, a dropped digit of 330 000; the
        // truth held, then taken on its candidate as a jump that keeps 33 000, then risen inside
        // its reach to 360 000. 36 500 on three frames, a tenth of the truth, used to be taken.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 100_000L))
        assertEquals(33_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 33_000L, 33_000L).second))
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 330_000L, 330_000L, 330_000L).second))
        var truth = 335_000L
        repeat(6) {
            guard.attackCompleted()
            assertEquals(truth, dark(settleAs(guard, Resource.DARK_ELIXIR, truth, truth).second))
            truth += 5_000L
        }
        guard.attackCompleted()
        val (frames, settled) = settleAs(guard, Resource.DARK_ELIXIR, 36_500L, 36_500L, 36_500L)
        assertEquals(3, frames)
        assertNull(dark(settled))
        assertEquals(360_000L, settled.flagged.single().trusted)
        guard.attackCompleted()
        assertEquals(370_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 370_000L, 370_000L).second))
    }

    @Test fun darkElixirGainedByHandKeepsTheOldBalanceButNoTenthOfTheTruth() {
        // UO-19 round 2 review, 15 October (L2): 40 000 trusted, then 45 000; 300 000 gained by
        // hand in a pause, so 345 000 is held, and 350 000 is taken on its candidate as a jump that
        // keeps 45 000. Risen to 370 000, 37 500 on three frames used to be taken.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 40_000L))
        assertEquals(40_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 40_000L, 40_000L).second))
        guard.attackCompleted()
        assertEquals(45_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 45_000L, 45_000L).second))
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 345_000L, 345_000L, 345_000L).second))
        var truth = 350_000L
        repeat(5) {
            guard.attackCompleted()
            assertEquals(truth, dark(settleAs(guard, Resource.DARK_ELIXIR, truth, truth).second))
            truth += 5_000L
        }
        guard.attackCompleted()
        val (frames, settled) = settleAs(guard, Resource.DARK_ELIXIR, 37_500L, 37_500L, 37_500L)
        assertEquals(3, frames)
        assertNull(dark(settled))
        guard.attackCompleted()
        assertEquals(380_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 380_000L, 380_000L).second))
    }

    @Test fun aBalanceTakenOnACandidateIsNoBalanceToComeBackTo() {
        // With nothing stored, 470 000 is the run's first and vouches for nothing, so the misread
        // that replaces it on a candidate keeps nothing. The truth then replaces the misread on a
        // candidate. The misread was taken outside the reach of the balance before it, so it vouches
        // for nothing either, and three frames of it again stay unread. It used to be vouched, and
        // the truth kept it to come back to.
        val misread = 4_700_000L
        val guard = HomeBalanceGuard(capacities).also { settleAs(it, Resource.DARK_ELIXIR, 470_000L, 470_000L) }
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, misread, misread, misread).second))
        guard.attackCompleted()
        assertEquals(misread, dark(settleAs(guard, Resource.DARK_ELIXIR, misread, misread).second))
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 470_000L, 470_000L, 470_000L).second))
        guard.attackCompleted()
        assertEquals(470_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 470_000L, 470_000L).second))
        guard.attackCompleted()
        val (frames, settled) = settleAs(guard, Resource.DARK_ELIXIR, misread, misread, misread)
        assertEquals(3, frames)
        assertNull(dark(settled))
    }

    @Test fun theBalanceAJumpKeptAllowsNoMoreThanAtTheFirstReadingAfterIt() {
        // 2 984 008 stored by a run that ended on it and read again; the truth held, then taken on
        // its candidate as a jump that keeps the misread. Sixty checkpoints on, the truth 372 000
        // read with a digit dropped used to be taken: the kept 2 984 008 had counted 63 attacks.
        val misread = 2_984_008L
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to misread))
        assertEquals(misread, dark(settleAs(guard, Resource.DARK_ELIXIR, misread, misread).second))
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 309_456L, 309_456L, 309_456L).second))
        var truth = 312_000L
        repeat(61) {
            guard.attackCompleted()
            assertEquals(truth, dark(settleAs(guard, Resource.DARK_ELIXIR, truth, truth).second))
            truth += 1_000L
        }
        guard.attackCompleted()
        val (frames, settled) = settleAs(guard, Resource.DARK_ELIXIR, 37_200L, 37_200L, 37_200L)
        assertEquals(3, frames)
        assertNull(dark(settled))
    }

    @Test fun darkElixirSpentByHandIsNotComeBackToOnADoubledDigit() {
        // 300 000 trusted, then 50 000 after a hero upgrade bought by hand in a pause: held, then
        // taken on its candidate as a jump that keeps 300 000. Eleven checkpoints after the jump,
        // 88 000 read with a digit doubled used to be taken, and the truth after it held.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.DARK_ELIXIR to 300_000L))
        assertEquals(300_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 300_000L, 300_000L).second))
        guard.attackCompleted()
        assertNull(dark(settleAs(guard, Resource.DARK_ELIXIR, 50_000L, 50_000L, 50_000L).second))
        var truth = 55_000L
        repeat(11) {
            guard.attackCompleted()
            assertEquals(truth, dark(settleAs(guard, Resource.DARK_ELIXIR, truth, truth).second))
            truth += 3_000L
        }
        guard.attackCompleted()
        val (frames, settled) = settleAs(guard, Resource.DARK_ELIXIR, 880_000L, 880_000L, 880_000L)
        assertEquals(3, frames)
        assertNull(dark(settled))
        guard.attackCompleted()
        assertEquals(91_000L, dark(settleAs(guard, Resource.DARK_ELIXIR, 91_000L, 91_000L).second))
    }

    @Test fun goldReadAfterAWallPhaseIsNotBroughtBackOverTheTruth() {
        // Walls take 26 431 950 to 18 431 950, read as 1 843 195 and trusted. The truth is held
        // against it, then taken on a candidate; 22 931 950 read the same way stays unread, and
        // 24 431 950 is taken on two frames.
        val guard = trusting(26_431_950L)
        guard.spendingPossible()
        assertEquals(1_843_195L, gold(settle(guard, 1_843_195L, 1_843_195L).second))
        guard.attackCompleted()
        assertNull(gold(settle(guard, 19_931_950L, 19_931_950L, 19_931_950L).second))
        guard.attackCompleted()
        assertEquals(21_431_950L, gold(settle(guard, 21_431_950L, 21_431_950L).second))
        guard.attackCompleted()
        val (frames, misread) = settle(guard, 2_293_195L, 2_293_195L, 2_293_195L)
        assertEquals(3, frames)
        assertNull(gold(misread))
        guard.attackCompleted()
        val (two, truth) = settle(guard, 24_431_950L, 24_431_950L)
        assertEquals(2, two)
        assertEquals(24_431_950L, gold(truth))
    }

    @Test fun goldStoredAsAMisreadIsNotBroughtBackOverTheTruth() {
        // 2 403 171 stored by an earlier run vouches for the same misread at this run's start. The
        // truth replaces it on a candidate, and the misread again on three frames stays unread.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.GOLD to 2_403_171L))
        assertEquals(2_403_171L, gold(settle(guard, 2_403_171L, 2_403_171L).second))
        guard.attackCompleted()
        assertNull(gold(settle(guard, 24_031_171L, 24_031_171L, 24_031_171L).second))
        guard.attackCompleted()
        assertEquals(24_536_584L, gold(settle(guard, 24_536_584L, 24_536_584L).second))
        guard.attackCompleted()
        val (frames, settled) = settle(guard, 2_453_658L, 2_453_658L, 2_453_658L)
        assertEquals(3, frames)
        assertNull(gold(settled))
    }

    @Test fun blood0412ElixirThatEveryMaskReadBeatsAStalePrior() {
        // 15 October 04:12: all nine masks read 387 427 on all three frames, held against the
        // 29 000 000 stored long before. The next elixir stored was 4 645 448.
        val prior = mapOf(Resource.ELIXIR to 29_000_000L)
        val alike = mapOf(Resource.ELIXIR to (387_427L to cuts("387 427")))
        val (frames, settled) = settleCuts(HomeBalanceGuard(capacities, prior), alike, alike, alike)
        assertEquals(2, frames)
        assertEquals(387_427L, agreedHomeBalances(settled.samples, capacities)[Resource.ELIXIR])
        val flag = settled.flagged.single()
        assertEquals(387_427L, flag.accepted)
        assertNull(flag.trusted)
        assertTrue(flag.everyMask)
        // With no cuts to go on, the same frames stay held, as they did on the phone.
        val bare = mapOf(Resource.ELIXIR to (387_427L to emptyList<String>()))
        val (three, held) = settleCuts(HomeBalanceGuard(capacities, prior), bare, bare, bare)
        assertEquals(3, three)
        assertNull(agreedHomeBalances(held.samples, capacities)[Resource.ELIXIR])
        assertFalse(held.flagged.single().everyMask)
    }

    @Test fun blood0501BalancesThatEveryMaskReadBeatThePriorWithOneMaskAbstaining() {
        // 05:01: gold 262 165 through all nine masks against 9 393 986 stored at 04:12; elixir
        // 3 876 306 through eight on the first frame, the 240 cut reading "908 9L8 E", and all
        // nine on the next two. Both were held for the whole run.
        val prior = mapOf(Resource.GOLD to 9_393_986L, Resource.ELIXIR to 29_000_000L)
        val gold = Resource.GOLD to (262_165L to cuts("262 165"))
        val first = mapOf(gold, Resource.ELIXIR to (3_876_306L to cuts("3 876 306", mapOf("240" to "908 9L8 E"))))
        val next = mapOf(gold, Resource.ELIXIR to (3_876_306L to cuts("3 876 306")))
        val (frames, settled) = settleCuts(HomeBalanceGuard(capacities, prior), first, next, next)
        assertEquals(2, frames)
        val agreed = agreedHomeBalances(settled.samples, capacities)
        assertEquals(262_165L, agreed[Resource.GOLD])
        assertEquals(3_876_306L, agreed[Resource.ELIXIR])
        assertEquals(setOf(Resource.GOLD, Resource.ELIXIR), settled.flagged.filter { it.everyMask }.map { it.resource }.toSet())
    }

    @Test fun aFrameNotEveryMaskReadLeavesThePriorToHoldTheBalance() {
        // Two masks with nothing on the second frame: every frame must count, so 262 165 is held.
        val prior = mapOf(Resource.GOLD to 9_393_986L)
        val alike = mapOf(Resource.GOLD to (262_165L to cuts("262 165")))
        val short = mapOf(Resource.GOLD to (262_165L to cuts("262 165").drop(2)))
        val (frames, settled) = settleCuts(HomeBalanceGuard(capacities, prior), alike, short, alike)
        assertEquals(3, frames)
        assertNull(gold(settled))
    }

    @Test fun aBalanceEveryMaskReadIsStillHeldAgainstOneThisRunTrusted() {
        // 08:16 on 8 October with every mask reading the misread: three minutes before, the run
        // trusted 23 429 258, and a misread of an unchanged screen repeats on every frame.
        val guard = trusting(23_429_258L)
        guard.attackCompleted()
        val misread = mapOf(Resource.GOLD to (2_403_171L to cuts("2 403 171")))
        val (frames, settled) = settleCuts(guard, misread, misread, misread)
        assertEquals(3, frames)
        assertNull(gold(settled))
        assertFalse(settled.flagged.single().everyMask)
    }

    @Test fun aBalanceEveryMaskReadIsStillHeldAgainstACandidate() {
        // The prior holds 2 403 171 on three frames, which makes it a candidate. After an attack,
        // 2 390 000 through every mask is below the candidate and is held like any reading.
        val guard = HomeBalanceGuard(capacities, prior = mapOf(Resource.GOLD to 23_429_258L))
        assertNull(gold(settle(guard, 2_403_171L, 2_403_171L, 2_403_171L).second))
        guard.attackCompleted()
        val lower = mapOf(Resource.GOLD to (2_390_000L to cuts("2 390 000")))
        val (frames, settled) = settleCuts(guard, lower, lower, lower)
        assertEquals(3, frames)
        assertNull(gold(settled))
    }
}
