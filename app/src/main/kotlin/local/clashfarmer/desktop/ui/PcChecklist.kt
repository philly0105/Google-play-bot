package local.clashfarmer.desktop.ui

import local.clashfarmer.desktop.RemotePage
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.Hello
import local.clashfarmer.remote.RemoteServer
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist

/**
 * Setup's lines about this PC that the phone didn't have or that read differently here: the game
 * window and tray icon in place of the phone's accessibility and notification lines, the text
 * reader, and the remote page. Pure, so their rules run in JVM tests. They are [CheckLine]s like
 * the rest, so [SetupChecklist.ready], badge and primaryFix count them as they counted the phone's.
 */
object PcChecklist {
    const val GAME_WINDOW = "game_window"
    const val TRAY_ICON = "tray_icon"
    const val TEXT_READER = "text_reader"
    /** The phone's key for its remote line, kept. */
    const val REMOTE = "remote"

    /**
     * The Clash window as the watcher last found it. Its fix, under the phone's accessibility fix
     * kind, sends `launch_clash`. A run starts Clash itself when the window is gone, but Google
     * Play Games may bring the window to the front then, so a missing window is amber, not red.
     * A minimized window is fine: a capture restores it without taking focus. A window closed by
     * hand is red with no fix: the game runs on hidden, and opening it again breaks posted input
     * until Google Play Games starts fresh, which only a restart of the PC was seen to do.
     */
    fun gameWindow(status: WindowStatus): CheckLine {
        val window = status.window
        val area = status.surface?.let { "The game area is ${it.width}x${it.height}." }
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
            else -> CheckLine(GAME_WINDOW, "Game window", listOfNotNull("Found.", area).joinToString(" "), "Open", Tone.OK)
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

    /**
     * The remote page, in place of the phone's remote line. It listens only while Tailscale is
     * connected, so without it the line is off rather than amber: the page is optional. Never
     * counted, as on the phone. The key stays off the screen.
     */
    fun remote(serviceOn: Boolean, status: RemotePage.Status?): CheckLine = when {
        !serviceOn || status == null -> CheckLine(REMOTE, "Remote page", "Starts with Clash Farmer.", "Off", Tone.IDLE, counts = false)
        status.tailscale == null -> CheckLine(REMOTE, "Remote page",
            "Off (Tailscale not connected). Optional: connect Tailscale on this PC to control runs from another device; " +
                "the page turns on within a minute.", "Off", Tone.IDLE, counts = false)
        !status.listening -> CheckLine(REMOTE, "Remote page",
            "Port ${RemoteServer.PORT} is in use by another program, so the page is off. Close that program; " +
                "Clash Farmer tries again every 10 minutes.", "Off", Tone.WARN, counts = false)
        else -> CheckLine(REMOTE, "Remote page",
            "${status.tailscale}:${RemoteServer.PORT} on your tailnet. The copied link holds the key; keep it private.",
            "On", Tone.OK, counts = false)
    }
}
