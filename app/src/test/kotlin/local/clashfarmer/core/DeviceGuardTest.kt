package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGuardTest {
    private fun health(level: Int? = 80, charging: Boolean? = false, tempC: Double? = 30.0) =
        DeviceHealth(level, charging, tempC)

    @Test fun aHealthyPhoneCarriesOn() {
        assertNull(DeviceGuard.stopReason(health()))
    }

    @Test fun lowBatteryOffTheChargerStopsAtFifteenPercent() {
        assertNull(DeviceGuard.stopReason(health(level = 16)))
        val reason = DeviceGuard.stopReason(health(level = 15))!!
        assertTrue(reason, reason.contains("15%"))
        assertTrue(DeviceGuard.stopReason(health(level = 3))!!.startsWith(DeviceGuard.LOW_BATTERY_PREFIX))
    }

    @Test fun lowBatteryOnTheChargerCarriesOn() {
        assertNull(DeviceGuard.stopReason(health(level = 5, charging = true)))
    }

    @Test fun heatStopsAtFortyFiveDegreesEvenWhileCharging() {
        assertNull(DeviceGuard.stopReason(health(tempC = 44.9)))
        val reason = DeviceGuard.stopReason(health(tempC = 45.0, charging = true))!!
        assertTrue(reason, reason.startsWith(DeviceGuard.HOT_PREFIX))
        assertTrue(reason, reason.contains("45.0"))
    }

    @Test fun heatIsReportedBeforeLowBattery() {
        assertTrue(DeviceGuard.stopReason(health(level = 10, tempC = 47.0))!!.startsWith(DeviceGuard.HOT_PREFIX))
    }

    @Test fun unknownReadingsNeverStopTheRun() {
        assertNull(DeviceGuard.stopReason(DeviceHealth(null, null, null)))
        // A level with no charging state is not proof the phone is off the charger.
        assertNull(DeviceGuard.stopReason(health(level = 5, charging = null)))
        assertNull(DeviceGuard.stopReason(health(tempC = null)))
    }

    @Test fun readsTheStickyBatteryExtras() {
        // EXTRA_LEVEL 30 of EXTRA_SCALE 200, status CHARGING (2), temperature in tenths of a degree.
        assertEquals(DeviceHealth(15, true, 41.3), DeviceHealth.fromExtras(level = 30, scale = 200, status = 2, tenthsC = 413))
        assertEquals(DeviceHealth(50, true, null), DeviceHealth.fromExtras(level = 50, scale = 100, status = 5, tenthsC = -1000))
        assertEquals(DeviceHealth(50, false, 30.0), DeviceHealth.fromExtras(level = 50, scale = 100, status = 3, tenthsC = 300))
        assertEquals(DeviceHealth(null, null, 30.0), DeviceHealth.fromExtras(level = -1, scale = 100, status = 1, tenthsC = 300))
    }

    @Test fun deviceGuardIsAKnownCompletionReason() {
        assertEquals(CompletionReason.DEVICE_GUARD, CompletionReason.fromKey("device_guard"))
    }
}
