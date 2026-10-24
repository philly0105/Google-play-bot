package local.clashfarmer.core

import java.util.Locale

/** The battery as the sticky ACTION_BATTERY_CHANGED intent reports it. Null is not read. */
data class DeviceHealth(val levelPercent: Int?, val charging: Boolean?, val tempC: Double?) {
    companion object {
        /** BatteryManager.BATTERY_STATUS_CHARGING and BATTERY_STATUS_FULL. */
        private val CHARGING_STATUSES = setOf(2, 5)
        private val UNREAD_STATUSES = setOf(-1, 1)

        /**
         * From the intent's extras: EXTRA_LEVEL of EXTRA_SCALE, EXTRA_STATUS, and EXTRA_TEMPERATURE in
         * tenths of a degree. A missing extra arrives as the caller's default, -1 or below.
         */
        fun fromExtras(level: Int, scale: Int, status: Int, tenthsC: Int): DeviceHealth = DeviceHealth(
            levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = if (status in UNREAD_STATUSES) null else status in CHARGING_STATUSES,
            tempC = if (tenthsC > -1000) tenthsC / 10.0 else null
        )
    }
}

/**
 * Ends a run at home before the phone goes flat or cooks. Only a reading that was taken can stop a
 * run; a battery the system did not report on is never a reason to stop.
 */
object DeviceGuard {
    const val LOW_BATTERY_PERCENT = 15
    const val HOT_C = 45.0
    const val LOW_BATTERY_PREFIX = "Battery low"
    const val HOT_PREFIX = "Phone too hot"

    fun stopReason(health: DeviceHealth): String? {
        val temp = health.tempC
        if (temp != null && temp >= HOT_C) {
            return "$HOT_PREFIX: ${String.format(Locale.ROOT, "%.1f", temp)} C (limit ${HOT_C} C)"
        }
        val level = health.levelPercent
        if (level != null && health.charging == false && level <= LOW_BATTERY_PERCENT) {
            return "$LOW_BATTERY_PREFIX: $level% and not charging (limit $LOW_BATTERY_PERCENT%)"
        }
        return null
    }
}
