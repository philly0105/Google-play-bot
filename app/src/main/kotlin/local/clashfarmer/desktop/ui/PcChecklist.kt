package local.clashfarmer.desktop.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.core.DeviceGuard
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.RemotePage
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.Hello
import local.clashfarmer.desktop.sidecar.PcInfo
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.remote.RemoteServer
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist

/**
 * Setup's lines about this PC that the phone didn't have or that read differently here: the game
 * window and tray icon in place of the phone's accessibility and notification lines, the text
 * reader, the remote page, and the account's line about its identity. Pure, so their rules run in
 * JVM tests. They are [CheckLine]s like the rest, so [SetupChecklist.ready], badge and primaryFix
 * count them as they counted the phone's.
 */
object PcChecklist {
    const val GAME_WINDOW = "game_window"
    const val TRAY_ICON = "tray_icon"
    const val WINDOWS = "windows"
    const val TEXT_READER = "text_reader"
    /** The phone's key for its remote line, kept. */
    const val REMOTE = "remote"

    /**
     * The Clash window as the watcher last found it. Its fix, under the phone's accessibility fix
     * kind, sends `launch_clash`. A run starts Clash itself when the window is gone, but Google
     * Play Games may bring the window to the front then, so a missing window is amber, not red.
     * A minimized window is fine: a capture restores it without taking focus. A window closed by
     * hand is red with no fix: the game runs on hidden, and opening it again breaks posted input
     * until Google Play Games starts fresh, which only a restart of the PC was seen to do. A window
     * opened again that way is red for the same reason, until Google Play Games runs in a new
     * process.
     *
     * With no window, the line says first what stops Clash from opening at all: Google Play Games
     * missing (red, since the fix's launch has nothing to open it), the last start failing, or
     * Clash missing. A full-screen window can't be sized to the calibration, so it is amber.
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
            // The lookup's own error names the app's internals, so the line says what to do instead.
            status.error != null -> CheckLine(GAME_WINDOW, "Game window",
                "Could not look for the Clash window. Restart Clash Farmer; if that doesn't help, unzip it again into a new folder.",
                SetupChecklist.chip(Tone.BAD), Tone.BAD)
            window == null && status.pc?.gpgInstalled == false -> CheckLine(GAME_WINDOW, "Game window",
                "Google Play Games is not installed. Install it from play.google.com/googleplaygames, " +
                    "install Clash of Clans in it, then come back here.", "Not installed", Tone.BAD)
            window == null && status.launchError != null -> CheckLine(GAME_WINDOW, "Game window",
                "Clash of Clans did not open. Open Google Play Games, start Clash of Clans there, then come back here.",
                "Did not open", Tone.WARN, CheckLine.Fix("Open Clash of Clans", CheckLine.FixKind.ACCESSIBILITY))
            window == null && status.pc?.clashInstalled == false -> CheckLine(GAME_WINDOW, "Game window",
                "Clash of Clans is not installed. Install it in Google Play Games, open it, then come back here.",
                "Not installed", Tone.WARN, CheckLine.Fix("Open Clash of Clans", CheckLine.FixKind.ACCESSIBILITY))
            window == null -> CheckLine(GAME_WINDOW, "Game window",
                "Open Clash of Clans in Google Play Games. A run opens it too, but may bring it to the front.",
                "Not open", Tone.WARN, CheckLine.Fix("Open Clash of Clans", CheckLine.FixKind.ACCESSIBILITY))
            !window.visible -> CheckLine(GAME_WINDOW, "Game window",
                "Closed. Google Play Games keeps Clash running hidden, and opening it again now stops the bot's taps " +
                    "from reaching the game. Restart the PC, then open Clash of Clans.", "Closed", Tone.BAD)
            status.reopenedInRunningVm -> CheckLine(GAME_WINDOW, "Game window",
                "Opened again after it was closed. Google Play Games kept the game running, so the bot's taps " +
                    "would not reach it. Restart the PC, then open Clash of Clans.", "Reopened", Tone.BAD)
            window.fullscreen -> CheckLine(GAME_WINDOW, "Game window",
                "Clash of Clans is full screen. Turn full screen off in Google Play Games so the bot can size the window.",
                "Full screen", Tone.WARN)
            window.iconic -> CheckLine(GAME_WINDOW, "Game window",
                listOfNotNull("Minimized. A run restores it without taking focus.", area).joinToString(" "), "Minimized", Tone.OK)
            surface != null && calibration != null -> compared(surface, window.room, calibration)
            else -> CheckLine(GAME_WINDOW, "Game window", listOfNotNull("Found.", area).joinToString(" "), "Open", Tone.OK)
        }
    }

    /**
     * An amber line on Windows 10, null on Windows 11 or before the PC has answered. Windows 10
     * can't hide the yellow frame it draws around a window being captured, and the bot was only
     * tried on Windows 11. Not counted: there is nothing to fix short of upgrading Windows.
     */
    fun windowsVersion(pc: PcInfo?): CheckLine? {
        if (pc == null || pc.osBuild >= WINDOWS_11_BUILD) return null
        return CheckLine(WINDOWS, "Windows",
            "Windows 10 shows a yellow frame around the game while the bot runs. The bot was tested on Windows 11 only.",
            "Windows 10", Tone.WARN, counts = false)
    }

    /** Windows 11's first build. */
    private const val WINDOWS_11_BUILD = 22000

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
        val resized = PcProfiles.sameAspect(surface.width, surface.height, profile.resolutionWidth, profile.resolutionHeight)
        PcProfiles.surfaceProblem(run, resized)?.let { return line("Found. $it", "Can't run", Tone.BAD) }
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

