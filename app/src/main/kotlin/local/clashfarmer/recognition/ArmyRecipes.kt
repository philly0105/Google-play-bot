package local.clashfarmer.recognition

import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which tab the army screen shows. Both tabs carry both labels, so only what is under them tells. */
enum class ArmyTab { MY_ARMY, SAVED_RECIPES, UNKNOWN }

/** A saved army's card in the Saved Recipes list: its title as read and the Use box beside it. */
data class RecipeCard(val title: RecognizedBox, val use: RecognizedBox)

/** What a look after a pause just before Attack! says to do with it. */
enum class AttackAfterPause {
    /** My Army is still showing. */
    SEND,
    /** Matchmaking, the scout or a battle: Attack! went through before the pause. */
    PASSED,
    /** The army screen on another tab, or one that cannot be told: ready My Army and press again. */
    READY_AGAIN,
    /** Not the army screen, or still not My Army after it was readied once. */
    STOP
}

/** What one more look says about tapping a saved army's Use. */
enum class UseCheck {
    /** The card's Use is where the last read put it. */
    TAP,
    /** Moved, or not read, with a look still left. */
    LOOK_AGAIN,
    /** Not read in one place twice before the looks ran out. */
    MOVED,
    /** The Saved Recipes list is not showing. */
    LOST_LIST
}

/**
 * The army screen's two tabs and the Saved Recipes list, from OCR boxes alone.
 *
 * Measured at 1600x720 on Blood's phone on 15 October (audit-evidence/recipe/inspect-10-top.jsonl,
 * inspect-12-scrolled.jsonl, and clash-18647/05-army-ocr.ocr.txt for the My Army tab). A card's
 * title starts at x 251 with its Use, Edit and Share stacked at x 1319-1385 to the right; the
 * first Use sits 43 to 47 px below its title's top. The My Army tab has Boost Army and Boost
 * Heroes at y 107-163 on the right instead, which spend gems.
 */
object ArmyRecipes {

    /** Swipes down the list before a title counts as missing; Blood's Army 5, the fourth card, needs one. */
    const val MAX_SWIPES = 6
    const val SWIPE_MS = 800L
    /**
     * The finger rests at a swipe's end this long before it lifts, so the list should stop where
     * the drag leaves it instead of flinging on; a list read while it coasts puts Use somewhere
     * else on every look (Blood, 15 October: "moved", Use never pressed).
     */
    const val HOLD_MS = 300L
    /**
     * Tries at the whole find, from the tab to Use, before the run goes on with the army it has.
     * The second starts at the top of the list unless the card is in view.
     */
    const val FIND_TRIES = 2
    /**
     * A find's end when a pause cut its Use tap short and the look after it showed neither the
     * card where it was nor a load. The tap may have gone out, so the find is not tried again.
     */
    const val USE_INTERRUPTED = "use_interrupted"
    /** Looks at the screen after a tab tap or a Use, each this long after the last. */
    const val LOOKS = 4
    const val SETTLE_MS = 800L
    /**
     * Looks a card's Use may take before it is tapped, the one that found it included. Two reads
     * in a row must put it in the same place: a list still coasting from a swipe carries it, and
     * the card's Edit is 59 px below it.
     */
    const val USE_LOOKS = 3
    /** From hiding the bot's panel to the tap under it: the window change has to reach input. */
    const val PANEL_HIDE_MS = 400L

    /**
     * The stop when My Army cannot be told before Attack!, or a look after a pause just before it
     * finds some other screen. It carries RecoveryPolicy's "the screen stayed unrecognized", so
     * Back and a new runner try again rather than end the session.
     */
    const val TAB_UNKNOWN = "The screen stayed unrecognized as My Army, so Attack! was not pressed"

    /**
     * Part of the screen as fractions of the viewport; [pc], when given, on the PC's frames
     * ([PcGeometry]), wide enough for 16:9 and for either way the screen may scale at 16:10.
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

    /**
     * "MY ARMY" at (356,55)-(460,72) and "Saved Recipes" at (726,57)-(874,74). The PC draws this
     * screen as the phone's scaled about 1.185 around the centre; at 1740x978 the labels read at
     * (333,121)-(474,159) and (773,123)-(966,161) (audit-evidence/spike/geo-army.json). At 16:10
     * the screen may keep that scale by the height, which puts My Army's centre at x 0.20, or fit the
     * narrower width, which puts it at y 0.18; the PC bands take both.
     */
    private val MY_ARMY_LABEL = Band(0.15, 0.03, 0.35, 0.14, pc = Band(0.12, 0.08, 0.34, 0.22))
    private val SAVED_RECIPES_LABEL = Band(0.40, 0.03, 0.60, 0.14, pc = Band(0.39, 0.08, 0.61, 0.22))

