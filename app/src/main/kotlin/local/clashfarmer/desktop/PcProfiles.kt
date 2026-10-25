package local.clashfarmer.desktop

import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Step
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.WallConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A calibration moved onto the game area Google Play Games gives now.
 *
 * GPG picks the game's aspect when it launches, from the monitor it opens on, and keeps it through
 * a resize: a profile calibrated at 1740x978 (16:9) meets 1860x1162 (16:10) after a relaunch, and
 * 1565x978 when 1740x978 is asked of that surface. Relaunching to get the old shape back breaks
 * the bot's taps until the PC restarts, so the run moves the calibration instead, with the rule
 * [PcGeometry] uses for its own values: everything scales by the height, and each x keeps its
 * distance from the edge or centre Clash draws it about. The 16:10 HUD follows that rule within
 * 5 px (PcGeometryTest), and the Battle button and storage balances are checked on a 16:10 capture
 * (PcProfilesTest). The army screen and the zoomed-out battle map are the exceptions: Clash fits
 * them inside the game area, so the Attack! button and the drop points are moved by the smaller of
 * the two scales about the centre. docs/PC-GEOMETRY.md has the anchors and their evidence.
 *
 * A moved profile is used for one run and never saved: profile.json keeps the points the user
 * tapped. At another aspect it counts as verified only once a supervised attack has worked at that
 * aspect ([SurfaceVerification]), since the tray, the army button and the battle map are moved by
 * a measured rule but were never seen there.
 */
object PcProfiles {
    /** The fewest rows the bot reads the game at: the 1280x720 a small window gave still read. */
    const val MIN_HEIGHT = 720

    /** How far two aspects may differ and still count as one: 1740x978 and 1920x1080 are both 16:9. */
    private const val SAME_ASPECT_TOLERANCE = 0.01

    fun aspect(width: Int, height: Int): Double = width.toDouble() / height

    fun sameAspect(width: Int, height: Int, otherWidth: Int, otherHeight: Int): Boolean =
        abs(aspect(width, height) / aspect(otherWidth, otherHeight) - 1) <= SAME_ASPECT_TOLERANCE

    fun sameAspect(aspect: Double, other: Double): Boolean = abs(aspect / other - 1) <= SAME_ASPECT_TOLERANCE

    /** The shape the user knows a screen by: 16:9, 16:10, or the ratio itself. */
    fun shape(width: Int, height: Int): String = when {
        sameAspect(aspect(width, height), 16.0 / 9) -> "16:9"
        sameAspect(aspect(width, height), 16.0 / 10) -> "16:10"
        else -> "%.2f:1".format(java.util.Locale.ROOT, aspect(width, height))
    }

    /**
     * Why the bot can't work on a game area of [surface], in words for the user, or null when it
     * can. A shape outside the PC's ([PcGeometry.isPc]) was never measured, and an area under
     * [MIN_HEIGHT] rows is too small to read.
     */
    fun surfaceProblem(surface: SurfaceSize): String? = when {
        !PcGeometry.isPc(Viewport(0, 0, surface.width, surface.height)) ->
            "Clash's game area is ${surface.width}x${surface.height}, a shape the bot can't work with. " +
                "Restart the PC, open Clash of Clans on a 16:9 or 16:10 screen, then start again."
        surface.height < MIN_HEIGHT ->
            "Clash's game area is ${surface.width}x${surface.height}, too small for the bot to read. " +
                "Move the Clash window to a larger screen, then start again."
        else -> null
    }

    /**
     * [want], or the largest size of its aspect inside [room] when it doesn't fit there. The
     * sidecar caps a resize with the same sum (Server.cs, FitInside), so a window is never sized
     * past its monitor's work area, where the taskbar would cover the game's bottom rows.
     */
    fun fitInside(want: SurfaceSize, room: SurfaceSize?): SurfaceSize {
        if (room == null || room.width <= 0 || room.height <= 0) return want
        if (want.width <= room.width && want.height <= room.height) return want
        val scale = minOf(room.width.toDouble() / want.width, room.height.toDouble() / want.height)
        return SurfaceSize(kotlin.math.floor(want.width * scale).toInt(), kotlin.math.floor(want.height * scale).toInt())
    }

