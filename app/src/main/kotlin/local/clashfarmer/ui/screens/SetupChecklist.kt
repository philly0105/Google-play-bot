package local.clashfarmer.ui.screens

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.DeviceGuard
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.insight.CalibrationHealth
import local.clashfarmer.insight.Finding
import local.clashfarmer.insight.Format
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.remote.RemoteServer
import local.clashfarmer.ui.TabBadge
import local.clashfarmer.ui.components.Tone
import java.util.Locale

/** One line of the Setup checklist. Pure, so its rules run in JVM tests. */
data class CheckLine(
    /** "accessibility", or "<account id>:walls". */
    val key: String,
    val title: String,
    val detail: String,
    val chip: String,
    val tone: Tone,
    val fix: Fix? = null,
    /** False for information (remote control, builders): outside the count, the tab dot and the Fix button. */
    val counts: Boolean = true,
    /** What the line's detail sheet lists; a line without findings has no sheet. */
    val findings: List<Finding> = emptyList(),
    /** The account the line is about, for the Fix button. */
    val accountName: String? = null
) {
    data class Fix(val label: String, val kind: FixKind, val accountId: String? = null)
    enum class FixKind { ACCESSIBILITY, NOTIFICATIONS, CALIBRATION, EDIT_SETTINGS }
}

/**
 * The checklist's rules (spec 2.6): what each line says, which lines count toward "ready", and
 * which fix the screen offers first. The remote-control key never reaches this object.
 */
object SetupChecklist {
    /** The account rows, in screen order, and their titles. */
    val ACCOUNT_AREAS = listOf(
        "calibration" to "Calibration", "tray" to "Troop tray", "walls" to "Walls",
        "goal" to "Goal and rules", "builders" to "Builders"
    )

    fun chip(tone: Tone): String = when (tone) {
        Tone.OK -> "OK"
        Tone.WARN -> "Check"
        Tone.BAD -> "Needs you"
        Tone.IDLE -> "Off"
        Tone.NEUTRAL -> "Not read"
    }

    private fun ui(tone: local.clashfarmer.insight.Tone) = Tone.valueOf(tone.name)

    private fun severity(tone: Tone) = when (tone) {
        Tone.BAD -> 0
        Tone.WARN -> 1
        else -> 2
    }

    fun accessibility(connected: Boolean): CheckLine =
        if (connected) CheckLine("accessibility", "Accessibility service",
            "Connected. The floating control panel is available in Clash.", "On", Tone.OK)
        else CheckLine("accessibility", "Accessibility service",
            "Turn on Clash Farmer in Android's accessibility settings to run the bot.", "Off", Tone.BAD,
            CheckLine.Fix("Open accessibility settings", CheckLine.FixKind.ACCESSIBILITY))

    /** Null when the setting could not be read. */
    fun notifications(enabled: Boolean?): CheckLine = when (enabled) {
        true -> CheckLine("notifications", "Notifications", "Runs can show their status and stop button.", "On", Tone.OK)
        false -> CheckLine("notifications", "Notifications",
            "Runs cannot show their status or stop button in the notification shade.", "Off", Tone.WARN,
            CheckLine.Fix("Open notification settings", CheckLine.FixKind.NOTIFICATIONS))
        null -> CheckLine("notifications", "Notifications", "The setting could not be read.", chip(Tone.NEUTRAL), Tone.NEUTRAL, counts = false)
    }

    /** Red when [DeviceGuard] would stop a run now; there is nothing to tap, only to plug in or cool down. */
    fun battery(health: DeviceHealth?): CheckLine {
        if (health == null) {
            return CheckLine("battery", "Battery and heat", "The battery could not be read.", chip(Tone.NEUTRAL), Tone.NEUTRAL, counts = false)
        }
        DeviceGuard.stopReason(health)?.let { return CheckLine("battery", "Battery and heat", "$it. Runs stop until it recovers.", chip(Tone.BAD), Tone.BAD) }
        val reading = listOfNotNull(
            health.levelPercent?.let { "$it%" },
            health.charging?.let { if (it) "charging" else "not charging" },
            health.tempC?.let { String.format(Locale.US, "%.1f C", it) }
        ).joinToString(", ")
        return CheckLine("battery", "Battery and heat", reading.ifEmpty { "No reading" }, chip(Tone.OK), Tone.OK)
    }

    /** Address and port only: the key stays off the screen. Information, never counted. */
    fun remote(serviceOn: Boolean, address: String?): CheckLine = when {
        !serviceOn -> CheckLine("remote", "Remote control", "Starts with the accessibility service.", "Off", Tone.IDLE, counts = false)
        address == null -> CheckLine("remote", "Remote control",
            "Tailscale is not connected. Open the Tailscale app and connect, then come back here.", "No Tailscale", Tone.WARN, counts = false)
        else -> CheckLine("remote", "Remote control",
            "$address:${RemoteServer.PORT} on your tailnet. The copied link holds the key; keep it private.", "On", Tone.OK, counts = false)
    }

