package local.clashfarmer.core

import java.util.Locale

object Policy {

    const val DEFAULT_MAX_WALL_LEVEL = 17
    const val DEFAULT_ATTACKS_PER_WALL_PHASE = 7
    // The loot is there to be spent on walls. Holding a million back just stalled
    // the phase on walls it could afford.
    const val DEFAULT_RESERVE_GOLD = 0L
    const val DEFAULT_RESERVE_ELIXIR = 0L
    const val DEFAULT_MAX_WALL_ADDITIONS = 20

    /** The quota behind the two long-run cards, which an account's own setting does not touch. */
    const val LONG_RUN_ATTACKS = 40

    /** True while the October troop event runs; false hides the composer's event-army choice. */
    const val EVENT_ACTIVE = true

    /** The timed card: two hours of farming, ended by the clock rather than a quota. */
    const val TIMED_RUN_MINUTES = 120
    const val TIMED_RUN_ATTACKS = 100
    const val TIMED_RUN_TRIGGER_PERCENT = 90
    const val TIMED_RUN_RESUME_PERCENT = 0

    /**
     * How long past its time limit a run may go before it is stopped outright. The limit is only
     * looked at at home, so an attack opened just before it still has to search (up to three
     * minutes), scout, fight (three), come home and pass the wall phase that follows.
     */
    const val TIME_LIMIT_GRACE_MS = 15 * 60_000L

    /**
     * The guard timeout for a run whose time limit falls at [limitAt]: up to [TIME_LIMIT_GRACE_MS]
     * past it, anchored there so a restart never earns a fresh grace. Always positive, as the
     * guard requires; past the grace it expires at once.
     */
    fun hardDeadlineMs(limitAt: Long, now: Long): Long =
        (limitAt + TIME_LIMIT_GRACE_MS - now).coerceAtLeast(1L)

    /** Less than this left of a combined run's deadline is not worth switching accounts for. */
    const val MIN_SECOND_ACCOUNT_MINUTES = 5

    private val BLOCKED_PHRASES = listOf(
        "connection lost",
        "reload",
        "out of sync",
        "buy gems",
        "not enough",
        "purchase",
        "maintenance",
        "another device",
        "disconnected",
        "try again",
        "shield will",
        "are you sure",
        // "Unable to attack village! Try again later." came back twice on 23 September with its
        // second sentence mangled past "try again", over matchmaking's Return Home, and the run
        // took it for a result screen.
        "unable"
    )

    /** Ends the current attempt; unattended runs wait before reclaiming the village. */
    const val VILLAGE_TAKEN = "Village opened on another device"

    /**
     * Clash's "Connection lost / Another device is connecting to this Village / Reload". Reload
     * takes the village back and throws the other device out. Unattended retries wait first.
     */
    fun villageTakenElsewhere(rawText: String): Boolean {
        val normalized = rawText.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return normalized.contains("another device") || normalized.contains("connection lost")
    }

    /** Only the takeover wording; the idle and lost-server dialogs say Connection lost too. */
    fun anotherDeviceConnected(rawText: String): Boolean =
        rawText.lowercase(Locale.ROOT).replace(Regex("[^a-z]+"), " ").contains("another device")

    private val PREMIUM_PRICE = Regex("[$€£]\\s*\\d")
    private val PREMIUM_WORDS = setOf("gem", "gems", "purchase", "offer", "offers", "pass")

