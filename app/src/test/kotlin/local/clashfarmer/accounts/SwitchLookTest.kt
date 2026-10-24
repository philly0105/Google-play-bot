package local.clashfarmer.accounts

import local.clashfarmer.core.Point
import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The looks a switch tap takes when a pause came between, or its frame went stale, before it is
 * sent (UO-21). A look that fails holds the tap and the switch reads its screen again.
 */
class SwitchLookTest {
    private fun box(text: String, top: Int, bottom: Int = top + 25) = RecognizedBox(text, 800, top, 1020, bottom)

    private fun List<RecognizedBox>.down(px: Int) = map { it.copy(top = it.top + px, bottom = it.bottom + px) }

    private val blood = AccountRecord(AccountStore.BLOOD_ID, "Blood", "VastMaNiSh", "#Y2G29YJQ8")

    /** Both rows as AccountIdentityParserTest reads them. */
    private val picker = listOf(
        box("Philly0105", 145), box("Sweat", 180), box("Town Hall 16", 215),
        box("VastMaNiSh", 300), box("Blood", 335), box("Town Hall 18", 370)
    )

    private val bloodRow =
        AccountIdentityParser.findPickerRow(AccountIdentityParser.parsePickerRows(picker), blood).clickPoint

    @Test fun theSameRowWhereItWasPassesTheLook() {
        assertTrue(AccountSwitcher.pickerRowStill(picker, blood, bloodRow))
        assertTrue(AccountSwitcher.pickerRowStill(picker.down(20), blood, bloodRow))
    }

    @Test fun aRowThatMovedOrWentHoldsTheTap() {
        // The list moved under the pause.
        assertFalse(AccountSwitcher.pickerRowStill(picker.down(60), blood, bloodRow))
        // The picker was closed and nothing in its column reads.
        assertFalse(AccountSwitcher.pickerRowStill(emptyList(), blood, bloodRow))
        // Only the other account's row reads where Blood's was.
        assertFalse(AccountSwitcher.pickerRowStill(picker.take(3), blood, bloodRow))
    }

    @Test fun aButtonReadAgainCountsOnlyWithinItsDrift() {
        val reload = Point(800, 480)
        assertTrue(AccountSwitcher.stillThere(reload, Point(812, 497)))
        assertFalse(AccountSwitcher.stillThere(reload, Point(800, 505)))
        assertFalse(AccountSwitcher.stillThere(reload, null))
    }
}
