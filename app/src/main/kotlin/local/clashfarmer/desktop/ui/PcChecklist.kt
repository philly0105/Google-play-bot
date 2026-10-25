package local.clashfarmer.desktop.ui

import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist

/**
 * Setup's two lines about this PC, in place of the phone's accessibility and notification lines.
 * Pure, so their rules run in JVM tests. They are [CheckLine]s like the rest, so
 * [SetupChecklist.ready], badge and primaryFix count them as they counted the phone's.
 */
object PcChecklist {
    const val GAME_WINDOW = "game_window"
    const val TRAY_ICON = "tray_icon"

    /**
     * The Clash window as the watcher last found it. Its fix, under the phone's accessibility fix
     * kind, sends `launch_clash`. A run starts Clash itself when the window is gone, but Google
     * Play Games may bring the window to the front then, so a missing window is amber, not red.
     * A minimized window is fine: a capture restores it without taking focus. A window closed by
     * hand is red with no fix: the game runs on hidden, and opening it again breaks posted input
     * until Google Play Games starts fresh, which only a restart of the PC was seen to do.
     *
     * With [calibration], an open window's game area is compared with it ([compared]).
     */
    fun gameWindow(status: WindowStatus, calibration: Calibration? = null): CheckLine {
        val window = status.window
        val surface = status.surface
        val area = surface?.let { "The game area is ${it.width}x${it.height}." }
        return when {
            !status.checked -> CheckLine(GAME_WINDOW, "Game window", "Looking for the Clash of Clans window.",
                SetupChecklist.chip(Tone.NEUTRAL), Tone.NEUTRAL, counts = false)
            status.error != null -> CheckLine(GAME_WINDOW, "Game window", "Could not look for the window: ${status.error}",
                SetupChecklist.chip(Tone.BAD), Tone.BAD)
            window == null -> CheckLine(GAME_WINDOW, "Game window",
                "Open Clash of Clans in Google Play Games. A run opens it too, but may bring it to the front.",
                "Not open", Tone.WARN, CheckLine.Fix("Open Clash of Clans", CheckLine.FixKind.ACCESSIBILITY))
            !window.visible -> CheckLine(GAME_WINDOW, "Game window",
                "Closed. Google Play Games keeps Clash running hidden, and opening it again now stops the bot's taps " +
                    "from reaching the game. Restart the PC, then open Clash of Clans.", "Closed", Tone.BAD)
            window.iconic -> CheckLine(GAME_WINDOW, "Game window",
                listOfNotNull("Minimized. A run restores it without taking focus.", area).joinToString(" "), "Minimized", Tone.OK)
            surface != null && calibration != null -> compared(surface, window.room, calibration)
            else -> CheckLine(GAME_WINDOW, "Game window", listOfNotNull("Found.", area).joinToString(" "), "Open", Tone.OK)
        }
    }

    /**
     * The calibration the Game window line compares the game area with: the account picked on
     * Home, by [name], with the shapes supervised attacks verified it at (surfaces.json).
     */
    class Calibration(val name: String, val profile: PhoneProfile, val verifiedAspects: Set<Double> = emptySet())

    /**
     * The game area a run on [profile] works at, from a window whose area is [surface] on a monitor
     * that holds [room] ([PcProfiles.runSize]): the calibration's size, shrunk to what the monitor
     * holds, when the area has its shape, else the area's own size, which a run leaves alone.
     */
    fun runSize(surface: SurfaceSize, room: SurfaceSize?, profile: PhoneProfile): SurfaceSize =
        PcProfiles.runSize(surface, room, SurfaceSize(profile.resolutionWidth, profile.resolutionHeight))

    /**
     * [profile] with isVerified as a run would count it on this window's game area: a calibration
     * moved onto another shape counts only once a supervised attack worked at that shape. Unchanged
     * when the area is not known.
     */
    fun verifiedHere(profile: PhoneProfile, status: WindowStatus, verifiedAspects: Set<Double>): PhoneProfile {
        val surface = status.surface ?: return profile
        if (status.window == null) return profile
        val size = runSize(surface, status.window.room, profile)
        val verified = PcProfiles.verifiedAt(profile, size.width, size.height, verifiedAspects)
        return if (verified == profile.isVerified) profile else profile.copy(isVerified = verified)
    }

    /**
     * An open window's line with the game area set beside [calibration]: red when a run can't work
     * at the size it would reach, amber at a shape the calibration was never verified at, and
     * green otherwise.
     */
    private fun compared(surface: SurfaceSize, room: SurfaceSize?, calibration: Calibration): CheckLine {
        val profile = calibration.profile
        val name = calibration.name
        val run = runSize(surface, room, profile)
        val wanted = SurfaceSize(profile.resolutionWidth, profile.resolutionHeight)
        val capped = PcProfiles.fitInside(wanted, room) != wanted
        val area ="Found. The game area is ${surface.width}x${surface.height}"
        val calibrated = "${profile.resolutionWidth}x${profile.resolutionHeight}"
        val shape = PcProfiles.shape(surface.width, surface.height)
        val calibratedShape = PcProfiles.shape(profile.resolutionWidth, profile.resolutionHeight)
        fun line(detail: String, chip: String, tone: Tone) = CheckLine(GAME_WINDOW, "Game window", detail, chip, tone)
        PcProfiles.surfaceProblem(run)?.let { return line("Found. $it", "Can't run", Tone.BAD) }
        return when {
            PcProfiles.sameAspect(run.width, run.height, profile.resolutionWidth, profile.resolutionHeight) -> when {
                // A calibration moved before it was verified can't earn verification there.
                capped && !profile.isVerified -> line("$area. $name was calibrated at $calibrated and has not been " +
                    "verified; this screen holds ${run.width}x${run.height}. Calibrate again on this game area.",
                    "New size", Tone.WARN)
                capped -> line("$area. $name was calibrated at $calibrated; this screen holds ${run.width}x${run.height}, " +
                    "and a run works at that.", "Open", Tone.OK)
                surface.width == profile.resolutionWidth && surface.height == profile.resolutionHeight ->
                    line("$area, the size $name was calibrated at.", "Open", Tone.OK)
                else -> line("$area. $name was calibrated at $calibrated, and a run sizes the window to it.", "Open", Tone.OK)
            }
            PcProfiles.verifiedAt(profile, run.width, run.height, calibration.verifiedAspects) ->
                line("$area ($shape). $name was calibrated at $calibratedShape, and a supervised attack has worked at $shape too.",
                    "Open", Tone.OK)
            profile.isVerified ->
                line("$area ($shape), but $name was calibrated at $calibratedShape. A run moves the calibration onto it; " +
                    "run one supervised attack, then the bot can farm on its own.", "New shape", Tone.WARN)
            else ->
                line("$area ($shape), but $name was calibrated at $calibratedShape and has not been verified. Calibrate " +
                    "again on this game area, or restart the PC and open Clash of Clans on a $calibratedShape screen.",
                    "New shape", Tone.WARN)
        }
    }

    /**
     * Whether runs can show their status in the notification area, the PC's notifications
     * (docs/PLAN.md). [supported] is java.awt.SystemTray.isSupported(). No fix: when Java finds no
     * notification area, no Windows setting brings one back.
     */
    fun trayIcon(supported: Boolean): CheckLine =
        if (supported) CheckLine(TRAY_ICON, "Tray icon", "Runs can show their status and stop button.", "On", Tone.OK)
        else CheckLine(TRAY_ICON, "Tray icon", "Runs cannot show their status or stop button in the notification area.",
            "Off", Tone.WARN)
}
