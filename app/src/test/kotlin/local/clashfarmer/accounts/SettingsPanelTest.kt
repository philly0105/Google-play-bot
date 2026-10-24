package local.clashfarmer.accounts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity-region differences the phone logged on 24 September, Clash 18.600.7. */
class SettingsPanelTest {

    @Test fun anEnrolledIdentityInItsPlaceMeansThePanelIsOpen() {
        assertTrue(AccountSwitcher.settingsPanelShowing(listOf(0.27, 18.48)))
        assertTrue(AccountSwitcher.settingsPanelShowing(listOf(18.48, 0.27)))
    }

    /** 11:01:06: the gear tap after the switch to Blood was lost and the village was read instead. */
    @Test fun theVillageBehindIsNotThePanel() {
        assertFalse(AccountSwitcher.settingsPanelShowing(listOf(68.37, 70.18)))
        assertFalse(AccountSwitcher.settingsPanelShowing(listOf(null, null)))
        assertFalse(AccountSwitcher.settingsPanelShowing(emptyList()))
    }

    /** The menu's tabs as read on 31-profile-default.png, and the village with the menu gone. */
    private val menuText = "Clans Social Inbox Settings Battle Build"
    private val villageText = "Sweat Joost WAR 08 Battle 1/6 1/2 Build"

    /** Presses the X until the menu reads closed; [frames] is what each look after a press reads. */
    private fun close(vararg frames: String): Pair<Boolean, Int> = runBlocking {
        var presses = 0
        val closed = AccountSwitcher.closeProfileMenu(
            press = { presses++ },
            stillOpen = { AccountIdentityParser.isProfileMenu(frames[presses - 1]) }
        )
        closed to presses
    }

    @Test fun theMenuThatClosesIsPressedOnce() {
        assertEquals(true to 1, close(villageText))
    }

    @Test fun aLostXIsPressedAgainWhileTheMenuStillShows() {
        assertEquals(true to 2, close(menuText, villageText))
    }

    @Test fun aMenuThatWillNotCloseIsPressedThreeTimesAndNoMore() {
        assertEquals(false to AccountSwitcher.SETTINGS_CLOSE_ATTEMPTS, close(menuText, menuText, menuText))
    }
}
