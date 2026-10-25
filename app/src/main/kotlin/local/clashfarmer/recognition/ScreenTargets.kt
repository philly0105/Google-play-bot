package local.clashfarmer.recognition

import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Policy
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenEvidence
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Part of the screen as fractions of the viewport, so a band means the same at any size. [pc], when
 * given, replaces it on the PC's frames ([PcGeometry]), wide enough for 16:9 and 16:10.
 */
private data class Band(val left: Double, val top: Double, val right: Double, val bottom: Double, val pc: Band? = null) {
    fun holds(point: Point, viewport: Viewport): Boolean {
        if (viewport.width <= 0 || viewport.height <= 0) return false
        if (pc != null && PcGeometry.isPc(viewport)) return pc.holds(point, viewport)
        val x = (point.x - viewport.left).toDouble() / viewport.width
        val y = (point.y - viewport.top).toDouble() / viewport.height
        return x in left..right && y in top..bottom
    }
}

// Measured at 1600x720 on 23 September; audit-evidence/2026-09-23-expansion-progress.md.

/** The result screen's Return Home, drawn at (801, 606) in nine hundred logged taps. */
private val RESULT_BUTTON = Band(0.35, 0.72, 0.65, 0.97)

/** Matchmaking's Return Home, which calls the search off: bottom-left, at (135, 640). */
private val SEARCH_BUTTON = Band(0.0, 0.72, 0.2, 1.0)

/** "Searching for opponents..." across the middle, at y 395. */
private val SEARCH_TITLE = Band(0.25, 0.4, 0.75, 0.7)

/** Total damage, Victory, You got and Troops expended stack down the middle at x 800. */
private val RESULT_COLUMN = Band(0.3, 0.0, 0.7, 0.72)

/**
 * The village's Attack! button at (134, 680). Since Clash 18.647 (14 October) the same button
 * reads "Battle", label at (140, 685); audit-evidence/clash-18647/01-home.png.
 */
private val HOME_ATTACK = Band(0.0, 0.8, 0.2, 1.0)

/**
 * The village's Battle button as read, letters only: "Batle" and "Bale" on Blood, 14 October,
 * and "Battie" on Sweat at 10:37 UTC that day. The PC's reader made it "Baftle" under the quit
 * prompt, audit-evidence/spike/geo-after-deselect.json, and "Batfle" on the village at 1860x1162,
 * audit-evidence/live/20261024-030816-profile/00-home.json.
 */
val BATTLE_READS = setOf("battle", "batle", "bale", "battie", "baftle", "batfle")

private val RESULT_WORDS = listOf("total damage", "you got", "troops expended", "victory", "defeat")

/**
 * "Searching" arrives as "SsaReiingFs", "SearaiNg", "SeaReiiNg"; "opponents" keeps its "opp" more
 * often than not. Either is enough inside the title's band, at the start of a word: the
 * laboratory's "Research" label sits in that band on the village, and on 29 September it held a
 * finished battle's way home as a search for three minutes until the run gave up.
 */
private val SEARCH_WORDS = listOf("opp", "sear", "ssar", "seai")

private fun spellsSearch(words: String): Boolean =
    words.split(" ").any { word -> SEARCH_WORDS.any { word.startsWith(it) } }

/** Return Home wraps onto two lines about twenty pixels apart; two buttons are much further. */
private const val BUTTON_SPREAD = 150.0

private fun words(text: String): String = text.lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9 ]"), " ")
    .split(Regex("\\s+"))
    .filter { it.isNotEmpty() }
    .joinToString(" ")

private fun isReturnHomeLine(text: String): Boolean {
    val w = words(text)
    return w.contains("return") || w.contains("home")
}

/**
 * The result screen's button. Since Clash 18.647 it reads "Claim Reward" when the attack earned
 * the star bonus; it takes the bonus, which costs nothing, and goes home like Return Home.
 */
private fun isResultButtonLine(text: String): Boolean =
    isReturnHomeLine(text) || words(text).replace(" ", "").contains("claimreward")

