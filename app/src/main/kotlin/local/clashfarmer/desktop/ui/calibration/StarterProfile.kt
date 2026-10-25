package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.core.StopException
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.insight.Finding
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.screens.CheckLine

/**
 * The standard positions: a calibration a new account starts from instead of 36 clicks.
 *
 * Every point in a PC calibration but the troop cards' order is Clash's own interface and battle
 * map, the same for every account at one game size. So the app ships the owner's 1740x978
 * calibration, which a supervised attack verified there, with nothing of the owner's village in
 * it: no collectors, wall points or deployment points, and not verified. The tray holds the
 * owner's army in the owner's order, so the user checks each troop card against their own tray
 * before saving, and the result counts as verified only after a supervised attack on the user's
 * account, as any saved calibration does.
 */
internal object StarterProfile {
    /** On the classpath, beside the other resources. */
    const val RESOURCE = "/profile/pc-start-1740x978.json"

    /** The starter as shipped, at 1740x978. */
    fun load(): PhoneProfile {
        val text = StarterProfile::class.java.getResourceAsStream(RESOURCE)?.use { it.bufferedReader().readText() }
            ?: throw IllegalStateException("The standard positions are missing from this copy of Clash Farmer")
        return PhoneProfile.fromJson(text).copy(isVerified = false)
    }

    /**
     * [starter] moved onto a game area of [size] with the rule a run uses ([PcProfiles.derive]),
     * laid over [current], the profile being edited. What the starter doesn't set is kept from
     * [current]: the village's grass, the wall panel, collectors and wall progress belong to the
     * account's village. A [current] of another size, such as a saved calibration under a frame
     * captured at another size, is moved onto [size] first the same way, which drops its
     * collectors as a capture does; one that can't be moved, such as the phone-shaped default,
     * has nothing to keep. Throws with words for the user when [size] is a game area the bot
     * can't work at.
     */
    fun onto(starter: PhoneProfile, current: PhoneProfile, size: SurfaceSize): PhoneProfile {
        val moved = PcProfiles.derive(starter, size, emptySet()).copy(isVerified = false)
        val own = try {
            PcProfiles.derive(current, size, emptySet())
        } catch (_: StopException) {
            return moved
        }
        return moved.copy(
            stateAnchors = own.stateAnchors + moved.stateAnchors,
            troopAnchors = own.troopAnchors + moved.troopAnchors,
            troopRois = own.troopRois + moved.troopRois,
            heroDrop = moved.heroDrop ?: own.heroDrop,
            wallPoints = own.wallPoints,
            wallRows = own.wallRows,
            wallCursor = own.wallCursor,
            wallConfig = moved.wallConfig ?: own.wallConfig,
            homeResourceRois = own.homeResourceRois + moved.homeResourceRois,
            scoutLootRois = own.scoutLootRois + moved.scoutLootRois,
            scoutCountdownRoi = moved.scoutCountdownRoi ?: own.scoutCountdownRoi,
            scoutFeeRoi = moved.scoutFeeRoi ?: own.scoutFeeRoi,
            collectionTargets = own.collectionTargets
        )
    }

    /**
     * The game area the starter is moved onto: the frame on show, else the size of the profile
     * being edited when the bot can work there, else the starter's own 1740x978. Before the
     * first capture of a new account the profile is the phone's 1600x720 unless the window was
     * measured; a capture later moves the starter onto the frame ([refit]).
     */
    fun sizeFor(frame: SurfaceSize?, current: PhoneProfile, starter: PhoneProfile): SurfaceSize {
        if (frame != null) return frame
        val edited = SurfaceSize(current.resolutionWidth, current.resolutionHeight)
        return if (PcProfiles.surfaceProblem(edited) == null) edited
        else SurfaceSize(starter.resolutionWidth, starter.resolutionHeight)
    }

    /** What Setup's calibration row says for an account with no calibration. */
    const val SETUP_HINT = "Not calibrated. Open calibration and start from the standard positions."

    /**
     * Setup's calibration row with [SETUP_HINT] for its detail when the account has no
     * calibration, so a new user learns there is a quicker way than 36 clicks; any other row as
     * it was. The phone's checklist (SetupChecklist, shared with the phone) words that row "Not
     * calibrated", which stays its fallback.
     */
    fun setupLine(line: CheckLine): CheckLine {
        val uncalibrated = line.key.endsWith(":calibration") && line.detail == "Not calibrated" &&
            line.findings.firstOrNull()?.kind == Finding.Kind.NOT_CALIBRATED
        return if (uncalibrated) line.copy(detail = SETUP_HINT) else line
    }
}