    /**
     * The list's Use, Edit and Share, centred at x 1352-1353. On the PC at x 1524 of 1740, 0.876,
     * and at 16:10 x 0.918 if the screen keeps its scale by the height (geo-recipes.json).
     */
    private val BUTTON_COLUMN = Band(0.80, 0.14, 0.89, 1.0, pc = Band(0.80, 0.14, 0.95, 1.0))

    /**
     * My Army's "Boost" over "ARMY" and over "HeROes", at y 117 to 149. On the PC at x 1212 and 1428,
     * y 210-232 of 1740x978 (geo-army.json); at 16:10, HeROes at x 0.857, or the row at y 0.264.
     */
    private val BOOST_ROW = Band(0.55, 0.12, 0.85, 0.26, pc = Band(0.55, 0.12, 0.90, 0.30))

    /** A title's left edge: 251-252 as read, 175 when the drag handle merged into it. */
    private const val TITLE_LEFT_MIN = 160.0 / 1600
    private const val TITLE_LEFT_MAX = 304.0 / 1600

    /** Left of this a title box may start with the drag handle read as a letter. */
    private const val TITLE_COLUMN = 240.0 / 1600

    /**
     * The PC's, x at 1740x978 kept from the centre ([PcGeometry]): titles start at x 210
     * (audit-evidence/spike/geo-recipes.json), so the column is the phone's 11 px gap scaled, and
     * the handle-merged minimum is the phone's 160 mapped about the centre, an estimate: no merged
     * title has been read on the PC.
     */
    private const val PC_TITLE_COLUMN = 197
    private const val PC_TITLE_LEFT_MIN = 112

    /** Below the tab row; the list starts at about y 105. */
    private const val LIST_TOP = 95.0 / 720

    /** A Use box's top below its title's top: 43 to 47 measured. */
    private const val USE_GAP_MIN = 25.0 / 720
    private const val USE_GAP_MAX = 70.0 / 720

    /**
     * No Use is taken higher than this. Boost Heroes ends at y 163 on the My Army tab, and a Use at
     * the top of a scrolled list (Use@1334,133) sits right where it is.
     */
    private const val USE_CENTRE_MIN = 170.0 / 720

    /**
     * The PC's, the phone's 170 mapped about the centre: Boost Heroes ends at y 247 of 978 there,
     * and the first card's Use is centred at 277. A fraction of the height holds wherever the
     * screen scales by the height. If it fits the width at 16:10 instead, Boost Heroes ends at
     * y 0.278 of the height, past this line, and the first Use sits at 0.305: unverified.
     */
    private const val PC_USE_CENTRE_MIN = 264.0 / 978

    private const val USE_SLACK = 4.0 / 720

    private val LIST_WORDS = setOf("use", "edit", "share")

    /** The read's look-alikes for a digit in a title's number. */
    private val DIGIT_LOOKALIKES = mapOf('s' to '5', 'o' to '0', 'i' to '1', 'l' to '1', '|' to '1')

    /** Lower case with spaces and punctuation gone; '|' stays as a 1's look-alike. */
    private fun compact(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '|' }

    private fun fraction(value: Int, origin: Int, size: Int) = (value - origin).toDouble() / size

    /**
     * The tab, by what each shows: the list's Use, Edit and Share, or My Army's Boost buttons and
     * "Tap to edit" slots. A boost button's "ARMY" or "HEROES" counts too, in case a running boost
     * shows its time where "Boost" was. Both or neither is [ArmyTab.UNKNOWN].
     */
    fun tab(boxes: List<RecognizedBox>, viewport: Viewport): ArmyTab {
        val listButtons = boxes.count { compact(it.text) in LIST_WORDS && BUTTON_COLUMN.holds(it.center, viewport) }
        val armyMarks = boxes.any {
            val text = compact(it.text)
            val boostButton = text.startsWith("boost") || text == "army" || text.startsWith("hero")
            (boostButton && BOOST_ROW.holds(it.center, viewport)) || text == "taptoedit"
        }
        return when {
            listButtons >= 2 && !armyMarks -> ArmyTab.SAVED_RECIPES
            // One stray "edit" split from "Tap to edit" does not hide the Boost buttons.
            armyMarks && listButtons < 2 -> ArmyTab.MY_ARMY
            else -> ArmyTab.UNKNOWN
        }
    }

    /**
     * The "Saved Recipes" tab label's centre, left of the tab's arrow, or null. A clipped read
     * such as "aved Recipes" (seen on 22 September) still names it.
     */
    fun savedRecipesLabel(boxes: List<RecognizedBox>, viewport: Viewport): Point? =
        boxes.firstOrNull { "recipe" in compact(it.text) && SAVED_RECIPES_LABEL.holds(it.center, viewport) }?.center

