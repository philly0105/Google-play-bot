package local.clashfarmer.core

import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListReader
import local.clashfarmer.recognition.builderCounterPoint
import local.clashfarmer.recognition.upgradeListClosePoint
import local.clashfarmer.recognition.upgradeListSwipe
import java.util.Locale

/** One capture: the recognised text, and the pixels behind it for icon and price colour. */
interface UpgradeListFrame {
    val observation: Observation
    /** The pixels under [region], row by row, on this frame and no other. */
    fun pixels(region: Region): IntArray
    /** A pause came while this frame was taken or read, so it proves nothing about the one before. */
    val paused: Boolean get() = false
}

/**
 * What the navigator needs of the phone. Every call that sends a gesture or waits goes through
 * here, so a test drives the navigator with scripted frames and a clock that only moves when it
 * waits.
 */
interface UpgradeListDevice {
    suspend fun capture(): UpgradeListFrame
    suspend fun tap(point: Point)
    /** [holdMs] keeps the finger at [to] that long before it lifts, so the list should not fling on. */
    suspend fun swipe(from: Point, to: Point, durationMs: Long, holdMs: Long)
    suspend fun wait(ms: Long)
    fun now(): Long

    /**
     * Called before every capture and gesture. Throws [StopException] when the run has stopped,
     * and suspends while it is paused; returns true if it did, because a frame read before the
     * pause is no longer evidence of what is on the screen.
     */
    suspend fun checkpoint(): Boolean
}

/** A Wall row as the list shows it. The price is a hint for discovery; the panel has the real one. */
data class WallCandidate(val name: String, val count: Int, val listPrice: Long, val currency: String)

enum class UpgradeListFailure { LIST_NOT_OPEN, WRONG_LIST, UNREADABLE, PANEL_NOT_CONFIRMED, BUDGET_EXCEEDED }

sealed interface UpgradeListResult {
    /** A wall of this row's level is selected, its panel open with the list closed. */
    data class Selected(val candidate: WallCandidate, val panelLevel: Int) : UpgradeListResult

    /** The whole list was read and no Wall row was both affordable and not capped. */
    data class Empty(val scrolls: Int, val wallRowsSeen: Int) : UpgradeListResult

    /** The list could not be read or driven; nothing was bought and nothing was concluded about walls. */
    data class Failed(val reason: UpgradeListFailure, val detail: String) : UpgradeListResult
}

/**
 * [maxScrolls] bounds the top reset and, separately, the scan, so a list left near its bottom does not
 * spend the scan's gestures (Sweat, 1 October). [maxMillis] bounds the whole phase.
 */
data class UpgradeListLimits(val maxScrolls: Int = 20, val maxMillis: Long = 180_000L)

/**
 * Finds a Wall row in the builder's upgrade list and taps it. It never presses Upgrade, Select
 * Row or a confirmation.
 *
 * The list is category ordered, not price ordered, so the whole list is scanned from the top and
 * the first Wall row in scan order that the caller can afford and has not capped wins. A row is
 * tapped only after two settled frames agree on it, on the coordinates of the second. The list
 * stays open over the wall panel after that tap, so the navigator closes it with its X, but only
 * while a frame still shows the list: with the list closed, the X's spot is the village's.
 *
 * Since Clash 18.647 the list is a panel down the right edge. A Wall row is a card among the
 * gold rows of "Other upgrades" ("Wall x 23", 8 000 000), and an account whose walls are all at
 * the top level has none, so its scan ends [UpgradeListResult.Empty] with no Wall row seen
 * (Blood and Sweat, 14 October; audit-evidence/clash-18647/clash-18647-notes.md, Round 3).
 */