    /**
     * The sidecar's text reader, from its last hello. Every run reads the game through it, so a
     * reader that failed to load is red: the run would stop on screens it can't read without
     * saying why. Until the first hello, or while the models load, it is not counted.
     */
    fun textReader(hello: Hello?): CheckLine = when {
        hello == null -> CheckLine(TEXT_READER, "Text reader", "", SetupChecklist.chip(Tone.NEUTRAL), Tone.NEUTRAL, counts = false)
        hello.ocrError != null -> CheckLine(TEXT_READER, "Text reader",
            "Could not start: ${hello.ocrError.trimEnd('.')}. Runs can't read the game. Unzip Clash Farmer again into a new folder. " +
                "If that doesn't help, install Microsoft's Visual C++ Redistributable (x64), then restart Clash Farmer.",
            "Failed", Tone.BAD)
        !hello.ocrReady -> CheckLine(TEXT_READER, "Text reader", "Loading.", "Loading", Tone.NEUTRAL, counts = false)
        else -> CheckLine(TEXT_READER, "Text reader", "Reads the game's text.", "Ready", Tone.OK)
    }

    /** The phone's key for its battery line, kept. */
    const val POWER = "battery"

    /**
     * Power, in place of the phone's "Battery and heat": the PC reports no temperature, so only the
     * battery guard can stop a run. [hasBattery] is the sidecar's flag, false only when Windows says
     * the PC has no battery: a desktop is always plugged in, so its line says that rather than
     * "charging", and isn't counted, having nothing to get ready. A laptop's line counts as the
     * phone's did and is red when [DeviceGuard] would stop a run now.
     */
    fun power(health: DeviceHealth?, hasBattery: Boolean?): CheckLine {
        if (hasBattery == false) {
            return CheckLine(POWER, "Power", "Plugged in. This PC has no battery, so the battery guard never stops a run.",
                "No battery", Tone.OK, counts = false)
        }
        if (health == null) {
            return CheckLine(POWER, "Power", "The battery could not be read.", SetupChecklist.chip(Tone.NEUTRAL),
                Tone.NEUTRAL, counts = false)
        }
        // The guard's own reason reads like a log line, so the row says it in plain words.
        if (DeviceGuard.stopReason(health) != null) {
            return CheckLine(POWER, "Power", "${health.levelPercent}%, on battery. Plug the laptop in; runs stop until then.",
                SetupChecklist.chip(Tone.BAD), Tone.BAD)
        }
        val plug = when (health.charging) {
            true -> "plugged in"
            false -> "on battery. Runs end at home at ${DeviceGuard.LOW_BATTERY_PERCENT}%"
            null -> null
        }
        val reading = listOfNotNull(health.levelPercent?.let { "$it%" }, plug).joinToString(", ")
        return CheckLine(POWER, "Power", if (reading.isEmpty()) "No reading." else reading.replaceFirstChar { it.uppercase() } + ".",
            SetupChecklist.chip(Tone.OK), Tone.OK)
    }

    /**
     * The remote page, in place of the phone's remote line. It listens only while Tailscale is
     * connected, so without it the line is a neutral "Optional" rather than amber: most people
     * never use the page. Never counted, as on the phone. The key stays off the screen.
     */
    fun remote(serviceOn: Boolean, status: RemotePage.Status?): CheckLine = when {
        !serviceOn || status == null -> CheckLine(REMOTE, "Remote page", "Starts with Clash Farmer.", "Off", Tone.IDLE, counts = false)
        status.tailscale == null -> CheckLine(REMOTE, "Remote page",
            "Not needed to farm. To watch and stop runs from another device, install Tailscale on this PC and on " +
                "that device and connect both; the page turns on within a minute.", "Optional", Tone.IDLE, counts = false)
        !status.listening && status.failure == RemotePage.Failure.PORT_IN_USE -> CheckLine(REMOTE, "Remote page",
            "Port ${RemoteServer.PORT} is in use by another program, so the page is off. Close that program; " +
                "Clash Farmer tries again every 10 minutes.", "Off", Tone.WARN, counts = false)
        !status.listening -> CheckLine(REMOTE, "Remote page",
            "Tailscale is connected, but the page could not start on its address yet. Clash Farmer tries again " +
                "every minute; if it stays off, restart Tailscale.", "Off", Tone.WARN, counts = false)
        else -> CheckLine(REMOTE, "Remote page",
            "${status.tailscale}:${RemoteServer.PORT} on your tailnet. The copied link works like a password: anyone " +
                "who has it can control runs. If it gets out, make a new link.",
            "On", Tone.OK, counts = false)
    }

    const val ACCOUNT = "account_identity"

    /**
     * Whether the bot has read [account] off Clash's Settings ([PcAccount]); [enrolled] is null
     * before the files are read. Until it has, only a supervised run can start, so the line is
     * amber. No fix: the read is part of a run, which the person starts from Run.
     */
    fun account(account: AccountRecord, enrolled: Boolean?): CheckLine = when (enrolled) {
        null -> CheckLine(ACCOUNT, "Account", "Checking whether the bot has read your account.",
            SetupChecklist.chip(Tone.NEUTRAL), Tone.NEUTRAL, counts = false)
        false -> CheckLine(ACCOUNT, "Account",
            "Not read yet. The first supervised attack opens Clash's Settings to read your village name and tag. " +
                "Other runs wait until then.", "Not read", Tone.WARN)
        true -> CheckLine(ACCOUNT, "Account",
            "Each run checks that Clash's Settings shows ${account.villageName} before it starts. " +
                "If that is the wrong account, press ${PcAccount.READ_AGAIN}.", "Read", Tone.OK)
    }
}