    /** The "My Army" tab label's centre, or null. */
    fun myArmyLabel(boxes: List<RecognizedBox>, viewport: Viewport): Point? =
        boxes.firstOrNull { compact(it.text) == "myarmy" && MY_ARMY_LABEL.holds(it.center, viewport) }?.center

    /**
     * Whether [read] is the card title [wanted]. Case and spaces do not count. A trailing number
     * must have as many digits as the wanted one, each the digit or its look-alike (S for 5, O for
     * 0, I, l or | for 1), so "Army 15" is not "Army 5"; everything before it must match exactly,
     * so "Imported Army 5" is not either. [strayAllowed] lets one leading character go, for a box
     * that starts left of the title column where the drag handle is read as a letter.
     */
    fun titleMatches(read: String, wanted: String, strayAllowed: Boolean): Boolean {
        val w = wanted.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (w.isEmpty()) return false
        val r = compact(read)
        return sameTitle(r, w) || (strayAllowed && r.length == w.length + 1 && sameTitle(r.substring(1), w))
    }

    private fun sameTitle(read: String, wanted: String): Boolean {
        if (read.length != wanted.length) return false
        val stem = wanted.length - wanted.takeLastWhile { it.isDigit() }.length
        return read.regionMatches(0, wanted, 0, stem) &&
            (stem until wanted.length).all { read[it] == wanted[it] || DIGIT_LOOKALIKES[read[it]] == wanted[it] }
    }

    /** Boxes whose left edge is where a card title starts, below the tabs, top first. */
    private fun titleBoxes(boxes: List<RecognizedBox>, viewport: Viewport): List<RecognizedBox> {
        if (viewport.width <= 0 || viewport.height <= 0) return emptyList()
        return boxes.filter {
            fraction(it.left, viewport.left, viewport.width) in PcGeometry.pick(viewport, TITLE_LEFT_MIN, PcGeometry.xFraction(viewport, PC_TITLE_LEFT_MIN, Anchor.CENTRE))..TITLE_LEFT_MAX &&
                fraction(it.top, viewport.top, viewport.height) >= LIST_TOP
        }.sortedBy { it.top }
    }

    /**
     * The topmost card titled [wanted] with its Use: the Use box nearest below the title within
     * the measured gap. A title whose Use is out of view, or too high to tap safely, is no card.
     */
    fun find(boxes: List<RecognizedBox>, viewport: Viewport, wanted: String): RecipeCard? {
        val uses = boxes.filter {
            compact(it.text) == "use" && BUTTON_COLUMN.holds(it.center, viewport) &&
                fraction(it.center.y, viewport.top, viewport.height) >= PcGeometry.pick(viewport, USE_CENTRE_MIN, PC_USE_CENTRE_MIN)
        }
        for (title in titleBoxes(boxes, viewport)) {
            val stray = fraction(title.left, viewport.left, viewport.width) < PcGeometry.pick(viewport, TITLE_COLUMN, PcGeometry.xFraction(viewport, PC_TITLE_COLUMN, Anchor.CENTRE))
            if (!titleMatches(title.text, wanted, stray)) continue
            val use = uses.filter { (it.top - title.top).toDouble() / viewport.height in USE_GAP_MIN..USE_GAP_MAX }
                .minByOrNull { it.top - title.top }
            if (use != null) return RecipeCard(title, use)
        }
        return null
    }

    /**
     * Whether [card] has its Use where [use] was read, give or take OCR's jitter: four pixels at
     * 720 high, so a tap moved three more still lands in the 18-pixel box.
     */
    fun useStillAt(card: RecipeCard?, use: Point, viewport: Viewport): Boolean {
        val centre = card?.use?.center ?: return false
        val slack = USE_SLACK * viewport.height
        return abs(centre.x - use.x) <= slack && abs(centre.y - use.y) <= slack
    }

    /**
     * What the look numbered [looks], counting the one that found the card, says about tapping its
     * Use: tap when [again] has it where [use] was last read; otherwise look again while looks are
     * left. A tab other than Saved Recipes is the list lost, whatever the frame reads.
     */
    fun useCheck(tab: ArmyTab, again: RecipeCard?, use: Point, looks: Int, viewport: Viewport): UseCheck = when {
        tab != ArmyTab.SAVED_RECIPES -> UseCheck.LOST_LIST
        useStillAt(again, use, viewport) -> UseCheck.TAP
        looks < USE_LOOKS -> UseCheck.LOOK_AGAIN
        else -> UseCheck.MOVED
    }