class UpgradeListNavigator(
    private val device: UpgradeListDevice,
    private val viewport: Viewport,
    private val limits: UpgradeListLimits = UpgradeListLimits()
) {

    private companion object {
        const val OPEN_SETTLE_MS = 1_200L
        const val OPEN_CAPTURES = 3
        /** Frames read for the village before the counter tap: the first and two more. */
        const val VILLAGE_LOOKS = 3
        const val SWIPE_MS = 500L
        /**
         * The finger rests at the swipe's end this long before it lifts, so the list should stop
         * where the drag left it, about the drag's 250 px on the 18.647 panel, four cards (not yet
         * seen on the phone: wall_list_page's overlap will show it). A finger that
         * lifted moving flung the list on by a distance that changed from swipe to swipe (pages of
         * 15 October shared 0 to 3 titles with the page before), and a frame taken while it
         * coasted read rows where they no longer were.
         */
        const val HOLD_MS = 300L
        /** As it was while the list flung; no run has yet shown how soon a held list is still. */
        const val SCROLL_SETTLE_MS = 900L
        const val REREAD_GAP_MS = 500L
        const val REREADS = 2
        const val CONFIRM_GAP_MS = 400L
        const val CONFIRM_FRAMES = 4
        const val TAP_SETTLE_MS = 1_200L
        const val CLOSE_ATTEMPTS = 2
        const val CLOSE_CAPTURES = 8
        const val PANEL_GAP_MS = 700L
        /** Scrolls that leave the page as it was before it is the bottom (or top). */
        const val UNCHANGED_LIMIT = 2
    }


    private class Failure(val result: UpgradeListResult.Failed) : Exception()

    /**
     * No two frames agreed on a pick, and the scan goes on from [last], the last of them. A pause
     * waited out just before the tap goes with it in [pauseOwed], so no frame of the scan's
     * agrees with [last] across that pause; a paused frame with no pick on it agrees with nothing
     * before it either, in select or in the retry.
     */
    private class PickLost(val last: Seen) : Exception()

    /**
     * One frame read. [paused] means a pause came between the last frame and this one, so it
     * proves nothing about what the previous frame showed.
     */
    private class Seen(val frame: UpgradeListFrame, val reading: UpgradeListReader.Reading, val paused: Boolean)

    /** What a page holds: the first Wall row worth taking, or none. */
    private class Verdict(val pick: UpgradeListReader.Row?)

    private var scrolls = 0
    private var topScrolls = 0
    private var wallRowsSeen = 0
    private var scanning = false
    private var startedAt = 0L
    /** Whether this scan has let a pick go once already; a second is a failure. */
    private var pickLost = false
    /** A pause the checkpoint before a row tap waited out, which the next frame looked at counts. */
    private var pauseOwed = false

    /**
     * [spendable] is balance minus reserve per currency ("gold", "elixir"); a currency not in it
     * is not spent. [cappedPrices] are list prices already found to be at the level cap.
     */
    suspend fun findAndSelectWall(spendable: Map<String, Long>, cappedPrices: Set<Long>): UpgradeListResult {
        scrolls = 0
        topScrolls = 0
        wallRowsSeen = 0
        scanning = false
        pickLost = false
        pauseOwed = false
        startedAt = device.now()
        return try {
            openList()
            val top = returnToTop()
            topScrolls = scrolls
            scrolls = 0
            scanning = true
            scan(top, spendable, cappedPrices)
        } catch (f: Failure) {
            f.result
        }
    }

    private fun fail(reason: UpgradeListFailure, detail: String): Nothing =
        throw Failure(UpgradeListResult.Failed(reason, detail))

    private suspend fun look(alreadyPaused: Boolean = false): Seen {
        val paused = device.checkpoint() || alreadyPaused || pauseOwed
        pauseOwed = false
        val frame = device.capture()
        return Seen(frame, UpgradeListReader.read(frame.observation.boxes, viewport, frame::pixels), paused || frame.paused)
    }

    private suspend fun openList() {
        var seen = look()
        // A pause before the tap leaves the frame stale, so it is read again.
        while (!seen.reading.hasListEvidence && device.checkpoint()) seen = look(alreadyPaused = true)
        // The counter is tapped only on a frame that reads as the village with no pause before or
        // during it, on every path; any other frame is read again, [VILLAGE_LOOKS] in all. On
        // another screen the counter's spot is something else: on 15 October at 05:31 UTC a tap
        // decided on the village landed on the gem store under its $0.99 card, and classify reads
        // the store as UNKNOWN, never HOME. The counter's own text is not asked for: ML Kit missed
        // "1/6 0/1" on all 7 of Blood's plain village reads from 16:17 UTC on 14 October to 03:28
        // on 15 October, though it was drawn, and each of those reads opened the list (UO-21
        // review, 15 October).
        var looks = 1
        while (!seen.reading.hasListEvidence && (seen.paused || seen.frame.observation.state != ScreenState.HOME)) {
            if (looks++ >= VILLAGE_LOOKS) {
                fail(UpgradeListFailure.LIST_NOT_OPEN, "the village was not read in $VILLAGE_LOOKS frames " +
                    "(state ${seen.frame.observation.state}${if (seen.paused) ", after a pause" else ""})")
            }
            device.wait(REREAD_GAP_MS)
            seen = look()
        }
        if (seen.reading.hasListEvidence) return
        device.tap(builderCounterPoint(viewport))
        repeat(OPEN_CAPTURES) {
            device.wait(OPEN_SETTLE_MS)
            val reading = look().reading
            if (reading.hasListEvidence) return
            if (reading.laboratoryShowing) {
                fail(UpgradeListFailure.WRONG_LIST, "the laboratory's list is showing, not the builder's")
            }
        }
        fail(UpgradeListFailure.LIST_NOT_OPEN, "no list header or priced rows after tapping the builder counter")
    }

    /** Swipes once and returns what the list shows afterwards. */
    private suspend fun scroll(towardsBottom: Boolean): Seen {
        if (scrolls >= limits.maxScrolls) {
            fail(UpgradeListFailure.BUDGET_EXCEEDED, "${limits.maxScrolls} scroll gestures used " +
                if (scanning) "scanning ($topScrolls to reach the top, $wallRowsSeen distinct Wall rows seen, " +
                    "last page match ${"%.2f".format(lastMatch)}, titles common ${lastAdvance?.common} " +
                    "revealed ${lastAdvance?.revealed}, paused=$lastPaused)"
                else "returning to the top")
        }
        if (device.now() - startedAt >= limits.maxMillis) {
            fail(UpgradeListFailure.BUDGET_EXCEEDED, "${limits.maxMillis} ms used")
        }
        // The last frame showed the list. After a pause it is read again, and the swipe waits for
        // a frame that still does: with the list closed, the swipe's top end is by the shield.
        var paused = false
        while (device.checkpoint()) {
            paused = true
            if (!look(alreadyPaused = true).reading.hasListEvidence) {
                fail(UpgradeListFailure.LIST_NOT_OPEN, "the list was gone after a pause")
            }
        }
        val (from, to) = upgradeListSwipe(viewport, towardsBottom)
        device.swipe(from, to, SWIPE_MS, HOLD_MS)
        scrolls++
        device.wait(SCROLL_SETTLE_MS)
        val next = look(paused)
        if (!next.reading.hasListEvidence) fail(UpgradeListFailure.LIST_NOT_OPEN, "the list was gone after a scroll: " +
            next.frame.observation.boxes.joinToString(" / ") { "${it.text}@${it.center.x},${it.center.y}" }.take(600))
        return next
    }

    /** Scrolls back until the list's first header shows, or the page stops moving. */
    private suspend fun returnToTop(): Seen {
        var seen = look()
        logPage("top", seen, null, null, 0)
        // Every swipe follows a frame that shows the list; this is the first one's.
        if (!seen.reading.hasListEvidence) fail(UpgradeListFailure.LIST_NOT_OPEN, "the list closed before the first scroll")
        var unchanged = 0
        while (!seen.reading.atTop && unchanged < UNCHANGED_LIMIT) {
            val next = scroll(towardsBottom = false)
            unchanged = if (!next.paused && UpgradeListReader.samePage(seen.reading, next.reading)) unchanged + 1 else 0
            seen = next
            logPage("top", seen, null, null, unchanged)
        }
        return seen
    }

    /**
     * Rows top to bottom: a Wall row that will not read cleanly makes the page unreadable (null),
     * an unaffordable or capped one is skipped, and the first that is neither is the pick.
     */
    /** How the last two scanned pages compared, named in a budget failure. */
    private var lastMatch = 0.0
    private var lastPaused = false
    private var lastAdvance: UpgradeListReader.Advance? = null

    /** The last Wall row that would not read, named in the failure so a live log shows why. */
    private var unreadable: UpgradeListReader.Row? = null

    private fun verdict(
        reading: UpgradeListReader.Reading, spendable: Map<String, Long>, capped: Set<Long>, seen: MutableSet<String>?
    ): Verdict? {
        for (row in reading.rows) {
            if (row.clipped || row.kind == UpgradeListReader.Kind.OTHER) continue
            if (!row.readable) { unreadable = row; return null }
            val price = (row.amount as UpgradeListReader.Amount.Exact).value
            seen?.add("${row.count}|$price|${row.currency}")
            if (price in capped) continue
            if (spendable.none { (currency, room) ->
                    price <= room && (currency != row.currency || row.payable != false)
                }) continue
            return Verdict(row)
        }
        return Verdict(null)
    }

    /** A page's verdict, rereading the same position while a Wall row will not read cleanly. */
    private suspend fun evaluate(
        first: Seen, spendable: Map<String, Long>, capped: Set<Long>, seen: MutableSet<String>
    ): Verdict {
        var current = first
        for (attempt in 0..REREADS) {
            verdict(current.reading, spendable, capped, seen)?.let { return it }
            if (attempt == REREADS) break
            device.wait(REREAD_GAP_MS)
            current = look()
            if (!current.reading.hasListEvidence) fail(UpgradeListFailure.LIST_NOT_OPEN, "the list closed during a reread")
        }
        fail(UpgradeListFailure.UNREADABLE, "a Wall row's price, count or currency would not read: " +
            unreadable?.let { "${it.kind} '${it.name}' count=${it.count} amount=${it.amount} currency=${it.currency}" })
    }

    /**
     * [page] is the furthest page down so far. A scroll that shows no row below it, by its rows'
     * titles in order or by every line where it was, has not moved; the first is what ends a list
     * whose last page bounces a row either way between frames (Sweat, 8 October).
     *
     * Once a scan, a pick that no two frames agree on is let go and the scan reads on from the
     * last of those frames: a row gone off the bottom edge comes back on the next swipe, read at
     * rest (Blood, 15 October: "Wall x7" picked at y 582, then four frames without it).
     */
    private suspend fun scan(top: Seen, spendable: Map<String, Long>, capped: Set<Long>): UpgradeListResult {
        val seen = mutableSetOf<String>()
        var page = top
        logPage("scan", page, null, null, 0)
        var verdict = evaluate(page, spendable, capped, seen)
        var unchanged = 0
        while (true) {
            val pick = verdict.pick
            if (pick != null) {
                try {
                    return select(page, pick, spendable, capped)
                } catch (lost: PickLost) {
                    page = lost.last
                    logPage("scan", page, null, null, 0)
                    verdict = evaluate(page, spendable, capped, seen)
                    unchanged = 0
                    continue
                }
            }
            val next = scroll(towardsBottom = true)
            lastMatch = UpgradeListReader.pageMatch(page.reading, next.reading)
            lastPaused = next.paused
            val before = UpgradeListReader.titles(page.reading)
            val after = UpgradeListReader.titles(next.reading)
            val advance = UpgradeListReader.advance(before, after)
            lastAdvance = advance
            val stopped = UpgradeListReader.stopped(advance, before, after) || UpgradeListReader.samePage(page.reading, next.reading)
            unchanged = if (!next.paused && stopped) unchanged + 1 else 0
            logPage("scan", next, advance, lastMatch, unchanged)
            if (unchanged >= UNCHANGED_LIMIT) return UpgradeListResult.Empty(scrolls, seen.size)
            // A page that did not move has been read already, and is no further down than the last.
            if (unchanged == 0) {
                verdict = evaluate(next, spendable, capped, seen)
                page = next
            }
            wallRowsSeen = seen.size
        }
    }

    /** One event per page read, so a list that will not end can be read back off the log. */
    private fun logPage(phase: String, seen: Seen, advance: UpgradeListReader.Advance?, match: Double?, unchanged: Int) {
        AppLog.log("wall_list_page", "phase" to phase, "scroll" to scrolls, "atTop" to seen.reading.atTop,
            "paused" to seen.paused, "common" to advance?.common, "revealed" to advance?.revealed,
            "match" to match?.let { "%.2f".format(it) }, "unchanged" to unchanged,
            "rows" to seen.reading.rows.size,
            "titles" to seen.reading.rows.joinToString(" / ") { row ->
                (if (row.clipped) "~" else "") + row.name.take(24) + (row.priceText?.let { " $it" } ?: "")
            }.take(600))
    }

    /**
     * One event per frame looked at to confirm a pick: the pick on it, if any, and how much of
     * [from], the page it was picked on, is still where it was (1.00 for a list that held still).
     * Rows carry their y, so a row that moved off can be told from one read as something else.
     */
    private fun logConfirm(frame: Int, from: Seen, seen: Seen, agreed: UpgradeListReader.Row, pick: UpgradeListReader.Row?) {
        AppLog.log("wall_list_confirm", "frame" to frame, "paused" to seen.paused,
            "agreed" to "${agreed.name}@${agreed.centerY}", "pick" to pick?.let { "${it.name}@${it.centerY}" },
            "match" to "%.2f".format(UpgradeListReader.pageMatch(from.reading, seen.reading)),
            "rows" to seen.reading.rows.joinToString(" / ") { row ->
                (if (row.clipped) "~" else "") + row.name.take(16) + "@" + row.centerY +
                    if (row.kind != UpgradeListReader.Kind.OTHER) " ${row.kind}" else ""
            }.take(600))
    }

    /**
     * Two frames must agree on the pick; the second one's coordinates are the ones tapped. [from]
     * is the page the pick was read on. When no two agree, the first time in a scan throws
     * [PickLost] and the second fails.
     */
    private suspend fun select(
        from: Seen, firstPick: UpgradeListReader.Row, spendable: Map<String, Long>, capped: Set<Long>
    ): UpgradeListResult {
        var agreed = firstPick
        // A pause since [agreed] was read: it is no frame to agree with once the list may have moved.
        var stale = false
        var missed: UpgradeListReader.Reading? = null
        var last = from
        repeat(CONFIRM_FRAMES) { index ->
            device.wait(CONFIRM_GAP_MS)
            val next = look()
            if (!next.reading.hasListEvidence) fail(UpgradeListFailure.LIST_NOT_OPEN, "the list closed before the row was tapped")
            last = next
            // A frame without the pick is not agreement; the next one is looked at instead, and
            // after a pause it has nothing from before the pause to agree with.
            val pick = verdict(next.reading, spendable, capped, null)?.pick
            logConfirm(index + 1, from, next, agreed, pick)
            if (pick == null) {
                missed = next.reading
                if (next.paused) stale = true
                return@repeat
            }
            // A pause between this frame and the tap would leave the tap on a stale frame, and
            // the next frame, here or on the scan a lost pick goes back to, comes after it.
            if (!next.paused && !stale && UpgradeListReader.sameRow(agreed, pick)) {
                if (!device.checkpoint()) {
                    device.tap(pick.tap)
                    return closeListAndConfirm(pick)
                }
                pauseOwed = true
            }
            agreed = pick
            stale = false
        }
        if (!pickLost) {
            pickLost = true
            AppLog.log("wall_list_pick_lost", "picked" to "${firstPick.name}@${firstPick.centerY}",
                "missed" to (missed != null), "rows" to last.reading.rows.size)
            throw PickLost(last)
        }
        val lastMiss = missed
        if (lastMiss != null) {
            val walls = lastMiss.rows.filter { it.kind != UpgradeListReader.Kind.OTHER }
            fail(UpgradeListFailure.UNREADABLE, "the Wall row was not readable on the next frame " +
                "(picked ${firstPick.name} at y ${firstPick.centerY}; then ${lastMiss.rows.size} rows, " +
                "wall rows ${walls.map { "${it.name}|${it.amount}|${it.currency}|clipped=${it.clipped}" }})")
        }
        fail(UpgradeListFailure.UNREADABLE, "the Wall row would not hold still for two frames")
    }

    private suspend fun closeListAndConfirm(row: UpgradeListReader.Row): UpgradeListResult {
        val candidate = WallCandidate(
            row.name, row.count!!, (row.amount as UpgradeListReader.Amount.Exact).value, row.currency!!
        )
        device.wait(TAP_SETTLE_MS)
        var closes = 0
        var listFrames = 0
        var panelFrames = 0
        repeat(CLOSE_CAPTURES) {
            val seen = look()
            val title = WallPanel.parseTitle(seen.frame.observation.boxes, viewport)
            // The panel's own totals sit flush right inside the popup's region and pass for list
            // rows (Sweat, 1 and 3 October); only a header proves the list is over the panel. Since
            // 18.647 a page can show no header with the list still open over the panel (Blood,
            // 14 October), so the list's own title has to be gone as well. A frame whose title did
            // not read looks the same, so it takes the X having been pressed, or a second such
            // frame in a row, and no card of the list's own column in view either.
            if (title != null && upgradeListGone(seen.reading, seen.frame.observation.boxes, viewport)) {
                panelFrames++
                if (closes > 0 || panelFrames >= 2) return UpgradeListResult.Selected(candidate, title.level)
                listFrames = 0
                device.wait(PANEL_GAP_MS)
                return@repeat
            }
            panelFrames = 0
            if (seen.reading.hasListEvidence) {
                if (closes >= CLOSE_ATTEMPTS) {
                    fail(UpgradeListFailure.PANEL_NOT_CONFIRMED, "the list would not close (title $title, " +
                        "header ${seen.reading.headerShowing}, text ${seen.frame.observation.boxes.joinToString(" ") { it.text }.take(300)})")
                }
                // Only ever tap the X while two frames in a row show the list: one frame of the
                // closing animation is not the list, and the X's spot on the village is not empty.
                listFrames++
                if (listFrames >= 2 && !seen.paused && !device.checkpoint()) {
                    device.tap(upgradeListClosePoint(viewport))
                    closes++
                    listFrames = 0
                    device.wait(TAP_SETTLE_MS)
                } else {
                    device.wait(PANEL_GAP_MS)
                }
                return@repeat
            }
            listFrames = 0
            device.wait(PANEL_GAP_MS)
        }
        fail(UpgradeListFailure.PANEL_NOT_CONFIRMED, "no Wall panel title on a frame without the list")
    }
}

