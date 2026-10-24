package local.clashfarmer.core

import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

data class DeploymentGroup(
    val name: String,
    val selectPoint: Point,
    val drops: List<Point>,
    val cardRoi: Region? = null,
    /** A hero: placed alongside the other heroes; its ability is fired once by [HeroAbilities]. */
    val isHero: Boolean = false,
    val isSiege: Boolean = false,
    /**
     * A spell: spent when its card greys or its badge counts down; retried once at most, and
     * only when its reading moved.
     */
    val isSpell: Boolean = false,
    /** Not in every army: its card is checked after the heroes rather than holding them up. */
    val isOptional: Boolean = false
)

object AttackPlanner {

    /**
     * How far a drop may be nudged from the point the plan computed for it, in pixels.
     *
     * Two attacks built from the same profile used to land the heroes on one identical pixel,
     * the jump dead centre, and the siege on the same corner every single time - the army
     * spread varied and nothing else did. The whole attack should look a little different each
     * time it is run, so every point gets a nudge. These are small enough on a 1600x720 screen
     * zoomed all the way out that a point calibrated onto deployable ground stays on it.
     */
    private const val DROP_JITTER = 18
    private const val HERO_JITTER = 28
    private const val CENTER_JITTER = 34

    /** How far apart a hero's fallback drops sit, in pixels. */
    private const val HERO_SPREAD = 70

    /** The same point, moved by up to [radius] in each direction and kept inside the viewport. */
    private fun nudge(point: Point, radius: Int, rng: Random, viewport: Viewport): Point {
        val x = point.x + rng.nextInt(-radius, radius + 1)
        val y = point.y + rng.nextInt(-radius, radius + 1)
        return Point(
            x.coerceIn(viewport.left + 1, viewport.right - 2),
            y.coerceIn(viewport.top + 1, viewport.bottom - 2)
        )
    }

    /**
     * Where a hero may be put down, best first.
     *
     * A rock or a tree standing in the drop zone swallows the tap that lands on it and says
     * nothing about it, and each hero carried exactly one point, so the three retries re-tapped
     * the pixel the game had already refused. On 18 September that left a hero standing in its
     * card on three attacks out of eight. The fallbacks step along the perimeter the drop was
     * calibrated against, far enough apart that one obstacle cannot cover two of them, and near
     * enough that the four heroes still land together.
     */
    private fun heroDrops(
        heroDrop: Point,
        segment: Pair<Point, Point>,
        rng: Random,
        viewport: Viewport,
        count: Int = SPREAD_COUNT
    ): List<Point> {
        val dx = (segment.second.x - segment.first.x).toDouble()
        val dy = (segment.second.y - segment.first.y).toDouble()
        val length = hypot(dx, dy).takeIf { it > 1.0 } ?: 1.0
        return listOf(0, HERO_SPREAD, -HERO_SPREAD).map { offset ->
            val stepped = Point(
                heroDrop.x + (dx / length * offset).roundToInt(),
                heroDrop.y + (dy / length * offset).roundToInt()
            )
            nudge(stepped, HERO_JITTER, rng, viewport)
        }
    }

    /**
     * How many event troops the event puts in every army (October 2026: 50 random troops), and so
     * how many drops the event group taps when its card's badge is not read (see [dropCounts]).
     * The tray is told apart by its badges, not this number.
     */
    const val EVENT_TROOP_COUNT = 50

    /**
     * The tray as it is drawn while a troop event lasts (temporary; first the Kane event of
     * September 2026, now the random-troop event of October 2026).
     *
     * The event adds [EVENT_TROOP_COUNT] troops to every army, and their card takes the first
     * slot: every card the profile calibrated moves one card width to the right. The width is
     * the gap between two neighbouring hero cards. The saved profile is left alone, so the
     * ordinary attack is right again the day the event ends.
     *
     * The game does not keep the event and Valkyrie cards in the same order: the scout at 02:49
     * UTC on 25 September had Kanes first, and the battle straight after had Valkyries first.
     * That is harmless. Both groups are spread the same way, and each one checks the slot it
     * tapped, so whichever card sits in either slot is placed and confirmed spent.
     */
    fun withEventCard(profile: PhoneProfile): PhoneProfile {
        val first = profile.troopAnchors["valkyries"]
            ?: throw StopException("Missing calibrated Valkyries troop card select anchor")
        val pitch = cardPitch(profile)
            ?: throw StopException("The event attack needs the king and queen cards calibrated to measure the tray")
        val anchors = profile.troopAnchors.mapValues { (_, p) -> Point(p.x + pitch, p.y) }
        val rois = profile.troopRois.mapValues { (_, r) -> Region(r.left + pitch, r.top, r.right + pitch, r.bottom) }
        return profile.copy(
            troopAnchors = anchors + ("event" to first),
            troopRois = rois + listOfNotNull(profile.troopRois["valkyries"]?.let { "event" to it })
        )
    }