fun screenEvidence(boxes: List<RecognizedBox>, viewport: Viewport): ScreenEvidence {
    fun any(band: Band, test: (String) -> Boolean) =
        boxes.any { band.holds(it.center, viewport) && test(words(it.text)) }
    return ScreenEvidence(
        resultAnchor = any(RESULT_COLUMN) { w -> RESULT_WORDS.any { w.contains(it) } },
        resultReturnButton = any(RESULT_BUTTON) { isResultButtonLine(it) },
        searchReturnButton = any(SEARCH_BUTTON) { isReturnHomeLine(it) },
        searchText = any(SEARCH_TITLE) { spellsSearch(it) },
        // Only the whole word Battle: End Battle and the menu's Battle card sit outside this band.
        homeAttackButton = any(HOME_ATTACK) { it.contains("attack") || it.replace(" ", "") in BATTLE_READS }
    )
}

/** The box-aware classifier. Policy.classify(text) remains for readings without positions. */
fun classifyScreen(boxes: List<RecognizedBox>, viewport: Viewport): ScreenState =
    Policy.classify(boxes.joinToString(" ") { it.text }, screenEvidence(boxes, viewport))

/**
 * Where to tap Return Home on a result screen, or null when that cannot be said.
 *
 * Only a Return or Home line inside the button's own band counts. The first box containing
 * "return" used to win, and at 19:36 on 23 September that was a defender named RETURN OF ELDER:
 * the tap landed on the name and the run died at 5 of 40. When the button's words were not read,
 * [calibrated] is used only if it lies in the band and the screen is positively a result screen.
 */
fun returnHomeTarget(boxes: List<RecognizedBox>, viewport: Viewport, calibrated: Point?): Point? {
    val candidates = boxes.filter { isResultButtonLine(it.text) && RESULT_BUTTON.holds(it.center, viewport) }
    val anchor = calibrated?.takeIf { RESULT_BUTTON.holds(it, viewport) }
    if (candidates.isEmpty()) {
        return anchor?.takeIf { screenEvidence(boxes, viewport).resultAnchor }
    }
    val spread = candidates.maxOf { a -> candidates.maxOf { b -> distance(a.center, b.center) } }
    if (spread > BUTTON_SPREAD) return null
    val chosen = if (anchor != null) {
        candidates.minBy { distance(it.center, anchor) }
    } else {
        candidates.firstOrNull { words(it.text).contains("return") } ?: candidates.first()
    }
    return chosen.center
}

private fun distance(a: Point, b: Point): Double =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

/**
 * "Reload" when another device took the village, "Reload game" after inactivity, "Try again" when
 * the server dropped the connection. Each is its dialog's only button, in the same place.
 */
private val RELOAD_WORDS = setOf("reload", "reload game", "try again")

/**
 * The takeover dialog's Reload at (790, 648), 25 September; audit-evidence/2026-09-25-takeover-now.png.
 * On the PC the idle dialog is Android's, centred, and sized by Android rather than by Clash: its
 * RELOAD GAME reads at (577, 545) of 1740x978 and (651, 660) of 1860x1162, x 0.33-0.35 and y 0.56-0.57
 * (audit-evidence/live/20261024-025411-look/frame.png, 20261024-031222-focuscheck/0-before.json),
 * so the band starts at mid-height. The takeover dialog has no PC capture yet.
 */
private val RELOAD_BUTTON = Band(0.3, 0.78, 0.7, 1.0, pc = Band(0.25, 0.5, 0.75, 1.0))

/**
 * Where to tap Reload, or null. Clash ends a connection with a dialog whose one button reconnects:
 * another device took the village, it sat idle too long, or the server dropped it. Its wording varies, so the button
 * alone, read as the whole word in its own band, is what counts.
 */
fun reloadTarget(boxes: List<RecognizedBox>, viewport: Viewport): Point? {
    return boxes.firstOrNull { words(it.text) in RELOAD_WORDS && RELOAD_BUTTON.holds(it.center, viewport) }?.center
}

/** The scout's Next button at (1440, 495), with its price beneath it. */
private val NEXT_BUTTON = Band(0.8, 0.55, 1.0, 0.82)

/** How far a calibrated Next may sit from the word read on the button. */
private const val NEXT_ANCHOR_SLACK = 80.0

/**
 * Where to tap Next on a scout screen, or null. Next spends gold, so the word itself must have
 * been read inside the button's band; a calibrated point is used only when it agrees with it.
 */
fun nextButtonTarget(boxes: List<RecognizedBox>, viewport: Viewport, calibrated: Point?): Point? {
    val word = boxes.firstOrNull { words(it.text) == "next" && NEXT_BUTTON.holds(it.center, viewport) }
        ?: return null
    if (calibrated == null) return word.center
    return calibrated.takeIf { distance(it, word.center) <= NEXT_ANCHOR_SLACK }
}