private val LIST_WALL_NAME = Regex("a[l1i|!]{1,2}\\s*[x×]\\s*[0-9oils]")

/**
 * Whether a card of the 18.647 list's column, right of x 1118, is in view: a Wall row's name, or
 * a price ending flush with the column, right of x 1500 ("8000 000" ends at 1512). The village's
 * numbers in that column end by x 1497, the gold bar's above y 60 and the rest by 1486, and the
 * wall panel draws its prices left of 1060 (01-home, 25-wall-panel, Blood's two home frames).
 * A price is at least [MIN_CARD_PRICE] and not on the counters' line above Build: the builder
 * counter's split half "071@1474,543,1513,563" on ARMY frames and a badge "6@1509,574,1521,591"
 * on the store button passed for cards and cost a wall panel its price pass (UO-18 round 2).
 *
 * On the PC, at 1740x978, the column is x 1272-1740 and y 63-873, and the list's prices end at
 * x 1708-1738 while the village's balances end by 1678 (audit-evidence/spike/geo-list-*.json and
 * geo-home.json), so flush is right of 1694. The phone's 1500 would take every balance for a card.
 * The column and the balances both keep to the right edge ([PcGeometry]), and so does flush.
 */
internal fun upgradeListCardShowing(boxes: List<RecognizedBox>, viewport: Viewport): Boolean {
    val left = PcGeometry.pick(viewport, viewport.left + viewport.width * 1118 / 1600, PcGeometry.x(viewport, 1272, Anchor.RIGHT))
    val flush = PcGeometry.pick(viewport, viewport.left + viewport.width * 1500 / 1600, PcGeometry.x(viewport, 1694, Anchor.RIGHT))
    val top = PcGeometry.pick(viewport, viewport.top + viewport.height * 60 / 720, PcGeometry.y(viewport, 63))
    val bottom = PcGeometry.pick(viewport, viewport.top + viewport.height * 625 / 720, PcGeometry.y(viewport, 873))
    return boxes.any { box ->
        box.center.x >= left && box.center.y in top..bottom &&
            (LIST_WALL_NAME.containsMatchIn(box.text.lowercase(Locale.ROOT)) ||
                box.right >= flush && !Builders.inCorner(box, viewport) &&
                (UpgradeListReader.parseAmount(box.text) as? UpgradeListReader.Amount.Exact)?.value?.let { it >= MIN_CARD_PRICE } == true)
    }
}

