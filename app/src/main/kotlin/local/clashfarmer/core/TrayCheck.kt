package local.clashfarmer.core

import local.clashfarmer.recognition.ImageMatcher
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader

/**
 * Setup's tray check: whether each calibrated tray card area holds the card its name says, read
 * off one scout frame the user opened themselves. Pure: the screen reads are passed in, and
 * nothing here taps the game or writes the profile.
 *
 * Blood's tray on 7 October showed why this is needed: with the event card in front, the rages
 * area moved to 872-939, the Minion Prince card, which has no count badge (D4).
 */
object TrayCheck {

    enum class Level { OK, WARN, BAD }

    data class Verdict(val level: Level, val text: String)

    data class Slot(
        val name: String,
        val roi: Region,
        /** What each cut of the badge band read, for the log. */
        val raw: List<String>,
        val badge: Int?,
        val saturation: Double,
        val verdict: Verdict,
        /** Set when the bot's saved tap for this card lies outside its card area. */
        val tapNote: String?
    ) {
        val wrong: Boolean get() = verdict.level == Level.BAD || tapNote != null
    }

    data class Report(
        val layout: AttackPlanner.TrayLayout,
        val slots: List<Slot>,
        /** Said in place of the layout's own line: the tray could not be measured or shifted. */
        val layoutNote: String? = null
    ) {
        /**
         * No slot's band read an x and a digit. Every badge band on the 08:36-08:47 run of
         * 14 October read blank on both accounts at normal saturations, so a frame like that says
         * more about the picture than about the tray.
         */
        val blank: Boolean get() = slots.isNotEmpty() && slots.none { slot -> slot.raw.any { isBadgeLike(it) } }
        val wrong: Int get() = slots.count { it.wrong }
        val unsure: Int get() = slots.count { !it.wrong && it.verdict.level == Level.WARN }
        val level: Level get() = when {
            blank -> Level.WARN
            wrong > 0 -> Level.BAD
            unsure > 0 -> Level.WARN
            else -> Level.OK
        }
        val headline: String get() = when {
            blank -> "No count badge read at all; the picture may be dim or mid-change. Take the check again."
            wrong > 0 -> "$wrong of ${slots.size} slots look wrong"
            unsure > 0 -> "$unsure of ${slots.size} slots need a look"
            else -> "All ${slots.size} slots look right"
        }
        val layoutLine: String get() = layoutNote ?: when (layout) {
            AttackPlanner.TrayLayout.EVENT ->
                "The tray starts with an event card, so every card area is read one card to the right, as an attack does."
            AttackPlanner.TrayLayout.PLAIN -> "The bot found no event card, so card areas are read where they were calibrated."
            AttackPlanner.TrayLayout.AMBIGUOUS ->
                "The first two badges disagree, so card areas are read where they were calibrated, as an attack does."
            AttackPlanner.TrayLayout.UNKNOWN ->
                "The first two badges did not read, so card areas are read where they were calibrated."
        }

        /**
         * What to do about the wrong slots, or null when none is wrong or the frame read no badge.
         * Calibrate saves a card's area with its tap, so it clears both a card area on the wrong
         * card and a tap note.
         */
        val advice: String? get() = if (blank || wrong == 0) null else listOfNotNull(
            RECALIBRATE_ADVICE,
            MISSED_EVENT_ADVICE.takeIf { layout != AttackPlanner.TrayLayout.EVENT }
        ).joinToString(" ")
    }

    private const val RECALIBRATE_ADVICE = "Recalibrate this account's tray in Calibrate, then check again."

    /** An event tray read as plain would be baked into the taps by recalibrating on it. */
    private const val MISSED_EVENT_ADVICE =
        "If an event card is in the tray, do not recalibrate. The attack will read it the same way; report it instead."

    val SPELLS = setOf("rages", "rage", "troop_2", "jumps", "jump")
    val HEROES = setOf("king", "queen", "warden", "champion")

    /** At or below this mean saturation a card is grey: empty or spent, as CardRetry judges it. */
    const val GREY = ImageMatcher.SPENT_CARD_SATURATION_THRESHOLD

