package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.FarmingSettingsEditor
import java.util.Locale

/** One thing wrong, or worth knowing, about an account's calibration or settings. */
data class Finding(
    val kind: Kind,
    /** Which Setup row it belongs to. */
    val area: Area,
    val tone: Tone,
    /** Short, for the row: "Same taps as Sweat's (5 of 6)". */
    val title: String,
    /** The detail sheet's sentences. */
    val detail: String,
    /** The account it was compared with, for [Kind.COPIED_ANCHORS]. */
    val otherAccountId: String? = null,
    /** For [Kind.COPIED_ANCHORS], a hash of the account's tray taps; part of the problem's dismissal key. */
    val fingerprint: String? = null
) {
    enum class Kind {
        NOT_CALIBRATED, NOT_VERIFIED, ATTACK_INCOMPLETE, WALLS_INCOMPLETE,
        COPIED_ANCHORS, UNEVEN_SPACING, GOAL_UNREACHABLE, WALL_SETUP
    }

    enum class Area { CALIBRATION, TRAY, WALLS, GOAL }
}

/**
 * Checks each account's calibration against its own rules and against the other accounts'.
 * Pure: the caller loads the profiles and, when it can, their save times.
 */
object CalibrationHealth {
    /** Two taps this close are the same tap. */
    const val SAME_PX = 3
    /** Fewer shared cards than this say nothing about copying. */
    const val MIN_SHARED = 4
    /** Share of the shared cards that must match, in percent. */
    const val COPIED_PERCENT = 80
    /** Anchors closer than this along x are aliases of one card ("valkyrie" and "troop_1"). */
    const val ALIAS_PX = 10
    /** A gap this many times the median gap is a missing card slot. */
    const val UNEVEN_FACTOR = 1.5
    /** Up to this many times the median, a gap from the first card to the King is one slot: a siege machine's. */
    const val SIEGE_FACTOR = 2.5

    /**
     * Findings per account id, every account present (an empty list when all is well).
     * [profiles] maps an account id to its profile, null or absent when there is no profile.json.
     * [savedAtMs] maps an account id to its profile.json's modification time, when known.
     */
    fun check(
        accounts: List<AccountRecord>,
        profiles: Map<String, PhoneProfile?>,
        savedAtMs: Map<String, Long> = emptyMap()
    ): Map<String, List<Finding>> = accounts.associate { account ->
        account.id to findings(account, accounts, profiles, savedAtMs)
    }

    private fun findings(
        account: AccountRecord,
        accounts: List<AccountRecord>,
        profiles: Map<String, PhoneProfile?>,
        savedAtMs: Map<String, Long>
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val name = account.displayName
        val profile = profiles[account.id]
        if (profile == null) {
            out += Finding(Finding.Kind.NOT_CALIBRATED, Finding.Area.CALIBRATION, Tone.BAD, "Not calibrated",
                "$name has no calibration on this phone. Calibrate it before running.")
        } else {
            if (!profile.isVerified) {
                out += Finding(Finding.Kind.NOT_VERIFIED, Finding.Area.CALIBRATION, Tone.WARN, "Needs one supervised attack",
                    "$name's calibration has not been proven yet. Run one supervised attack to verify it.")
            }
            failure { profile.validateForAttack() }?.let {
                out += Finding(Finding.Kind.ATTACK_INCOMPLETE, Finding.Area.CALIBRATION, Tone.BAD, "Attack calibration incomplete", it)
            }
            if (account.settings.wallsEnabled) failure { profile.validateForWalls() }?.let {
                out += Finding(Finding.Kind.WALLS_INCOMPLETE, Finding.Area.WALLS, Tone.BAD, "Wall calibration incomplete", it)
            }
            for (other in accounts) {
                if (other.id == account.id) continue
                val otherProfile = profiles[other.id] ?: continue
                copied(account, profile, other, otherProfile, savedAtMs)?.let { out += it }
            }
            uneven(profile)?.let { out += it }
            goalNotes(account.settings, profile).forEach {
                out += Finding(Finding.Kind.GOAL_UNREACHABLE, Finding.Area.GOAL, Tone.WARN, "Goal or rule cannot work", it)
            }
        }
        (account.settings.wallSetupRequired ?: account.settings.wallCapacityProblem())?.let {
            out += Finding(Finding.Kind.WALL_SETUP, Finding.Area.WALLS, Tone.WARN, "Wall setup needed", it)
        }
        return out
    }

