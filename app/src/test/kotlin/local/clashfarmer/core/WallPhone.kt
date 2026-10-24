package local.clashfarmer.core

import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListFixture
import local.clashfarmer.recognition.builderCounterPoint
import local.clashfarmer.recognition.upgradeListClosePoint

/**
 * A scripted phone for the wall phase: the builder list, the wall panel in its one-wall and bulk
 * forms, the purchase dialogs and the village, laid out as the September captures show them
 * (1600x720). Time moves only when the phase waits, captures or presses.
 *
 * It presses what the boxes say: a tap is matched against the buttons it drew, so a phase that
 * pressed Select Row, a stale coordinate or a confirmation it was never offered shows in
 * [taps], [selectRowPresses] and [confirmTaps].
 *
 * A wall at panel level N costs, unless [unitOverride] says otherwise (15: 4M, 16: 5M, 17: 5.6M,
 * 18: 8M, 19: 9M), and buying raises it to N+1.
 */
class WallPhone(
    var gold: Long = 40_000_000,
    var elixir: Long = 40_000_000,
    var level: Int = 15,
    /** Where the phone starts: "panel" (as after the row tap), "home" or "list". */
    start: String = "panel",
    var addTenWorks: Boolean = true,
    /** The most walls the game will let the selection grow to. */
    var growthLimit: Int = 100,
    /** A gold total above this is drawn red, whatever the balance. */
    var payableLimit: Long? = null,
    /**
     * "stays" shows the next level, "closes" goes home, "unchanged" keeps the old level, and
     * "maxed" shows the next level as the cap: the title over Info and Select Row, nothing to buy.
     */
    var afterBuy: String = "stays",
    /** True: the "maxed" selection reads as HOME with the band Blood's did at 16:18 UTC on 14 October. */
    var maxedReadsHome: Boolean = false,
    /** The capped title as that HOME frame reads it, when it is not "Wall (Level N)". */
    var maxedTitle: String? = null,
    /** "normal", "insufficient", "gems", "dark", "money" or "wrongprice". */
    var dialogKind: String = "normal",
    /** False: pressing Okay does nothing at all. */
    var okayWorks: Boolean = true,
    /** The title as drawn, when it is not "Wall (Level N)". */
    var titleOverride: String? = null,
    /** The bulk panel's title for a selection of n walls, when it is not "Wall (Level N)x n". */
    var bulkTitle: ((Int) -> String)? = null,
    /**
     * True: the bulk panel's gold and elixir prices read as one box, as on Blood on 15 October.
     * Each icon is drawn where it is, so only a region holding one of them names a currency.
     */
    var mergedPrices: Boolean = false,
    var goldButton: Boolean = true,
    /** False: the one-wall panel has no Upgrade More, as for an account that can afford one wall on 18.647. */
    var upgradeMore: Boolean = true,
    /** How many dialog frames read with no price on the Okay row before one does. */
    var unpricedDialogFrames: Int = 0,
    /** What one wall costs when it is not the fake's table price, such as a Gold Pass discount. */
    var unitOverride: Long? = null,
    /** A struck-out full price drawn beside the single-wall dialog's Okay, as with the Gold Pass. */
    var dialogFullPrice: Long? = null,
    /** What the balance read says for gold, when it is not the balance. */
    var barGold: (() -> Long?)? = null,
    /** What the settle read says, when it is not the real balance. */
    var settleOverride: Long? = null,
    var counterOpens: Boolean = true,
    /** Forever on a screen nothing reads. */
    var stuck: Boolean = false,
    /** Text drawn outside the panel band on every panel and home frame, such as a chat line. */
    var extraText: String? = null,
    /** The builder counter as the top bar draws it beside the face, such as "0/6"; null for none read. */
    var builders: String? = null,
    /** The builder counter by phone time, for a counter that changes between frames; overrides [builders]. */
    var buildersAt: ((Long) -> String?)? = null,
    /**
     * True: Okay on the purchase dialog spends nothing and opens "All builders are busy!" with its
     * gem button, as on Blood at 09:33 UTC on 8 October.
     */
    var buildersBusy: Boolean = false,
    /** False: the busy dialog's red X does not come back as a box. */
    var busyCloseRead: Boolean = true,
    /** Frames the busy dialog is captured on before it fades by itself, as after an earlier close. */
    var busyFrames: Int = Int.MAX_VALUE,
    /**
     * Clash's "Confirm Exit" prompt as read, up from the first frame until Cancel or Back closes
     * it. Its buttons are where the phone draws them, whatever the boxes say: Cancel around
     * (659,462) and Okay around (939,462). Okay quits the game.
     */
    var quitPrompt: List<RecognizedBox>? = null,
    var items: List<UpgradeListFixture.Item> = listOf(
        UpgradeListFixture.Header("Upgrades in progress:", 123),
        UpgradeListFixture.Line("Available!", 161, icon = true),
        UpgradeListFixture.Header("Other upgrades:", 199),
        UpgradeListFixture.gold("Bomb x8", 237, 4_000_000),
        UpgradeListFixture.gold("Wall x80", 275, 5_000_000)
    )
) : WallPanelDevice {

    enum class Panel { NONE, SINGLE, BULK }

    private class Dialog(val boxes: List<RecognizedBox>, val okay: RecognizedBox, val cancel: RecognizedBox)

    var time = 0L
    var count = 1
    var panel = if (start == "panel") Panel.SINGLE else Panel.NONE
    var listOpen = start == "list"
    /** Frames the open list is still captured on before it closes by itself, as a closing animation. */
    var listClosesAfter: Int? = null
    private var dialog: Dialog? = null
    private var dialogTotal = 0L

    val taps = mutableListOf<String>()
    var confirmTaps = 0
    var selectRowPresses = 0
    var upgradePresses = 0
    var gestures = 0
    var captures = 0
    var checkpoints = 0
    /** Whether the builders-busy dialog is up, and what was pressed on it. */
    var busyShowing = false
    var busyCloses = 0
    var gemTaps = 0
    /** Back presses, and those that met no dialog: Back on the bare village asks to quit the game. */
    var backs = 0
    var backsOnVillage = 0
    /** Presses that landed on the village while no dialog was up, such as a blind corner tap. */
    var busyTapsAfterFade = 0
    private var busyCaptures = 0
    /** Presses on the quit prompt's buttons. Okay leaves the game, so nothing reads after it. */
    var quitCancels = 0
    var quitOkays = 0
    private var busyFaded = false
    private var dialogCheckpoints = 0

    /** The nth checkpoint taken while a dialog is open reports a pause. */
    var pauseAtDialogCheckpoint = -1

    /** The first n checkpoints taken while a dialog is open report a pause. */
    var pauseFirst = 0
    var confirmationFailure: Throwable? = null

    /**
     * The next n taps, plain or button, are held as AndroidWallDevice holds one whose look did
     * not find the screen the same: [TapAfterHold.HELD], and nothing pressed.
     */
    var holdTaps = 0

    /** The confirmation is refused before it goes out, as after a pause, and nothing is pressed. */
    var confirmationNotSent = false

    /**
     * The next n confirmations are held before they go out with no pause, as a look that found
     * the dialog changed holds one, and nothing is pressed.
     */
    var confirmationsHeldUnpaused = 0

    /**
     * The next n once-taps are held ([TapAfterHold.HELD]), or refused with [refuseOnce]
     * ([TapAfterHold.REFUSED]), and nothing is pressed.
     */
    var holdOnceTaps = 0
    var refuseOnce = false

    /**
     * A screen over everything else, as a person or a stray tap could leave one: the attack menu,
     * the army screen, a battle, or the gem store. Back closes the menu, the army screen and the
     * store, the neutral tap a WALL screen; nothing closes the rest. [tapsOnOver] counts presses
     * that landed on it.
     */
    var over: Pair<ScreenState, List<RecognizedBox>>? = null
    var tapsOnOver = 0

    /** Where each Back was sent from, as AndroidWallDevice names it to backIfClear. */
    val backWheres = mutableListOf<String>()

    /** Runs just before a confirmation is dispatched, to look at what the phase had persisted. */
    var beforeConfirmation: () -> Unit = {}

    /** A confirmation press that is ignored by the phone but dispatched without error. */
    private val neutral = Point(280, 330)
    private val counter = builderCounterPoint(UpgradeListFixture.screen)
    private val listClose = upgradeListClosePoint(UpgradeListFixture.screen)

    val neutralPoint: Point get() = neutral
    val unit: Long get() = unitOverride ?: unitFor(level)
    val total: Long get() = unit * count

    private fun unitFor(level: Int): Long = when (level) {
        15 -> 4_000_000L
        16 -> 5_000_000L
        17 -> 5_600_000L
        18 -> 8_000_000L
        19 -> 9_000_000L
        else -> 9_500_000L
    }

    private fun payable(total: Long): Boolean = total <= gold && (payableLimit?.let { total <= it } ?: true)

    private fun spaced(value: Long) = UpgradeListFixture.spaced(value)

    // --- frames ------------------------------------------------------------------------------

    private class PanelFrame(
        override val observation: Observation,
        private val prices: List<Triple<RecognizedBox, String, Boolean>>
    ) : UpgradeListFrame {
        override fun pixels(region: Region): IntArray {
            val out = IntArray(maxOf(1, region.width * region.height)) { 0xFF203020.toInt() }
            val hit = prices.firstOrNull { it.first.left == region.left && it.first.top == region.top }
                ?: prices.singleOrNull { it.first.top == region.top && it.first.center.x in region.left until region.right }
                ?: return out
            val icon = if (hit.second == "gold") 0xFFFFD21C.toInt() else 0xFFC832DC.toInt()
            val text = if (hit.third) 0xFFFFFFFF.toInt() else 0xFFFF8C8C.toInt()
            for (i in out.indices) out[i] = if (i < 140) icon else text
            return out
        }
    }

    private fun observation(state: ScreenState, boxes: List<RecognizedBox>) =
        Observation(state, boxes, boxes.joinToString(" ") { it.text }, UpgradeListFixture.screen, time)

    private var maxed = false

    private val stray: List<RecognizedBox>
        get() = (extraText?.let { listOf(RecognizedBox(it, 300, 200, 500, 225)) } ?: emptyList()) + topBar

    /** The raid counter, which is not the builders', and the builder counter when one is drawn. */
    private val topBar: List<RecognizedBox>
        get() = (buildersAt.let { if (it != null) it(time) else builders })?.let { listOf(RecognizedBox("0/1", 638, 26, 684, 56), RecognizedBox(it, 795, 26, 845, 56)) }
            ?: emptyList()

    private val busyX = RecognizedBox("X", 1040, 195, 1082, 238)
    private val gemButton = RecognizedBox("", 678, 400, 918, 510)

    /** The quit prompt's drawn buttons, as on the phone at 14:05 UTC on 8 October. */
    val quitCancel = RecognizedBox("", 579, 432, 739, 492)
    val quitOkay = RecognizedBox("", 859, 432, 1019, 492)

    private fun busyFrame(): UpgradeListFrame = PanelFrame(observation(ScreenState.HOME, listOf(
        RecognizedBox("All builders are", 640, 190, 960, 222),
        RecognizedBox("busy!", 745, 225, 850, 258),
        RecognizedBox("Complete the previous building and free up a", 540, 308, 1055, 335),
        RecognizedBox("builder?", 750, 337, 845, 362),
        RecognizedBox("212", 740, 430, 812, 475),
        RecognizedBox("Attack!", 60, 650, 210, 705)
    ) + (if (busyCloseRead) listOf(busyX) else emptyList()) + topBar), emptyList())

    private fun panelFrame(): UpgradeListFrame {
        if (maxed && maxedReadsHome) return PanelFrame(observation(ScreenState.HOME, listOf(
            RecognizedBox("21h 47m", 90, 470, 190, 494),
            RecognizedBox("IAROO8", 5, 530, 85, 552),
            RecognizedBox(maxedTitle ?: "Wall (Level $level)", 679, 465, 921, 495),
            RecognizedBox("INfo", 709, 575, 760, 605),
            RecognizedBox("Select", 835, 570, 905, 596),
            RecognizedBox("ROW", 849, 596, 898, 614)
        ) + UpgradeListFixture.overlay + stray), emptyList())
        if (maxed) return PanelFrame(observation(ScreenState.WALL, listOf(
            RecognizedBox("Wall (Level $level)", 672, 465, 928, 495),
            RecognizedBox("Info", 690, 575, 755, 605),
            RecognizedBox("Select", 835, 570, 905, 596),
            RecognizedBox("ROW", 848, 596, 898, 614)
        ) + UpgradeListFixture.overlay + stray), emptyList())
        val price = spaced(total)
        val boxes = mutableListOf<RecognizedBox>()
        val prices = mutableListOf<Triple<RecognizedBox, String, Boolean>>()
        val bulk = panel == Panel.BULK
        val title = titleOverride ?: if (bulk) bulkTitle?.invoke(count) ?: "Wall (Level $level)x $count" else "Wall (Level $level)"
        boxes += RecognizedBox(title, if (bulk) 660 else 672, 465, if (bulk) 940 else 928, 495)
        if (bulk) {
            boxes += RecognizedBox("Remove Wall", 470, 574, 566, 614)
            boxes += RecognizedBox("Add Wall", 620, 586, 696, 612)
            boxes += RecognizedBox("Add Wall", 762, 586, 838, 612)
        } else {
            boxes += RecognizedBox("Info", 495, 570, 548, 598)
            boxes += RecognizedBox("Select", 623, 570, 689, 596)
            boxes += RecognizedBox("ROW", 636, 596, 686, 614)
            if (upgradeMore) {
                boxes += RecognizedBox("Upgrade", 751, 570, 845, 596)
                boxes += RecognizedBox("More", 769, 596, 832, 614)
            }
        }
        val goldPrice = if (bulk) RecognizedBox(price, 898, 508, 980, 532) else RecognizedBox(price, 893, 508, 1010, 532)
        val goldLabel = if (bulk) RecognizedBox("Upgrade", 900, 586, 982, 612) else RecognizedBox("Upgrade", 892, 586, 984, 612)
        val elixirPrice = if (bulk) RecognizedBox(price, 1042, 508, 1124, 532) else RecognizedBox(price, 1044, 508, 1148, 532)
        val elixirLabel = if (bulk) RecognizedBox("Upgrade", 1044, 586, 1126, 612) else RecognizedBox("Upgrade", 1035, 586, 1127, 612)
        if (bulk && mergedPrices) {
            // One box over both prices; the icons are the last 22 px of each price.
            boxes += RecognizedBox("$price $price", 898, 508, 1124, 532)
            boxes += goldLabel
            boxes += elixirLabel
            prices += Triple(RecognizedBox("", 958, 508, 980, 532), "gold", payable(total))
            prices += Triple(RecognizedBox("", 1102, 508, 1124, 532), "elixir", total <= elixir)
            return PanelFrame(observation(ScreenState.WALL, boxes + UpgradeListFixture.overlay + stray), prices)
        }
        if (goldButton) {
            boxes += goldPrice
            boxes += goldLabel
            prices += Triple(goldPrice, "gold", payable(total))
        }
        boxes += elixirPrice
        boxes += elixirLabel
        prices += Triple(elixirPrice, "elixir", total <= elixir)
        return PanelFrame(observation(ScreenState.WALL, boxes + UpgradeListFixture.overlay + stray), prices)
    }

    private fun buildDialog(): Dialog {
        val okay = RecognizedBox("Okay", 880, 420, 1000, 460)
        val cancel = RecognizedBox("Cancel", 600, 420, 720, 460)
        val named = if (dialogKind == "wrongprice") total + 1_000_000 else total
        val boxes = when {
            dialogKind == "insufficient" -> listOf(
                RecognizedBox("You do not have enough Gold. Use gems to buy the missing amount", 555, 298, 1050, 326), cancel, okay
            )
            dialogKind == "gems" -> listOf(
                RecognizedBox("Do you really want to upgrade the selected Walls for $total Gold? 200 gems", 555, 298, 1050, 326), cancel, okay
            )
            dialogKind == "dark" -> listOf(
                RecognizedBox("Do you really want to upgrade the selected Walls for $total Dark Elixir?", 555, 298, 1050, 326), cancel, okay
            )
            dialogKind == "money" -> listOf(
                RecognizedBox("Special Offer! $4.99", 555, 298, 1050, 326), cancel, okay
            )
            count > 1 -> listOf(
                RecognizedBox("Do you really want to upgrade the selected Walls for $named Gold?", 555, 298, 1050, 326), cancel, okay
            )
            else -> listOf(
                RecognizedBox("Upgrade Wall to Level ${level + 1}?", 555, 298, 1050, 326), cancel,
                RecognizedBox("Okay " + spaced(named), 880, 420, 1100, 460)
            ).let { boxes ->
                val full = dialogFullPrice ?: return@let boxes
                listOf(boxes[0], boxes[1], RecognizedBox(spaced(full), 1110, 420, 1230, 445), boxes[2])
            }
        }
        return Dialog(boxes, boxes.last(), cancel)
    }

    private fun homeFrame(): UpgradeListFrame = PanelFrame(
        observation(ScreenState.HOME, UpgradeListFixture.overlay + RecognizedBox("Attack!", 60, 650, 210, 705) + stray),
        emptyList()
    )

    private fun unreadableFrame(): UpgradeListFrame = PanelFrame(observation(ScreenState.UNKNOWN, emptyList()), emptyList())

    override suspend fun capture(): UpgradeListFrame {
        time += 100
        captures++
        quitPrompt?.let { return PanelFrame(observation(ScreenState.HOME, it), emptyList()) }
        over?.let { return PanelFrame(observation(it.first, it.second), emptyList()) }
        if (busyShowing) {
            if (busyCaptures++ < busyFrames) return busyFrame()
            busyShowing = false
            busyFaded = true
        }
        dialog?.let {
            if (unpricedDialogFrames > 0) {
                unpricedDialogFrames--
                return PanelFrame(observation(ScreenState.HOME, it.boxes.map { box ->
                    if (box === it.okay) box.copy(text = "Okay") else box
                }), emptyList())
            }
            return PanelFrame(observation(ScreenState.HOME, it.boxes), emptyList())
        }
        if (stuck) return unreadableFrame()
        listClosesAfter?.let { left ->
            if (left <= 0) { listOpen = false; listClosesAfter = null } else listClosesAfter = left - 1
        }
        if (listOpen) return UpgradeListFixture.Frame(items, 0)
        if (panel != Panel.NONE) return panelFrame()
        return homeFrame()
    }

    // --- gestures ----------------------------------------------------------------------------

    private fun inside(point: Point, box: RecognizedBox, slack: Int = 6) =
        point.x in (box.left - slack)..(box.right + slack) && point.y in (box.top - slack)..(box.bottom + slack)

    private fun at(left: Int, top: Int, right: Int, bottom: Int) = RecognizedBox("", left, top, right, bottom)

    private fun near(a: Point, b: Point) = kotlin.math.abs(a.x - b.x) <= 4 && kotlin.math.abs(a.y - b.y) <= 4

    private fun held(point: Point) {
        if (holdTaps <= 0) return
        holdTaps--
        taps += "held ${point.x},${point.y}"
        throw StopException(TapAfterHold.HELD)
    }

    override suspend fun tap(point: Point) {
        time += 100
        held(point)
        gestures++
        taps += "tap ${point.x},${point.y}"
        press(point)
    }

    override suspend fun tapButton(point: Point) {
        time += 100
        held(point)
        gestures++
        taps += "button ${point.x},${point.y}"
        press(point)
    }

    override suspend fun tapConfirmation(point: Point) {
        time += 100
        if (confirmationNotSent) throw ConfirmationNotSent(StopException(TapAfterHold.REFUSED))
        if (confirmationsHeldUnpaused > 0) {
            confirmationsHeldUnpaused--
            taps += "held ${point.x},${point.y}"
            throw ConfirmationNotSent(StopException(TapAfterHold.HELD), paused = false)
        }
        gestures++
        confirmTaps++
        beforeConfirmation()
        taps += "confirm ${point.x},${point.y}"
        confirmationFailure?.let { throw it }
        press(point)
    }

    override suspend fun tapButtonOnce(point: Point) {
        time += 100
        if (holdOnceTaps > 0) {
            holdOnceTaps--
            taps += "held ${point.x},${point.y}"
            throw StopException(if (refuseOnce) TapAfterHold.REFUSED else TapAfterHold.HELD)
        }
        gestures++
        taps += "once ${point.x},${point.y}"
        press(point)
    }

    override suspend fun back(where: String): Boolean {
        time += 100
        gestures++
        backs++
        backWheres += where
        taps += "back"
        val shown = over
        when {
            quitPrompt != null -> quitPrompt = null
            shown != null -> if (shown.first == ScreenState.MENU || shown.first == ScreenState.ARMY ||
                Policy.showsShop(shown.second.joinToString(" ") { it.text })) over = null
            busyShowing -> { busyShowing = false; busyCloses++ }
            dialog != null -> dialog = null
            else -> backsOnVillage++
        }
        return true
    }

    override suspend fun swipe(from: Point, to: Point, durationMs: Long, holdMs: Long) {
        time += durationMs + holdMs
        gestures++
    }

    override suspend fun wait(ms: Long) {
        time += ms
    }

    override fun now(): Long = time

    override suspend fun checkpoint(): Boolean {
        checkpoints++
        if (dialog != null) {
            dialogCheckpoints++
            if (dialogCheckpoints <= pauseFirst || dialogCheckpoints == pauseAtDialogCheckpoint) return true
        }
        return false
    }

    private fun press(point: Point) {
        over?.let { shown ->
            if (shown.first == ScreenState.WALL && near(point, neutral)) over = null else tapsOnOver++
            return
        }
        if (quitPrompt != null) {
            when {
                inside(point, quitCancel, slack = 0) -> { quitPrompt = null; quitCancels++ }
                inside(point, quitOkay, slack = 0) -> { quitPrompt = null; quitOkays++; stuck = true }
            }
            return
        }
        if (busyShowing) {
            when {
                inside(point, busyX) -> { busyShowing = false; busyCloses++ }
                inside(point, gemButton, slack = 0) -> gemTaps++
            }
            return
        }
        if (busyFaded && inside(point, at(980, 160, 1150, 270), slack = 0)) {
            busyTapsAfterFade++
        }
        dialog?.let { d ->
            if (inside(point, d.okay)) okay() else dialog = null
            return
        }
        if (listOpen && near(point, listClose)) {
            listOpen = false
            return
        }
        if (near(point, counter)) {
            if (!listOpen && counterOpens && panel == Panel.NONE) listOpen = true
            return
        }
        if (listOpen) {
            val wall = items.filterIsInstance<UpgradeListFixture.Line>().firstOrNull { it.name.startsWith("Wall x") }
            if (wall != null && point.x in 662..985 && kotlin.math.abs(point.y - wall.y) <= 15) {
                panel = Panel.SINGLE
                count = 1
            }
            return
        }
        if (near(point, neutral)) {
            panel = Panel.NONE
            return
        }
        when (panel) {
            Panel.NONE -> {}
            Panel.SINGLE -> when {
                upgradeMore && inside(point, at(769, 596, 832, 614)) -> { panel = Panel.BULK; count = 1 }
                inside(point, at(623, 570, 689, 614)) -> selectRowPresses++
                goldButton && inside(point, at(892, 586, 984, 612)) -> upgrade()
            }
            Panel.BULK -> when {
                inside(point, at(620, 586, 696, 612)) -> {
                    if (addTenWorks && payable(unit * (count + 10)) && count + 10 <= growthLimit) count += 10
                }
                inside(point, at(762, 586, 838, 612)) -> if (count + 1 <= growthLimit) count += 1
                inside(point, at(470, 574, 566, 614)) -> if (count > 1) count -= 1
                goldButton && inside(point, at(900, 586, 982, 612)) -> upgrade()
            }
        }
    }

    private fun upgrade() {
        upgradePresses++
        dialogTotal = total
        dialogCheckpoints = 0
        dialog = buildDialog()
    }

    private fun okay() {
        dialog = null
        if (!okayWorks || dialogKind == "insufficient") return
        if (buildersBusy) { busyShowing = true; return }
        gold -= dialogTotal
        when (afterBuy) {
            "closes" -> panel = Panel.NONE
            "stays" -> level += 1
            "maxed" -> { level += 1; maxed = true }
            else -> {}
        }
    }

    // --- resource bar --------------------------------------------------------------------------

    override suspend fun readBalance(currency: String): Long? =
        if (currency != "gold") elixir else barGold?.let { it() } ?: (if (barGold != null) null else gold)

    override suspend fun settledBalance(currency: String, expected: Long, starting: Long): Long =
        settleOverride ?: (if (currency == "gold") gold else elixir)
}