    fun isSpellSlot(name: String) = name in SPELLS
    fun isHeroSlot(name: String) = name in HEROES

    /** "troop_2" as "Troop 2": slot names in plain words. */
    fun label(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Why there is nothing to check for this account, or null when there is. */
    fun refusal(profile: PhoneProfile?): String? = when {
        profile == null -> "This account is not calibrated yet. Calibrate it first, then check the tray."
        profile.troopRois.isEmpty() -> "This account has no tray card areas saved, so there is nothing to check."
        else -> null
    }

    /** Why the captured frame cannot be checked, in plain words, or null for a scout screen. */
    fun frameProblem(state: ScreenState, width: Int, height: Int, profile: PhoneProfile): String? = when {
        width != profile.resolutionWidth || height != profile.resolutionHeight ->
            "The picture is ${width}x$height, not the ${profile.resolutionWidth}x${profile.resolutionHeight} game, so it is " +
                "not a scout screen. Clash was probably still turning. Wait a moment and check again."
        state != ScreenState.SCOUT ->
            "This is not a scout screen: Clash showed ${seen(state)}. In Clash, tap Attack, then Find a match, and " +
                "check again. The bot does not open it for you, because Find a match costs gold."
        else -> null
    }

    private fun seen(state: ScreenState) = when (state) {
        ScreenState.HOME -> "the village"
        ScreenState.MENU -> "the attack menu"
        ScreenState.ARMY -> "the army screen"
        ScreenState.SEARCHING -> "the search for an opponent"
        ScreenState.BATTLE -> "a battle"
        ScreenState.REWARD -> "a reward"
        ScreenState.RESULT -> "a battle result"
        ScreenState.WALL -> "a wall upgrade"
        else -> "a screen the bot does not recognise"
    }

    /** The part of the frame shown for a slot: its card area plus the badge band above and past it. */
    fun pictureArea(roi: Region): Region {
        val band = AttackPlanner.badgeBand(roi)
        return Region(roi.left, band.top, band.right, roi.bottom)
    }

    /**
     * Reads the tray the way an attack does: the first two badges pick the layout, an event
     * layout shifts every card area one card right, and then each card area is judged. An event
     * layout that [troopInSlot2] finds is the plain tray is read as the plain tray, as an attack
     * whose hero check is due reads it.
     * [readBadge] returns the cut readings of a badge band; [saturation] the mean saturation of
     * a card area. Inline so a caller can pass suspending reads.
     */
    inline fun check(
        profile: PhoneProfile,
        readBadge: (Region) -> List<String>,
        saturation: (Region) -> Double
    ): Report {
        val reads = HashMap<Region, List<String>>()
        val bands = AttackPlanner.trayBadgeRegions(profile)
        val read = if (bands == null) AttackPlanner.TrayLayout.UNKNOWN else AttackPlanner.trayLayout(
            reads.getOrPut(bands[0]) { readBadge(bands[0]) },
            reads.getOrPut(bands[1]) { readBadge(bands[1]) }
        )
        val shift = runCatching { AttackPlanner.forTray(profile, read, eventMode = false).profile }
        val shiftedHeroes = shift.getOrNull()?.takeIf { read == AttackPlanner.TrayLayout.EVENT }?.let {
            heroSlots(it, read, { band -> reads.getOrPut(band) { readBadge(band) } }, saturation)
        }
        val troop = shiftedHeroes != null && troopInSlot2(profile, shiftedHeroes,
            heroSlots(profile, AttackPlanner.TrayLayout.PLAIN, { band -> reads.getOrPut(band) { readBadge(band) } }, saturation)
        ) { band -> reads.getOrPut(band) { readBadge(band) } }
        val layout = if (troop) AttackPlanner.TrayLayout.PLAIN else read
        val tray = if (troop) profile else shift.getOrDefault(profile)
        val slots = tray.troopRois.entries.sortedBy { it.value.left }.map { (name, roi) ->
            val band = AttackPlanner.badgeBand(roi)
            slot(tray, name, roi, reads.getOrPut(band) { readBadge(band) }, saturation(roi))
        }
        return report(layout, slots, measured = bands != null, shiftFailure = shift.exceptionOrNull()?.message,
            troopInSlot2 = troop)
    }

    /**
     * Whether a tray read as an event tray is the plain tray with another troop of x5 or more in
     * slot 2, whose first two badges read as the event card's and the main troop's do. [shifted]
     * is its hero slots judged one card right, where the event tray puts them; [plain] the same
     * slots judged where they were calibrated, off the same frame. [readBadge] reads the band of
     * the spell after the heroes, where it was calibrated.
     *
     * On the plain tray with a troop in slot 2 every card is where it was calibrated, so [plain]
     * shows no count and the spell after the heroes shows its own. One card right the heroes show
     * no count either: the band past the last hero stops a few pixels into the spell's badge, and
     * on Blood's scout (audit-evidence/spike/geo-scout.png) it read "x!" on every cut. So either a
     * count one card right or the spell's count where it was calibrated says plain. With the event
     * card in front every card sits one card right of its calibration: the King's calibrated slot
     * lies on the card left of him, which shows a count when it is the main troop or another troop,
     * and the spell's calibrated slot lies on the last hero, which shows none.
     *
     * Not told apart: an event tray with a card showing no count (a siege card) left of the King
     * and a hero away, whose spell then sits where it was calibrated. It is read plain.
     */
    inline fun troopInSlot2(
        profile: PhoneProfile,
        shifted: Report,
        plain: Report,
        readBadge: (Region) -> List<String>
    ): Boolean = wrongHero(plain) == null && (wrongHero(shifted) != null ||
        spellAfterHeroes(profile)?.let { AttackPlanner.spellBadgeCount(readBadge(AttackPlanner.badgeBand(it))) } != null)

    /**
     * The card area of the first card right of the last hero when it is a spell less than one and
     * a half cards past him, or null. On an event tray that spell's calibrated slot lies on the
     * last hero; a card between them the profile does not know (another spell) would put it on
     * that card's count instead.
     */
    fun spellAfterHeroes(profile: PhoneProfile): Region? {
        val king = profile.troopAnchors["king"] ?: return null
        val queen = profile.troopAnchors["queen"] ?: return null
        val last = profile.troopRois.filterKeys { isHeroSlot(it) }.values.maxByOrNull { it.left } ?: return null
        val next = profile.troopRois.entries.filter { it.value.left > last.left }.minByOrNull { it.value.left } ?: return null
        return next.value.takeIf { isSpellSlot(next.key) && (it.left - last.left) * 2 < (queen.x - king.x) * 3 }
    }

    /**
     * Whether an attack whose hero check is not due judges its heroes anyway: the tray reads as an
     * event tray and its first two badges ([counts]) differ from those of the last hero check that
     * found a troop in slot 2 ([remembered]). An attack deploys that tray plain only at those
     * counts, so a slot-2 count misread (an 8 read as 0, or cuts that leave no count) is judged
     * again rather than deployed one card right.
     */
    fun troopInSlot2CheckDue(layout: AttackPlanner.TrayLayout, counts: Pair<Int?, Int?>, remembered: Pair<Int?, Int?>?): Boolean =
        layout == AttackPlanner.TrayLayout.EVENT && remembered != null && counts != remembered

    /**
     * The hero slots of the tray an attack is about to deploy, judged as [check] judges them, off
     * the scout frame the attack already reads its first two badges from. [tray] is the profile
     * the attack deploys from, already moved for an event card. Only a slot showing count-badge
     * evidence (a clean count, x0 included, or a badge-like read) is red, and a frame with no badge
     * evidence on any of these slots is only amber, as on Setup's blank picture.
     *
     * Blood's attacks of 14 October put the Giant, the Yeti and two x2 cards where its King, Queen,
     * Warden and Champion were calibrated (audit-evidence/accept/56-scout.png), so the hero groups
     * tapped troop cards and the real heroes stayed in the tray for two hours.
     */
    inline fun heroSlots(
        tray: PhoneProfile,
        layout: AttackPlanner.TrayLayout,
        readBadge: (Region) -> List<String>,
        saturation: (Region) -> Double
    ): Report {
        val slots = tray.troopRois.entries.filter { isHeroSlot(it.key) }.sortedBy { it.value.left }.map { (name, roi) ->
            slot(tray, name, roi, readBadge(AttackPlanner.badgeBand(roi)), saturation(roi))
        }
        return report(layout, slots, measured = true, shiftFailure = null)
    }

    /**
     * The first hero slot an attack must not deploy over, or null when the tray may go ahead.
     * [shifted] is the same frame judged one card right. A tray deployed plain may be an event
     * tray whose pale event card did not read, and then the King's slot is on the Valkyries'
     * badge; a tray whose heroes show no count one card right is the one calibrated.
     */
    fun wrongHero(report: Report, shifted: Report? = null): Slot? =
        report.slots.firstOrNull { isHeroSlot(it.name) && it.verdict.level == Level.BAD }
            ?.takeIf { shifted == null || wrongHero(shifted) != null }

    /** In every stop reason [trayMismatch] gives. No recoverable phrase is part of it. */
    const val TRAY_MISMATCH = "army no longer matches its tray setup"

    /** In every stop reason [heroesStayed] gives. */
    const val HEROES_STAYED = "heroes did not leave the tray"

    private const val CHECK_TRAY = "Check the tray in Setup, then recalibrate it."

    /**
     * What a stop on an event layout adds before [CHECK_TRAY]. A troop of x5 or more right of the
     * main troop is read as the plain tray when the calibrated hero slots show no count (see
     * [troopInSlot2]), so this stop comes only when they show one too, and recalibrating fixes that.
     */
    private const val TROOP_AS_EVENT_CARD = "If there is no event card, the bot read another troop with a count of " +
        "x5 or more right of the main troop as one, and the hero cards are not where they were calibrated either."

    /**
     * Why an attack left the scout screen without deploying: [slot] is a hero slot showing a count.
     * [eventCard]: the tray was read as starting with an event card, so [slot] lay one card right
     * of its calibration.
     */
    fun trayMismatch(village: String?, slot: Slot, eventCard: Boolean = false): String =
        "${possessive(village)} $TRAY_MISMATCH: the ${label(slot.name)}'s card shows a troop count" +
            if (eventCard) ", with the tray read as starting with an event card. $TROOP_AS_EVENT_CARD $CHECK_TRAY" else ". $CHECK_TRAY"

    /** Why a run stopped after [attacks] attacks in a row whose hero cards showed no landed hero. */
    fun heroesStayed(village: String?, attacks: Int): String =
        "${possessive(village)} $HEROES_STAYED on $attacks attacks in a row, so the tray setup may be wrong. $CHECK_TRAY"

    private fun possessive(village: String?) =
        village?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it's" } ?: "This account's"

    /**
     * The report on [slots] judged under [layout]. [measured] is false when the profile cannot
     * place the first two badges; [shiftFailure] is why an event layout could not shift the card
     * areas; [troopInSlot2] says an event layout was read as the plain tray (see [check]). On a
     * frame where no band read a badge, a slot's "no count badge" red is only amber: the picture,
     * not the card, is the likely cause.
     */
    fun report(
        layout: AttackPlanner.TrayLayout,
        slots: List<Slot>,
        measured: Boolean,
        shiftFailure: String?,
        troopInSlot2: Boolean = false
    ): Report {
        val note = when {
            !measured -> "The Valkyries card area or the King and Queen taps are not calibrated, so the bot cannot " +
                "look for an event card. Card areas are read where they were calibrated."
            shiftFailure != null -> "The tray starts with an event card, but the card areas could not be moved one " +
                "card right ($shiftFailure), so they are read where they were calibrated."
            troopInSlot2 -> "The card right of the main troop shows a troop count, as an event card in front would, " +
                "but the hero cards are where they were calibrated. So there is no event card, and card areas are " +
                "read where they were calibrated, as an attack does."
            else -> null
        }
        val report = Report(layout, slots, note)
        if (!report.blank) return report
        return report.copy(slots = slots.map { slot ->
            if (slot.verdict.level != Level.BAD) slot
            else slot.copy(verdict = Verdict(Level.WARN, "${label(slot.name)}: no count badge read on this picture"))
        })
    }

    /**
     * One card area judged. Troop cards are counted with [AttackPlanner.badgeCount], which only
     * believes x5 and up; spell and hero cards with [AttackPlanner.spellBadgeCount], from x0, so
     * a real x1 spell card is not taken for a card without a badge.
     */
    fun slot(profile: PhoneProfile, name: String, roi: Region, raw: List<String>, saturation: Double): Slot {
        val count: (List<String>) -> Int? =
            if (isSpellSlot(name) || isHeroSlot(name)) AttackPlanner::spellBadgeCount else AttackPlanner::badgeCount
        val badge = count(raw)
        // A cut read a count but the cuts did not agree, as a clipped x11 can read x1 on some cuts.
        val unclear = badge == null && raw.any { count(listOf(it)) != null }
        // A badge read with a letter after its digits: Sweat's x40 read "x4C" on three or four cuts
        // of each scout on 14 October, while the band stopped at the card edge.
        val misread = badge == null && raw.any { isBadgeLike(it) && AttackPlanner.spellBadgeCount(listOf(it)) == null }
        val anchor = profile.troopAnchors[name]
        val tapNote = if (anchor != null && !roi.contains(anchor)) {
            "${label(name)}: the bot taps at x ${anchor.x}, y ${anchor.y}, outside this card area " +
                "(x ${roi.left} to ${roi.right}, y ${roi.top} to ${roi.bottom}). Tap the middle of the right card " +
                "again in Calibrate; the card area moves with the tap."
        } else null
        return Slot(name, roi, raw, badge, saturation, verdict(name, badge, saturation, isSpellSlot(name), unclear, misread), tapNote)
    }

    /** An x and a digit: a count badge, read cleanly or not, as AttackPlanner tells one from other text. */
    private val BADGE_LIKE = Regex("[xX×]\\s*\\d")

    private fun isBadgeLike(raw: String) = BADGE_LIKE.containsMatchIn(ScreenReader.normalizeAmountText(raw))

    /**
     * The plain sentence for one slot: what its badge and colour say about the card in it.
     * [unclear]: the cuts read counts that disagree. [misread]: a cut read a badge but no clean count.
     */
    fun verdict(
        name: String,
        badge: Int?,
        saturation: Double,
        isSpellSlot: Boolean,
        unclear: Boolean = false,
        misread: Boolean = false
    ): Verdict {
        val label = label(name)
        return when {
            // Any badge evidence on a hero slot is red, however unclear: x0 and a mixed read included.
            isHeroSlot(name) && (badge != null || misread || unclear) -> Verdict(Level.BAD,
                "$label: shows ${badge?.let { "x$it" } ?: "a count badge"}, so this is not a hero card (a hero card has no count badge)")
            badge == 0 -> Verdict(Level.WARN, "$label: x0, empty or already used")
            // The event card is pale, 58.2 on Blood and 54.1 on Sweat in October, under the grey
            // line, and on 8 October three cuts of four read nothing on it. So it is never grey.
            // Cuts that read counts which disagree did read, so they fall to the unclear sentence.
            name == "event" && badge == null && !misread && !unclear ->
                Verdict(Level.WARN, "$label: the badge on this pale card did not read, so check the picture")
            badge == null && saturation <= GREY && name != "event" -> Verdict(Level.WARN, "$label: grey card, empty or already used")
            badge == null && unclear -> Verdict(Level.WARN, "$label: the count badge did not read clearly, so check the picture")
            // No badge cannot tell a hero from a siege card: Blood's siege card reads "L" or nothing.
            isHeroSlot(name) -> Verdict(Level.OK, "$label: no count badge: a hero card, or a siege card; check the picture")
            badge != null -> Verdict(Level.OK, "$label: x$badge, looks right")
            misread -> Verdict(Level.OK, "$label: has a count badge, looks right")
            isSpellSlot -> Verdict(Level.BAD, "$label: no count badge, so this is not a spell card (a hero or siege card has no badge)")
            else -> Verdict(Level.BAD, "$label: no count badge, so this is not a troop card (a hero or siege card has no badge)")
        }
    }
}