    /** One card width in the tray: the gap between two neighbouring hero cards. */
    private fun cardPitch(profile: PhoneProfile): Int? {
        val king = profile.troopAnchors["king"] ?: return null
        val queen = profile.troopAnchors["queen"] ?: return null
        return queen.x - king.x
    }

    /**
     * Which tray the scout shows: with the event card in front, without it, unread, or read with
     * evidence that contradicts itself. Only [EVENT] shifts the tray.
     */
    enum class TrayLayout { EVENT, PLAIN, UNKNOWN, AMBIGUOUS }

    /** How far above the calibrated card ROI the count badge starts, and how far into it it ends. */
    private const val BADGE_ABOVE = 5
    private const val BADGE_BELOW = 20

    /**
     * How far past the calibrated card ROI's right edge the badge band runs. A badge's last digit
     * overhangs its card by 3 to 6 px: on Sweat's 18.647 scout the x40 ends at 338 against 335,
     * the x5 at 845 against 842 and the x1 at 941 against 938, and Blood's event-tray x11 at
     * about 1040 against 1035. Cut at the ROI edge, x40 read "x4C" on three or four cuts of every
     * Sweat scout that read a badge on 14 October, the x1 jump read no count at all, and x11 read
     * x1. The next card's badge starts at least 35 px further right, so 8 px reaches no neighbour.
     */
    private const val BADGE_RIGHT = 8

    /**
     * The count badge bands of the first two card slots, slot 1 first.
     *
     * The badge is white "xNN" text with a dark outline at the top right of a troop card. On
     * Blood's event scout of 7 October, "x50" sat at x 282-328 and "x44" at x 380-425, both at
     * y 597-617, inside the x span of the calibrated Valkyrie ROI (269-335) and of that ROI moved
     * one card right. Slot 1 is where the calibrated Valkyries sit; slot 2 is one card width
     * further right. Null when the Valkyrie ROI or the two heroes that measure a card width are
     * not calibrated, which leaves the tray unread.
     */
    fun trayBadgeRegions(profile: PhoneProfile): List<Region>? {
        val roi = profile.troopRois["valkyries"] ?: return null
        val pitch = cardPitch(profile) ?: return null
        val band = badgeBand(roi)
        return listOf(band, Region(band.left + pitch, band.top, band.right + pitch, band.bottom))
    }

    /**
     * The "xNN" badge band across the top of the card whose calibrated ROI is [roi], running
     * [BADGE_RIGHT] past it so the last digit is whole. Spell badges sit where troop badges do:
     * Blood's "x11" and "x1" at y 597-617 on 7 October. The ROI itself is unchanged, so a card's
     * saturation is still measured on that card alone.
     */
    fun badgeBand(roi: Region): Region =
        Region(roi.left, (roi.top - BADGE_ABOVE).coerceAtLeast(0), roi.right + BADGE_RIGHT, roi.top + BADGE_BELOW)

    private val BADGE = Regex("[xX\u00D7]\\s*(\\d{1,3})(?![\\dA-Za-z])")

    /** An x followed by a digit: a badge, clean or misread ("x4C"), as opposed to other text. */
    private val BADGE_LIKE = Regex("[xX\u00D7]\\s*\\d")

    /**
     * The smallest count a troop card's badge shows. A siege machine's card reads "x1", and the
     * two armies this runs carry 40 or 44 Valkyries and events have added 40 or 50 troops.
     */
    private const val MIN_TROOP_BADGE = 5

    /** The troop counts the clean cuts of one badge band read, one entry per cut that read one. */
    private fun cleanCounts(rawReadings: List<String>): List<Int> = rawReadings.mapNotNull { raw ->
        BADGE.find(ScreenReader.normalizeAmountText(raw))?.groupValues?.get(1)?.toIntOrNull()
    }.filter { it >= MIN_TROOP_BADGE }