    /**
     * Where one look put the card titled [wanted], for the log: "title@y use@x,y" when [find] has
     * it; else "title@y no use" for a title with no Use in the gap below it, "title@y use@x,y too
     * high" for one whose Use is above the line a Use is taken below, or "no title".
     */
    fun describe(boxes: List<RecognizedBox>, viewport: Viewport, wanted: String): String {
        find(boxes, viewport, wanted)?.let { return "title@${it.title.center.y} use@${it.use.center.x},${it.use.center.y}" }
        val title = titleBoxes(boxes, viewport).firstOrNull {
            titleMatches(it.text, wanted, fraction(it.left, viewport.left, viewport.width) < PcGeometry.pick(viewport, TITLE_COLUMN, PcGeometry.xFraction(viewport, PC_TITLE_COLUMN, Anchor.CENTRE)))
        } ?: return "no title"
        val use = boxes.filter {
            compact(it.text) == "use" && BUTTON_COLUMN.holds(it.center, viewport) &&
                (it.top - title.top).toDouble() / viewport.height in USE_GAP_MIN..USE_GAP_MAX
        }.minByOrNull { it.top - title.top } ?: return "title@${title.center.y} no use"
        return "title@${title.center.y} use@${use.center.x},${use.center.y} too high"
    }

    /** What the title column reads, top first; the same after a swipe means the list did not move. */
    fun titles(boxes: List<RecognizedBox>, viewport: Viewport): List<String> =
        titleBoxes(boxes, viewport).map { compact(it.text) }

    /**
     * What to do with Attack! when a pause, or the shade's hold, came just before it, from a look
     * taken after the resume. The session has already moved on to the scout by then, so the army
     * screen is never handed back to the run loop. [retried] is whether My Army has been readied
     * once already after such a look.
     */
    fun attackAfterPause(state: ScreenState, tab: ArmyTab, retried: Boolean): AttackAfterPause = when (state) {
        ScreenState.ARMY -> when {
            tab == ArmyTab.MY_ARMY -> AttackAfterPause.SEND
            retried -> AttackAfterPause.STOP
            else -> AttackAfterPause.READY_AGAIN
        }
        ScreenState.SEARCHING, ScreenState.SCOUT, ScreenState.BATTLE -> AttackAfterPause.PASSED
        else -> AttackAfterPause.STOP
    }

    /** Clash's toast after Use: "Recipe Army 5 set as active army!". */
    fun loadToast(rawText: String): Boolean = compact(rawText).contains("activearmy")

    /**
     * Runs [find], given the try's number from 1, until it presses Use (null) or [FIND_TRIES] are
     * used, and returns the last miss. [USE_INTERRUPTED] is always the last: another find would
     * press Use a second time. [again] runs before each try after the first.
     */
    inline fun findTries(again: () -> Unit, find: (Int) -> String?): String? {
        var tries = 0
        while (true) {
            val miss = find(++tries) ?: return null
            if (miss == USE_INTERRUPTED || tries >= FIND_TRIES) return miss
            again()
        }
    }

    /** A find's miss when a look before Use found the list gone or Use somewhere else; tried again. */
    fun useMiss(check: UseCheck): String = if (check == UseCheck.LOST_LIST) "lost_list" else "moved"

    /**
     * How a find ends when the look a pause called for at its Use tap gave [check], not
     * [UseCheck.TAP], and the look after that was on [tab] with [rawText]. [sent] is whether the tap
     * had gone out before the pause. If it had not, Use was never pressed, and the find is a miss
     * like any other ([useMiss]). If it had, the pause cut it short and Clash may have taken it,
     * so neither answer leads to another find: the toast or My Army is the load going through
     * (null, confirmed as after any Use), anything else [USE_INTERRUPTED].
     */
    fun afterInterruptedUse(sent: Boolean, check: UseCheck, tab: ArmyTab, rawText: String): String? = when {
        !sent -> useMiss(check)
        loadToast(rawText) || tab == ArmyTab.MY_ARMY -> null
        else -> USE_INTERRUPTED
    }

    /**
     * One swipe up the list, about a card and a fifth, along x 1080: clear of the drag handles at
     * the left, the Use column at the right and the bot's panel at the top left. Held at its end
     * ([HOLD_MS]), it should move the list the drag's 300 px and no further, so a card is never
     * carried past the top in one step and each comes into view with its Use below its title.
     * Reversed, it is the way back up for a second try. On the PC the cards are 296 px apart at
     * 1740x978, so the same card and a fifth is 355 px, along the phone's x 1080 mapped to 1202 and
     * kept from the centre.
     */
    fun listSwipe(viewport: Viewport): Pair<Point, Point> = PcGeometry.pick(viewport,
        scaled(viewport, 1080.0 / 1600, 600.0 / 720) to scaled(viewport, 1080.0 / 1600, 300.0 / 720),
        PcGeometry.point(viewport, 1202, 773, Anchor.CENTRE) to PcGeometry.point(viewport, 1202, 418, Anchor.CENTRE))

    private fun scaled(viewport: Viewport, fx: Double, fy: Double) = Point(
        viewport.left + (viewport.width * fx).roundToInt(),
        viewport.top + (viewport.height * fy).roundToInt()
    )
}