    /** Same taps as the other account: the rule that would have caught Blood on 22 September. */
    private fun copied(
        account: AccountRecord,
        profile: PhoneProfile,
        other: AccountRecord,
        otherProfile: PhoneProfile,
        savedAtMs: Map<String, Long>
    ): Finding? {
        val anchors = matchCount(profile.troopAnchors, otherProfile.troopAnchors) { a, b -> near(a, b) }
        val rois = matchCount(profile.troopRois, otherProfile.troopRois) { a, b -> near(a, b) }
        val anchorsCopied = isCopied(anchors)
        val roisCopied = isCopied(rois)
        if (!anchorsCopied && !roisCopied) return null
        val name = account.displayName
        val otherName = other.displayName
        val (k, of) = if (anchorsCopied) anchors else rois
        val what = when {
            anchorsCopied && roisCopied -> "troop and hero taps and tray regions"
            anchorsCopied -> "troop and hero taps"
            else -> "tray regions"
        }
        val later = savedLater(account, other, savedAtMs)
        val detail = buildString {
            append("$name's $what match $otherName's on $k of $of cards. ")
            append("That is fine if both trays hold the same cards in the same order. ")
            append("If $name's army differs (a siege machine, another hero, a different troop), check $name's tray")
            append(later?.let { "; ${it.displayName}'s calibration was saved later, so check that one first." } ?: ".")
        }
        // Only the regions matching is not "same taps": after a tap-only recalibration the taps differ.
        val title = if (anchorsCopied) "Same taps as $otherName's ($k of $of)" else "Same tray regions as $otherName's ($k of $of)"
        return Finding(Finding.Kind.COPIED_ANCHORS, Finding.Area.TRAY, Tone.WARN, title, detail, other.id, fingerprint(profile))
    }

    /** Changes when the account's tray taps or regions change, so a dismissal lasts until then. */
    internal fun fingerprint(profile: PhoneProfile): String =
        (profile.troopAnchors.toSortedMap().toString() + profile.troopRois.toSortedMap().toString()).hashCode().toString(16)

    private fun <T> matchCount(a: Map<String, T>, b: Map<String, T>, same: (T, T) -> Boolean): Pair<Int, Int> {
        val shared = a.keys intersect b.keys
        return shared.count { same(a.getValue(it), b.getValue(it)) } to shared.size
    }

    private fun isCopied(count: Pair<Int, Int>): Boolean {
        val (k, of) = count
        return of >= MIN_SHARED && k >= MIN_SHARED && k * 100 >= of * COPIED_PERCENT
    }

    private fun near(a: Point, b: Point) = Math.abs(a.x - b.x) <= SAME_PX && Math.abs(a.y - b.y) <= SAME_PX

    private fun near(a: Region, b: Region) = Math.abs(a.left - b.left) <= SAME_PX && Math.abs(a.top - b.top) <= SAME_PX &&
        Math.abs(a.right - b.right) <= SAME_PX && Math.abs(a.bottom - b.bottom) <= SAME_PX

    private fun savedLater(a: AccountRecord, b: AccountRecord, savedAtMs: Map<String, Long>): AccountRecord? {
        val ta = savedAtMs[a.id] ?: return null
        val tb = savedAtMs[b.id] ?: return null
        return when {
            ta > tb -> a
            tb > ta -> b
            else -> null
        }
    }

    /** The tray's cards left to right: aliases within [ALIAS_PX] fold into the first name. */
    private fun cards(profile: PhoneProfile): List<Pair<String, Int>> {
        val sorted = profile.troopAnchors.entries.sortedBy { it.value.x }
        val out = mutableListOf<Pair<String, Int>>()
        for (e in sorted) {
            if (out.isEmpty() || e.value.x - out.last().second >= ALIAS_PX) out += e.key to e.value.x
        }
        return out
    }

