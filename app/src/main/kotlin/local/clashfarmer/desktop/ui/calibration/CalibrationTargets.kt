package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.WallConfig
import local.clashfarmer.recognition.WallCurrencyConfig

/** One point calibration saves: its key, its line in the list, and what to click on the frame. */
class CalibrationTarget(val name: String, val label: String, val description: String)

/** The name of the one target Apply adds to a list instead of setting. */
internal const val COLLECTOR = "collector"

/**
 * The phone's 38 targets in the phone's order, which Apply walks. The descriptions say "click"
 * where the phone said "tap", and the viewport corners no longer mention the phone's status and
 * navigation bars: the PC frame is the game surface alone.
 */
val CALIBRATION_TARGETS: List<CalibrationTarget> = listOf(
    CalibrationTarget("vp_tl", "1. Viewport Top-Left", "Click the top-left edge of the Clash rendering area."),
    CalibrationTarget("vp_br", "2. Viewport Bottom-Right", "Click the bottom-right edge of the Clash rendering area."),
    CalibrationTarget("village_neutral", "3. Village Neutral Grass", "Click empty grass in village to dismiss popups safely."),
    CalibrationTarget("attack", "4. Button: Attack", "Click the 'Attack' button on Home screen."),
    CalibrationTarget("find_match", "5. Button: Find Match", "Click 'Find a Match' button on Attack menu."),
    CalibrationTarget("army", "6. Tab: Army", "Click 'Army' / 'Train Troops' tab."),
    CalibrationTarget("return_home", "7. Button: Return Home", "Click 'Return Home' button on Battle Result screen."),
    CalibrationTarget("end_battle", "8. Button: End Battle", "Click 'End Battle' / 'Surrender' during battle."),
    CalibrationTarget("valkyries", "9. Troop: Valkyries", "Click the middle of the Valkyries card on battle deployment bar. Use a bar without the event card."),
    CalibrationTarget("rages", "10. Spell: Rage", "Click the middle of the Rage spell card on battle deployment bar."),
    CalibrationTarget("jumps", "11. Spell: Jump (Optional)", "Click the middle of the Jump spell card on battle deployment bar."),
    CalibrationTarget("hero_drop", "12. Hero Drop Point", "Click default deployment location for heroes."),
    CalibrationTarget("king", "13. Hero: Barbarian King", "Click the middle of the King card on deployment bar."),
    CalibrationTarget("queen", "14. Hero: Archer Queen", "Click the middle of the Queen card on deployment bar."),
    CalibrationTarget("warden", "15. Hero: Grand Warden", "Click the middle of the Warden card on deployment bar."),
    CalibrationTarget("champion", "16. Hero: Royal Champion", "Click the middle of the Royal Champion card, or your fourth hero's, on deployment bar."),
    CalibrationTarget("perim_1_start", "17. Perimeter 1 Start", "Click start corner of Perimeter Edge 1."),
    CalibrationTarget("perim_1_end", "18. Perimeter 1 End", "Click end corner of Perimeter Edge 1."),
    CalibrationTarget("perim_2_start", "19. Perimeter 2 Start", "Click start corner of Perimeter Edge 2."),
    CalibrationTarget("perim_2_end", "20. Perimeter 2 End", "Click end corner of Perimeter Edge 2."),
    CalibrationTarget("perim_3_start", "21. Perimeter 3 Start", "Click start corner of Perimeter Edge 3."),
    CalibrationTarget("perim_3_end", "22. Perimeter 3 End", "Click end corner of Perimeter Edge 3."),
    CalibrationTarget("perim_4_start", "23. Perimeter 4 Start", "Click start corner of Perimeter Edge 4."),
    CalibrationTarget("perim_4_end", "24. Perimeter 4 End", "Click end corner of Perimeter Edge 4."),
    CalibrationTarget("bulk_button", "25. Wall: Upgrade More Button", "Click 'Upgrade More' button on wall panel."),
    CalibrationTarget("remove_button", "26. Wall: Remove Wall Button", "Click 'Remove Wall' / minus button on wall panel."),
    CalibrationTarget("gold_cost_btn", "27. Wall: Gold Upgrade Button", "Click Gold upgrade button on wall panel."),
    CalibrationTarget("elixir_cost_btn", "28. Wall: Elixir Upgrade Button", "Click Elixir upgrade button on wall panel."),
    CalibrationTarget("next", "29. Scout: Next Button", "On a scout screen, click the 'Next' button."),
    CalibrationTarget("scout_gold", "30. Scout: Gold Loot", "Click the first digit of the gold under 'Available Loot'."),
    CalibrationTarget("scout_elixir", "31. Scout: Elixir Loot", "Click the first digit of the elixir under 'Available Loot'."),
    CalibrationTarget("scout_dark", "32. Scout: Dark Elixir Loot", "Click the first digit of the dark elixir under 'Available Loot'."),
    CalibrationTarget("scout_countdown", "33. Scout: Countdown", "Click the middle of the seconds under 'Battle starts in'."),
    CalibrationTarget("scout_fee", "34. Scout: Next Price", "Click just left of the gold coin beside the price under 'Next'."),
    CalibrationTarget("home_gold", "35. Village: Gold Balance", "On the village screen, click the middle of your gold amount."),
    CalibrationTarget("home_elixir", "36. Village: Elixir Balance", "On the village screen, click the middle of your elixir amount."),
    CalibrationTarget("home_dark", "37. Village: Dark Elixir Balance", "On the village screen, click the middle of your dark elixir amount."),
    CalibrationTarget(COLLECTOR, "38. Collector (adds one)", "Show the camera reference, then click a gold mine, elixir collector or drill. Each Apply adds one.")
)

/**
 * [profile] with [pt] saved for the target named [name], as the phone's Apply wrote it. The
 * collector target is not handled here: it adds to a list, under rules of its own. An unknown
 * name leaves the profile as it was.
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
        scoutCountdownRoi = Region(pt.x - 50, pt.y - 16, pt.x + 50, pt.y + 18), isVerified = false
    )
    // Ends at the tap so the coin, which reads as another zero, stays out.
    "scout_fee" -> profile.copy(
        scoutFeeRoi = Region(pt.x - 84, pt.y - 15, pt.x + 4, pt.y + 15), isVerified = false
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
        costRoi = Region(pt.x - 50, pt.y - 40, pt.x + 50, pt.y - 10),
        buttonPoint = pt
    )
    curMap[currency] = existing.copy(buttonPoint = pt)
    return profile.copy(wallConfig = currentWc.copy(currencies = curMap), isVerified = false)
}

/** A scout loot row starting at the tapped first digit, wide enough for seven digits. */
private fun lootRow(pt: Point) = Region(pt.x - 4, pt.y - 15, pt.x + 144, pt.y + 15)

private fun withScoutLoot(profile: PhoneProfile, resource: Resource, pt: Point) =
    profile.copy(scoutLootRois = profile.scoutLootRois + (resource to lootRow(pt)), isVerified = false)

/** A goal balance, centred on the tap: 194x36 is the gold region measured on the phone. */
private fun withHomeBalance(profile: PhoneProfile, resource: Resource, pt: Point) = profile.copy(
    homeResourceRois = profile.homeResourceRois + (resource to Region(pt.x - 97, pt.y - 18, pt.x + 97, pt.y + 18)),
    isVerified = false
)