    /**
     * The "xNN" count from several binarisation cuts of one badge band: the count most cuts agree
     * on when two or more do, or the only count read when the cuts read just one. A number without
     * its x is not taken: hero levels and siege counts are bare numbers. Digits followed by a
     * letter are a misread, not a count: on 8 October two cuts gave "x4C" for x40. The count is
     * only logged; the layout does not depend on it.
     */
    fun badgeCount(rawReadings: List<String>): Int? = agreedCount(cleanCounts(rawReadings))

    /**
     * How many drops above its fixed count a troop card may be given off its badge. A tap past
     * the card's stock lands whatever card the game selects next, which can put the King down
     * alone and fire his ability early, so an overread costs far more than an underread: the
     * card stays coloured and the retry places the rest.
     */
    private const val MAX_EXTRA_DROPS = 5

    /**
     * How many drops the troop card whose badge band read [rawReadings] gets: the "xNN" count
     * when two or more cuts agree on it, clamped to [MIN_TROOP_BADGE]..[fixed] +
     * [MAX_EXTRA_DROPS], and [fixed] otherwise. Unlike [badgeCount], one lone cut is not enough,
     * and a count above [fixed] needs every clean cut behind it: a 0/8 confusion on two cuts of
     * an x40 card ("x48,x48,x40") would otherwise spill eight taps.
     *
     * Blood's tray reads "ttX,X44,X44,X44" and holds 44 Valkyries. The fixed 40 left four of them
     * for a 10-drop retry, whose other six taps went past the empty card, every Blood battle on
     * 8 October.
     *
     * With [loneLow], a count under [fixed] is taken off one clean cut when no other cut read
     * one, since an underread is the cheap side. Blood's changed army of 14 October put a x10
     * card where its Valkyries were calibrated, and forty taps ran thirty past its stock. The
     * event card is not read that way: its x50 reads x40 on one pale cut.
     */
    fun troopDropCount(rawReadings: List<String>, fixed: Int, loneLow: Boolean = false): Int {
        val counts = cleanCounts(rawReadings)
        if (loneLow && counts.size == 1 && counts[0] < fixed) return counts[0]
        val agreed = counts.groupingBy { it }.eachCount().filterValues { it >= 2 }
        val top = agreed.maxOfOrNull { it.value } ?: return fixed
        val count = agreed.filterValues { it == top }.keys.singleOrNull() ?: return fixed
        if (count > fixed && counts.any { it != count }) return fixed
        return count.coerceIn(MIN_TROOP_BADGE, fixed + MAX_EXTRA_DROPS)
    }

    /** How many drops the Valkyrie and event groups get. */
    data class DropCounts(val valkyries: Int, val event: Int)

    /**
     * Each troop group's drops, off the badge of the slot it taps. The event layout puts the
     * event group on slot 1 and the Valkyries on slot 2; every other layout deploys the plain
     * tray, with the Valkyries on slot 1 and no event group. Whichever card sits in a slot, its
     * group drops that slot's count. Only the plain tray takes a lone low count; the event
     * layout's counts are as they were.
     */
    fun dropCounts(layout: TrayLayout, slot1: List<String>, slot2: List<String>): DropCounts =
        if (layout == TrayLayout.EVENT) {
            DropCounts(troopDropCount(slot2, SPREAD_COUNT), troopDropCount(slot1, EVENT_TROOP_COUNT))
        } else {
            DropCounts(troopDropCount(slot1, SPREAD_COUNT, loneLow = true), EVENT_TROOP_COUNT)
        }

    /**
     * A spell card's "xNN" count, agreed across cuts the way [badgeCount] agrees a troop's, but
     * from zero up: spell stocks are small, and an emptied card shows "x0".
     */
    fun spellBadgeCount(rawReadings: List<String>): Int? = agreedCount(rawReadings.mapNotNull { raw ->
        BADGE.find(ScreenReader.normalizeAmountText(raw))?.groupValues?.get(1)?.toIntOrNull()
    })

    private fun agreedCount(counts: List<Int>): Int? {
        val tally = counts.groupingBy { it }.eachCount()
        if (tally.size == 1) return tally.keys.single()
        val agreed = tally.filterValues { it >= 2 }
        val top = agreed.maxOfOrNull { it.value } ?: return null
        return agreed.filterValues { it == top }.keys.singleOrNull()
    }

    /** How many cuts read text that is no badge at all, not even a misread one. */
    private fun nonBadgeText(rawReadings: List<String>): Int =
        rawReadings.count { it.isNotBlank() && !BADGE_LIKE.containsMatchIn(ScreenReader.normalizeAmountText(it)) }

