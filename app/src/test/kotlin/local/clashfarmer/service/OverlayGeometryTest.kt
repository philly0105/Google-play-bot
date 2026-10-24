package local.clashfarmer.service

import local.clashfarmer.accounts.AccountSwitcher
import local.clashfarmer.service.OverlayGeometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay's windows in display pixels, and the bot's own taps that must not land in one that
 * takes touches. An injected gesture whose down lands in a touchable accessibility overlay goes to
 * the overlay, not to Clash. The A21 is density 1.75; the spec was drawn at 2.0.
 */
class OverlayGeometryTest {

    private val densities = listOf(1.75f, 2.0f)

    /** Each target with the spread its taps land in, as (x, y, spread). */
    private val targets = listOf(
        // The account check's profile card; tapButton moves a press up to 3 px.
        Triple(AccountSwitcher.PROFILE_BUTTON.x, AccountSwitcher.PROFILE_BUTTON.y, 3),
        // A hero's third drop (AttackPlanner's HERO_SPREAD fallback), and segment 0's drops.
        Triple(218, 188, 28),
        Triple(195, 215, 18),
        // Every VillageCamera clamp drag starts at 31% x 28% of 1600x720.
        Triple(1600 * 31 / 100, 720 * 28 / 100, 0)
    )

    @Test fun theOpenCardIsATouchableHeaderOverAnInfoWindowThatTakesNoTouches() {
        assertEquals(
            listOf(Box(21, 21, 511, 91, touchable = true), Box(21, 91, 511, 217, touchable = false)),
            OverlayGeometry.windows(LiveStatus.State.RUNNING, collapsed = false, density = 1.75f)
        )
        assertEquals(
            listOf(Box(24, 24, 584, 104, touchable = true), Box(24, 104, 584, 248, touchable = false)),
            OverlayGeometry.windows(LiveStatus.State.RUNNING, collapsed = false, density = 2.0f)
        )
        assertEquals(listOf(Box(21, 21, 252, 91, touchable = true)),
            OverlayGeometry.windows(LiveStatus.State.RUNNING, collapsed = true, density = 1.75f))
        assertEquals(listOf(Box(24, 24, 288, 104, touchable = true)),
            OverlayGeometry.windows(LiveStatus.State.RUNNING, collapsed = true, density = 2.0f))
    }

    @Test fun noBotTapLandsInATouchableWindowWhileARunIsUp() {
        val busy = listOf(LiveStatus.State.RUNNING, LiveStatus.State.PAUSED, LiveStatus.State.HELD)
        for (density in densities) for (state in busy) for (collapsed in listOf(false, true)) {
            val touchable = OverlayGeometry.windows(state, collapsed, density).filter { it.touchable }
            assertTrue(touchable.isNotEmpty())
            for ((x, y, spread) in targets) for (dx in -spread..spread) for (dy in -spread..spread) {
                for (box in touchable) {
                    assertFalse("($x,$y)+($dx,$dy) in $box at $density, $state, collapsed $collapsed",
                        box.contains(x + dx, y + dy))
                }
            }
        }
    }

    @Test fun theInfoWindowIsDrawnOverTheProfileButtonButLetsItThrough() {
        for (density in densities) {
            val info = OverlayGeometry.windows(LiveStatus.State.RUNNING, collapsed = false, density = density).last()
            assertFalse(info.touchable)
            assertTrue(info.contains(AccountSwitcher.PROFILE_BUTTON.x, AccountSwitcher.PROFILE_BUTTON.y))
        }
    }
}