    /**
     * Whether [profile] counts as verified on a game area of [width]x[height]: it was verified
     * where it was calibrated, and the area has that aspect or one in [verifiedAspects].
     */
    fun verifiedAt(profile: PhoneProfile, width: Int, height: Int, verifiedAspects: Collection<Double>): Boolean =
        profile.isVerified && (
            sameAspect(width, height, profile.resolutionWidth, profile.resolutionHeight) ||
                verifiedAspects.any { sameAspect(aspect(width, height), it) }
            )

    private val SIZE = Regex("""Viewport\(left=(-?\d+), top=(-?\d+), right=(-?\d+), bottom=(-?\d+)\)""")
    private val GUARD_MISMATCH = Regex("""Viewport mismatch: expected (Viewport\([^)]*\)), got (Viewport\([^)]*\)|null)""")
    private val RUNNER_MISMATCH = Regex("""The screen is (Viewport\([^)]*\)|null) but this profile was calibrated for (Viewport\([^)]*\))""")

    /**
     * [reason] in words for the user when it is one of the copied code's game-area stops, which
     * print Kotlin's Viewport(left=0, top=0, right=1740, bottom=978); else [reason] unchanged. The
     * run guard's stop says the area changed under the run, and the runner's that it never became
     * the size the run expected.
     */
    fun plainStopReason(reason: String): String {
        GUARD_MISMATCH.find(reason)?.let { m ->
            val seen = size(m.groupValues[2]) ?: return "Clash's game area went missing during the run. " +
                "Check that the Clash window is open, then start again."
            return "Clash's game area changed from ${size(m.groupValues[1])} to $seen during the run. " +
                "Leave the Clash window's size alone while the bot runs, then start again."
        }
        RUNNER_MISMATCH.find(reason)?.let { m ->
            val seen = size(m.groupValues[1]) ?: return "The bot couldn't see Clash's game area. " +
                "Check that the Clash window is open, then start again."
            return "Clash's game area is $seen, not the ${size(m.groupValues[2])} the run expected. " +
                "Start again, and if this keeps happening, open Calibrate and capture the game again."
        }
        return reason
    }

    /** "1740x978" for Kotlin's printed Viewport, null for "null". */
    private fun size(viewport: String): String? = SIZE.matchEntire(viewport)?.let { m ->
        val (left, top, right, bottom) = m.destructured
        "${right.toInt() - left.toInt()}x${bottom.toInt() - top.toInt()}"
    }

    /**
     * The game area a run on a calibration of [calibrated] works at, from a window whose game area
     * is [surface] on a monitor that holds [room]. FarmerService.fitSurface asks for the
     * calibration's size when the area has its shape, and the sidecar caps that to [room]; an area
     * of another shape is left as it is, since Google Play Games keeps the shape it chose.
     */
    fun runSize(surface: SurfaceSize, room: SurfaceSize?, calibrated: SurfaceSize): SurfaceSize =
        if (sameAspect(surface.width, surface.height, calibrated.width, calibrated.height)) fitInside(calibrated, room)
        else surface

    /**
     * Why a run is refused for want of verification, in words for the user, or null when it may
     * start. [saved] is the account's calibration and [run] the game area the run works at, null
     * while it is not known; [runVerified] is whether the calibration counts as verified there.
     * [unverifiedAllowed] is Policy.mayStartUnverified for the run, and [attacks] whether it
     * attacks. A supervised attack earns verification where the calibration was taken, or at a new
     * shape for a calibration verified already (FarmerService.movedProfileUpdater); a calibration
     * not yet verified, moved onto another game area, can't earn it, so even that attack is refused.
     */
    fun startRefusal(
        villageName: String,
        saved: PhoneProfile,
        run: SurfaceSize?,
        runVerified: Boolean,
        attacks: Boolean,
        unverifiedAllowed: Boolean
    ): String? {
        if (runVerified) return null
        val earnable = !moved(saved, run) || saved.isVerified
        if (unverifiedAllowed && (earnable || !attacks)) return null
        return verificationRequired(villageName, saved, run)
    }

