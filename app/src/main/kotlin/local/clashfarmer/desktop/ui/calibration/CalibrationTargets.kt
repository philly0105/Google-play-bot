package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.WallConfig
import local.clashfarmer.recognition.WallCurrencyConfig

/**
 * One point calibration saves: its key, its line in the list, what to click on the frame, the
 * game screen it is on ([group]), and whether an attack needs it ([required]).
 */
class CalibrationTarget(
    val name: String,
    val label: String,
    val description: String,
    val group: String,
    val required: Boolean = false
)

/** The name of the one target Apply adds to a list instead of setting. */
internal const val COLLECTOR = "collector"

/** The game area's corners, which a capture sets on the PC ([targetEnabled]). */
internal val VIEWPORT_TARGETS = setOf("vp_tl", "vp_br")

/** The game screens [CALIBRATION_TARGETS] are grouped by, as the list's headers say them. */
private const val GAME_AREA = "Game area"
private const val VILLAGE = "Village"
private const val ATTACK_MENU = "Attack menu and army"
private const val SCOUT = "Scout screen"
private const val TRAY = "Battle tray"
private const val MAP = "Battle map"
private const val RESULT = "Battle result"
private const val WALLS = "Wall panel"
private const val COLLECTORS = "Collectors"

/**
 * The phone's 38 targets, grouped by the game screen each is clicked on, in the order Apply walks
 * them: a user captures one screen and sets its points before moving on. The phone numbered them
 * in the order they were added, with the attack's screens mixed. The descriptions say "click"
 * where the phone said "tap", and the viewport corners no longer mention the phone's status and
 * navigation bars: the PC frame is the game surface alone.
 *
 * The troop cards are named for what the attack does with them (AttackPlanner), not for the
 * owner's army: the main troop is dropped all around the base, the spell is cast five times
 * inside it, the optional jump spell once in the middle, and each hero only when its card is set.
 * [CalibrationTarget.required] marks what [PhoneProfile.validateForAttack] asks for.
 */
