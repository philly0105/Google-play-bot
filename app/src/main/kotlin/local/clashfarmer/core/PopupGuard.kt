package local.clashfarmer.core

import local.clashfarmer.recognition.BATTLE_READS
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.RecognizedBox
import java.text.Normalizer
import java.util.Locale

/**
 * Names the dialogs Clash draws over a frame that otherwise reads as nothing, and says what may be
 * done about each. Only words whose box sits in the middle of the screen count, because a modal is
 * centred and the top strip carries player and clan names that can say anything ("kogyi 23 + TRY
 * again" froze a scout frame on 21 September).
 *
 * The only taps it ever proposes are a close X in a popup's top-right corner, an Okay, OK or
 * Close button read as the whole box, and the text of a character's speech bubble over the
 * village. Nothing with a number, a price or a gem is pressed, and a
 * popup whose text spends gems or money is only ever closed by its X.
 *
 * None of the wording below for maintenance, a forced update, event intros or reward windows has
 * been captured on the phone yet; it is matched loosely until it is.
 */
object PopupGuard {

    /**
     * Recoverable without spending a restart; the start's account check presses Reload once.
     * It does not say "reloading": a run that cannot recover ends with this text as its reason.
     */
    const val SERVER_DIALOG = "Clash lost its server connection"

    /** Recoverable without spending a restart; nothing was tapped while it lasted. */
    const val MAINTENANCE_OVER = "Clash came back from a maintenance break"

    /** Not recoverable: installing an update is the operator's call. */
    const val UPDATE_REQUIRED = "Clash needs an update from the store. The run stopped without tapping Update"

    fun maintenanceTimedOut(minutes: Long) =
        "Clash stayed in maintenance for over $minutes minutes. The run stopped without tapping anything"

    /** How often a maintenance screen is looked at again, and for how long before giving up. */
    const val MAINTENANCE_POLL_MS = 60_000L
    const val MAINTENANCE_MAX_MS = 60 * 60_000L

    sealed interface Verdict {
        /** Nothing known: the caller carries on as it always has. */
        object None : Verdict
        /** The server dropped the game or it sat idle ("Anyone there?"). Not the takeover. */
        data class ServerDialog(val kind: String) : Verdict
        object Maintenance : Verdict
        object UpdateRequired : Verdict
        /** A known popup and the one safe point that closes it. */
        data class Dismiss(val popup: String, val tap: Point) : Verdict
        /** A known popup with nothing safe to tap; it is waited on. */
        data class Wait(val popup: String) : Verdict
        /** A known popup whose text spends gems or money and which has no X. */
        data class RefusedPremium(val popup: String) : Verdict
        /**
         * A character's speech bubble over the village; tapping the bubble shows the next one.
         * Also the star bonus chest and its Continue, which are tapped through the same way.
         */
        data class AdvanceDialogue(val text: String, val tap: Point) : Verdict
        /**
         * The gem store or a priced offer ([Policy.showsShop]). Only Back answers it: its red X
         * sits a card's width from a price, and the 15 October Attack tap landed under its $0.99.
         */
        object Shop : Verdict {
            const val POPUP = "gem_shop"
        }
    }

    /** Bubble taps allowed per encounter. The event intro of 8 October had eight bubbles. */
    const val MAX_DIALOGUE_ADVANCES = 12

    /** Fractions of the viewport. */
    private data class Band(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun holds(point: Point, viewport: Viewport): Boolean {
            if (viewport.width <= 0 || viewport.height <= 0) return false
            val x = (point.x - viewport.left).toDouble() / viewport.width
            val y = (point.y - viewport.top).toDouble() / viewport.height
            return x in left..right && y in top..bottom
        }
    }

    /** Title, body and buttons of a centred modal, below the strip where names sit. */
    private val DIALOG = Band(0.15, 0.12, 0.85, 1.0)

    /** Where a popup draws its close X. */
    private val CLOSE_CORNER = Band(0.55, 0.03, 0.95, 0.45)

    /** Where a popup draws its Okay or Close button. */
    private val BUTTON = Band(0.2, 0.5, 0.8, 0.97)