/** No card of the list costs under a hundred; a lone number that small is a counter or a badge. */
private const val MIN_CARD_PRICE = 100L

/**
 * Whether the list is gone from a frame: no header, no 18.647 title and no card of its column.
 * [UpgradeListReader.Reading.hasListEvidence] cannot say so on its own, because a wall panel's
 * prices sit flush right inside the old popup's region and pass for list rows.
 */
internal fun upgradeListGone(reading: UpgradeListReader.Reading, boxes: List<RecognizedBox>, viewport: Viewport): Boolean =
    !reading.headerShowing && !reading.builderPanel && !upgradeListCardShowing(boxes, viewport)

/**
 * Whether a frame is the wall panel with the list gone and no dialog over it. Such a frame has
 * its prices read again one label at a time, and only such a frame: read as the list, a bulk
 * panel's elixir price was cut at the old popup's edge, x 985, and came back "80" on all seven
 * rereads (Blood, 02:15 on 15 October).
 */
internal fun wallPanelFrame(reading: UpgradeListReader.Reading, boxes: List<RecognizedBox>, viewport: Viewport): Boolean =
    WallPanel.findConfirmButton(boxes) == null && WallPanel.isPanel(boxes, viewport) &&
        upgradeListGone(reading, boxes, viewport)

/** What [AndroidWallDevice.capture] reads again after a frame's first read: one pass at most. */
internal enum class SecondRead { NONE, LIST_CROP, PRICE_REGIONS }

/**
 * Decided once per frame: a wall panel ([wallPanelFrame]) has its prices read one label at a time,
 * any other frame with list evidence has the popup's crop read again, and no frame gets both.
 */
internal fun secondRead(reading: UpgradeListReader.Reading, boxes: List<RecognizedBox>, viewport: Viewport): SecondRead = when {
    wallPanelFrame(reading, boxes, viewport) -> SecondRead.PRICE_REGIONS
    reading.hasListEvidence -> SecondRead.LIST_CROP
    else -> SecondRead.NONE
}