/** The battle's Surrender, End Battle before a troop is down, bottom-left at (140, 537). */
private val SURRENDER_BUTTON = Band(0.0, 0.6, 0.25, 1.0)

/** The surrender dialog's Okay, right of Cancel under "Are you sure". */
private val CONFIRM_BUTTON = Band(0.3, 0.45, 0.95, 0.95)

/**
 * Where to tap Surrender on a battle, or null. The word read inside the button's band wins; a
 * calibrated End Battle point is used only when it lies in that band and nothing was read.
 */
fun surrenderTarget(boxes: List<RecognizedBox>, viewport: Viewport, calibrated: Point?): Point? {
    val word = boxes.firstOrNull { box ->
        val w = words(box.text)
        (w.contains("surrender") || w.contains("end battle")) && SURRENDER_BUTTON.holds(box.center, viewport)
    }
    return word?.center ?: calibrated?.takeIf { SURRENDER_BUTTON.holds(it, viewport) }
}

/** Where to tap Okay on the surrender dialog, or null when the whole word was not read there. */
fun confirmSurrenderTarget(boxes: List<RecognizedBox>, viewport: Viewport): Point? =
    boxes.firstOrNull { words(it.text) in setOf("okay", "ok") && CONFIRM_BUTTON.holds(it.center, viewport) }?.center

/**
 * A battle's surrender budget, spent frame by frame: [maxTries] taps dispatched on a located
 * button before the battle is left to run out on its own, and [maxMisses] frames that fired the
 * trigger without the button on them before it is given up as unreadable. A miss never spends
 * a try, and nothing is ever tapped at a guessed spot.
 */
class SurrenderTracker(private val maxTries: Int, private val maxMisses: Int) {
    var tries = 0
        private set
    var misses = 0
        private set

    /** Once either is spent, no frame is read for the button again: the surrender is given up. */
    val exhausted: Boolean get() = tries >= maxTries || misses >= maxMisses

    /**
     * The point to tap on this frame, or null when the button was not on it or the budget is
     * spent. A located button spends a try, the tap going out on it right away; a frame without
     * one spends a miss.
     */
    fun locate(boxes: List<RecognizedBox>, viewport: Viewport, calibrated: Point?): Point? {
        if (exhausted) return null
        val target = surrenderTarget(boxes, viewport, calibrated)
        if (target == null) misses++ else tries++
        return target
    }
}

// The builder upgrade list, measured at 1600x720 on 29 September; audit-evidence/2026-09-29-upgrade-list-discovery.md.

private fun scaled(viewport: Viewport, fx: Double, fy: Double) = Point(
    viewport.left + (viewport.width * fx).roundToInt(),
    viewport.top + (viewport.height * fy).roundToInt()
)

/**
 * The builder counter, "1/6" over the builder's face. Until Clash 18.647 it sat in the top bar at
 * (790, 42); since 14 October it sits above Build at (1430, 553), with the goblin's "1/2" beside
 * it at (1493, 553). It opens the upgrade list. The open list covers it, so it never closes the
 * list again: [upgradeListClosePoint] does. On the PC the "0/6" reads at x 1576-1642, y 774-804
 * of 1740x978 (audit-evidence/spike/geo-home.json), kept from the right edge ([PcGeometry]).
 */
fun builderCounterPoint(viewport: Viewport): Point = PcGeometry.pick(viewport,
    scaled(viewport, 1430.0 / 1600, 553.0 / 720), PcGeometry.point(viewport, 1609, 789, Anchor.RIGHT))

/**
 * The open list's red X, top right of its panel at (1520, 28); audit-evidence/clash-18647/12-now.png.
 * On the PC it reads at x 1690-1732, y 10-47 of 1740x978 (audit-evidence/spike/geo-list-top.json),
 * kept from the right edge.
 */
fun upgradeListClosePoint(viewport: Viewport): Point = PcGeometry.pick(viewport,
    scaled(viewport, 1520.0 / 1600, 28.0 / 720), PcGeometry.point(viewport, 1711, 28, Anchor.RIGHT))

/**
 * Since Clash 18.647 the list is a full-height panel down the right edge, x 1118-1545, with its
 * "Buildings & Heroes" title above y 60 and its tabs below y 625. The title is what tells it from
 * the old popup.
 */