    /**
     * The account's rows from its [findings] (CalibrationHealth). The tray row needs a profile;
     * the goal row shows only when a goal or rule cannot work. [profileError] is why the
     * profile would not load, for the selected account.
     */
    fun account(
        account: AccountRecord,
        profile: PhoneProfile?,
        findings: List<Finding>,
        profileError: String?,
        builders: BuilderReading?,
        nowMs: Long
    ): List<CheckLine> {
        val id = account.id
        val name = account.displayName
        val calibrate = CheckLine.Fix("Open calibration", CheckLine.FixKind.CALIBRATION, id)
        val edit = CheckLine.Fix("Edit settings", CheckLine.FixKind.EDIT_SETTINGS, id)
        fun area(a: Finding.Area) = findings.filter { it.area == a }.sortedBy { severity(ui(it.tone)) }
        fun headline(list: List<Finding>) = list.first().title + if (list.size > 1) " and ${list.size - 1} more" else ""
        fun line(area: String, detail: String, tone: Tone, fix: CheckLine.Fix? = null, list: List<Finding> = emptyList()) =
            CheckLine("$id:$area", ACCOUNT_AREAS.first { it.first == area }.second, detail, chip(tone), tone, fix, findings = list, accountName = name)
        val lines = mutableListOf<CheckLine>()

        val calibration = area(Finding.Area.CALIBRATION)
        lines += when {
            profileError != null -> line("calibration", "Could not be loaded: $profileError", Tone.BAD, calibrate,
                listOf(Finding(Finding.Kind.NOT_CALIBRATED, Finding.Area.CALIBRATION, local.clashfarmer.insight.Tone.BAD,
                    "Calibration could not be loaded", profileError)) + calibration.filter { it.kind != Finding.Kind.NOT_CALIBRATED })
            calibration.isEmpty() -> line("calibration", CalibrationHealth.calibrationLine(profile), Tone.OK)
            // Only a supervised attack verifies a calibration, so "not verified" alone offers no fix.
            else -> line("calibration", headline(calibration), ui(CalibrationHealth.tone(findings, Finding.Area.CALIBRATION)),
                calibrate.takeIf { calibration.any { it.kind != Finding.Kind.NOT_VERIFIED } }, calibration)
        }

        if (profile != null) {
            val tray = area(Finding.Area.TRAY)
            lines += if (tray.isEmpty()) line("tray", CalibrationHealth.trayLine(profile), Tone.OK)
                // A copied tray is amber: it is fine when both trays really hold the same cards.
                else line("tray", headline(tray), ui(CalibrationHealth.tone(findings, Finding.Area.TRAY)), calibrate, tray)
        }

        val walls = area(Finding.Area.WALLS)
        lines += when {
            walls.isEmpty() && !account.settings.wallsEnabled -> line("walls", "Walls are off for this account.", Tone.IDLE)
            walls.isEmpty() -> line("walls", CalibrationHealth.wallsLine(account.settings), Tone.OK)
            else -> line("walls", headline(walls), ui(CalibrationHealth.tone(findings, Finding.Area.WALLS)),
                if (walls.first().kind == Finding.Kind.WALLS_INCOMPLETE) calibrate else edit, walls)
        }

        val goal = area(Finding.Area.GOAL)
        if (goal.isNotEmpty()) lines += line("goal", headline(goal), ui(CalibrationHealth.tone(findings, Finding.Area.GOAL)), edit, goal)

        lines += when {
            builders == null -> line("builders", "Read at home during the next run.", Tone.NEUTRAL)
            builders.free == 0 -> line("builders", "0 of ${builders.total} free, read ${Format.ago(builders.atMs, nowMs)}. " +
                "Walls wait for a free builder.", Tone.WARN).copy(chip = "Busy")
            else -> line("builders", "${builders.free} of ${builders.total} free, read ${Format.ago(builders.atMs, nowMs)}",
                Tone.OK).copy(chip = "${builders.free} free")
        }.copy(counts = false)
        return lines
    }

    /** Ready over counted lines; an account's walls switched off counts as ready. */
    fun ready(lines: List<CheckLine>): Pair<Int, Int> {
        val counted = lines.filter { it.counts }
        return counted.count { it.tone == Tone.OK || it.tone == Tone.IDLE } to counted.size
    }

    /** Setup's amber tab dot: any counted line amber or red. */
    fun badge(lines: List<CheckLine>): TabBadge? =
        if (lines.any { it.counts && (it.tone == Tone.WARN || it.tone == Tone.BAD) }) TabBadge(0, Tone.WARN) else null

    /** The screen's one primary action: the worst counted line with a fix, the first on screen among equals. */
    fun primaryFix(lines: List<CheckLine>): CheckLine? =
        lines.filter { it.counts && it.fix != null && (it.tone == Tone.BAD || it.tone == Tone.WARN) }
            .minByOrNull { severity(it.tone) }

    fun fixLabel(line: CheckLine): String = "Fix: ${line.fix?.label.orEmpty()}" + (line.accountName?.let { " ($it)" } ?: "")

    /**
     * Whether a row of one account's [lines] shows "Open calibration" (CheckRow shows a fix only
     * off OK). The card's own button hides then, and the row's stays beside its reason.
     */
    fun rowOffersCalibration(lines: List<CheckLine>): Boolean =
        lines.any { it.tone != Tone.OK && it.fix?.kind == CheckLine.FixKind.CALIBRATION }
}