val CALIBRATION_TARGETS: List<CalibrationTarget> = listOf(
    CalibrationTarget("vp_tl", "Game area, top-left corner", "Click the top-left corner of the Clash game area.", GAME_AREA),
    CalibrationTarget("vp_br", "Game area, bottom-right corner", "Click the bottom-right corner of the Clash game area.", GAME_AREA),
    CalibrationTarget("village_neutral", "Empty grass",
        "Click empty grass in the village, away from buildings. The bot clicks here to close windows safely.", VILLAGE),
    CalibrationTarget("attack", "Attack! button", "Click the Attack! button in the village's bottom-left corner.", VILLAGE, required = true),
    CalibrationTarget("home_gold", "Gold balance", "Click the middle of your gold amount at the top right of the village.", VILLAGE),
    CalibrationTarget("home_elixir", "Elixir balance", "Click the middle of your elixir amount at the top right of the village.", VILLAGE),
    CalibrationTarget("home_dark", "Dark elixir balance", "Click the middle of your dark elixir amount at the top right of the village.", VILLAGE),
    CalibrationTarget("find_match", "Find a Match button", "After Attack!, click Find a Match.", ATTACK_MENU, required = true),
    CalibrationTarget("army", "Attack! on the army screen", "After Find a Match, Clash shows your army. Click its Attack! button.", ATTACK_MENU),
    CalibrationTarget("next", "Next button", "On a scout screen, click the Next button.", SCOUT),
    CalibrationTarget("scout_gold", "Gold loot", "Click the first digit of the gold under Available Loot.", SCOUT),
    CalibrationTarget("scout_elixir", "Elixir loot", "Click the first digit of the elixir under Available Loot.", SCOUT),
    CalibrationTarget("scout_dark", "Dark elixir loot", "Click the first digit of the dark elixir under Available Loot.", SCOUT),
    CalibrationTarget("scout_countdown", "Countdown", "Click the middle of the seconds under Battle starts in.", SCOUT),
    CalibrationTarget("scout_fee", "Next price", "Click just left of the gold coin beside the price under Next.", SCOUT),
    CalibrationTarget("end_battle", "End Battle button", "Click the End Battle button at the bottom left.", SCOUT),
    CalibrationTarget("valkyries", "Main troop card (e.g. Valkyries)",
        "The troop the bot drops all around the base, such as Valkyries. Click the middle of its card. " +
            "Use a tray without the event card.", TRAY, required = true),
    CalibrationTarget("rages", "Spell cast inside (e.g. Rage)",
        "The spell the bot casts five times inside the base, such as Rage. Click the middle of its card.", TRAY, required = true),
    CalibrationTarget("jumps", "Jump spell card (optional)",
        "Optional: a spell the bot casts once in the middle of the base, such as Jump. Click the middle of its card. " +
            "If you don't bring one, press Not in my army.", TRAY),
    CalibrationTarget("king", "Barbarian King (if you have it)",
        "Click the middle of the Barbarian King's card. If you don't have it, press Not in my army.", TRAY),
    CalibrationTarget("queen", "Archer Queen (if you have it)",
        "Click the middle of the Archer Queen's card. If you don't have it, press Not in my army.", TRAY),
    CalibrationTarget("warden", "Grand Warden (if you have it)",
        "Click the middle of the Grand Warden's card. If you don't have it, press Not in my army.", TRAY),
    CalibrationTarget("champion", "Fourth hero (if you have it)",
        "Click the middle of the Royal Champion's card, or your fourth hero's. If you don't have one, press Not in my army.", TRAY),
    CalibrationTarget("hero_drop", "Hero drop point", "Click where the heroes should land, just outside the base.", MAP),
    CalibrationTarget("perim_1_start", "Edge 1 start", "Click the start of the first edge the main troop is dropped along.", MAP, required = true),
    CalibrationTarget("perim_1_end", "Edge 1 end", "Click the end of the first edge.", MAP, required = true),
    CalibrationTarget("perim_2_start", "Edge 2 start", "Click the start of the second edge.", MAP, required = true),
    CalibrationTarget("perim_2_end", "Edge 2 end", "Click the end of the second edge.", MAP, required = true),
    CalibrationTarget("perim_3_start", "Edge 3 start", "Click the start of the third edge.", MAP, required = true),
    CalibrationTarget("perim_3_end", "Edge 3 end", "Click the end of the third edge.", MAP, required = true),
    CalibrationTarget("perim_4_start", "Edge 4 start", "Click the start of the fourth edge.", MAP, required = true),
    CalibrationTarget("perim_4_end", "Edge 4 end", "Click the end of the fourth edge.", MAP, required = true),
    CalibrationTarget("return_home", "Return Home button", "After a battle, click Return Home on the result screen.", RESULT, required = true),
    CalibrationTarget("bulk_button", "Upgrade More button", "With a wall selected, click Upgrade More.", WALLS),
    CalibrationTarget("remove_button", "Remove wall button", "On the wall panel, click Remove Wall (the minus button).", WALLS),
    CalibrationTarget("gold_cost_btn", "Gold upgrade button", "On the wall panel, click the gold upgrade button.", WALLS),
    CalibrationTarget("elixir_cost_btn", "Elixir upgrade button", "On the wall panel, click the elixir upgrade button.", WALLS),
    CalibrationTarget(COLLECTOR, "Collector (each Apply adds one)",
        "Show the camera reference, then click a gold mine, elixir collector or drill. Each Apply adds one.", COLLECTORS)
)

/** The targets the progress line counts: all but the game area's corners, which always have a value. */
internal val COUNTED_TARGETS: List<CalibrationTarget> = CALIBRATION_TARGETS.filter { it.name !in VIEWPORT_TARGETS }

/**
 * Whether [name] may be picked and applied on [profile]. A PC capture always fills the frame, so
 * the game area's corners are set by Capture there: PcGeometry scales by the viewport, and a
 * stray click on a corner would shrink it. A phone-shaped profile keeps them.
 */
internal fun targetEnabled(profile: PhoneProfile, name: String): Boolean =
    name !in VIEWPORT_TARGETS || !PcGeometry.isPc(profile.viewport)