private fun isBuilderPanel(box: RecognizedBox, viewport: Viewport): Boolean {
    val letters = box.text.lowercase(Locale.ROOT).filter { it in 'a'..'z' }
    return (letters.contains("buildings") || letters.contains("heroes")) &&
        box.center.x >= viewport.left + viewport.width * 1118 / 1600 &&
        box.center.y <= viewport.top + viewport.height * 60 / 720
}

/** Whether the frame shows the 18.647 panel rather than the old popup. */
fun builderPanelShowing(boxes: List<RecognizedBox>, viewport: Viewport): Boolean =
    boxes.any { isBuilderPanel(it, viewport) }

/**
 * The builder list's popup, x 649-985 and y 100-555, arrow tip excluded. Blood's is wider, x
 * 578-1054, and is recognised by a price ending near x 1040. The coin before a price often reads
 * as a letter ("O 5 000 000", "e8 000 000"); one such glyph either side still makes it a price, or
 * a Blood frame with every coin read that way falls back to the narrow popup and loses its names.
 * Since Clash 18.647 the panel is x 1118-1545, y 60-625, on both accounts. On the PC it runs to the
 * frame's right edge, x 1272-1740, with rows from y 61 to 856 and its tabs from y 917 at
 * 1740x978 (audit-evidence/spike/geo-list-*.json), and is kept from the right edge.
 */
fun upgradePopupRegion(viewport: Viewport, boxes: List<RecognizedBox> = emptyList()): Region {
    if (builderPanelShowing(boxes, viewport)) {
        if (PcGeometry.isPc(viewport)) return PcGeometry.region(viewport, Region(1272, 63, 1740, 873), Anchor.RIGHT)
        val topLeft = scaled(viewport, 1118.0 / 1600, 60.0 / 720)
        val bottomRight = scaled(viewport, 1545.0 / 1600, 625.0 / 720)
        return Region(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }
    val wider = boxes.any {
        it.left >= viewport.width * 850 / 1600 &&
            it.right > viewport.width * 1015 / 1600 && it.right <= viewport.width * 1054 / 1600 &&
            it.center.y in (viewport.height * 100 / 720)..(viewport.height * 555 / 720) &&
            it.text.count(Char::isDigit) >= 6 &&
            it.text.trim().replace(COIN_GLYPH, "").matches(Regex("[0-9 ,.'’]+"))
    }
    val topLeft = scaled(viewport, (if (wider) 578.0 else 649.0) / 1600, 100.0 / 720)
    val bottomRight = scaled(viewport, (if (wider) 1054.0 else 985.0) / 1600, 555.0 / 720)
    return Region(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
}

private val COIN_GLYPH = Regex("^[^0-9\\s]\\s*|\\s*[^0-9\\s]$")

/** Where the laboratory's popup (x 502-808) draws its rows, left of the builder popup. */
fun laboratoryColumnRegion(viewport: Viewport): Region {
    val builder = upgradePopupRegion(viewport)
    val left = scaled(viewport, 510.0 / 1600, 0.0).x
    return Region(left, builder.top, builder.left - 8, builder.bottom)
}

/**
 * The swipe that scrolls the list: (817, 500) to (817, 250) over 500 ms moved the old popup's
 * content 355 to 390 px because the fling carries on. Up scrolls the list down; the other way
 * scrolls back. Since Clash 18.647 the list is the right-hand panel, so the swipe runs at x 1330;
 * at x 817 it would pan the village instead.
 */
fun upgradeListSwipe(viewport: Viewport, towardsBottom: Boolean): Pair<Point, Point> {
    val low = scaled(viewport, 1330.0 / 1600, 500.0 / 720)
    val high = scaled(viewport, 1330.0 / 1600, 250.0 / 720)
    return if (towardsBottom) low to high else high to low
}

/**
 * The bot's own floating panel, top-left; whatever it says is not the game's. The PC's panel is a
 * window of its own that the capture never holds, and the corner holds Clash's text there: the
 * player's name, the clan, the scout's loot.
 */
fun isOwnOverlay(box: RecognizedBox, viewport: Viewport): Boolean {
    if (PcGeometry.isPc(viewport)) return false
    val limit = scaled(viewport, 600.0 / 1600, 105.0 / 720)
    return box.center.x < limit.x && box.center.y < limit.y
}