    /** A dialog that would spend gems or real money. Checked before any Okay or Confirm is pressed. */
    fun spendsPremium(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        if (PREMIUM_PRICE.containsMatchIn(lower)) return true
        val normalized = lower.replace(Regex("[^a-z0-9 ]"), " ")
        val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.any { it in PREMIUM_WORDS }) return true
        val joined = words.joinToString(" ")
        return joined.contains("finish with") || joined.contains("finish now")
    }

    // Clash's gem store, whose cards read "Pocketful of Gems 80 $0.99" up to "Box of Gems 6500
    // $49.99". On 15 October at 05:31 UTC an Attack tap sent 19 s after the frame it was decided on
    // landed about 10 px under the $0.99 card, and the store read as a bare UNKNOWN that recovery
    // waited on for 20 s before its Back (audit-evidence/shop/notes.md). A price needs a currency
    // sign touching a number with two decimals; every game amount is grouped in threes or not at
    // all and carries no sign. The one sign ever read on a game screen is the € OCR makes of the
    // "&" in "Buildings & Heroes", with no digit touching it. Either rule alone finds the store
    // when OCR drops every sign or misreads every title.
    private val SHOP_PRICE = Regex("(?<!\\d)[$€£]\\s?\\d{1,4}[.,]\\d{2}(?!\\d)")
    private val SHOP_PRICE_AFTER = Regex("(?<![\\d.,])\\d{1,4}[.,]\\d{2}\\s?[$€£]")
    private val SHOP_PACK = Regex("\\b(pocketful|handful|pile|bag|sack|box|chest)\\s+of\\s+ge(m|rn)s?\\b")

    /**
     * A screen that sells for real money: the gem store, or an offer with a price on it. It reads
     * as UNKNOWN whatever else is on it, and is only ever left with Back.
     */
    fun showsShop(rawText: String): Boolean {
        val text = rawText.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return SHOP_PRICE.containsMatchIn(text) || SHOP_PRICE_AFTER.containsMatchIn(text) ||
            SHOP_PACK.containsMatchIn(text)
    }

    /**
     * Clash's "Confirm Exit / Do you want to quit the game?", which Back on the village opened on
     * 8 October. Its Okay sits where the surrender confirmation's does (938, 462) and quits Clash.
     * Only the prompt's own words count, as OCR reads them: "quit" anywhere on the frame let a
     * clan or player named "Never Quit" over a battle hold every early-end Okay (UO-21 review,
     * 15 October).
     */
    fun showsQuitPrompt(rawText: String): Boolean {
        val text = rawText.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ")
        return QUIT_BODY.containsMatchIn(text) || QUIT_TITLE.containsMatchIn(text)
    }

    // "quit the game", with OCR's 1 or l for the i, 3 for an e and a space it may drop. The PC's
    // reader made "the" "Che" (audit-evidence/spike/geo-after-deselect.json).
    private val QUIT_BODY = Regex("\\bqu[i1l]t ?[tc]h[e3] ?gam[e3]\\b")
    // "Confirm Exit", read "CONFiRM Bxit" on 8 October: any one letter, or none, before "xit". The
    // PC's reader made the t a plus, "CONfiRM Exi+", so the t may be missing.
    private val QUIT_TITLE = Regex("\\bc[o0]nf[i1l]rm ?[a-z0-9]?x[i1l]t?\\b")

    private val STATE_RULES = listOf(
        ScreenState.REWARD to listOf("pick a reward"),
        ScreenState.RESULT to listOf("return home"),
        // Since Clash 18.647 a result that pays the star bonus has "Claim Reward" where Return
        // Home was, and no Return Home. The first attack on Sweat on 14 October waited on it until
        // the transition timed out. With the loot lines, so no other reward claim matches.
        // audit-evidence/clash-18647/50-result-claim.png.
        ScreenState.RESULT to listOf("claim reward", "you got"),
        ScreenState.RESULT to listOf("claim reward", "troops expended"),
        // Matchmaking offers Return Home to call the search off, so this screen answers the
        // result rule word for word. On 18 September every search that took more than a couple
        // of seconds ended the run as an unexpected result screen. "Searching for opponents"
        // comes back differently mangled every time - "SsaReiingFs oppoIeS", "SearaiNg foR
        // OPPONeNis", "SsaRe:tvgfoR OPpoNeis", "SeaReiiNg tOR OPPDNeNiS" - and three letters
        // is the most of it that survives. The fourth used to be there too, until the run that
        // stopped eight attacks in at 15:10 on 18 September read the O as a D. Nothing else on
        // a result screen - total damage, victory, what you got - spells opp.
        ScreenState.SEARCHING to listOf("return home", "opp"),
        ScreenState.SCOUT to listOf("available loot", "next"),
        ScreenState.SCOUT to listOf("availabie loot", "next"),
        ScreenState.SCOUT to listOf("loot", "next"),
        ScreenState.SCOUT to listOf("battle starts"),
        ScreenState.SCOUT to listOf("end battle", "next"),
        // "Overall" is two l's in a font that draws l and I identically, and the reader picks
        // whichever it likes: "OveRall", "OverallI", "UVeRal", "OveRalI". Three of those do not
        // contain "overall", so a battle in plain sight went unclassified for a full thirty
        // seconds and the run died. "Damage" beside the countdown is the same screen and the
        // reader has never once got that word wrong. The countdown is what separates this from
        // the scout screen, which counts down to a battle starting rather than ending.
        ScreenState.BATTLE to listOf("battle ends", "damage"),
        // The countdown is not as dependable as that: from 25 to 28 September it came back as
        // "Battleends", "Batčle ends", "Batdle endstne" or not at all on 800 battle frames, and
        // "Damage" once in twenty as "Damagge". The button under the loot panel - End Battle
        // before a troop lands, Surrender after - is on every one of them, and the scout screen
        // that shares End Battle has no damage anywhere.
        ScreenState.BATTLE to listOf("end battle", "damag"),
        ScreenState.BATTLE to listOf("surrender", "damag"),
        // Or the damage goes: "OveRall Daage 29%" on Blood at 15:55 UTC on 14 October, and the
        // battle read as nothing. The countdown and Surrender are both a battle's own: the scout
        // counts down to a battle starting and has End Battle, a result has neither, and the
        // takeover dialog drawn over a battle is blocked by its Connection lost and Reload.
        ScreenState.BATTLE to listOf("battle ends", "surrender"),
        // "Attack!" is the one word on this screen the reader keeps getting wrong -
        // "AMack!", "Atack!", "Attad" - and it cost 12s of waiting on every run.
        // "My Army" beside "Saved Recipes" is already this screen and nothing else.
        ScreenState.ARMY to listOf("my army", "saved recipes"),
        // The live reader can clip the first letter of "Saved" while preserving the rest.
        // Keep My Army as the screen-specific guard and accept the stable trailing label.
        ScreenState.ARMY to listOf("my army", "recipes"),
        ScreenState.ARMY to listOf("my army", "army cookbook"),
        // "Join Tournament" is not on this menu. It was read on every earlier run, but on
        // 17 September at 23:53 a tap on Attack opened a menu whose Ranked Battle card had
        // reached its attack limit, with a 4x Star Bonus banner along the bottom and no
        // tournament named anywhere; the menu classified as HOME - the village leaks "Shop"
        // down the edge and "Attacks Completed!" supplies the "attack" - and the run died
        // waiting fifteen seconds for a screen that was already up. "Find a Match" is the
        // button this menu exists to offer and no other screen carries those words.
        ScreenState.MENU to listOf("find a match"),
        // "FiNd a MateH" on every frame for fifteen seconds at 02:46 UTC on 25 September. The
        // first three letters of the last word are the part the reader has not yet mangled.
        ScreenState.MENU to listOf("find a mat"),
        ScreenState.WALL to listOf("wall", "level", "upgrade", "select row"),
        // Upgrade More replaces the panel above with a bulk one that has no Select Row button,
        // so the rule above cannot see it: at 23:05 on 17 September the phase pressed Upgrade
        // More, got the bulk panel it asked for, read the screen as HOME and walked away from
        // it. "Remove Wall" is what this panel has instead, and no other screen has it.
        ScreenState.WALL to listOf("wall", "level", "upgrade", "remove wall"),
        ScreenState.HOME to listOf("attack", "shop"),
        // Without Shop, only the button's own word counts. As a substring, "attack" also
        // matched the loading screen's tip ("aggressive attacking") and the menu's "Attacks
        // Completed!", and every popup of that kind became a village with Attack to press.
        ScreenState.HOME to listOf(" attack "),
        // Clash 18.647 (14 October) renamed the village's buttons: Attack! is "Battle" and Shop is
        // "Build". For four minutes that morning every village frame read as nothing and the run
        // stopped. Both whole words are needed: End Battle, Ranked Battle and "Battle ends in"
        // carry the first alone. audit-evidence/clash-18647/01-home.png.
        ScreenState.HOME to listOf(" battle ", " build "),
        // The button's outline reads as a hyphen often enough: "Bat-tle" on Sweat at 06:09 UTC.
        ScreenState.HOME to listOf(" bat tle ", " build "),
        // Or loses a t: "Batle" on every frame of Blood's village from 07:58 to 08:04 UTC, and each
        // wall phase there ended at its first balance read.
        ScreenState.HOME to listOf(" batle ", " build "),
        // Or both: "Bale" on Blood's village after the reward chest at 08:40:33 UTC.
        ScreenState.HOME to listOf(" bale ", " build "),
        // Or reads its l as an i: "Battie" on Sweat's village at 10:37 UTC.
        ScreenState.HOME to listOf(" battie ", " build "),
        // The PC's reader made the first t an f: "Baftle" on Blood's village under the quit
        // prompt, audit-evidence/spike/geo-after-deselect.json.
        ScreenState.HOME to listOf(" baftle ", " build "),
        // Or the second: "Batfle" on six of Blood's village frames at 1860x1162 on 24 October,
        // audit-evidence/live/20261024-030816-profile/00-home.json among them.
        ScreenState.HOME to listOf(" batfle ", " build ")
    )

    private val AMOUNT_REGEX = Regex("^(?:[0-9]+|[0-9]{1,3}(?:[ ,][0-9]{3})+)$")

    /**
     * Classifies a screen from its words, and from where they sit when [evidence] is given.
     *
     * With evidence, matchmaking is recognised by its own title or its corner button rather than
     * by "opp" anywhere on screen, the result screen needs its own anchors or its button in its
     * own place, and the village needs its Attack! button in its corner. Without evidence the
     * text rules alone apply, as they always have.
     */
    fun classify(rawText: String, evidence: ScreenEvidence? = null): ScreenState {
        // Ahead of everything: the store leaks the village's balances, and Back is its only answer.
        if (showsShop(rawText)) return ScreenState.UNKNOWN
        val normalized = rawText.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        // Padded so a keyword written with spaces round it matches whole words only.
        val padded = " $normalized "

        for (blocked in BLOCKED_PHRASES) {
            if (normalized.contains(blocked)) {
                return ScreenState.UNKNOWN
            }
        }

        // A state with more than one rule must not count twice, or matching both of its rules
        // would look like two states disagreeing.
        val matchingStates = mutableListOf<ScreenState>()
        for ((state, keywords) in STATE_RULES) {
            if (evidence != null && state == ScreenState.SEARCHING) continue
            if (keywords.all { keyword -> padded.contains(keyword) } &&
                !matchingStates.contains(state)
            ) {
                matchingStates.add(state)
            }
        }
        if (evidence != null) {
            val resultSeen = evidence.resultAnchor || evidence.resultReturnButton
            if (!resultSeen) matchingStates.remove(ScreenState.RESULT)
            if (!evidence.homeAttackButton) matchingStates.remove(ScreenState.HOME)
            // Matchmaking has nothing else on it. A scout or battle frame with a base label that
            // happens to spell "opp" mid-screen is still a scout or a battle.
            val otherScreen = matchingStates.any { it != ScreenState.HOME }
            if ((evidence.searchText || evidence.searchReturnButton) && !resultSeen && !otherScreen) {
                matchingStates.add(ScreenState.SEARCHING)
            }
        }

        if (matchingStates.isEmpty()) return ScreenState.UNKNOWN

        // The reward banner is drawn over a battle in progress and over a won battle alike,
        // and it takes the taps meant for either, so it outranks whatever it is covering.
        if (matchingStates.contains(ScreenState.REWARD)) return ScreenState.REWARD

        // Matchmaking always matches the result rule as well as its own, and it is the one of
        // the two that is actually on screen.
        if (matchingStates.contains(ScreenState.SEARCHING)) return ScreenState.SEARCHING

        if (matchingStates.size == 1) return matchingStates[0]

        // Clash draws its panels over the village, and on a tall phone screen the village
        // stays visible down both edges, so HOME's "attack" and "shop" leak into every
        // panel screen. A single panel on top of HOME is that panel.
        val overHome = matchingStates.filter { it != ScreenState.HOME }
        if (overHome.size == 1) return overHome[0]

        // A battle keeps the scout screen's loot panel and End Battle button, so an opponent
        // whose name reads "next" (NextLevel, at 22:54 on 26 September) makes it a scout screen
        // as well. Every battle rule needs the damage, which a scout screen does not have. A
        // frame with the scout countdown on it as well is still not understood.
        if (overHome.toSet() == setOf(ScreenState.SCOUT, ScreenState.BATTLE) &&
            !padded.contains("battle starts")
        ) return ScreenState.BATTLE

        // Anything else conflicting is not understood
        return ScreenState.UNKNOWN
    }

    fun amount(text: String): Long {
        val trimmed = text.trim()
        if (!AMOUNT_REGEX.matches(trimmed)) {
            throw StopException("Ambiguous amount: '$text'")
        }
        val clean = trimmed.replace(" ", "").replace(",", "")
        return clean.toLongOrNull() ?: throw StopException("Failed to parse amount: '$text'")
    }

    fun permittedUpgrade(currency: String, balance: Long, cost: Long, reserve: Long): Boolean {
        if (currency != "gold" && currency != "elixir") {
            throw StopException("Invalid currency '$currency'. Only 'gold' and 'elixir' are permitted.")
        }
        if (cost <= 0L || balance < 0L || reserve < 0L) {
            throw StopException("Invalid amounts: cost=$cost, balance=$balance, reserve=$reserve")
        }
        return (balance - cost) >= reserve
    }

    /**
     * Whether a run may start before the profile has earned verification. Only the watched
     * one-attack session may, since that is the attack that earns it. Attacks-only used to pass
     * as supervised to skip its wall phase, and so ran forty unwatched attacks unverified.
     */
    fun mayStartUnverified(supervised: Boolean, attacks: Int, wallsOnly: Boolean): Boolean =
        wallsOnly || (supervised && attacks == 1)

    fun verificationRequired(villageName: String): String =
        "$villageName has not earned verification on this PC. Run \"Supervised attack\" " +
            "once and let it return to the village; that attack earns it."

    /**
     * [settings] as the timed card runs them: walls on, triggered at 90%, and each phase buying
     * every wall the balance covers. With the account's 75% resume level a 29M storage at 26.8M
     * had 5M above it, so every phase of 30 September's timed runs bought exactly one 8M wall.
     */
    fun timedRunSettings(settings: local.clashfarmer.accounts.AccountSettings) = settings.copy(
        wallsEnabled = true,
        triggerPercent = TIMED_RUN_TRIGGER_PERCENT,
        resumePercent = TIMED_RUN_RESUME_PERCENT
    )

    /**
     * [settings] as the farm and event runs use them: each wall phase buys every wall the balance
     * covers. The account's 75% resume level left 4.2M above it on a 26M storage at 23.7M, so a
     * 5M wall phase on 5 October bought one wall and handed the rest back to the next raid.
     */
    fun farmingRunSettings(settings: local.clashfarmer.accounts.AccountSettings) =
        settings.copy(resumePercent = TIMED_RUN_RESUME_PERCENT)

    /**
     * The event run is the farming run with every early-end trigger off: the event troops are
     * free, so each battle is left to run to its own end.
     */
    fun eventRunSettings(settings: local.clashfarmer.accounts.AccountSettings) =
        farmingRunSettings(settings).copy(earlyEnd = settings.earlyEnd.copy(enabled = false))

    fun validateLimits(attacks: Int, minutes: Int) {
        if (attacks !in 1..100) {
            throw StopException("Attacks limit must be between 1 and 100 (requested: $attacks)")
        }
        if (minutes !in 1..120) {
            throw StopException("Session minutes must be between 1 and 120 (requested: $minutes)")
        }
    }

    class AttackCounter(val limit: Int) {
        init {
            if (limit !in 1..100) {
                throw StopException("This version supports one to a hundred attacks (requested: $limit)")
            }
        }

        var completed: Int = 0
            private set

        val isQuotaReached: Boolean get() = completed >= limit

        var active: Boolean = false
            private set

        fun deploy() {
            if (active || completed >= limit) {
                throw StopException("Cannot start another attack (active=$active, completed=$completed, limit=$limit)")
            }
            active = true
        }

        fun result() {
            if (!active) {
                throw StopException("Unexpected or duplicate battle result (attack not active)")
            }
            completed += 1
            active = false
        }
    }
}