    /** What one badge band shows: a troop badge, no badge, or a badge that other cuts contradict. */
    private enum class SlotBadge { BADGED, NONE, CONTRADICTED }

    /**
     * A band is badged when two cuts read a troop count, or when one does and every other cut
     * that read anything read a badge too (a misread like "x4C"). The cuts that read nothing are
     * dropped before they get here, and they contradict nothing: the event card is pale, and at
     * 07:28 and 07:36 UTC on 8 October one cut of four read its "x50" and three read nothing.
     * One troop count against text that is no badge at all is a contradiction.
     */
    private fun slotBadge(rawReadings: List<String>): SlotBadge {
        val clean = cleanCounts(rawReadings).size
        val other = nonBadgeText(rawReadings)
        return when {
            clean >= 2 || (clean == 1 && other == 0) -> SlotBadge.BADGED
            clean == 1 -> SlotBadge.CONTRADICTED
            else -> SlotBadge.NONE
        }
    }

    /**
     * Whether the event card is in the tray, from the raw cut readings of the first two slots'
     * badge bands.
     *
     * The decision rests on which slots carry a troop badge, not on the numbers. Slot 1 holds a
     * troop in either layout. In the plain tray slot 2 is the King's card or a siege machine's
     * card (Blood's siege card read "L" on 8 October), and neither shows an "xNN" troop badge; in
     * the event tray slots 1 and 2 are the event card and the Valkyries, in either order (the
     * game swapped them on 25 September), and both are badged. The counts vary by event and
     * account: Kanes x40 beside Valkyries x40 in September, Totem Throwers x50 beside x40 or x44
     * in October.
     *
     * Slot 1 is read leniently: any clean troop count there means a troop card. Slot 2 has to be
     * [SlotBadge.BADGED]. The event card is pale and barely reads (Blood, 7 October, in slot 1),
     * so two clean counts in slot 2 are the event tray on their own unless slot 1 reads text
     * that is no badge at all. A contradicted slot 2, or one clean count in slot 2 with nothing
     * badged in slot 1, or a badged slot 2 beside non-badge text in slot 1, is
     * [TrayLayout.AMBIGUOUS], and [forTray] deploys it as the plain tray.
     *
     * Both mistakes are bad: deployed plain, the event tray puts every hero and spell group on
     * the card to its left, so the rage group taps the Champion card and its retry fires her
     * ability, and the last card is never tapped. Deployed shifted, the plain tray never taps
     * slot 1, so in a normal run the Valkyries stay home. Plain is the fallback because a normal
     * run then still puts its army down.
     */
    fun trayLayout(slot1: List<String>, slot2: List<String>): TrayLayout {
        val first = cleanCounts(slot1).isNotEmpty()
        val second = slotBadge(slot2)
        val strongSecond = cleanCounts(slot2).size >= 2
        return when {
            second == SlotBadge.CONTRADICTED -> TrayLayout.AMBIGUOUS
            first && second == SlotBadge.BADGED -> TrayLayout.EVENT
            first -> TrayLayout.PLAIN
            strongSecond && nonBadgeText(slot1) == 0 -> TrayLayout.EVENT
            second == SlotBadge.BADGED -> TrayLayout.AMBIGUOUS
            else -> TrayLayout.UNKNOWN
        }
    }

    /** The profile to deploy from, whether to place the event troops, and the decision logged. */
    data class TrayPlan(val profile: PhoneProfile, val withEventTroops: Boolean, val decision: String)

    /**
     * Chooses the tray from what the scout showed rather than from the run mode. A detected
     * event card shifts every calibrated card one slot right in any mode; only the event mode
     * also places the event troops. An event run without a detected card deploys the plain tray.
     */
    fun forTray(profile: PhoneProfile, layout: TrayLayout, eventMode: Boolean): TrayPlan = when {
        layout == TrayLayout.EVENT && eventMode -> TrayPlan(withEventCard(profile), true, "event_card")
        layout == TrayLayout.EVENT -> TrayPlan(withEventCard(profile), false, "shift_only")
        eventMode -> TrayPlan(profile, false, "event_fallback_plain")
        else -> TrayPlan(profile, false, "plain")
    }

    /** How many Valkyries the spread puts down when the tray's badge is not read. */
    private const val SPREAD_COUNT = 40