    /**
     * The character's speech bubble, drawn at x 370-705 and y 405-530 on every frame of the event
     * intro on 8 October; its lines are centred at x 538. audit-evidence/2026-10-08-event-intro-*.png.
     */
    private val BUBBLE = Band(0.25, 0.57, 0.45, 0.74)

    /** The village's Shop button at (1463, 679), which the dimmed intro leaves readable. */
    private val SHOP_CORNER = Band(0.8, 0.85, 1.0, 1.0)

    /** The village's Attack! button at (134, 680), which the intro's character stands over. */
    private val ATTACK_CORNER = Band(0.0, 0.8, 0.2, 1.0)

    /** Neighbouring bubble lines sit at most this fraction of the screen height apart. */
    private const val MAX_LINE_GAP = 0.05

    /** Words of the attack menu, padded to whole words; audit-evidence/2026-09-23-third-40-menu-stop.png. */
    private val MENU_PHRASES = listOf(" star bonus ", " ranked ", " tournament ", " find a ")

    private val SERVER_PHRASES = listOf("connection lost", "lost connection", "disconnected", "out of sync")
    private val IDLE_PHRASES = listOf("anyone there")

    /** Samsung's heads-up notification, not Clash; it is never a reason to stop. */
    private const val SYSTEM_BANNER = "software update ready to install"

    private val CLOSE_GLYPHS = setOf("x", "×")

    private const val BUILDERS_BUSY = "builders_busy"
    private val BUTTON_WORDS = setOf("okay", "ok", "close")

    /** Each known popup and the whole words or phrases, read in the dialog band, that name it. */
    private val KNOWN_POPUPS = listOf(
        "privacy_notice" to listOf("privacy"),
        "league_promotion" to listOf("promoted", "league overview"),
        "event_intro" to listOf("event"),
        "reward" to listOf("reward", "rewards")
    )

    /**
     * Words of Clash's legal and account screens, never of a character's bubble. During the switch
     * to Sweat at 07:55 UTC on 14 October the Supercell ID overlay put "PRİvacY Policy TeRMS Of
     * seRvice" where a bubble sits, with Build still read in its corner, and the links were tapped
     * as a bubble (r3-log-final.jsonl:692). The privacy dialog's "Do not sell or share my personal
     * information" sits there too.
     */
    private val LEGAL_WORDS = listOf("privacy", "terms", "policy", "supercell", "log in", "login", "account", "sell or share")

    /** Words that sell, beyond [Policy.spendsPremium]'s, and a price with its sign after the number. */
    private val SALE_WORDS = setOf("buy", "deal", "deals")
    private val PRICE_SIGN_AFTER = Regex("\\d\\s*[$€£]")

    /**
     * [dialogue] false leaves a character's bubble untapped, for the account switch's wait, where
     * the Supercell ID overlay is the frame most like one.
     */
    fun inspect(boxes: List<RecognizedBox>, viewport: Viewport, rawText: String, dialogue: Boolean = true): Verdict {
        val inDialog = boxes.filter { DIALOG.holds(it.center, viewport) }
        val band = " " + normalize(inDialog.joinToString(" ") { it.text }).replace(SYSTEM_BANNER, " ") + " "

        // The takeover dialog says Connection lost too; it has its own ten-minute wait.
        if (band.contains("another device")) return Verdict.None
        if (IDLE_PHRASES.any { band.contains(it) }) return Verdict.ServerDialog("idle")
        if (SERVER_PHRASES.any { band.contains(it) }) return Verdict.ServerDialog("connection_lost")
        if (band.contains(" maintenance ")) return Verdict.Maintenance
        val update = band.contains(" update ") || band.contains(" new version ")
        val forced = listOf(" available ", " required ", " store ", " download ", " new version ").any { band.contains(it) }
        if (update && forced) return Verdict.UpdateRequired

        // Before anything that taps: the store's X and an offer's Okay are never pressed.
        if (Policy.showsShop(rawText)) return Verdict.Shop

        if (normalize(rawText).contains(SYSTEM_BANNER)) return Verdict.Wait("samsung_update_banner")

        val close = boxes.firstOrNull {
            it.text.trim().lowercase(Locale.ROOT) in CLOSE_GLYPHS && CLOSE_CORNER.holds(it.center, viewport)
        }
        // "All builders are busy!" offers to finish a building for gems: only its X is ever pressed.
        if (Builders.isBusyDialog(boxes, viewport)) {
            return if (close != null) Verdict.Dismiss(BUILDERS_BUSY, close.center) else Verdict.RefusedPremium(BUILDERS_BUSY)
        }

        rewardChest(boxes, viewport, rawText)?.let { return it }

        // Before the known popups: the intro's fifth bubble says "rewards".
        if (dialogue) characterDialogue(boxes, viewport, band)?.let { return it }

        val popup = KNOWN_POPUPS.firstOrNull { (_, words) -> words.any { band.contains(" $it ") } }?.first
            ?: return Verdict.None
        if (close != null) return Verdict.Dismiss(popup, close.center)
        if (Policy.spendsPremium(rawText) || band.contains("gem")) return Verdict.RefusedPremium(popup)
        val button = inDialog.firstOrNull {
            normalize(it.text) in BUTTON_WORDS && !it.text.any(Char::isDigit) && BUTTON.holds(it.center, viewport)
        }
        return if (button != null) Verdict.Dismiss(popup, button.center) else Verdict.Wait(popup)
    }