/**
 * Whether [profile] has a value for the target [name]. Apply fills an edge's missing end with the
 * clicked point, so an edge with both ends on one point does not say which end was clicked: an
 * end counts once the edge has two different ends, or once it is in [applied], the edge ends
 * applied on this visit.
 */
internal fun isSet(profile: PhoneProfile, name: String, applied: Set<String> = emptySet()): Boolean {
    perimeterEnd(name)?.let { (index, start) ->
        val (first, second) = profile.perimeterSegments.getOrNull(index) ?: return false
        if ((if (start) first else second) == Point(0, 0)) return false
        return first != second || name in applied
    }
    return when (name) {
        in VIEWPORT_TARGETS -> true
        in TROOP_CARDS -> name in profile.troopAnchors
        "hero_drop" -> profile.heroDrop != null
        "bulk_button" -> profile.wallConfig?.bulkButton != null
        "remove_button" -> profile.wallConfig?.removeButton != null
        "gold_cost_btn" -> profile.wallConfig?.currencies?.containsKey("gold") == true
        "elixir_cost_btn" -> profile.wallConfig?.currencies?.containsKey("elixir") == true
        COLLECTOR -> profile.collectionTargets.isNotEmpty()
        else -> name in profile.stateAnchors || savedBox(profile, name) != null
    }
}

/**
 * The point saved for the target [name] on [profile], drawn on the frame and where the arrow keys
 * start from; null for a box target, the collector, the game area's corners and a target not set.
 */
internal fun savedPoint(profile: PhoneProfile, name: String, applied: Set<String> = emptySet()): Point? {
    if (!isSet(profile, name, applied)) return null
    perimeterEnd(name)?.let { (index, start) ->
        val edge = profile.perimeterSegments[index]
        return if (start) edge.first else edge.second
    }
    return when (name) {
        in TROOP_CARDS -> profile.troopAnchors[name]
        "hero_drop" -> profile.heroDrop
        "bulk_button" -> profile.wallConfig?.bulkButton
        "remove_button" -> profile.wallConfig?.removeButton
        "gold_cost_btn" -> profile.wallConfig?.currencies?.get("gold")?.buttonPoint
        "elixir_cost_btn" -> profile.wallConfig?.currencies?.get("elixir")?.buttonPoint
        else -> profile.stateAnchors[name]
    }
}

/** The box saved for the target [name] on [profile]: a card's area or where a number is read; null for the rest. */
internal fun savedBox(profile: PhoneProfile, name: String): Region? = when (name) {
    in TROOP_CARDS -> profile.troopRois[name]
    "scout_gold" -> profile.scoutLootRois[Resource.GOLD]
    "scout_elixir" -> profile.scoutLootRois[Resource.ELIXIR]
    "scout_dark" -> profile.scoutLootRois[Resource.DARK_ELIXIR]
    "scout_countdown" -> profile.scoutCountdownRoi
    "scout_fee" -> profile.scoutFeeRoi
    "home_gold" -> profile.homeResourceRois[Resource.GOLD]
    "home_elixir" -> profile.homeResourceRois[Resource.ELIXIR]
    "home_dark" -> profile.homeResourceRois[Resource.DARK_ELIXIR]
    else -> null
}

private val TROOP_CARDS = setOf("valkyries", "rages", "jumps", "king", "queen", "warden", "champion")

/**
 * The cards an army may lack: the jump spell and the four heroes. The attack uses every one whose
 * card is set (AttackPlanner), so a hero card set where the user has no such hero drops whatever
 * sits in that slot as a hero, or the tray check stops the run on the count badge there.
 */
internal val OPTIONAL_CARDS = setOf("jumps", "king", "queen", "warden", "champion")

/** Whether [name] is a perimeter edge's start or end. */
internal fun isPerimeterEnd(name: String): Boolean = perimeterEnd(name) != null

/**
 * [profile] without the card [name], for "Not in my army": its click point and card area go, so
 * the attack skips it. Anything but a set optional card leaves the profile as it was.
 */
internal fun withoutCard(profile: PhoneProfile, name: String): PhoneProfile {
    if (name !in OPTIONAL_CARDS || (name !in profile.troopAnchors && name !in profile.troopRois)) return profile
    return profile.copy(troopAnchors = profile.troopAnchors - name, troopRois = profile.troopRois - name, isVerified = false)
}

