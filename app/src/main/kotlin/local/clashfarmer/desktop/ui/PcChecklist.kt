package local.clashfarmer.desktop.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist

/**
 * Setup's two lines about this PC, in place of the phone's accessibility and notification lines,
 * and the account's line about its identity. Pure, so their rules run in JVM tests. They are [CheckLine]s like the rest, so
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