    private fun gaps(cards: List<Pair<String, Int>>): List<Int> = cards.zipWithNext { a, b -> b.second - a.second }

    private fun median(values: List<Int>): Int? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    private fun uneven(profile: PhoneProfile): Finding? {
        val cards = cards(profile)
        val gaps = gaps(cards)
        if (gaps.size < 2) return null
        val median = median(gaps) ?: return null
        if (median <= 0) return null
        // Calibration has no siege target, so a siege machine leaves one slot empty between the
        // troops and the King: Blood's Valkyries to King is about 204 px against 97 between the
        // heroes. With no King tap there, the empty slot is the missing King.
        val siegeSlot = cards[1].first == "king" && gaps[0] <= median * SIEGE_FACTOR
        val i = gaps.indices.firstOrNull { gaps[it] > median * UNEVEN_FACTOR && !(it == 0 && siegeSlot) } ?: return null
        val left = words(cards[i].first)
        val right = words(cards[i + 1].first)
        return Finding(Finding.Kind.UNEVEN_SPACING, Finding.Area.TRAY, Tone.WARN, "Uneven card spacing",
            "The gap between $left and $right is ${gaps[i]} px against $median px elsewhere. " +
                "A card slot may be missing, or a tap was saved on the wrong card.")
    }

    /** The goal and rule notes the settings editor shows, for this profile's calibrated regions. */
    private fun goalNotes(s: AccountSettings, profile: PhoneProfile): List<String> {
        val needed = s.opponent.minimums.keys +
            (if (s.opponent.minimumGoldAndElixir != null) setOf(Resource.GOLD, Resource.ELIXIR) else emptySet())
        val scoutMissing = buildList {
            (needed - profile.scoutLootRois.keys).forEach { add("${Format.resourceName(it).lowercase(Locale.ROOT)} loot") }
            if (profile.scoutCountdownRoi == null) add("countdown")
            if (profile.scoutFeeRoi == null) add("Next price")
        }
        val lootMissing = if (!s.earlyEnd.enabled) emptyList()
            else (s.earlyEnd.lootLeftBelow.keys - profile.scoutLootRois.keys)
                .map { "${Format.resourceName(it).lowercase(Locale.ROOT)} loot" }
        return FarmingSettingsEditor.readiness(s, profile.homeResourceRois.keys, scoutMissing, lootMissing)
    }

    // ----- Setup row lines -----

    /** "Verified on this phone", "Needs one supervised attack" or "Not calibrated". */
    fun calibrationLine(profile: PhoneProfile?): String = when {
        profile == null -> "Not calibrated"
        !profile.isVerified -> "Needs one supervised attack"
        else -> "Verified on this phone"
    }

    /** "7 cards, spacing 97 px"; "No tray taps saved" when there are none. */
    fun trayLine(profile: PhoneProfile?): String {
        val cards = profile?.let { cards(it) }.orEmpty()
        if (cards.isEmpty()) return "No tray taps saved"
        val spacing = median(gaps(cards))
        return Format.count(cards.size, "card") + (spacing?.let { ", spacing $it px" } ?: "")
    }

    /** "Off", "Capacities set, buys at 90%" or the setup problem. The resume level is left out: most runs spend to 0%. */
    fun wallsLine(s: AccountSettings): String {
        s.wallSetupRequired?.let { return "Off: setup needed" }
        if (!s.wallsEnabled) return "Off"
        s.wallSetupProblem()?.let { return "Setup needed" }
        return "Capacities set, buys at ${s.triggerPercent}%"
    }

    /** The worst tone among [findings] for one [area]; OK when there are none. */
    fun tone(findings: List<Finding>, area: Finding.Area): Tone {
        val tones = findings.filter { it.area == area }.map { it.tone }
        return when {
            Tone.BAD in tones -> Tone.BAD
            Tone.WARN in tones -> Tone.WARN
            else -> Tone.OK
        }
    }

    private fun words(key: String) = key.replace('_', ' ')

    private inline fun failure(block: () -> Unit): String? = try {
        block(); null
    } catch (e: Exception) {
        e.message ?: "Calibration is incomplete"
    }
}