/** The perimeter edge [name] belongs to, from 0, and whether it is the edge's start; null for any other target. */
private fun perimeterEnd(name: String): Pair<Int, Boolean>? {
    val match = PERIMETER.matchEntire(name) ?: return null
    return match.groupValues[1].toInt() - 1 to (match.groupValues[2] == "start")
}

private val PERIMETER = Regex("""perim_(\d)_(start|end)""")

/**
 * [profile] with [pt] saved for the target named [name], as the phone's Apply wrote it. The
 * collector target is not handled here: it adds to a list, under rules of its own. An unknown
 * name leaves the profile as it was. On the PC's frames each box Apply draws around [pt] is the
 * PC's size ([PC_LOOT_ROW]).
 */
internal fun applyTarget(profile: PhoneProfile, name: String, pt: Point): PhoneProfile = when (name) {
    "vp_tl" -> profile.copy(viewport = profile.viewport.copy(left = pt.x, top = pt.y), isVerified = false)
    "vp_br" -> profile.copy(viewport = profile.viewport.copy(right = pt.x, bottom = pt.y), isVerified = false)
    "village_neutral" -> profile.withAnchor("village_neutral", pt)
    "attack" -> profile.withAnchor("attack", pt)
    "find_match" -> profile.withAnchor("find_match", pt)
    "army" -> profile.withAnchor("army", pt)
    "return_home" -> profile.withAnchor("return_home", pt)
    "end_battle" -> profile.withAnchor("end_battle", pt)
    "valkyries" -> profile.withTroopCard("valkyries", pt)
    "rages" -> profile.withTroopCard("rages", pt)
    "jumps" -> profile.withTroopCard("jumps", pt)
    "hero_drop" -> profile.withHeroDrop(pt)
    "king" -> profile.withTroopCard("king", pt)
    "queen" -> profile.withTroopCard("queen", pt)
    "warden" -> profile.withTroopCard("warden", pt)
    "champion" -> profile.withTroopCard("champion", pt)
    "perim_1_start" -> profile.withPerimeterSegment(0, pt, profile.perimeterSegments.getOrNull(0)?.second ?: pt)
    "perim_1_end" -> profile.withPerimeterSegment(0, profile.perimeterSegments.getOrNull(0)?.first ?: pt, pt)
    "perim_2_start" -> profile.withPerimeterSegment(1, pt, profile.perimeterSegments.getOrNull(1)?.second ?: pt)
    "perim_2_end" -> profile.withPerimeterSegment(1, profile.perimeterSegments.getOrNull(1)?.first ?: pt, pt)
    "perim_3_start" -> profile.withPerimeterSegment(2, pt, profile.perimeterSegments.getOrNull(2)?.second ?: pt)
    "perim_3_end" -> profile.withPerimeterSegment(2, profile.perimeterSegments.getOrNull(2)?.first ?: pt, pt)
    "perim_4_start" -> profile.withPerimeterSegment(3, pt, profile.perimeterSegments.getOrNull(3)?.second ?: pt)
    "perim_4_end" -> profile.withPerimeterSegment(3, profile.perimeterSegments.getOrNull(3)?.first ?: pt, pt)
    "bulk_button" -> {
        val currentWc = profile.wallConfig ?: WallConfig()
        profile.copy(wallConfig = currentWc.copy(bulkButton = pt), isVerified = false)
    }
    "remove_button" -> {
        val currentWc = profile.wallConfig ?: WallConfig()
        profile.copy(wallConfig = currentWc.copy(removeButton = pt), isVerified = false)
    }
    "gold_cost_btn" -> withWallButton(profile, "gold", pt)
    "elixir_cost_btn" -> withWallButton(profile, "elixir", pt)
    "next" -> profile.withAnchor("next", pt)
    "scout_gold" -> withScoutLoot(profile, Resource.GOLD, pt)
    "scout_elixir" -> withScoutLoot(profile, Resource.ELIXIR, pt)
    "scout_dark" -> withScoutLoot(profile, Resource.DARK_ELIXIR, pt)
    "scout_countdown" -> profile.copy(
        scoutCountdownRoi = around(profile.viewport, pt, Region(-50, -16, 50, 18), PC_COUNTDOWN), isVerified = false
    )
    // Ends at the tap so the coin, which reads as another zero, stays out.
    "scout_fee" -> profile.copy(
        scoutFeeRoi = around(profile.viewport, pt, Region(-84, -15, 4, 15), PC_FEE), isVerified = false
    )
    "home_gold" -> withHomeBalance(profile, Resource.GOLD, pt)
    "home_elixir" -> withHomeBalance(profile, Resource.ELIXIR, pt)
    "home_dark" -> withHomeBalance(profile, Resource.DARK_ELIXIR, pt)
    else -> profile
}