    /**
     * [count] drops spaced evenly along all the calibrated deploy lines together, so a long line
     * gets more of them than a short one.
     *
     * It used to be ten to each of four edges. Sweat's four edges were short strips at the far
     * left and right of the base, so every Valkyrie landed at those two ends. A profile can now
     * carry more and longer lines, drawn around as much of the base as the screen shows. The
     * drops start at a random point and each is shifted by up to a third of a step so no two
     * attacks look the same.
     */
    private fun perimeterSpread(
        segments: List<Pair<Point, Point>>,
        rng: Random,
        viewport: Viewport,
        count: Int = SPREAD_COUNT
    ): List<Point> {
        val lengths = segments.map { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
        val total = lengths.sum()
        if (total <= 0.0) throw StopException("The calibrated perimeter has no length")
        val step = total / count
        val offset = rng.nextDouble(0.0, step)
        val drops = (0 until count).map { i ->
            val along = (offset + i * step + rng.nextDouble(-step / 3, step / 3) + total) % total
            var remaining = along
            var edge = 0
            while (edge < segments.lastIndex && remaining > lengths[edge]) {
                remaining -= lengths[edge]
                edge++
            }
            val (start, end) = segments[edge]
            val t = if (lengths[edge] > 0.0) (remaining / lengths[edge]).coerceIn(0.0, 1.0) else 0.0
            val lx = start.x + (end.x - start.x) * t
            val ly = start.y + (end.y - start.y) * t
            val pt = nudge(Point(lx.roundToInt(), ly.roundToInt()), DROP_JITTER, rng, viewport)
            if (!viewport.contains(pt)) {
                throw StopException("Deployment point $pt falls outside the calibrated viewport $viewport")
            }
            pt
        }
        return drops.shuffled(rng)
    }

    fun buildDeployment(
        profile: PhoneProfile,
        rng: Random = Random.Default,
        /**
         * The temporary troop event attack: the event card's troops spread the way the Valkyries
         * are, [troopDrops] of them ([EVENT_TROOP_COUNT] as the fallback).
         */
        withEventTroops: Boolean = false,
        /** How many drops each troop group gets; see [dropCounts]. */
        troopDrops: DropCounts = DropCounts(SPREAD_COUNT, EVENT_TROOP_COUNT)
    ): List<DeploymentGroup> {
        val groups = mutableListOf<DeploymentGroup>()

        val segments = if (profile.perimeterSegments.size >= 4) {
            profile.perimeterSegments
        } else if (profile.deploymentPoints.size >= 4) {
            // Pair deployment points into 4 edges
            listOf(
                Pair(profile.deploymentPoints[0], profile.deploymentPoints[1]),
                Pair(profile.deploymentPoints[1], profile.deploymentPoints[2]),
                Pair(profile.deploymentPoints[2], profile.deploymentPoints[3]),
                Pair(profile.deploymentPoints[3], profile.deploymentPoints[0])
            )
        } else {
            throw StopException("Four calibrated perimeter segments are required for deployment")
        }

        val allCorners = segments.flatMap { listOf(it.first, it.second) }
        val centerX = allCorners.sumOf { it.x }.toDouble() / allCorners.size
        val centerY = allCorners.sumOf { it.y }.toDouble() / allCorners.size

        val heroDrop = profile.heroDrop ?: profile.stateAnchors["hero_drop"]
            ?: segments[0].first

        // 1. Valkyries: the card's count, spread evenly along every perimeter segment
        val valkSelect = profile.troopAnchors["valkyries"] ?: profile.troopAnchors["valkyrie"]
            ?: profile.troopAnchors["troop_1"]
            ?: throw StopException("Missing calibrated Valkyries troop card select anchor")
        val valkRoi = profile.troopRois["valkyries"] ?: profile.troopRois["valkyrie"] ?: profile.troopRois["troop_1"]

        val valkyries = DeploymentGroup(
            name = "valkyries",
            selectPoint = valkSelect,
            drops = perimeterSpread(segments, rng, profile.viewport, troopDrops.valkyries),
            cardRoi = valkRoi
        )

        // 2. Rage Spells: 5 count, inward 0.3
        val rageSelect = profile.troopAnchors["rages"] ?: profile.troopAnchors["rage"]
            ?: profile.troopAnchors["troop_2"]
            ?: throw StopException("Missing calibrated Rage spell select anchor")
        val rageRoi = profile.troopRois["rages"] ?: profile.troopRois["rage"] ?: profile.troopRois["troop_2"]

        val rageDrops = mutableListOf<Point>()
        val countRage = 5
        // How deep the rages sit varied by nothing at all before, so five spells landed on the
        // same ring of the base on every attack of a session.
        val inward = rng.nextDouble(0.22, 0.38)
        while (rageDrops.size < countRage) {
            val order = segments.indices.shuffled(rng)
            for (idx in order.take(countRage - rageDrops.size)) {
                val (start, end) = segments[idx]
                val t = rng.nextDouble(0.2, 0.8)
                val lx = start.x + (end.x - start.x) * t
                val ly = start.y + (end.y - start.y) * t
                val dx = (lx + (centerX - lx) * inward).roundToInt()
                val dy = (ly + (centerY - ly) * inward).roundToInt()
                rageDrops.add(nudge(Point(dx, dy), DROP_JITTER, rng, profile.viewport))
            }
        }
        groups.add(
            DeploymentGroup(
                name = "rages",
                selectPoint = rageSelect,
                drops = rageDrops,
                cardRoi = rageRoi,
                isSpell = true
            )
        )
        groups.add(valkyries)

        if (withEventTroops) {
            val eventSelect = profile.troopAnchors["event"]
                ?: throw StopException("Missing event card anchor; build the plan from withEventCard(profile)")
            groups.add(
                DeploymentGroup(
                    name = "event",
                    selectPoint = eventSelect,
                    drops = perimeterSpread(segments, rng, profile.viewport, troopDrops.event),
                    cardRoi = profile.troopRois["event"]
                )
            )
        }

        // 3. Optional Jump Spell: 1 count
        val jumpSelect = profile.troopAnchors["jumps"] ?: profile.troopAnchors["jump"]
        if (jumpSelect != null) {
            val jumpRoi = profile.troopRois["jumps"] ?: profile.troopRois["jump"]
            val jumpPoint = nudge(
                Point(centerX.roundToInt(), centerY.roundToInt()),
                CENTER_JITTER, rng, profile.viewport
            )
            groups.add(
                DeploymentGroup(
                    name = "jumps",
                    selectPoint = jumpSelect,
                    drops = listOf(jumpPoint),
                    cardRoi = jumpRoi,
                    isSpell = true,
                    isOptional = true
                )
            )
        }

        // 4. Heroes: King, Queen, Warden, Champion
        val heroNames = listOf("king", "queen", "warden", "champion")
        val heroSegment = segments.minByOrNull { (start, end) ->
            hypot(
                (start.x + end.x) / 2.0 - heroDrop.x,
                (start.y + end.y) / 2.0 - heroDrop.y
            )
        } ?: segments[0]
        for (hName in heroNames) {
            val hSelect = profile.troopAnchors[hName]
            if (hSelect != null) {
                groups.add(
                    DeploymentGroup(
                        name = hName,
                        selectPoint = hSelect,
                        drops = heroDrops(heroDrop, heroSegment, rng, profile.viewport),
                        // A hero's card keeps its colour while the ability is ready, so it
                        // is never retried; see CardRetry. Its area is read once, for the
                        // landed hero's health bar (LocalRunner.heroLandings).
                        cardRoi = profile.troopRois[hName],
                        isHero = true
                    )
                )
            }
        }

        // 5. Optional Siege Machine (if configured)
        val siegeSelect = profile.troopAnchors["siege"] ?: profile.troopAnchors["flame_flinger"]
        if (siegeSelect != null) {
            val siegeCorner = nudge(segments[0].first, HERO_JITTER, rng, profile.viewport)
            groups.add(
                DeploymentGroup(
                    name = "siege",
                    selectPoint = siegeSelect,
                    drops = listOf(siegeCorner),
                    isSiege = true,
                    isOptional = true
                )
            )
        }

        return groups
    }

    /**
     * Splits a plan into the phases it is deployed in, one card select per drop within a phase.
     *
     * The four heroes are one phase. Placed a group at a time, each was followed by a reward
     * sweep and a read of its own card before the next hero's card was touched, which put whole
     * seconds between the king landing and the champion following him. They go down together
     * instead, and their cards are read afterwards.
     */
    fun phases(plan: List<DeploymentGroup>): List<List<DeploymentGroup>> {
        val phases = mutableListOf<List<DeploymentGroup>>()
        for (group in plan) {
            val last = phases.lastOrNull()
            if (group.isHero && last != null && last.last().isHero) {
                phases[phases.lastIndex] = last + group
            } else {
                phases.add(listOf(group))
            }
        }
        return phases
    }
}
