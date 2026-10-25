package local.clashfarmer.desktop.ui

import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.PcInfo
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
    const val WINDOWS = "windows"

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
     */
    fun gameWindow(status: WindowStatus): CheckLine {
        val window = status.window
        val area = status.surface?.let { "The game area is ${it.width}x${it.height}." }
        return when {
            !status.checked -> CheckLine(GAME_WINDOW, "Game window", "Looking for the Clash of Clans window.",
                SetupChecklist.chip(Tone.NEUTRAL), Tone.NEUTRAL, counts = false)
            status.error != null -> CheckLine(GAME_WINDOW, "Game window", "Could not look for the window: ${status.error}",
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
     * Whether runs can show their status in the notification area, the PC's notifications
     * (docs/PLAN.md). [supported] is java.awt.SystemTray.isSupported(). No fix: when Java finds no
     * notification area, no Windows setting brings one back.
     */
    fun trayIcon(supported: Boolean): CheckLine =
        if (supported) CheckLine(TRAY_ICON, "Tray icon", "Runs can show their status and stop button.", "On", Tone.OK)
        else CheckLine(TRAY_ICON, "Tray icon", "Runs cannot show their status or stop button in the notification area.",
            "Off", Tone.WARN)
}