/**
 * A wall upgrade button for [currency]. The first one also sets the cost box above it; later
 * ones move the button only, so a cost box measured since is kept.
 */
private fun withWallButton(profile: PhoneProfile, currency: String, pt: Point): PhoneProfile {
    val currentWc = profile.wallConfig ?: WallConfig()
    val curMap = currentWc.currencies.toMutableMap()
    val existing = curMap[currency] ?: WallCurrencyConfig(
        costRoi = around(profile.viewport, pt, WALL_COST, WALL_COST),
        buttonPoint = pt
    )
    curMap[currency] = existing.copy(buttonPoint = pt)
    return profile.copy(wallConfig = currentWc.copy(currencies = curMap), isVerified = false)
}

/** A scout loot row starting at the tapped first digit, wide enough for seven digits. */
private fun lootRow(viewport: Viewport, pt: Point) = around(viewport, pt, Region(-4, -15, 144, 15), PC_LOOT_ROW)

private fun withScoutLoot(profile: PhoneProfile, resource: Resource, pt: Point) =
    profile.copy(scoutLootRois = profile.scoutLootRois + (resource to lootRow(profile.viewport, pt)), isVerified = false)

/** A goal balance, centred on the tap: 194x36 is the gold region measured on the phone. */
private fun withHomeBalance(profile: PhoneProfile, resource: Resource, pt: Point) = profile.copy(
    homeResourceRois = profile.homeResourceRois + (resource to around(profile.viewport, pt, Region(-97, -18, 97, 18), PC_BALANCE)),
    isVerified = false
)

/**
 * A box around [pt]: [phone] holds its edges' distances from [pt] on the phone's frames, and [pc]
 * on the PC's, in pixels of 1740x978 that scale with the frame's height ([PcGeometry]).
 */
private fun around(viewport: Viewport, pt: Point, phone: Region, pc: Region): Region {
    if (!PcGeometry.isPc(viewport)) return Region(pt.x + phone.left, pt.y + phone.top, pt.x + phone.right, pt.y + phone.bottom)
    fun scaled(pixels: Int) = PcGeometry.length(viewport, pixels)
    return Region(pt.x + scaled(pc.left), pt.y + scaled(pc.top), pt.x + scaled(pc.right), pt.y + scaled(pc.bottom))
}

/**
 * Apply's boxes on the PC, this and the three below, in pixels of 1740x978. The PC draws the
 * scout's panels and the village balances about 1.13 times the phone's size there, so each is the
 * phone's box times 1.13: its loot rows are 43 px apart where Sweat's phone had 38, and its
 * balances 56 px apart where Blood's phone had 49 (audit-evidence/spike/geo-scout.png and
 * geo-home.png, and the phone tree's audit-evidence/clash-18647/r4-07-sweat-scout-a.png and
 * r4-01-blood-home.png).
 */
private val PC_LOOT_ROW = Region(-5, -17, 163, 17)
private val PC_COUNTDOWN = Region(-57, -18, 57, 20)
private val PC_FEE = Region(-95, -17, 5, 17)
private val PC_BALANCE = Region(-110, -20, 110, 20)

/**
 * A wall price's box, above the button tapped, on both: the PC draws a selected building's buttons
 * at the phone's pixel size at 1740x978 (core/WallPanel.kt). No wall price has been read on the PC.
 */
private val WALL_COST = Region(-50, -40, 50, -10)
