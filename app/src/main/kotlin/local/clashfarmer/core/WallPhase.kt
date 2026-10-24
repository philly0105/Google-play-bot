package local.clashfarmer.core

import kotlinx.coroutines.CancellationException
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.UpgradeListReader
import local.clashfarmer.recognition.upgradeListClosePoint
import java.io.IOException

/**
 * What the wall phase needs of the phone, on top of what the list navigator needs. One Android
 * adapter serves both.
 */
interface WallPanelDevice : UpgradeListDevice {
    /** A press on a button. It may be re-sent after a pause cancelled it; never used to confirm. */
    suspend fun tapButton(point: Point)

    /**
     * The press that accepts a purchase dialog. It is sent at most once: a pause or a timeout
     * that leaves its outcome unknown throws, and nothing re-sends it. One refused or held before
     * it went out throws [ConfirmationNotSent].
     */
    suspend fun tapConfirmation(point: Point)

    /** A press on a button that a pause is never followed by again; a pause during it throws. */
    suspend fun tapButtonOnce(point: Point)

    /** The system Back, or false when it was withheld because the run may not act. */
    suspend fun back(where: String = "wall_builders_busy"): Boolean

    /** The resource bar's balance for "gold" or "elixir", or null when it will not read. */
    suspend fun readBalance(currency: String): Long?

    /**
     * The balance once it stops rolling, or -1 when it cannot be read or reads higher than
     * [starting], which a spend cannot cause.
     */
    suspend fun settledBalance(currency: String, expected: Long, starting: Long): Long
}

/**
 * The confirmation was refused ([TapAfterHold.REFUSED]) or held ([TapAfterHold.HELD]) before it
 * went out, so nothing was bought and the pending marker can go. [paused] is false when no pause
 * came since the dialog's frame was asked for: a look found the dialog changed, and the dialog is
 * read again.
 */
class ConfirmationNotSent(cause: StopException, val paused: Boolean = true) :
    StopException("The wall confirmation was not sent: ${cause.message}", cause)

/**
 * One wall phase's instructions. [balance] is the agreed HOME reading the phase is being run on;
 * the resource bar is read again on the panel and must still say the same.
 */
data class WallPhaseOrder(
    val accountId: String,
    val mode: WallPhaseRequest,
    val currency: Resource,
    val balance: Long,
    val capacity: Long,
    val resumePercent: Int,
    val reserve: Long,
    val maxWallsPerPhase: Int,
    val maxWallLevel: Int,
    val cappedPrices: Set<Long> = emptySet()
) {
    /** Why this order cannot be run, or null. */
    fun problem(): String? = when {
        currency != Resource.GOLD && currency != Resource.ELIXIR -> "walls cannot spend ${currency.key}"
        balance < 0L || reserve < 0L -> "negative balance or reserve"
        mode == WallPhaseRequest.AUTOMATIC && capacity <= 0L -> "no capacity for an automatic phase"
        resumePercent !in 0..99 -> "resume percent $resumePercent is outside 0..99"
        maxWallsPerPhase !in 1..100 -> "walls per phase $maxWallsPerPhase is outside 1..100"
        maxWallLevel < 1 -> "no wall level cap"
        else -> null
    }
}

/**
 * One wall phase: one view of the upgrade list and at most one purchase, in one currency.
 *
 * The list is read once by [UpgradeListNavigator], which leaves a Wall panel open with one wall
 * selected. From there this reads the panel, sizes the selection from the storage policy, grows
 * it with Upgrade More and Add Wall, asks the game for a purchase, checks the dialog, and proves
 * the spend. It never goes back to the list and never presses Select Row, which is move mode.
 *
 * Every outcome ends at a confirmed HOME. Anything that goes wrong before a confirmation is sent
 * is a [WallPhaseOutcome.Failed] and costs nothing. Once a confirmation may have been sent and
 * the spend is not proven, the phase throws [StopException] with the pending marker in place, and
 * only an operator's acknowledge lets walls run on that account again.
 *
 * A top bar that reads no builder free ends the phase as [WallPhaseOutcome.NoBuilder] before the
 * list is opened. If a confirmation opens "All builders are busy!" anyway, only its X or Back
 * closes it, and an unchanged balance afterwards clears the marker and ends the phase as
 * [WallPhaseOutcome.BuildersBusy].
 */
