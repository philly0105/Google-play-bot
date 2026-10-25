package local.clashfarmer.desktop.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.RunPlan
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.Tab

/**
 * The link under a Start that is off, to where the first thing stopping the plan is fixed. Worked
 * out from the plan and the profiles as [RunPlan.readiness] checks them, not from its wording.
 */
sealed class StartFix(val label: String) {
    /** An account with no calibration, or one the validator refuses: calibration opens on it. */
    class Calibrate(val accountId: String, label: String) : StartFix(label)

    /** A calibration not yet verified: the supervised attack under Run's More tools earns it. */
    class SupervisedAttack(label: String) : StartFix(label)

    /** Any other reason that names Setup, a wall setup or a storage size among them, is fixed in [tab]. */
    class Open(val tab: Tab, label: String) : StartFix(label)

    companion object {
        /**
         * The fix for [reason], the first thing stopping [plan]. Null when nothing stops it, while
         * a run is going or Clash Farmer is not up, where the reason already says what to do, and
         * for a choice made on Run itself, such as an end time already past. The checks go in
         * [RunPlan.readiness]'s order, so the link fixes the reason it sits beside.
         */
        fun of(
            plan: RunPlan,
            accounts: List<AccountRecord>,
            profiles: Map<String, PhoneProfile?>,
            selectedId: String?,
            connected: Boolean,
            busy: Boolean,
            reason: String?
        ): StartFix? {
            if (reason == null || busy || !connected) return null
            val resolved = plan.resolve(accounts, selectedId)
            for (id in resolved.accountIds) {
                val account = accounts.firstOrNull { it.id == id } ?: continue
                val profile = profiles[account.id]
                val calibrate = Calibrate(account.id, "Calibrate ${account.villageName} >")
                if (profile == null || runCatching { profile.validateForAttack() }.isFailure) return calibrate
                if (!profile.isVerified) return SupervisedAttack("Run a supervised attack >")
                val walls = if (resolved.isBoth) account.settings.wallsEnabled else resolved.walls
                if (walls && runCatching { profile.validateForWalls() }.isFailure) return calibrate
            }
            return if ("Setup" in reason) Open(Tab.SETUP, "Open Setup >") else null
        }

        /** The phone's not-connected reason, which RunPlan.readiness still gives, in the PC's words. */
        fun plainReason(reason: String?): String? =
            if (reason == "Turn on the accessibility service in Setup") NOT_RUNNING else reason
    }
}