    /** [inspect] for a caller that has only the boxes, such as the account switcher's waits. */
    fun decide(boxes: List<RecognizedBox>, viewport: Viewport): Verdict =
        inspect(boxes, viewport, boxes.joinToString(" ") { it.text })

    /**
     * Whether [fresh], one frame taken just before Back, still shows what [decided] was read off:
     * the store, or the same priced offer on a frame nothing else reads. At 04:11:37 UTC on
     * 15 October a start read the store and the next frame, about 2 s later with no input, was the
     * village. Back sent on the first frame alone would have landed on the village and opened
     * Clash's quit prompt (UO-21 review).
     */
    fun stillShows(decided: Verdict, fresh: Observation, dialogue: Boolean = true): Boolean =
        Policy.showsShop(fresh.rawText) || (decided is Verdict.RefusedPremium && fresh.state == ScreenState.UNKNOWN &&
            inspect(fresh.boxes, fresh.viewport, fresh.rawText, dialogue) == decided)

    /**
     * An event intro: a character over the dimmed village talking in a speech bubble, advanced by
     * tapping the bubble. On 8 October the first one read "Y-ouCH! THat daRN PoRtal PendaNt Has
     * MasHed up OUR WORIds." and stopped a run on each account. The reader cannot see the dim, so
     * the village is recognised by what the dim leaves: Shop in its corner while Attack!, which the
     * character covers, is not read. The bubble must hold a sentence, at least three words, so a
     * building's NEW or Upgrade label where the bubble would be does not count.
     *
     * The tap goes on the middle of the bubble's own text. A bubble that mentions gems, a pass, an
     * offer, buying or any number, or a dialog band that does, is refused rather than tapped.
     */
    private fun characterDialogue(boxes: List<RecognizedBox>, viewport: Viewport, band: String): Verdict? {
        // The attack menu in its cooldown reads Shop, has no Attack! and puts the Ranked card's
        // title and status line in the bubble band; a tap there could join a tournament.
        if (MENU_PHRASES.any { band.contains(it) } || MultiplayerCooldown.showing(band)) return null
        // Folded, because the overlay's "PRİvacY" normalizes to "pri vacy".
        val legal = " " + fold(boxes.filter { DIALOG.holds(it.center, viewport) }.joinToString(" ") { it.text }) + " "
        if (LEGAL_WORDS.any { legal.contains(" $it ") }) return null
        val lines = boxes.filter { BUBBLE.holds(it.center, viewport) }.sortedBy { it.center.y }
        if (lines.isEmpty()) return null
        // A bubble's lines are about 19 px apart; the menu card's are 50 to 70.
        if (lines.zipWithNext().any { (a, b) -> b.center.y - a.center.y > viewport.height * MAX_LINE_GAP }) return null
        val text = lines.joinToString(" ") { it.text.trim() }
        val words = normalize(text).split(" ").filter { word -> word.any(Char::isLetter) }
        if (words.size < 3 || words.sumOf { word -> word.count(Char::isLetter) } < 12) return null
        // Since Clash 18.647 the two buttons read "Build" and "Battle".
        if (boxes.none { SHOP_CORNER.holds(it.center, viewport) && normalize(it.text).let { t -> t.contains("shop") || t == "build" } }) return null
        if (boxes.any { ATTACK_CORNER.holds(it.center, viewport) && normalize(it.text).let { t -> t.contains("attack") || t.replace(" ", "") in BATTLE_READS } }) return null
        if (text.any(Char::isDigit) || "buy" in words || Policy.spendsPremium(text) || Policy.spendsPremium(band)) {
            return Verdict.RefusedPremium("character_dialogue")
        }
        val tap = Point(
            (lines.minOf { it.left } + lines.maxOf { it.right }) / 2,
            (lines.minOf { it.top } + lines.maxOf { it.bottom }) / 2
        )
        return Verdict.AdvanceDialogue(text, tap)
    }