class WallPhase(
    private val device: WallPanelDevice,
    private val viewport: Viewport,
    private val store: WallPurchaseStore,
    private val neutralPoint: Point?,
    selectWall: (suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult)? = null
) {

    private companion object {
        /** Frames the panel is given to appear, and the gap between them. */
        const val PANEL_FRAMES = 4
        const val PANEL_GAP_MS = 600L

        /** A press or a read that a pause made stale is redone this many times, then refused. */
        const val STALE_RETRIES = 3

        /**
         * Reads of the Upgrade total before it is refused. The digits drift between frames
         * ("24000 00", "20000 000" for 24M on Blood, 1 October), so a clean one needs more tries.
         */
        const val BUY_READS = 6

        const val DIALOG_SETTLE_MS = 1_500L
        const val CONFIRM_SETTLE_MS = 2_000L
        const val HOME_STEP_MS = 1_000L
        const val HOME_CLEAR_GAP_MS = 700L

        /** Backs [confirmHome] sends the gem store before it waits for the deadline instead. */
        const val SHOP_BACKS = 3

        /** Frames looked at, after a spend, for the panel's next level or its absence. */
        const val EVIDENCE_FRAMES = 6
        const val EVIDENCE_GAP_MS = 700L

        const val UNRESOLVED = "A wall purchase was confirmed but its spend could not be proven; " +
            "look at the village and acknowledge the pending purchase before walls run again"
        const val HOME_NOT_CONFIRMED = "The village could not be confirmed after the wall phase; " +
            "nothing was left half-bought, but a screen is still in the way"
    }

    private val select: suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult =
        selectWall ?: { spendable, capped -> UpgradeListNavigator(device, viewport).findAndSelectWall(spendable, capped) }

    /** A refusal before any confirmation was sent: nothing was spent. */
    private class Refusal(val outcome: WallPhaseOutcome.Failed) : Exception(outcome.detail)

    /** The stop thrown for an unproven purchase, so the catch-all below can tell it from wall trouble. */
    private var unresolved: StopException? = null

    private fun refuse(reason: WallPhaseFailure, detail: String): Nothing {
        AppLog.log("wall_phase_refused", "reason" to reason.name, "detail" to detail)
        throw Refusal(WallPhaseOutcome.Failed(reason, detail))
    }

    suspend fun run(order: WallPhaseOrder): WallPhaseOutcome {
        order.problem()?.let { return WallPhaseOutcome.Failed(WallPhaseFailure.INVALID_ORDER, it) }
        val neutral = neutralPoint
            ?: return WallPhaseOutcome.Failed(WallPhaseFailure.INVALID_ORDER, "no village_neutral anchor to close panels with")
        val pending = try {
            store.isPending(order.accountId)
        } catch (e: IllegalArgumentException) {
            return WallPhaseOutcome.Failed(WallPhaseFailure.INVALID_ORDER, e.message ?: "bad account id")
        }
        if (pending) {
            AppLog.log("wall_phase_blocked_by_marker", "account" to order.accountId)
            return WallPhaseOutcome.Failed(
                WallPhaseFailure.PENDING_PURCHASE,
                "an earlier wall purchase was never proven; acknowledge it after checking the village"
            )
        }

        unresolved = null
        val outcome = try {
            if (noFreeBuilder(order.accountId)) WallPhaseOutcome.NoBuilder else attempt(order)
        } catch (r: Refusal) {
            r.outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: StopException) {
            if (e === unresolved) throw e
            abandon(e)
        } catch (e: Exception) {
            abandon(e)
        }
        confirmHome(neutral)
        AppLog.log("wall_phase_outcome", "outcome" to outcome.toString())
        return outcome
    }

    /**
     * Wall trouble is not worth a run: a tap that timed out or a panel that would not read costs
     * the phase and nothing else. A stopped run is not wall trouble, and the checkpoint says so.
     */
    private suspend fun abandon(e: Exception): WallPhaseOutcome {
        device.checkpoint()
        AppLog.log("wall_phase_abandoned", "reason" to (e.message ?: e.toString()))
        // A tap the list held after a pause, with no look to clear it, is the pause's doing.
        val reason = if (TapAfterHold.isHeld(e)) WallPhaseFailure.PAUSED else WallPhaseFailure.ERROR
        return WallPhaseOutcome.Failed(reason, e.message ?: e.toString())
    }

    /**
     * Whether the top bar says no builder is free. Clash takes no wall upgrade without one: at
     * 09:33 UTC on 8 October Blood (builders 0/6) confirmed three walls and got "All builders are
     * busy!" with a 212-gem button under the confirm point. A counter that does not read is not
     * evidence either way, and the phase runs as before. A zero is checked on a second frame a
     * moment later: only a second count with a builder free lets the phase run. A second frame
     * that does not read keeps the zero, since skipping is the safe side; at 13:06:59 UTC on
     * 8 October Blood read 0/6 then null, ran the phase and met the busy dialog.
     */
    private suspend fun noFreeBuilder(accountId: String): Boolean {
        val boxes = look().observation.boxes
        val count = Builders.read(boxes, viewport)
        AppLog.log("builders_read", "free" to count?.free, "total" to count?.total,
            "text" to Builders.topBarText(boxes, viewport))
        local.clashfarmer.service.FarmerService.instance?.builderStore?.record(accountId, count, System.currentTimeMillis())
        if (count == null || count.free != 0) return false
        device.wait(PANEL_GAP_MS)
        val secondBoxes = look().observation.boxes
        val again = Builders.read(secondBoxes, viewport)
        if (again != null && again.free != 0) {
            AppLog.log("builders_read_disagree", "first" to "${count.free}/${count.total}",
                "second" to "${again.free}/${again.total}")
            return false
        }
        AppLog.log("wall_phase_skipped_no_builder", "total" to count.total,
            "reason" to if (again == null) "second read unreadable, zero kept" else "second read is zero too",
            "secondText" to if (again == null) Builders.topBarText(secondBoxes, viewport) else null)
        return true
    }

    private suspend fun attempt(order: WallPhaseOrder): WallPhaseOutcome {
        val key = order.currency.key
        val spendable = order.balance - order.reserve
        if (spendable <= 0L) return WallPhaseOutcome.Empty
        var found = select(mapOf(key to spendable), order.cappedPrices)
        var rescanFailed: String? = null
        // No walls ends walls in both currencies for the run, so a second scan of the list, still
        // open, has to agree: a Wall name garbled past reading ("9Wall x 23") is another card.
        // A rescan that could not finish proves nothing and keeps the first scan's verdict: a
        // Failed here would re-arm walls and bring more counter taps later in the run.
        if (found is UpgradeListResult.Empty && found.wallRowsSeen == 0) {
            AppLog.log("wall_phase_rescan", "reason" to "no Wall row in the first scan", "scrolls" to found.scrolls)
            val again = select(mapOf(key to spendable), order.cappedPrices)
            if (again is UpgradeListResult.Failed) rescanFailed = "${again.reason}: ${again.detail}" else found = again
        }
        return when (found) {
            is UpgradeListResult.Empty -> if (found.wallRowsSeen == 0) {
                AppLog.log("wall_phase_no_walls", "reason" to "no walls left to upgrade in the builder's list",
                    "scrolls" to found.scrolls, "rescanFailed" to rescanFailed)
                WallPhaseOutcome.NoWalls
            } else WallPhaseOutcome.Empty
            is UpgradeListResult.Failed ->
                WallPhaseOutcome.Failed(WallPhaseFailure.LIST_FAILED, "${found.reason}: ${found.detail}")
            is UpgradeListResult.Selected -> transact(order, found)
        }
    }

    // --- the panel -----------------------------------------------------------------------------

    private class Quote(
        val boxes: List<local.clashfarmer.recognition.RecognizedBox>,
        val rawText: String,
        val title: WallPanel.WallSelection?,
        /** The count after the title's x, when the title has one and it read. */
        val titled: Int?,
        val buttons: Map<String, WallPanel.PurchaseButton>,
        val payable: Map<String, Boolean?>
    )

    private suspend fun look(): UpgradeListFrame {
        device.checkpoint()
        return device.capture()
    }

    /** The panel's title and purchase buttons off a fresh frame, with the pixels that price them. */
    private suspend fun quote(): Quote {
        val frame = look()
        val boxes = frame.observation.boxes
        val buttons = WallPanel.purchaseButtons(boxes, viewport) { box ->
            WallPanel.iconCurrency(
                frame.pixels(Region(box.left, box.top, box.right + WallRunner.ICON_SAMPLE_OUTSIDE, box.bottom))
            )
        }
        val payable = buttons.mapValues { WallPanel.priceAffordable(frame.pixels(it.value.priceBox.region)) }
        return Quote(boxes, frame.observation.rawText, WallPanel.parseTitle(boxes, viewport),
            WallPanel.titleCount(boxes, viewport), buttons, payable)
    }

    /** The panel once it has appeared, or null. Clash slides it in, so one frame is not enough. */
    private suspend fun readPanel(key: String, listPrice: Long): Quote? {
        var last: Quote? = null
        for (i in 0 until PANEL_FRAMES) {
            val q = quote()
            if (q.title != null && WallPanel.isPanel(q.boxes, viewport)) {
                last = q
                if (q.buttons[key]?.price == listPrice && q.payable[key] == true) return q
                AppLog.log("wall_panel_reread", "attempt" to i + 1, "currency" to key,
                    "listPrice" to listPrice, "panel" to WallPanel.bandText(q.boxes, viewport))
            } else {
                AppLog.log("wall_panel_missing", "attempt" to i + 1, "title" to q.title?.toString(),
                    "panel" to WallPanel.bandText(q.boxes, viewport), "text" to q.rawText.take(400))
            }
            if (i < PANEL_FRAMES - 1) device.wait(PANEL_GAP_MS)
        }
        return last
    }

    /**
     * Presses a button unless a pause came since the frame it was found on, or the press was held
     * because its frame was stale and a look did not find the screen the same; false means
     * nothing was sent and the caller reads again.
     */
    private suspend fun act(point: Point): Boolean {
        if (device.checkpoint()) return false
        return sentOrHeld { device.tapButton(point) }
    }

    private suspend fun actPlain(point: Point): Boolean {
        if (device.checkpoint()) return false
        return sentOrHeld { device.tap(point) }
    }

    private fun pricedCount(q: Quote, key: String, unit: Long): Int? =
        q.buttons[key]?.price?.let { WallPanel.countFor(it, unit) }

    /**
     * The walls selected: the button's total over [unit], or the title's "x N" when only that
     * read. When both read they must agree, and two that do not are no count, so the caller reads
     * again rather than pick one. buy() still takes the count from the price alone.
     */
    private fun selectedCount(q: Quote, key: String, unit: Long): Int? {
        val priced = pricedCount(q, key, unit)
        return if (priced != null && q.titled != null) priced.takeIf { it == q.titled } else priced ?: q.titled
    }

    private suspend fun transact(order: WallPhaseOrder, sel: UpgradeListResult.Selected): WallPhaseOutcome {
        val key = order.currency.key
        val first = readPanel(key, sel.candidate.listPrice)
            ?: refuse(WallPhaseFailure.PANEL_UNREADABLE, "no Wall panel with a title after the row was tapped")
        val title = first.title!!
        if (title.count != 1) {
            refuse(WallPhaseFailure.MULTIPLE_SELECTION, "${title.count} walls are selected, not one")
        }
        val button = first.buttons[key]
            ?: refuse(WallPhaseFailure.WRONG_CURRENCY, "the panel has no $key purchase button")
        val unit = button.price ?: refuse(WallPhaseFailure.PRICE_UNKNOWN, "the $key price did not read")
        if (unit != sel.candidate.listPrice) {
            refuse(WallPhaseFailure.PRICE_CHANGED, "the list said ${sel.candidate.listPrice}, the panel says $unit")
        }
        // The title gives the level and the price only confirms it, within the Gold Pass band.
        val level = WallPanel.wallLevel(title.level, unit)
        if (maxOf(level ?: 0, title.level) >= order.maxWallLevel) {
            AppLog.log("wall_level_capped", "unit" to unit, "level" to (level ?: title.level), "cap" to order.maxWallLevel)
            return WallPhaseOutcome.Capped(sel.candidate.listPrice)
        }
        if (level == null) {
            refuse(WallPhaseFailure.PRICE_UNKNOWN, "$unit is not 0-20% under the full price of a level ${title.level} wall")
        }
        if (first.payable[key] != true) {
            refuse(WallPhaseFailure.NOT_PAYABLE, "the game draws the $key price as unaffordable or the colour did not settle")
        }
        val balance = agreedBalance(key, order)
        val planned = wallBulkCount(
            WallStorageRules(100, order.resumePercent), balance, order.capacity, order.reserve,
            unit, order.maxWallsPerPhase, order.mode
        )
        if (planned == 0) refuse(WallPhaseFailure.NOTHING_PLANNED, "balance $balance, reserve ${order.reserve}, unit $unit")
        AppLog.log("wall_phase_planned", "currency" to key, "unit" to unit, "count" to planned, "balance" to balance)
        if (planned > 1) grow(key, unit, planned)
        return buy(order, key, unit, level, planned, balance)
    }

    /** Refresh the agreed resource bar before calculating the purchase. */
    private suspend fun agreedBalance(key: String, order: WallPhaseOrder): Long {
        var previous: Long? = null
        repeat(3) {
            val read = device.readBalance(key)
            if (read != null && read in 0L..order.capacity && read == previous) return read
            previous = read?.takeIf { it in 0L..order.capacity }
            device.wait(PANEL_GAP_MS)
        }
        refuse(WallPhaseFailure.BALANCE_UNREAD, "the resource bar did not agree within capacity ${order.capacity}")
    }

    // --- growing the selection -----------------------------------------------------------------

    /**
     * Turns the panel into the bulk one with Upgrade More, grows the selection toward [target]
     * with Add Wall, and trims it with Remove Wall while the game draws the total in red.
     *
     * The count is the total on the button divided by the price of one wall, held to the title's
     * "x N" when that reads too (see [selectedCount]), and every press is
     * confirmed by reading it again: on the phone the +10 is drawn red and does nothing while +1
     * works, so a +10 that does not move the count is dropped for +1 from then on.
     */
    private suspend fun grow(key: String, unit: Long, target: Int) {
        var pressed = false
        for (attempt in 0 until STALE_RETRIES) {
            val q = quote()
            val more = WallPanel.upgradeMoreTap(q.boxes, viewport)
            if (more == null) {
                // On 18.647 an account that could afford one wall got a panel with no Upgrade More
                // (r2-07-walltap.png). One wall is still bought; buy() proves the count.
                if (q.title?.count == 1 && WallPanel.isPanel(q.boxes, viewport)) {
                    AppLog.log("wall_bulk_unavailable", "currency" to key, "target" to target,
                        "panel" to WallPanel.bandText(q.boxes, viewport))
                    return
                }
                refuse(WallPhaseFailure.SELECTION_REFUSED, "no Upgrade More on the panel")
            }
            if (act(more)) { pressed = true; break }
        }
        if (!pressed) refuse(WallPhaseFailure.PAUSED, "paused repeatedly before Upgrade More")
        device.wait(WallRunner.BULK_PRESS_MS)

        var q = quote()
        if (WallPanel.findConfirmButton(q.boxes) != null || WallPanel.isInsufficientFunds(q.rawText)) {
            refuse(WallPhaseFailure.SELECTION_REFUSED, "Upgrade More opened a purchase dialog")
        }
        // Only the count is waited for: a missing Add Wall label is covered by its pitch. On
        // 30 September the count read on the fourth frame, "Add Wall" came back as "Ada Wa", and
        // waiting for both labels threw that frame away for one that misread.
        var read = 0
        while (selectedCount(q, key, unit) == null && read++ < PANEL_FRAMES * 2) {
            AppLog.log("wall_bulk_reread", "attempt" to read, "currency" to key,
                "count" to selectedCount(q, key, unit), "priced" to pricedCount(q, key, unit), "titled" to q.titled,
                "panel" to WallPanel.bandText(q.boxes, viewport))
            device.wait(WallRunner.PANEL_REREAD_MS)
            q = quote()
        }
        var count = selectedCount(q, key, unit)
            ?: refuse(WallPhaseFailure.SELECTION_REFUSED, "the count did not read after Upgrade More")

        var tenBroken = false
        var presses = 0
        // Every way out of growth below the target is logged with what the panel showed. On
        // 5 October two elixir phases ended at 1 and 3 of 4 with nothing in the log to say why.
        fun ended(reason: String, count: Int?, step: Int) = AppLog.log("wall_bulk_growth_ended",
            "reason" to reason, "count" to count, "step" to step, "target" to target, "presses" to presses,
            "payable" to q.payable[key], "panel" to WallPanel.bandText(q.boxes, viewport))
        while (count < target) {
            if (presses++ >= target + STALE_RETRIES) { ended("presses_exhausted", count, 0); break }
            var step = WallBatchPlan.nextStep(count, target)
            if (step == 0) { ended("no_step", count, 0); break }
            if (step == 10 && tenBroken) step = 1
            val adds = WallPanel.addButtons(q.boxes, viewport)
            val press = if (adds.size == 2) {
                (if (step == 10) adds[0] else adds[1]).center
            } else {
                // The +10 is drawn red and its label often does not read. +1 is the button left
                // of the gold one, a button's pitch away, and the count read after it proves it.
                step = 1
                val one = WallPanel.addOneByPitch(q.buttons, viewport)
                if (one == null) { ended("no_add_button", count, step); break }
                one
            }
            if (!act(press)) {
                q = quote()
                val reread = selectedCount(q, key, unit)
                if (reread == null) { ended("unread_after_pause", null, step); break }
                count = reread
                continue
            }
            device.wait(WallRunner.BULK_PRESS_MS)
            var next = quote()
            var after = selectedCount(next, key, unit)
            var reads = 0
            while ((after == null || after == count) && reads++ < PANEL_FRAMES) {
                device.wait(WallRunner.PANEL_REREAD_MS)
                next = quote()
                after = selectedCount(next, key, unit)
            }
            q = next
            when {
                after == null -> {
                    AppLog.log("wall_bulk_growth_unread", "count" to count, "step" to step, "target" to target,
                        "panel" to WallPanel.bandText(q.boxes, viewport))
                    break
                }
                WallBatchPlan.acceptsGrowth(count, after, step, target) -> count = after
                after == count && step == 10 -> tenBroken = true
                else -> {
                    AppLog.log("wall_bulk_growth_stopped", "count" to count, "read" to after, "step" to step, "target" to target)
                    break
                }
            }
        }

        var trims = 0
        var rounds = 0
        var trimEnd = "limit"
        while (trims < WallRunner.MAX_TRIMS && rounds++ < WallRunner.MAX_TRIMS * 2) {
            val n = selectedCount(q, key, unit)
            if (n == null) { trimEnd = "unread"; break }
            if (n <= 1 || (q.payable[key] != false && n <= target)) { trimEnd = "settled"; break }
            val remove = WallPanel.buttons(q.boxes, viewport)[WallPanel.WallButton.REMOVE]
            if (remove == null) { trimEnd = "no_remove_button"; break }
            AppLog.log("wall_bulk_trim", "count" to n, "payable" to q.payable[key], "target" to target,
                "panel" to WallPanel.bandText(q.boxes, viewport))
            if (!act(remove.center)) {
                q = quote()
                continue
            }
            device.wait(WallRunner.BULK_PRESS_MS)
            q = quote()
            trims++
        }
        val selected = selectedCount(q, key, unit)
        AppLog.log("wall_bulk_selected", "currency" to key, "count" to selected, "target" to target,
            "trims" to trims, "trimEnd" to trimEnd)
        // Fewer than planned is still bought: Upgrade prices exactly what is selected and buy()
        // proves the count from that price. It is logged so a short phase is never silent.
        if (selected == null || selected < target) {
            AppLog.log("wall_bulk_short", "currency" to key, "count" to selected, "target" to target,
                "payable" to q.payable[key], "panel" to WallPanel.bandText(q.boxes, viewport))
        }
    }

    // --- the purchase --------------------------------------------------------------------------

    private class Confirmation(val target: Point)

    private suspend fun buy(
        order: WallPhaseOrder, key: String, unit: Long, level: Int, planned: Int, balance: Long
    ): WallPhaseOutcome {
        var total = 0L
        var count = 0
        var pressed = false
        for (attempt in 0 until BUY_READS) {
            val q = quote()
            val button = q.buttons[key]
            val price = button?.price
            val n = price?.let { WallPanel.countFor(it, unit) }
            if (button == null || price == null || n == null || n !in 1..planned ||
                q.payable[key] != true || !Policy.permittedUpgrade(key, balance, price, order.reserve)
            ) {
                AppLog.log("wall_buy_reread", "attempt" to attempt + 1, "currency" to key, "total" to price,
                    "payable" to q.payable[key], "panel" to WallPanel.bandText(q.boxes, viewport),
                    "text" to q.rawText.take(300))
                if (attempt < BUY_READS - 1) {
                    device.wait(PANEL_GAP_MS)
                    continue
                }
                refuse(
                    WallPhaseFailure.SELECTION_REFUSED,
                    "total $price, count $n, planned $planned, payable ${q.payable[key]}, balance $balance"
                )
            }
            total = price
            count = n
            if (act(button.tap)) { pressed = true; break }
        }
        if (!pressed) refuse(WallPhaseFailure.PAUSED, "paused repeatedly before Upgrade")
        device.wait(DIALOG_SETTLE_MS)

        val expected = balance - total
        val marker = confirm(order, key, level, balance, total, count, expected)
        AppLog.log("wall_confirmation_dispatched", "currency" to key, "cost" to total, "walls" to count)

        var busy = false
        val proven = try {
            device.wait(CONFIRM_SETTLE_MS)
            busy = closeBuildersBusy(key, total, count)
            if (busy) {
                false
            } else {
                val settled = device.settledBalance(key, expected, balance)
                val spent = WallRunner.spendVerified(expected, settled)
                if (!spent) {
                    AppLog.log("wall_spend_unverified", "currency" to key, "cost" to total, "expected" to expected, "settled" to settled)
                    busy = closeBuildersBusy(key, total, count)
                }
                spent && nextLevelShown(level)
            }
        } catch (e: CancellationException) {
            AppLog.log("wall_purchase_outcome_unknown", "stage" to "verify", "currency" to key, "cost" to total, "walls" to count)
            throw e
        } catch (e: Exception) {
            throw unresolvedStop(e, "verify", key, total, count)
        }
        if (busy) return noBuilderAfterConfirm(marker, key, balance, total, count)
        if (!proven) throw unresolvedStop(null, "unproven", key, total, count)

        if (!store.clearProven(marker)) AppLog.log("wall_marker_not_cleared", "account" to order.accountId)
        AppLog.log("wall_upgraded", "currency" to key, "walls" to count, "toLevel" to (level + 1), "cost" to total)
        return WallPhaseOutcome.Bought(order.currency, count, total)
    }

    /**
     * Reads the dialog, writes the pending marker and sends the confirmation, and returns the
     * marker once it went out. One refused or held before it went out clears the marker: after a
     * pause the phase ends as paused, and with no pause the dialog is read again, [STALE_RETRIES]
     * times in all, since nothing was pressed and the dialog is still up. The UO-21 review
     * (15 October) found a hold with no pause possible: the single-wall dialog read HOME on 108 of
     * its 109 frames of 20 September and UNKNOWN on one, and a look that compared the state ended
     * the phase as paused.
     */
    private suspend fun confirm(
        order: WallPhaseOrder, key: String, level: Int, balance: Long, total: Long, count: Int, expected: Long
    ): PendingWallPurchase {
        repeat(STALE_RETRIES) { read ->
            if (read > 0) device.wait(PANEL_GAP_MS)
            val confirmation = readConfirmation(order, key, level, balance, total, count)
            val marker = PendingWallPurchase(order.accountId, order.currency, count, total, expected, balance, device.now())
            try {
                store.markPending(marker)
            } catch (e: IOException) {
                refuse(WallPhaseFailure.MARKER_WRITE_FAILED, "the pending marker could not be written: ${e.message}")
            } catch (e: IllegalStateException) {
                refuse(WallPhaseFailure.PENDING_PURCHASE, "a marker appeared while the dialog was open")
            }

            try {
                device.tapConfirmation(confirmation.target)
                return marker
            } catch (e: CancellationException) {
                AppLog.log("wall_purchase_outcome_unknown", "stage" to "dispatch", "currency" to key, "cost" to total, "walls" to count)
                throw e
            } catch (e: ConfirmationNotSent) {
                // Refused or held before it went out: nothing was bought, so the marker goes.
                if (!store.clearProven(marker)) AppLog.log("wall_marker_not_cleared", "account" to order.accountId)
                AppLog.log("wall_confirmation_not_sent", "currency" to key, "cost" to total, "walls" to count,
                    "reason" to e.cause?.message, "paused" to e.paused, "read" to read + 1)
                if (e.paused) refuse(WallPhaseFailure.PAUSED, "the confirmation was not sent")
            } catch (e: Exception) {
                throw unresolvedStop(e, "dispatch", key, total, count)
            }
        }
        refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "the confirmation was held on $STALE_RETRIES reads with no pause")
    }

    /** Built once per failure. The reason never carries the cause's text, which could read as recoverable. */
    private fun unresolvedStop(cause: Throwable?, stage: String, key: String, total: Long, count: Int): StopException {
        AppLog.log(
            "wall_purchase_outcome_unknown", "stage" to stage, "currency" to key, "cost" to total,
            "walls" to count, "cause" to (cause?.message ?: "none")
        )
        return StopException(UNRESOLVED, cause).also { unresolved = it }
    }

    /**
     * Closes "All builders are busy!" if a confirmation opened it, and says whether it was there.
     * Its green button finishes a building for gems and is never pressed.
     */
    private suspend fun closeBuildersBusy(key: String, total: Long, count: Int): Boolean {
        val obs = look().observation
        if (!Builders.isBusyDialog(obs.boxes, viewport)) return false
        AppLog.log("wall_builders_busy", "currency" to key, "cost" to total, "walls" to count,
            "closeAt" to (Builders.closeTap(obs.boxes, viewport)?.let { "(${it.x}, ${it.y})" } ?: "back"),
            "text" to obs.rawText.take(200))
        closeBusyDialog("after_confirm")
        return true
    }

    /**
     * One press to close the busy dialog, sent only if a fresh frame still shows it: a frame
     * caught while the dialog fades, or after an earlier press worked, has no dialog to close and
     * a tap there lands on the village. The X read off that frame is pressed once and never
     * re-sent after a pause; without an X, Back closes it instead of a tap at a fixed corner.
     * Returns whether the dialog was still up.
     */
    private suspend fun closeBusyDialog(where: String): Boolean {
        val obs = look().observation
        if (!Builders.isBusyDialog(obs.boxes, viewport)) {
            AppLog.log("wall_builders_busy_gone", "where" to where)
            return false
        }
        // A pause since that frame makes it stale; the caller reads the screen again.
        if (device.checkpoint()) return true
        val x = Builders.closeTap(obs.boxes, viewport)
        val sent = if (x != null) {
            try {
                device.tapButtonOnce(x)
                true
            } catch (e: StopException) {
                // Held or refused before it went out, the X was not sent and the dialog is still
                // up for the next look to close. In the UO-21 review on 15 October a held X after a
                // confirmation reached the verify step's catch and stopped the run as unproven.
                if (!TapAfterHold.isHeld(e) && e.message != TapAfterHold.REFUSED) throw e
                false
            }
        } else {
            device.back()
        }
        AppLog.log("wall_builders_busy_close", "where" to where,
            "by" to (x?.let { "x (${it.x}, ${it.y})" } ?: "back"), "sent" to sent)
        device.wait(HOME_STEP_MS)
        return true
    }

    /**
     * After the busy dialog the purchase did not happen, and the balance has to say so: unchanged
     * from before the confirmation clears the marker and ends the phase without a builder. Any
     * other reading, a drop included, is the unproven path and stops the run.
     */
    private suspend fun noBuilderAfterConfirm(
        marker: PendingWallPurchase, key: String, balance: Long, total: Long, count: Int
    ): WallPhaseOutcome {
        val settled = try {
            device.settledBalance(key, balance, balance)
        } catch (e: CancellationException) {
            AppLog.log("wall_purchase_outcome_unknown", "stage" to "builders_busy", "currency" to key, "cost" to total, "walls" to count)
            throw e
        } catch (e: Exception) {
            throw unresolvedStop(e, "builders_busy", key, total, count)
        }
        if (settled != balance) {
            AppLog.log("wall_spend_unverified", "currency" to key, "cost" to total, "expected" to balance,
                "settled" to settled, "stage" to "builders_busy")
            throw unresolvedStop(null, "builders_busy", key, total, count)
        }
        if (!store.clearProven(marker)) AppLog.log("wall_marker_not_cleared", "account" to marker.accountId)
        AppLog.log("wall_phase_no_builder", "currency" to key, "balance" to settled, "walls" to count)
        return WallPhaseOutcome.BuildersBusy
    }

    /**
     * The dialog Upgrade opened, checked for exactly the selection that was priced, or refused.
     * A pause since the frame was read means the frame proves nothing, so it is read again.
     */
    private suspend fun readConfirmation(
        order: WallPhaseOrder, key: String, level: Int, balance: Long, expected: Long, count: Int
    ): Confirmation {
        repeat(STALE_RETRIES) { attempt ->
            val obs = look().observation
            val raw = obs.rawText

            if (Policy.spendsPremium(WallPanel.withoutGoldPassNote(raw))) {
                AppLog.log("premium_dialog_refused", "where" to "wall_confirmation", "text" to raw.take(200))
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "premium spend dialog: ${raw.take(120)}")
            }
            if (WallPanel.isInsufficientFunds(raw)) {
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "insufficient funds dialog: ${raw.take(120)}")
            }
            val confirmBox = WallPanel.findConfirmButton(obs.boxes)
            val named = WallPanel.namedPrice(raw, key) ?: WallPanel.priceOnButton(obs.boxes, confirmBox)
            if (named == null) {
                AppLog.log("wall_confirmation_unpriced", "attempt" to attempt + 1, "confirm" to confirmBox?.text,
                    "boxes" to obs.boxes.joinToString(" / ") { "${it.text}@${it.left},${it.top}" }.take(600))
                if (attempt < STALE_RETRIES - 1) {
                    device.wait(PANEL_GAP_MS)
                    return@repeat
                }
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "no price on the dialog: ${raw.take(120)}")
            }

            val target = WallPanel.targetLevel(obs.boxes.joinToString("\n") { it.text })
            val bulk = raw.contains("selected walls", ignoreCase = true) && WallPanel.namesCurrency(raw, key)
            if ((!bulk && target == null) || raw.contains("gem", ignoreCase = true) || raw.contains("dark", ignoreCase = true)) {
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "not a $key wall dialog: ${raw.take(120)}")
            }
            if (target != null && (target > order.maxWallLevel || target != level + 1)) {
                AppLog.log("wall_confirmation_reread", "attempt" to attempt + 1,
                    "target" to target, "expected" to level + 1)
                if (attempt < STALE_RETRIES - 1) {
                    device.wait(PANEL_GAP_MS)
                    return@repeat
                }
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "the dialog buys level $target, expected ${level + 1} within ${order.maxWallLevel}")
            }
            if (WallPanel.isBlocked(raw)) refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "the upgrade is blocked: ${raw.take(120)}")
            if (named != expected) {
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "the dialog says $named, the panel said $expected")
            }
            // A discounted dialog may draw the full price struck out beside the paid one. When it
            // does, it must be the full price of this level for this many walls.
            val struck = WallPanel.struckPrice(obs.boxes, confirmBox, named)
            if (struck != null && (struck % count != 0L ||
                    WallPanel.wallLevel(level, named / count, struck / count) != level)) {
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED,
                    "the struck-out price $struck is not the full price of $count level $level walls")
            }
            if (!Policy.permittedUpgrade(key, balance, named, order.reserve)) {
                refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "$named would breach the reserve ${order.reserve}")
            }
            val pressAt = confirmBox?.center
                ?: refuse(WallPhaseFailure.CONFIRMATION_REFUSED, "no accept button on the dialog")

            if (device.checkpoint()) return@repeat
            AppLog.log("wall_confirmation_agreed", "currency" to key, "cost" to named, "level" to target,
                "fromLevel" to level, "struck" to struck, "confirmAt" to "(${pressAt.x}, ${pressAt.y})")
            return Confirmation(pressAt)
        }
        refuse(WallPhaseFailure.PAUSED, "paused repeatedly while the confirmation was open")
    }

    /**
     * Whether the game shows the purchase took: the panel at the next level, or no panel at all.
     * A confirm or blocked dialog is never evidence. The balance has already been proven by the
     * caller; this is the second witness.
     *
     * Insufficient-funds words are read across the whole screen with the gaps between boxes
     * removed, so village labels can spell "gem" or "magic" by accident. They only keep a frame
     * from counting as clear; they no longer veto a panel titled at the next level. On 5 October
     * a balance-proven elixir purchase on Sweat was stopped as unproven while the Level 17 panel
     * stood on screen, and nothing in the log said which frame refused it.
     */
    private suspend fun nextLevelShown(level: Int): Boolean {
        var clear = 0
        for (i in 0 until EVIDENCE_FRAMES) {
            val obs = look().observation
            val confirm = WallPanel.findConfirmButton(obs.boxes)
            val blocked = WallPanel.isBlocked(obs.rawText)
            val short = WallPanel.isInsufficientFunds(obs.rawText)
            // A wall raised to the cap has nothing left to buy, so its panel is the title over
            // Info and Select Row with no Upgrade button and is not a purchase panel at all. At
            // 04:34 on 30 September that Level 19 panel sat on screen after a proven spend and
            // the purchase was stopped as unproven. The title in the panel band is the witness.
            val title = WallPanel.parseTitle(obs.boxes, viewport)
            val panel = WallPanel.isPanel(obs.boxes, viewport)
            AppLog.log("wall_evidence_frame", "frame" to i + 1, "state" to obs.state.name,
                "title" to title?.level, "expected" to level + 1, "panel" to panel,
                "confirm" to confirm?.text, "blocked" to blocked, "insufficient" to short,
                "band" to WallPanel.bandText(obs.boxes, viewport))
            if (confirm != null || blocked) return false
            if (title?.level == level + 1) return true
            if (title != null || panel || short) {
                clear = 0
            } else if (obs.state == ScreenState.HOME) {
                if (++clear >= 2) return true
            } else {
                clear = 0
            }
            if (i < EVIDENCE_FRAMES - 1) device.wait(EVIDENCE_GAP_MS)
        }
        AppLog.log("wall_evidence_missing", "expected" to level + 1, "frames" to EVIDENCE_FRAMES)
        return false
    }

    // --- getting home --------------------------------------------------------------------------

    /**
     * Closes whatever is open and waits for two clear HOME frames. It never presses an accept
     * button: a dialog is cancelled, a list is closed with its counter, a panel with empty grass,
     * the gem store, the attack menu and the army screen with Back. A battle screen gets nothing.
     * Throws when the village cannot be confirmed, so the run ends instead of farming on a screen
     * nobody looked at.
     */
    private suspend fun confirmHome(neutral: Point) {
        val deadline = device.now() + WallRunner.RETURN_HOME_TIMEOUT_MS
        var clear = 0
        var lastState = ScreenState.UNKNOWN
        var listFrames = 0
        var shopBacks = 0
        while (device.now() < deadline) {
            val checked = device.checkpoint()
            val frame = device.capture()
            val paused = checked || frame.paused
            val obs = frame.observation
            lastState = obs.state
            val listOpen = UpgradeListReader.read(obs.boxes, viewport, frame::pixels).hasListEvidence
            // List frames in a row, counted afresh after a pause or any frame that is not the list.
            val listStreak = if (paused) 0 else listFrames
            listFrames = 0
            when {
                // The gem store or a priced offer is only ever left with Back, a few times at most:
                // on 15 October at 05:31 UTC an Attack tap landed on the store under its $0.99 card.
                Policy.showsShop(obs.rawText) -> {
                    clear = 0
                    if (shopBacks < SHOP_BACKS) {
                        AppLog.log("gem_shop_left", "where" to "wall_home", "text" to obs.rawText.take(200))
                        if (device.back("gem_shop")) shopBacks++
                    }
                    device.wait(HOME_STEP_MS)
                }
                // Its gem button is never pressed, and a tap on open ground may not close it.
                Builders.isBusyDialog(obs.boxes, viewport) -> {
                    clear = 0
                    closeBusyDialog("confirm_home")
                }
                showsDialog(obs) -> {
                    clear = 0
                    val cancel = WallPanel.findCancelButton(obs.boxes)?.center
                    if (cancel != null) act(cancel) else actPlain(neutral)
                    device.wait(HOME_STEP_MS)
                }
                // The attack menu and the army screen close with Back. Open ground there is a card
                // or a boost, so neither gets the neutral tap.
                obs.state == ScreenState.MENU || obs.state == ScreenState.ARMY -> {
                    clear = 0
                    device.back("wall_home")
                    device.wait(HOME_STEP_MS)
                }
                // Matchmaking, a battle, its result or its reward: nothing of this phase's belongs
                // there, so nothing is pressed and the deadline ends the run if it stays.
                obs.state != ScreenState.HOME && obs.state != ScreenState.UNKNOWN && obs.state != ScreenState.WALL -> {
                    clear = 0
                    device.wait(HOME_STEP_MS)
                }
                // A panel's price boxes can read as list evidence, so a panel is judged first.
                WallPanel.isPanel(obs.boxes, viewport) -> {
                    clear = 0
                    actPlain(neutral)
                    device.wait(HOME_STEP_MS)
                }
                // Two frames in a row, as the navigator asks: one frame of the closing animation
                // would put the X on the village's gold bar.
                listOpen -> {
                    clear = 0
                    listFrames = listStreak + 1
                    if (listFrames >= 2) {
                        actPlain(upgradeListClosePoint(viewport))
                        listFrames = 0
                    }
                    device.wait(HOME_STEP_MS)
                }
                // A wall raised to the cap keeps its title over Info and Select Row, with nothing
                // to buy, and reads as HOME: at 16:18 UTC on 14 October Blood's elixir phase went
                // home with "Wall (Level 19)" still selected. A title or Select Row in the band is
                // a selection; the title alone misreads, as "Walf (Level 19)" did at 07:06 that day.
                obs.state == ScreenState.WALL ||
                    WallPanel.parseTitle(obs.boxes, viewport) != null ||
                    WallPanel.WallButton.SELECT_ROW in WallPanel.buttons(obs.boxes, viewport) -> {
                    clear = 0
                    actPlain(neutral)
                    device.wait(HOME_STEP_MS)
                }
                obs.state == ScreenState.HOME -> {
                    if (++clear >= 2) return
                    device.wait(HOME_CLEAR_GAP_MS)
                }
                else -> {
                    // Nothing readable is the game loading; it is waited out without a press.
                    clear = 0
                    device.wait(HOME_STEP_MS)
                }
            }
        }
        AppLog.log("wall_village_timeout", "lastState" to lastState.name)
        throw StopException(HOME_NOT_CONFIRMED)
    }

    /** A purchase dialog reads as HOME, with the village showing around it. */
    private fun showsDialog(obs: local.clashfarmer.recognition.Observation): Boolean {
        if (WallPanel.findConfirmButton(obs.boxes) != null) return true
        val letters = obs.rawText.lowercase().replace(Regex("[^a-z]"), "")
        return letters.contains("enou") || letters.contains("donthave") || letters.contains("notenough")
    }
}
