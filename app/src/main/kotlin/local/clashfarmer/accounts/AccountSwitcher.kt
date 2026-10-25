package local.clashfarmer.accounts

import kotlinx.coroutines.delay
import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Policy
import local.clashfarmer.core.PopupGuard
import local.clashfarmer.core.Region
import local.clashfarmer.core.ReturnHome
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.StopException
import local.clashfarmer.core.TapAfterHold
import local.clashfarmer.core.Viewport
import local.clashfarmer.core.sentOrHeld
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.recognition.ImageMatcher
import local.clashfarmer.recognition.reloadTarget
import local.clashfarmer.recognition.returnHomeTarget
import local.clashfarmer.service.FarmerService

class AccountSwitcher(
    private val service: FarmerService,
    private val screenReader: ScreenReader
) {
    companion object {
        const val MAX_IDENTITY_DIFFERENCE = 8.0
        const val EXPECTED_WIDTH = 1600
        const val EXPECTED_HEIGHT = 720

        /**
         * Clash 18.647 (14 October) took the gear off the village: "Settings has moved to the
         * Profile Menu." The profile card top left opens it, on its profile tab. The bot's overlay
         * takes touches only in its header, which ends at y 91 (y 104 at density 2.0); the card's
         * lower part is drawn over this point but lets touches through to Clash, and captures
         * hide it. audit-evidence/clash-18647/28-profile.png to 37.
         */
        val PROFILE_BUTTON = Point(100, 116)
        val SETTINGS_TAB = Point(1173, 59)
        val SETTINGS_DIALOG_BOUNDS = Region(375, 50, 1225, 671)
        /** Name and tag on the Settings tab's Supercell ID strip; templates cut on 14 October. */
        val SETTINGS_IDENTITY_REGION = Region(700, 104, 920, 179)
        val SWITCH_ARROWS_BTN = Point(1004, 137)
        /** The profile menu's red X. */
        val SETTINGS_CLOSE_BTN = Point(1281, 57)
        val PICKER_REGION = Region(758, 0, 1600, 720)
        val OKAY_DEFAULT = Point(800, 600)
        /** The Layout Editor's red X, measured on the phone on 29 September. */
        val LAYOUT_EDITOR_CLOSE = Point(1489, 54)

        /**
         * The PC's profile menu, in pixels of 1740x978 kept to the edge [onDisplay] is given
         * ([PcGeometry]): the profile card on the left, and the menu's Settings tab, its X and the
         * Supercell ID strip about the middle. A press of the card opened the menu
         * (audit-evidence/live/20261024-134805-profile2). The Layout Editor has not been seen on the
         * PC; its X is an estimate kept to the right edge.
         */
        val PC_PROFILE_BUTTON = Point(77, 92)
        val PC_SETTINGS_TAB = Point(1335, 113)
        val PC_SETTINGS_CLOSE_BTN = Point(1470, 111)
        val PC_SETTINGS_IDENTITY_REGION = Region(745, 171, 1020, 264)
        val PC_LAYOUT_EDITOR_CLOSE = Point(1676, 62)

        /** The PC never presses the switch arrows: it runs one account ([PcAccount]). */
        const val PC_SWITCH_REFUSED = "The PC does not switch accounts"

        /** The phone's 1600x720, or a PC game surface ([PcGeometry.isPc]) of any size. */
        fun supportedDisplay(display: Viewport?): Boolean = display != null &&
            ((display.width == EXPECTED_WIDTH && display.height == EXPECTED_HEIGHT) || PcGeometry.isPc(display))

        /** [phone], or on a PC frame of [display]'s size [pc], a point of 1740x978 kept to [anchor]. */
        fun onDisplay(display: Viewport?, phone: Point, pc: Point, anchor: Anchor): Point =
            if (display != null && PcGeometry.isPc(display)) PcGeometry.point(display, pc.x, pc.y, anchor) else phone

        /** The Supercell ID strip's name and tag on a frame of [display]'s size. */
        fun identityRegion(display: Viewport): Region =
            if (PcGeometry.isPc(display)) PcGeometry.region(display, PC_SETTINGS_IDENTITY_REGION, Anchor.CENTRE)
            else SETTINGS_IDENTITY_REGION

        /**
         * [ImageMatcher.gainCorrectedDifference] of [region] of [screen] against [template]. A
         * region of another size than the template, on a PC frame of another height than the one
         * it was cut at, is compared at the smaller of the two sizes: a template cut on a taller
         * frame is scaled down to the region, and a region larger than its template is scaled
         * down to the template. Scaling up would blur one side and add to the difference.
         */
        fun identityDifference(screen: android.graphics.Bitmap, region: Region, template: android.graphics.Bitmap): Double? {
            if (region.width == template.width && region.height == template.height) {
                return ImageMatcher.gainCorrectedDifference(screen, region, template)
            }
            if (template.height > region.height && region.right <= screen.width && region.bottom <= screen.height) {
                val smaller = android.graphics.Bitmap.createScaledBitmap(template, region.width, region.height, true)
                return try {
                    ImageMatcher.gainCorrectedDifference(screen, region, smaller)
                } finally { smaller.recycle() }
            }
            val left = region.left.coerceIn(0, screen.width - 1)
            val top = region.top.coerceIn(0, screen.height - 1)
            val crop = android.graphics.Bitmap.createBitmap(screen, left, top,
                region.right.coerceIn(left + 1, screen.width) - left, region.bottom.coerceIn(top + 1, screen.height) - top)
            val scaled = try {
                android.graphics.Bitmap.createScaledBitmap(crop, template.width, template.height, true)
            } finally { crop.recycle() }
            return try {
                ImageMatcher.gainCorrectedDifference(scaled, Region(0, 0, template.width, template.height), template)
            } finally { scaled.recycle() }
        }

        /** Settings is open when an enrolled identity template matches its row. */
        fun settingsPanelShowing(differences: List<Double?>) =
            differences.any { it != null && it <= MAX_IDENTITY_DIFFERENCE }

        /**
         * Desktop: the profile menu on its Settings tab, by its words, for an account with no
         * template yet. The tab has the Supercell ID strip and the sound and language settings;
         * the profile tab has none of them (pc-frames/profile).
         */
        fun settingsTabShowing(rawText: String): Boolean {
            val text = AccountIdentityParser.normalize(rawText)
            return AccountIdentityParser.isProfileMenu(rawText) && text.contains("SUPERCELL") &&
                (text.contains("MUSIC") || text.contains("LANGUAGE"))
        }

        /** Presses of the profile menu's X before the close is given up on. */
        const val SETTINGS_CLOSE_ATTEMPTS = 3

        /**
         * Times the switch arrows, a picker row or Reload may be held before the switch gives up.
         * Each hold reads its screen again first, so only a screen that keeps changing uses them up.
         */
        const val HELD_TAP_TRIES = 3

        /** How far a button read again on a look may sit from where its frame had it. */
        const val BUTTON_DRIFT_PX = 24.0

        /** Whether [seen], a button read again on a look, is still where [decided] had it. */
        fun stillThere(decided: Point, seen: Point?): Boolean =
            seen != null && Math.hypot((seen.x - decided.x).toDouble(), (seen.y - decided.y).toDouble()) <= BUTTON_DRIFT_PX

        /** Whether the picker read on a look still has [target]'s own row where [decided] was. */
        fun pickerRowStill(boxes: List<RecognizedBox>, target: AccountRecord, decided: Point): Boolean {
            val row = try {
                AccountIdentityParser.findPickerRow(AccountIdentityParser.parsePickerRows(boxes), target)
            } catch (e: StopException) {
                null
            }
            return stillThere(decided, row?.clickPoint)
        }

        /** Presses the X until the menu is seen closed, at most [SETTINGS_CLOSE_ATTEMPTS] times. */
        suspend fun closeProfileMenu(press: suspend () -> Unit, stillOpen: suspend () -> Boolean): Boolean {
            repeat(SETTINGS_CLOSE_ATTEMPTS) {
                press()
                if (!stillOpen()) return true
            }
            return false
        }
    }

    /**
     * Verifies that the game is on the requested [target] account.
     * If already on [target], closes Settings and returns.
     * If on a different account, switches via Supercell ID picker, handles "Welcome Back Chief!" if present,
     * verifies identity name and tag again in Settings, and closes Settings.
     */
    suspend fun ensureAccount(target: AccountRecord, runToken: Long, reclaimTakeover: Boolean = true) {
        AppLog.log("account_switch_start", "targetId" to target.id, "targetVillage" to target.villageName)
        service.overlay?.updateStatus("Checking account identity...")

        // 1. Verify display dimensions
        // Launching Clash from the portrait helper brings its package to the foreground before
        // Android has finished rotating the display. Wait for the calibrated landscape geometry
        // instead of rejecting that normal transition as a mismatch.
        val displayDeadline = System.currentTimeMillis() + 60_000L
        var display = service.currentDisplayViewport()
        while (display != null &&
            !supportedDisplay(display) &&
            System.currentTimeMillis() < displayDeadline) {
            delay(500)
            display = service.currentDisplayViewport()
        }
        if (!supportedDisplay(display)) {
            throw StopException(
                "Display dimensions mismatch: expected ${EXPECTED_WIDTH}x${EXPECTED_HEIGHT} or a PC game surface, got " +
                    (display?.let { "${it.width}x${it.height}" } ?: "unknown")
            )
        }

        reclaimTakenVillage(runToken, reclaimTakeover)

        // A restart after a hiccup is the only start whose battle may be the bot's own.
        returnHomeBeforeCheck(runToken, ownBattle = !reclaimTakeover)

        // A held arrows tap opens and reads Settings again from the start, at most HELD_TAP_TRIES
        // times: a person can change the menu, or the account, under a pause (UO-21).
        var heldArrows = 0
        while (true) {
            // 2. Open Settings dialog from Home
            openSettings(runToken)

            // 3. Check current identity in Settings
            val currentIdentity = readSettingsIdentity(runToken)
            val identityAt = service.transport.lastCaptureAt
            val initialCheck = AccountIdentityParser.verifySettingsIdentity(currentIdentity, target)

            if (initialCheck is IdentityVerificationResult.Matched) {
                AppLog.log("account_identity_already_active", "village" to target.villageName, "tag" to target.playerTag)
                AppLog.markActiveAccount(target.id, target.villageName)
                service.overlay?.updateStatus("Active account: ${target.villageName}")
                closeSettings(runToken)
                return
            }

            // Not currently active account: perform switch
            AppLog.log("account_switch_required",
                "currentVillage" to (currentIdentity?.villageName ?: "unknown"),
                "currentTag" to (currentIdentity?.playerTag ?: "unknown"),
                "targetVillage" to target.villageName,
                "targetTag" to target.playerTag
            )
            if (display != null && PcGeometry.isPc(display)) {
                throw StopException("$PC_SWITCH_REFUSED: Clash is on ${currentIdentity.villageName}, not ${target.villageName}")
            }
            service.overlay?.updateStatus("Switching to ${target.villageName}...")

            // Tap switch arrows button to open picker. Decided on the identity frame; after a pause
            // or on a stale frame a look needs the Settings strip still in its place, since the
            // arrows are on it and the profile tab has something else there.
            if (sentOrHeld {
                    service.transport.tapButton(SWITCH_ARROWS_BTN, runToken, decidedOn = identityAt, afterPause = {
                        look(runToken) { settingsPanelShowing(identityDifferences(it)) }
                    })
                }) break
            if (++heldArrows >= HELD_TAP_TRIES) throw StopException(TapAfterHold.HELD)
        }
        delay(1500)

        // A held row tap reads the picker again, at most HELD_TAP_TRIES times.
        var heldRows = 0
        while (true) {
            // Wait for picker screen
            val pickerObs = waitForPicker(runToken, timeoutSeconds = 20)

            // Parse picker rows and find target row
            val rows = AccountIdentityParser.parsePickerRows(pickerObs.boxes)
            if (rows.isEmpty()) {
                throw StopException("No account rows detected in Supercell ID picker (read: ${pickerObs.rawText.take(200)})")
            }

            val targetRow = AccountIdentityParser.findPickerRow(rows, target)
            AppLog.log("picker_row_selected",
                "village" to targetRow.villageName,
                "supercell" to targetRow.supercellName,
                "tapPoint" to "(${targetRow.clickPoint.x}, ${targetRow.clickPoint.y})"
            )

            // Decided on the picker's frame; after a pause or on a stale frame the picker is read
            // again and the same row has to be within BUTTON_DRIFT_PX of where it was.
            if (sentOrHeld {
                    service.transport.tapButton(targetRow.clickPoint, runToken, decidedOn = pickerObs.timestampMs, afterPause = {
                        look(runToken) { pickerRowStill(screenReader.observe(it).boxes, target, targetRow.clickPoint) }
                    })
                }) break
            if (++heldRows >= HELD_TAP_TRIES) throw StopException(TapAfterHold.HELD)
        }

        // Wait for reload and home village
        waitForHomeAfterSwitch(runToken, timeoutSeconds = 45)

        // Verify Settings identity again after switch
        openSettings(runToken)
        val postIdentity = readSettingsIdentity(runToken)
        val postCheck = AccountIdentityParser.verifySettingsIdentity(postIdentity, target)

        if (postCheck !is IdentityVerificationResult.Matched) {
            val reason = (postCheck as? IdentityVerificationResult.Mismatch)?.reason ?: "Identity not recognized after switch"
            AppLog.log("account_switch_verify_failed", "reason" to reason)
            throw StopException("Account switch verification failed: $reason")
        }

        AppLog.log("account_switch_verified", "village" to target.villageName, "tag" to target.playerTag)
        AppLog.markActiveAccount(target.id, target.villageName)
        service.overlay?.updateStatus("Verified: ${target.villageName}")
        closeSettings(runToken)
    }

    /**
     * Desktop: reads the account Clash is on into [target], which has no identity template yet
     * ([PcAccount]). It walks to the village and opens Settings with the same presses the check
     * uses, knowing the Settings tab by its words, reads the name and tag off the Supercell ID
     * strip, saves the record and then the strip's crop, and closes Settings. Returns [target]
     * with the name and tag read, for [ensureAccount] to check against the new template.
     */
    suspend fun enrol(target: AccountRecord, runToken: Long): AccountRecord {
        val display = service.currentDisplayViewport()
        if (display == null || !PcGeometry.isPc(display)) {
            throw StopException("Could not read your account: the game area is " +
                (display?.let { "${it.width}x${it.height}" } ?: "not found"))
        }
        service.overlay?.updateStatus("Reading your account in Clash's Settings...")
        reclaimTakenVillage(runToken, reclaimTakeover = true)
        returnHomeBeforeCheck(runToken, ownBattle = false)
        openSettings(runToken, byText = true)
        // The tab's contents slide in after its words read.
        delay(800)
        val frame = service.transport.capture(runToken)
        val read = try {
            val observation = screenReader.observe(frame.bitmap, acquisitionTimeMs = frame.timestampMs)
            if (!settingsTabShowing(observation.rawText)) throw StopException(PcAccount.IDENTITY_UNREADABLE)
            val screen = Viewport(0, 0, frame.bitmap.width, frame.bitmap.height)
            val region = identityRegion(screen)
            val identity = PcAccount.readIdentity(screenReader.observeRegion(frame.bitmap, region, scale = 5).boxes)
                ?: throw StopException(PcAccount.IDENTITY_UNREADABLE)
            val store = AccountStore(service.filesDir)
            val stored = store.load().firstOrNull { it.id == target.id }
                ?: throw StopException("Account not found")
            store.update(stored.copy(villageName = identity.villageName, playerTag = identity.playerTag))
            PcAccount.saveTemplate(service.filesDir, target.id, frame.bitmap, region)
            // The tag is the player's own; the log, which a person may share, keeps the name only.
            AppLog.log("account_identity_enrolled", "village" to identity.villageName,
                "width" to screen.width, "height" to screen.height)
            identity
        } finally { frame.bitmap.recycle() }
        closeSettings(runToken)
        return target.copy(villageName = read.villageName, playerTag = read.playerTag)
    }

    /**
     * Settings opens only over the village, and a start can find Clash on the army screen, a
     * result, an open menu or under a popup. Walks back within [ReturnHome.START_BOUND_MS].
     */
    private suspend fun returnHomeBeforeCheck(runToken: Long, ownBattle: Boolean) {
        val startedAt = System.currentTimeMillis()
        val walk = ReturnHome.forStart(startedAt, ownBattle)
        // The walk's clock leaves out time held under the shade or by a person's pause.
        val held0 = service.heldMs
        var steps = 0
        var firstState: ScreenState? = null
        while (true) {
            val frame = service.transport.capture(runToken)
            val (observation, settingsOpen) = try {
                val obs = screenReader.observe(frame.bitmap, acquisitionTimeMs = frame.timestampMs)
                // Attack! stays visible behind Settings, so only an unread frame is checked for it.
                obs to (obs.state == ScreenState.UNKNOWN && settingsPanelShowing(identityDifferences(frame.bitmap)))
            } finally { frame.bitmap.recycle() }
            val text = observation.rawText
            if (Policy.anotherDeviceConnected(text)) throw StopException(Policy.VILLAGE_TAKEN)
            if (firstState == null) firstState = observation.state
            val now = System.currentTimeMillis() - (service.heldMs - held0)
            val unread = observation.state == ScreenState.UNKNOWN
            val popup = if (unread) PopupGuard.inspect(observation.boxes, observation.viewport, text)
                else PopupGuard.Verdict.None
            val step = walk.next(
                ReturnHome.Frame(
                    state = observation.state,
                    popup = popup,
                    reloadDialog = reloadTarget(observation.boxes, observation.viewport) != null,
                    // What openSettings already deals with: the village by its words, Settings
                    // itself, Welcome Back and the Layout Editor.
                    settingsCanHandle = unread && (settingsOpen || AccountIdentityParser.isWelcomeBackDialog(text) ||
                        AccountIdentityParser.isLayoutEditor(text) || AccountIdentityParser.isVillageHome(text)),
                    text = text,
                    boxes = observation.boxes
                ),
                now
            )
            if (walk.clearedAfter > 0) {
                AppLog.log("popup_dialogue_cleared", "advances" to walk.clearedAfter,
                    "state" to observation.state.name, "where" to "start")
            }
            if (step == ReturnHome.Step.Done) {
                AppLog.log("start_reached_home", "fromState" to firstState.name, "state" to observation.state.name,
                    "steps" to steps, "elapsedMs" to now - startedAt)
                return
            }
            if (step is ReturnHome.Step.Stop) {
                AppLog.log(if (step.reason == ReturnHome.BATTLE_REFUSED) "start_refused_battle" else "start_home_timeout",
                    "state" to observation.state.name, "steps" to steps, "elapsedMs" to now - startedAt,
                    "reason" to step.reason.take(200), "text" to text.take(200))
                throw StopException(step.reason)
            }
            steps++
            AppLog.log("start_return_home", "state" to observation.state.name, "step" to step.name,
                "elapsedMs" to now - startedAt, "text" to text.take(160))
            if (steps == 1) service.overlay?.updateStatus("Returning to the village...")
            // A press decided on this frame is held after a pause or once the frame is stale, and
            // the next pass reads the screen again (UO-21). Back is withheld while paused.
            when (step) {
                ReturnHome.Step.ReturnHome -> {
                    // Only the button in its own place; a defender's name can say Return too.
                    val button = returnHomeTarget(observation.boxes, observation.viewport, Point(800, 624))
                    if (button != null &&
                        !sentOrHeld { service.transport.tapButton(button, runToken, decidedOn = observation.timestampMs) }) continue
                    delay(3000)
                }
                ReturnHome.Step.Back -> {
                    // Back for the store or an offer goes only if one more frame still shows it.
                    if ((popup == PopupGuard.Verdict.Shop || popup is PopupGuard.Verdict.RefusedPremium) &&
                        !service.shopStillUp("start", popup) { observeNow(runToken) }) continue
                    // A withheld Back is waited out by the gate, which adds a person's pause to
                    // heldMs as well as the shade's; awaitResume added only the shade's, so a
                    // pause here ran down the walk's 90 s (UO-21 review, 15 October).
                    if (service.backIfClear(runToken, "start")) delay(2000) else {
                        service.awaitClearToAct("start")
                        delay(1000)
                    }
                }
                is ReturnHome.Step.ClosePopup -> {
                    if (!sentOrHeld { service.transport.tapButton(step.tap, runToken, decidedOn = observation.timestampMs) }) continue
                    AppLog.log("popup_dismissed", "popup" to step.popup, "x" to step.tap.x, "y" to step.tap.y,
                        "where" to "start", "text" to text.take(200))
                    delay(1500)
                }
                is ReturnHome.Step.AdvanceDialogue -> {
                    if (!sentOrHeld { service.transport.tapButton(step.tap, runToken, decidedOn = observation.timestampMs) }) continue
                    AppLog.log("popup_dialogue_advanced", "x" to step.tap.x, "y" to step.tap.y,
                        "where" to "start", "text" to step.text.take(200))
                    delay(1500)
                }
                is ReturnHome.Step.CancelQuitPrompt ->
                    if (service.cancelQuitPrompt(step.tap, runToken, observation.timestampMs, "start", text)) delay(1500) else delay(1000)
                else -> delay(1000)
            }
        }
    }

    /**
     * Opens the profile menu on its Settings tab. It is known by the account's template, or with
     * [byText], for an account with no template yet, by the tab's words ([settingsTabShowing]).
     */
    private suspend fun openSettings(runToken: Long, byText: Boolean = false) {
        val deadline = System.currentTimeMillis() + 30_000L
        val held0 = service.heldMs
        var lastState = ScreenState.UNKNOWN
        var lastText = ""
        val popupTaps = PopupTaps()
        var quitCancels = 0
        while (System.currentTimeMillis() < deadline + (service.heldMs - held0)) {
            val frame = service.transport.capture(runToken)
            val observation = try {
                // Attack! stays visible behind the panel, so look for the panel before the village.
                if (settingsPanelShowing(identityDifferences(frame.bitmap))) return
                screenReader.observe(frame.bitmap, acquisitionTimeMs = frame.timestampMs)
            } finally { frame.bitmap.recycle() }
            lastState = observation.state
            lastText = observation.rawText
            if (Policy.anotherDeviceConnected(observation.rawText)) throw StopException(Policy.VILLAGE_TAKEN)
            // Before the profile menu below, which would press the tab again.
            if (byText && settingsTabShowing(observation.rawText)) return
            // The store gets Back and nothing else, before anything below can read it as the village.
            if (Policy.showsShop(observation.rawText)) {
                if (!backOutOfShop(observation, runToken, "settings", popupTaps)) delay(750)
                continue
            }
            // Every press below is decided on this frame. After a pause, or once the frame is stale,
            // it is held and the loop reads the screen again; nothing goes out on what was there
            // before (UO-21). So the Welcome Back Okay is sent only after a fresh premium check.
            val decidedOn = observation.timestampMs
            // Clash's quit prompt reads as the village and PROFILE_BUTTON is outside it (UO-21
            // review). It gets Cancel, MAX_POPUP_TAPS times at most, and is read again in this wait.
            val quit = ReturnHome.quitPrompt(observation.rawText, observation.boxes)
            if (quit != null) {
                if (quitCancels < ReturnHome.MAX_POPUP_TAPS &&
                    service.cancelQuitPrompt(quit.cancel, runToken, decidedOn, "settings", observation.rawText)) {
                    quitCancels++
                    delay(1500)
                } else delay(750)
                continue
            }
            // The menu opens on its profile tab. The tab's tap also clears the one-time "Settings
            // has moved" bubble, which dims the strip the identity is matched on.
            if (AccountIdentityParser.isProfileMenu(observation.rawText)) {
                val tab = onDisplay(observation.viewport, SETTINGS_TAB, PC_SETTINGS_TAB, Anchor.CENTRE)
                if (sentOrHeld { service.transport.tapButton(tab, runToken, decidedOn = decidedOn) }) delay(1200)
                continue
            }
            if (observation.state == ScreenState.HOME ||
                AccountIdentityParser.isVillageHome(observation.rawText)) {
                // The next frame confirms the panel; a tap lost to a loading village is retried.
                val profile = onDisplay(observation.viewport, PROFILE_BUTTON, PC_PROFILE_BUTTON, Anchor.LEFT)
                if (sentOrHeld { service.transport.tapButton(profile, runToken, decidedOn = decidedOn) }) delay(1200)
                continue
            }
            if (observation.state == ScreenState.RESULT ||
                observation.rawText.lowercase(java.util.Locale.ROOT).contains("return home")) {
                // Only the button in its own place; a defender's name can say Return too.
                val returnHomeBtn = returnHomeTarget(observation.boxes, observation.viewport, Point(800, 624))
                if (returnHomeBtn != null) {
                    if (sentOrHeld { service.transport.tapButton(returnHomeBtn, runToken, decidedOn = decidedOn) }) delay(3000)
                    continue
                }
            }
            if (AccountIdentityParser.isWelcomeBackDialog(observation.rawText)) {
                if (Policy.spendsPremium(observation.rawText)) {
                    AppLog.log("premium_dialog_refused", "where" to "welcome_back_settings",
                        "text" to observation.rawText.take(200))
                    throw StopException(
                        "Refused a dialog that spends gems or money: ${observation.rawText.take(120)}"
                    )
                }
                val okayBtn = AccountIdentityParser.findOkayButton(observation.boxes)
                if (okayBtn == null) {
                    delay(750)
                    continue
                }
                if (sentOrHeld { service.transport.tapButton(okayBtn, runToken, decidedOn = decidedOn) }) delay(2000)
                continue
            }
            // A gear tap that lands while the village's buttons are still settling can open the
            // Layout Editor above it instead (04:51 UTC, 29 September). Close it and try again.
            if (AccountIdentityParser.isLayoutEditor(observation.rawText)) {
                val close = onDisplay(observation.viewport, LAYOUT_EDITOR_CLOSE, PC_LAYOUT_EDITOR_CLOSE, Anchor.RIGHT)
                if (sentOrHeld { service.transport.tapButton(close, runToken, decidedOn = decidedOn) }) {
                    AppLog.log("layout_editor_closed", "text" to observation.rawText.take(160))
                    delay(1500)
                }
                continue
            }
            // A popup can land on the village after the start's walk reached it.
            if (closeKnownPopup(observation, runToken, "settings", popupTaps)) continue
            delay(750)
        }
        throw StopException(
            "Cannot verify account identity because Settings did not open over the village home screen " +
                "(state: ${lastState.name}, read: ${lastText.take(160)})"
        )
    }

    /**
     * Closes a popup [PopupGuard] knows by the one tap it offers, on a frame nothing else reads.
     * Maintenance stops the start without a tap; the store and a priced offer get Back. Returns
     * whether the caller should read again at once: a tap or Back went out, or a tap was held.
     */
    private suspend fun closeKnownPopup(
        observation: local.clashfarmer.recognition.Observation,
        runToken: Long,
        where: String,
        taps: PopupTaps
    ): Boolean {
        if (observation.state != ScreenState.UNKNOWN) {
            if (taps.advances > 0) {
                AppLog.log("popup_dialogue_cleared", "advances" to taps.advances,
                    "state" to observation.state.name, "where" to where)
                taps.advances = 0
            }
            return false
        }
        // No bubble taps in the switch's wait: the Supercell ID overlay's legal links passed for
        // one on 14 October (r3-log-final.jsonl:692). An intro there costs this wait and a restart.
        val dialogue = where != "switch"
        return when (val verdict = PopupGuard.inspect(observation.boxes, observation.viewport, observation.rawText, dialogue)) {
            PopupGuard.Verdict.Maintenance -> {
                AppLog.log("maintenance_break", "where" to where, "text" to observation.rawText.take(200))
                throw StopException(ReturnHome.MAINTENANCE_AT_START)
            }
            PopupGuard.Verdict.Shop, is PopupGuard.Verdict.RefusedPremium ->
                backOutOfShop(observation, runToken, where, taps, verdict, dialogue)
            // A held tap is read again and not counted (UO-21).
            is PopupGuard.Verdict.Dismiss -> {
                if (taps.dismissed >= ReturnHome.MAX_POPUP_TAPS) return false
                if (!sentOrHeld { service.transport.tapButton(verdict.tap, runToken, decidedOn = observation.timestampMs) }) return true
                taps.dismissed++
                AppLog.log("popup_dismissed", "popup" to verdict.popup, "x" to verdict.tap.x, "y" to verdict.tap.y,
                    "where" to where, "text" to observation.rawText.take(200))
                delay(1500)
                true
            }
            is PopupGuard.Verdict.AdvanceDialogue -> {
                if (taps.advancesTotal >= PopupGuard.MAX_DIALOGUE_ADVANCES) return false
                if (!sentOrHeld { service.transport.tapButton(verdict.tap, runToken, decidedOn = observation.timestampMs) }) return true
                taps.advancesTotal++
                taps.advances++
                AppLog.log("popup_dialogue_advanced", "advance" to taps.advances, "x" to verdict.tap.x,
                    "y" to verdict.tap.y, "where" to where, "text" to verdict.text.take(200))
                delay(1500)
                true
            }
            else -> false
        }
    }

    /**
     * Back out of the gem store or a priced offer, the only answer either gets: on 15 October at
     * 05:31 UTC a tap meant for the village landed on the store under its $0.99 card. At most
     * [ReturnHome.MAX_SHOP_BACKS] in one wait, and false past that, so the screen is only waited
     * on. Back is withheld while paused, and the next capture waits the pause out. [decided] is
     * what [observation] was read as; Back goes only if one more frame still shows it.
     */
    private suspend fun backOutOfShop(
        observation: Observation,
        runToken: Long,
        where: String,
        taps: PopupTaps,
        decided: PopupGuard.Verdict = PopupGuard.Verdict.Shop,
        dialogue: Boolean = true
    ): Boolean {
        if (taps.shopBacks >= ReturnHome.MAX_SHOP_BACKS) return false
        if (!service.shopStillUp(where, decided, dialogue) { observeNow(runToken) }) return true
        AppLog.log("gem_shop_left", "where" to where, "text" to observation.rawText.take(200))
        service.overlay?.updateStatus("Leaving the gem store...")
        if (service.backIfClear(runToken, "gem_shop_switch")) taps.shopBacks++
        delay(1200)
        return true
    }

    /** One frame, taken and read now. */
    private suspend fun observeNow(runToken: Long): Observation {
        val frame = service.transport.capture(runToken)
        return try {
            screenReader.observe(frame.bitmap, acquisitionTimeMs = frame.timestampMs)
        } finally { frame.bitmap.recycle() }
    }

    /**
     * A look before a tap that was held or decided on a stale frame: a fresh capture, and
     * [TapAfterHold.HELD] unless [still] passes on it, so the tap is not sent.
     */
    private suspend fun look(runToken: Long, still: suspend (android.graphics.Bitmap) -> Boolean) {
        val frame = service.transport.capture(runToken)
        val passed = try { still(frame.bitmap) } finally { frame.bitmap.recycle() }
        if (!passed) throw StopException(TapAfterHold.HELD)
    }

    /** What [closeKnownPopup] has tapped in one wait. */
    private class PopupTaps {
        var dismissed = 0
        /** Bubbles since the last known screen, for `popup_dialogue_cleared`. */
        var advances = 0
        /** Bubbles over the whole wait; the cap, never reset. */
        var advancesTotal = 0
        /** Backs out of the store or a priced offer over the whole wait. */
        var shopBacks = 0
    }

    /**
     * The open menu still reads as HOME, Battle and Build showing beside it (31-profile-default to
     * 33), so a lost X would leave the run tapping over it. Its tabs or the Settings identity say
     * it is still open.
     */
    private suspend fun closeSettings(runToken: Long) {
        // The first press is decided on the identity frame, each later one on the frame that read
        // the menu still open. A held press is not sent and counts as one; the next read decides.
        var decidedOn = service.transport.lastCaptureAt
        val closed = closeProfileMenu(
            press = {
                val close = onDisplay(service.currentDisplayViewport(), SETTINGS_CLOSE_BTN, PC_SETTINGS_CLOSE_BTN, Anchor.CENTRE)
                if (sentOrHeld { service.transport.tapButton(close, runToken, decidedOn = decidedOn) }) delay(800)
            },
            stillOpen = {
                val frame = service.transport.capture(runToken)
                decidedOn = frame.timestampMs
                try {
                    AccountIdentityParser.isProfileMenu(screenReader.observe(frame.bitmap).rawText) ||
                        settingsPanelShowing(identityDifferences(frame.bitmap))
                } finally { frame.bitmap.recycle() }
            }
        )
        if (!closed) AppLog.log("settings_close_failed", "presses" to SETTINGS_CLOSE_ATTEMPTS)
    }

    /**
     * Desktop: each account's own template from its folder ([PcAccount]), where the phone's two
     * were app assets. An account not read yet has none, and nothing is compared for it.
     */
    private fun templates(accounts: List<AccountRecord>): List<Pair<AccountRecord, PcAccount.Template>> =
        accounts.mapNotNull { account -> PcAccount.loadTemplate(service.filesDir, account.id)?.let { account to it } }

    private fun identityDifferences(screen: android.graphics.Bitmap): List<Double?> {
        val display = Viewport(0, 0, screen.width, screen.height)
        return templates(AccountStore(service.filesDir).load()).map { (_, template) ->
            try {
                identityDifference(screen, identityRegion(display), template.bitmap)
            } finally { template.bitmap.recycle() }
        }
    }

    private suspend fun readSettingsIdentity(runToken: Long): SettingsIdentity {
        val frame = service.transport.capture(runToken)
        return try {
            val display = Viewport(0, 0, frame.bitmap.width, frame.bitmap.height)
            val region = identityRegion(display)
            val obs = screenReader.observeRegion(frame.bitmap, region, scale = 5)
            val enrolled = AccountStore(service.filesDir).load()
            val matches = templates(enrolled).mapNotNull { (account, template) ->
                val villageVisible = obs.boxes.any {
                    AccountIdentityParser.normalize(it.text) == AccountIdentityParser.normalize(account.villageName)
                }
                val difference = try {
                    identityDifference(frame.bitmap, region, template.bitmap)
                } finally { template.bitmap.recycle() }
                AppLog.log(
                    "account_identity_candidate", "village" to account.villageName,
                    "villageVisible" to villageVisible, "visualDifference" to difference,
                    "templateFrame" to "${template.frame.width}x${template.frame.height}"
                )
                if (villageVisible && difference != null && difference <= MAX_IDENTITY_DIFFERENCE) account else null
            }
            when (matches.size) {
                1 -> SettingsIdentity(matches.single().villageName, matches.single().playerTag)
                0 -> throw StopException(
                    if (PcGeometry.isPc(display)) PcAccount.NOT_THE_READ_VILLAGE
                    else "Current account identity is unreadable or is not one of the enrolled accounts"
                )
                else -> throw StopException("Current account identity is ambiguous; refusing to switch")
            }
        } finally {
            frame.bitmap.recycle()
        }
    }

    private suspend fun waitForPicker(runToken: Long, timeoutSeconds: Int): local.clashfarmer.recognition.Observation {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        val held0 = service.heldMs
        while (System.currentTimeMillis() < deadline + (service.heldMs - held0)) {
            val frame = service.transport.capture(runToken)
            val obs = try {
                screenReader.observe(frame.bitmap, acquisitionTimeMs = frame.timestampMs)
            } finally {
                frame.bitmap.recycle()
            }
            val norm = AccountIdentityParser.normalize(obs.rawText)
            checkForLoginPrompt(obs.rawText)
            if (Policy.anotherDeviceConnected(obs.rawText)) throw StopException(Policy.VILLAGE_TAKEN)
            if (norm.contains("SWITCH ID") || norm.contains("OTHER IDS")) {
                return obs
            }
            delay(500)
        }
        throw StopException("Timed out waiting for Supercell ID account picker")
    }

    private fun checkForLoginPrompt(rawText: String) {
        val norm = AccountIdentityParser.normalize(rawText)
        if (norm.contains("ENTER CODE") || norm.contains("VERIFICATION CODE") || norm.contains("LOG IN WITH SUPERCELL ID")) {
            throw StopException("Unexpected Supercell ID login required; accounts must be saved without login")
        }
    }

    /**
     * A start is the operator taking the village back, so a disconnect dialog's Reload (another
     * device, or idle too long) is pressed here, once. Unattended takeover recovery also permits
     * this after its ten-minute wait. Reload can disconnect the other device.
     */
    private suspend fun reclaimTakenVillage(runToken: Long, reclaimTakeover: Boolean) {
        // A Reload held by a pause or a stale frame reads the dialog again, HELD_TAP_TRIES times
        // at most (UO-21).
        repeat(HELD_TAP_TRIES) {
            val frame = service.transport.capture(runToken)
            val obs = try {
                screenReader.observe(frame.bitmap, acquisitionTimeMs = frame.timestampMs)
            } finally {
                frame.bitmap.recycle()
            }
            // A run picking itself up after a hiccup is not the operator taking the village back.
            if (!reclaimTakeover && Policy.anotherDeviceConnected(obs.rawText)) {
                throw StopException(Policy.VILLAGE_TAKEN)
            }
            val reload = reloadTarget(obs.boxes, obs.viewport)
            if (reload == null) {
                if (Policy.villageTakenElsewhere(obs.rawText)) {
                    throw StopException("Clash lost its connection and its Reload or Try again button could not be read")
                }
                return
            }
            // Decided on this frame; after a pause or on a stale frame a look reads Reload again
            // and it has to be within BUTTON_DRIFT_PX of where it was.
            val sent = sentOrHeld {
                service.transport.tapButton(reload, runToken, decidedOn = obs.timestampMs, afterPause = {
                    look(runToken) {
                        val again = screenReader.observe(it)
                        stillThere(reload, reloadTarget(again.boxes, again.viewport))
                    }
                })
            }
            if (sent) {
                AppLog.log("takeover_reload_tapped", "x" to reload.x, "y" to reload.y, "text" to obs.rawText.take(200))
                service.overlay?.updateStatus("Reloading the village...")
                waitForHomeAfterSwitch(runToken, 90)
                AppLog.log("takeover_reloaded")
                return
            }
        }
        throw StopException(TapAfterHold.HELD)
    }

    private suspend fun waitForHomeAfterSwitch(runToken: Long, timeoutSeconds: Int) {
        val startedAt = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        val held0 = service.heldMs
        var sawReloadState = false
        var consecutiveHomeFrames = 0
        val popupTaps = PopupTaps()
        while (System.currentTimeMillis() < deadline + (service.heldMs - held0)) {
            delay(1000)
            val frame = service.transport.capture(runToken)
            val obs = try {
                screenReader.observe(frame.bitmap, acquisitionTimeMs = frame.timestampMs)
            } finally {
                frame.bitmap.recycle()
            }
            checkForLoginPrompt(obs.rawText)

            // Give the tapped Reload five seconds to disappear before treating the dialog as
            // a renewed takeover. The caller will background Clash and wait again.
            if (System.currentTimeMillis() - startedAt >= 5_000L && Policy.anotherDeviceConnected(obs.rawText)) {
                throw StopException(Policy.VILLAGE_TAKEN)
            }

            // The store gets Back and nothing else, before anything below can read it as the village.
            if (Policy.showsShop(obs.rawText)) {
                sawReloadState = true
                consecutiveHomeFrames = 0
                backOutOfShop(obs, runToken, "switch", popupTaps)
                continue
            }

            // Check for benign "Welcome Back Chief!" popup
            if (AccountIdentityParser.isWelcomeBackDialog(obs.rawText)) {
                sawReloadState = true
                consecutiveHomeFrames = 0
                AppLog.log("welcome_back_dialog_detected")
                if (Policy.spendsPremium(obs.rawText)) {
                    AppLog.log("premium_dialog_refused", "where" to "welcome_back_switch",
                        "text" to obs.rawText.take(200))
                    throw StopException("Refused a dialog that spends gems or money: ${obs.rawText.take(120)}")
                }
                val tapPoint = AccountIdentityParser.findOkayButton(obs.boxes)
                    ?: throw StopException("Welcome Back dialog was detected but its Okay button was unreadable")
                // Held after a pause or on a stale frame; the next pass checks the premium text again.
                if (sentOrHeld { service.transport.tapButton(tapPoint, runToken, decidedOn = obs.timestampMs) }) delay(1500)
                continue
            }

            // The picker can briefly expose the old village before Clash starts reloading.
            // Require either a visible reload state or an eight-second grace period, then two
            // consecutive HOME observations so Settings is not opened over an animation.
            val homeVisible = obs.state == ScreenState.HOME ||
                AccountIdentityParser.isVillageHome(obs.rawText)
            if (!homeVisible) {
                sawReloadState = true
                consecutiveHomeFrames = 0
                // An event intro after Welcome Back held the village past this wait on 8 October.
                closeKnownPopup(obs, runToken, "switch", popupTaps)
                continue
            }
            if (sawReloadState || System.currentTimeMillis() - startedAt >= 8_000L) {
                consecutiveHomeFrames += 1
                if (consecutiveHomeFrames >= 2) return
            }
        }
        throw StopException("Timed out waiting for home village screen after account switch")
    }

}