    /**
     * Why a run that needs a verified calibration is refused, the PC's Policy.verificationRequired,
     * with advice that works on [run], the game area the run works at (null when not known).
     */
    fun verificationRequired(villageName: String, saved: PhoneProfile, run: SurfaceSize?): String {
        val earn = "Run \"Supervised attack\" once and let it return to the village; that attack earns it."
        if (run == null || !moved(saved, run)) return "$villageName has not earned verification on this PC. $earn"
        val calibrated = "${saved.resolutionWidth}x${saved.resolutionHeight}"
        val calibratedShape = shape(saved.resolutionWidth, saved.resolutionHeight)
        if (!saved.isVerified) {
            val restart = if (sameAspect(run.width, run.height, saved.resolutionWidth, saved.resolutionHeight)) ""
            else ", or restart the PC and open Clash of Clans on a $calibratedShape screen"
            return "$villageName has not earned verification, and it was calibrated at $calibrated, not on this " +
                "${run.width}x${run.height} game area, where an attack can't earn it. Open Calibrate, capture the " +
                "game and save the points again$restart. Then run \"Supervised attack\" once."
        }
        return "$villageName was calibrated on a game area of another shape and has not had a supervised attack at " +
            "${shape(run.width, run.height)} yet. $earn"
    }

    /** Whether a run on [run] works on [saved] moved, not at its own size. */
    private fun moved(saved: PhoneProfile, run: SurfaceSize?): Boolean =
        run != null && (run.width != saved.resolutionWidth || run.height != saved.resolutionHeight)

    /**
     * [profile] moved onto a game area of [surface]. At the profile's own size it is [profile]
     * itself, so a run at the calibrated size is what it always was. Otherwise both sizes must be
     * the PC's and [surface] at least [MIN_HEIGHT] rows, or this throws with words for the user.
     * Collection targets are dropped, since they belong to the camera reference captured at the
     * calibrated size; collection then ends as "no targets".
     */
    fun derive(profile: PhoneProfile, surface: SurfaceSize, verifiedAspects: Collection<Double>): PhoneProfile {
        val toW = surface.width
        val toH = surface.height
        if (toW == profile.resolutionWidth && toH == profile.resolutionHeight) return profile
        surfaceProblem(surface)?.let { throw StopException(it) }
        val fromW = profile.resolutionWidth
        val fromH = profile.resolutionHeight
        if (!PcGeometry.isPc(Viewport(0, 0, fromW, fromH))) {
            throw StopException(
                "This account was calibrated for a ${fromW}x$fromH game area, which the bot can't move to " +
                    "${toW}x$toH. Open Calibrate, capture the game, and save the points again."
            )
        }
        val move = Mover(fromW, fromH, toW, toH)
        return profile.copy(
            resolutionWidth = toW,
            resolutionHeight = toH,
            viewport = Viewport(0, 0, toW, toH),
            stateAnchors = profile.stateAnchors.mapValues { (name, point) -> stateAnchor(name, point, move) },
            troopAnchors = profile.troopAnchors.mapValues { move.point(it.value, Anchor.CENTRE) },
            troopRois = profile.troopRois.mapValues { move.region(it.value, Anchor.CENTRE) },
            deploymentPoints = profile.deploymentPoints.map { move.fitted(it) },
            perimeterSegments = profile.perimeterSegments.map { Pair(move.fitted(it.first), move.fitted(it.second)) },
            heroDrop = profile.heroDrop?.let { move.fitted(it) },
            wallPoints = profile.wallPoints.map { move.point(it, Anchor.CENTRE) },
            wallRows = profile.wallRows?.let {
                it.copy(
                    origin = move.point(it.origin, Anchor.CENTRE),
                    alongStep = Step(it.alongStep.dx * move.scale, it.alongStep.dy * move.scale),
                    rowStep = Step(it.rowStep.dx * move.scale, it.rowStep.dy * move.scale)
                )
            },
            wallConfig = profile.wallConfig?.let { wallConfig(it, move) },
            homeResourceRois = profile.homeResourceRois.mapValues { move.region(it.value, Anchor.RIGHT) },
            scoutLootRois = profile.scoutLootRois.mapValues { move.region(it.value, Anchor.LEFT) },
            scoutCountdownRoi = profile.scoutCountdownRoi?.let { move.region(it, Anchor.CENTRE) },
            scoutFeeRoi = profile.scoutFeeRoi?.let { move.region(it, Anchor.RIGHT) },
            collectionTargets = emptyList(),
            isVerified = verifiedAt(profile, toW, toH, verifiedAspects)
        )
    }