    /** The chest screen's "Tap!" above the chest, at (790, 165). */
    private val CHEST_TAP_LABEL = Band(0.4, 0.15, 0.6, 0.32)

    /** The chest's rarity on its pedestal, "Common" or "Rare" at (790, 540). */
    private val CHEST_RARITY = Band(0.4, 0.68, 0.6, 0.82)

    /** The reward's Continue button, at (811, 675). */
    private val CHEST_CONTINUE = Band(0.4, 0.86, 0.6, 1.0)

    private val CHEST_RARITIES = setOf("common", "rare", "epic", "legendary")

    /** The chest itself, which each tap hammers once; (795, 390) on 14 October. */
    private const val CHEST_X = 795.0 / 1600
    private const val CHEST_Y = 390.0 / 720

    /**
     * Since Clash 18.647 the star bonus is a chest. Claim Reward on the result screen opens a
     * chest that says "Tap!" and is tapped until its hammers run out (four on 14 October, the
     * chest going from Common to Rare on the way), then shows its reward with a Continue that
     * leaves for the village. Neither tap costs anything. They are bubble taps to every caller,
     * so the dialogue budget bounds them. Both are refused, as a known popup's buttons are, when
     * the frame sells anything. audit-evidence/clash-18647/53-clash-loaded.png to 57.
     */
    private fun rewardChest(boxes: List<RecognizedBox>, viewport: Viewport, rawText: String): Verdict? {
        val tapLabel = boxes.any { CHEST_TAP_LABEL.holds(it.center, viewport) && normalize(it.text) == "tap" }
        val rarity = boxes.any { CHEST_RARITY.holds(it.center, viewport) && normalize(it.text) in CHEST_RARITIES }
        if (tapLabel && rarity) {
            if (sells(rawText)) return Verdict.RefusedPremium("reward_chest")
            val chest = Point(
                viewport.left + (viewport.width * CHEST_X).toInt(),
                viewport.top + (viewport.height * CHEST_Y).toInt()
            )
            return Verdict.AdvanceDialogue("reward_chest", chest)
        }
        val next = boxes.singleOrNull {
            CHEST_CONTINUE.holds(it.center, viewport) && normalize(it.text) == "continue"
        } ?: return null
        // Only the chest's own Continue: its reward stands on the pedestal, "1900 O00" on 56.
        if (boxes.none {
                CHEST_RARITY.holds(it.center, viewport) && (normalize(it.text) in CHEST_RARITIES || it.text.any(Char::isDigit))
            }) return null
        // The village never draws Continue there; a frame with its buttons is something else.
        if (boxes.any { normalize(it.text).let { t -> t == "build" || t.replace(" ", "") in BATTLE_READS } }) return null
        if (sells(rawText)) return Verdict.RefusedPremium("reward_chest")
        return Verdict.AdvanceDialogue("reward_chest_continue", next.center)
    }

    /**
     * What a known popup is refused for, gathered for the chest: [Policy.spendsPremium], "gem"
     * anywhere ("500gems"), buy and deal, and a price whose sign follows it ("4,99 €").
     */
    private fun sells(rawText: String): Boolean {
        if (Policy.spendsPremium(rawText) || PRICE_SIGN_AFTER.containsMatchIn(rawText)) return true
        val text = fold(rawText)
        return text.contains("gem") || text.split(" ").any { it in SALE_WORDS }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** [normalize] with accents dropped first, so "PRİvacY" reads "privacy". */
    private fun fold(text: String): String =
        normalize(Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), ""))
}