    /**
     * [point], the button [name] taps, moved the way Clash moves that button. Battle and End Battle
     * sit in the HUD's left corners and Next in its right; Find a Match's cards run from the left.
     * The army screen is a dialog Clash fits inside the game area: at 1860x1162 it grows by the
     * width's 1.069, not the height's 1.188, about the centre, and the height's rule would put the
     * tap on Attack!'s bottom edge. The hero drop is on the battle map, fitted the same way. Return
     * Home and anything else are on panels drawn about the centre.
     */
    private fun stateAnchor(name: String, point: Point, move: Mover): Point = when (name) {
        "attack", "end_battle", "find_match" -> move.point(point, Anchor.LEFT)
        "next" -> move.point(point, Anchor.RIGHT)
        "army", "hero_drop" -> move.fitted(point)
        else -> move.point(point, Anchor.CENTRE)
    }

    /** The wall dialogs are centred popups; the balances they read are the HUD's, on the right. */
    private fun wallConfig(config: WallConfig, move: Mover): WallConfig = config.copy(
        currencies = config.currencies.mapValues { (_, c) ->
            c.copy(
                costRoi = move.region(c.costRoi, Anchor.CENTRE),
                buttonPoint = move.point(c.buttonPoint, Anchor.CENTRE),
                iconRoi = c.iconRoi?.let { move.region(it, Anchor.CENTRE) },
                confirmationRoi = c.confirmationRoi?.let { move.region(it, Anchor.CENTRE) },
                confirmationButton = c.confirmationButton?.let { move.point(it, Anchor.CENTRE) }
            )
        },
        bulkButton = config.bulkButton?.let { move.point(it, Anchor.CENTRE) },
        removeButton = config.removeButton?.let { move.point(it, Anchor.CENTRE) },
        titleRoi = config.titleRoi?.let { move.region(it, Anchor.CENTRE) },
        bulkConfirmationBodyRoi = config.bulkConfirmationBodyRoi?.let { move.region(it, Anchor.CENTRE) },
        bulkConfirmationButton = config.bulkConfirmationButton?.let { move.point(it, Anchor.CENTRE) },
        resourceRois = config.resourceRois.mapValues { move.region(it.value, Anchor.RIGHT) }
    )

    /**
     * Moves points and boxes from a frame [fromW]x[fromH] to one [toW]x[toH], by the heights'
     * [scale]. A point that lands past an edge is kept on the frame's last pixel, and a box edge on
     * the frame's border, so the moved profile still passes [PhoneProfile.validate].
     */
    private class Mover(val fromW: Int, val fromH: Int, val toW: Int, val toH: Int) {
        val scale = toH.toDouble() / fromH

        /**
         * The scale of something Clash fits inside the frame, drawn for a 16:9 game area: the
         * smaller of the width's and the height's against 1740x978 at each end, so a 16:10
         * calibration moved onto 16:9 shrinks by the width's 0.935 and comes back.
         */
        private val fit = fitScale(toW, toH) / fitScale(fromW, fromH)

        private fun fitScale(width: Int, height: Int): Double =
            minOf(width.toDouble() / PcGeometry.WIDTH, height.toDouble() / PcGeometry.HEIGHT)

        fun point(p: Point, anchor: Anchor): Point = Point(
            PcGeometry.moveX(p.x, fromW, toW, scale, anchor).coerceIn(0, toW - 1),
            PcGeometry.moveY(p.y, scale).coerceIn(0, toH - 1)
        )

        fun region(r: Region, anchor: Anchor): Region = Region(
            PcGeometry.moveX(r.left, fromW, toW, scale, anchor).coerceIn(0, toW - 1),
            PcGeometry.moveY(r.top, scale).coerceIn(0, toH - 1),
            PcGeometry.moveX(r.right, fromW, toW, scale, anchor).coerceIn(1, toW),
            PcGeometry.moveY(r.bottom, scale).coerceIn(1, toH)
        )

        /** [p] on something Clash fits inside the frame, scaled by [fit] about the centre. */
        fun fitted(p: Point): Point = Point(
            (toW / 2.0 + (p.x - fromW / 2.0) * fit).roundToInt().coerceIn(0, toW - 1),
            (toH / 2.0 + (p.y - fromH / 2.0) * fit).roundToInt().coerceIn(0, toH - 1)
        )
    }
}
