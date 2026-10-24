package local.clashfarmer.core

import kotlinx.coroutines.runBlocking
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a wall tap's look compares (UO-21): the frame the tap was read off against one taken just
 * before it goes out, after a pause or a decision older than TapAfterHold.MAX_DECISION_AGE_MS.
 * On 15 October at 05:31 UTC a tap decided on the village landed on the gem store.
 */
class WallSignatureTest {

    private val screen = UpgradeListFixture.screen

    private fun signature(observation: Observation) = wallSignature(observation, screen)

    private fun list(offset: Int = 0, extra: List<RecognizedBox> = emptyList()) =
        UpgradeListFixture.Frame(UpgradeListFixture.sweat(), offset, UpgradeListFixture.overlay + extra).observation

    private fun withBoxes(observation: Observation, extra: List<RecognizedBox>): Observation {
        val boxes = observation.boxes + extra
        return Observation(observation.state, boxes, boxes.joinToString(" ") { it.text }, observation.viewport)
    }

    @Test fun twoFramesOfTheSameListPageAreTheSame() {
        assertEquals(signature(list()), signature(list()))
    }

    @Test fun whereTheListHasScrolledIsLeftToTheRowsOwnCheck() {
        assertEquals(signature(list(0)), signature(list(370)))
    }

    @Test fun aConfirmationDialogOverTheListIsNotTheList() {
        val dialog = withBoxes(list(), listOf(
            RecognizedBox("Upgrade Wall to Level 16?", 555, 298, 1050, 326),
            RecognizedBox("Cancel", 600, 420, 720, 460),
            RecognizedBox("Okay 4 000 000", 880, 420, 1100, 460)
        ))
        assertTrue(signature(dialog).confirm)
        assertNotEquals(signature(list()), signature(dialog))
    }

    @Test fun theBusyDialogIsNotTheList() {
        val busy = withBoxes(list(), listOf(
            RecognizedBox("All builders are", 640, 190, 960, 222),
            RecognizedBox("busy!", 745, 225, 850, 258),
            RecognizedBox("Complete the previous building and free up a", 540, 308, 1055, 335),
            RecognizedBox("builder?", 750, 337, 845, 362),
            RecognizedBox("X", 1040, 195, 1082, 238)
        ))
        assertTrue(signature(busy).busy)
        assertNotEquals(signature(list()), signature(busy))
    }

    @Test fun theStoreIsNotTheList() {
        val store = withBoxes(list(), listOf(RecognizedBox("Pile of Gems", 300, 420, 460, 450), RecognizedBox("$0.99", 1380, 540, 1450, 566)))
        assertTrue(signature(store).shop)
        assertNotEquals(signature(list()), signature(store))
    }

    @Test fun thePanelIsNotTheVillageAndTwoFramesOfItAreTheSame() {
        val panel = runBlocking { WallPhone(start = "panel").capture().observation }
        val again = runBlocking { WallPhone(start = "panel").capture().observation }
        val home = runBlocking { WallPhone(start = "home").capture().observation }
        assertTrue(signature(panel).panel)
        assertEquals(signature(panel), signature(again))
        assertNotEquals(signature(home), signature(panel))
    }

    /**
     * UO-21 review, 15 October: the confirmation's look compares only what its button depends
     * on. The dimmed Battle and Build behind the dialog can read HOME on one frame and UNKNOWN on
     * the next; the store or the busy dialog in its place still holds it.
     */
    @Test fun theConfirmationsLookLeavesOutTheStateThePanelAndAListCard() {
        val dialog = listOf(
            RecognizedBox("Upgrade Wall to Level 16?", 555, 298, 1050, 326),
            RecognizedBox("Cancel", 600, 420, 720, 460),
            RecognizedBox("Okay 4 000 000", 880, 420, 1100, 460)
        )
        fun frame(state: ScreenState, boxes: List<RecognizedBox>) =
            Observation(state, boxes, boxes.joinToString(" ") { it.text }, screen)
        val home = signature(frame(ScreenState.HOME, dialog))
        val unknown = signature(frame(ScreenState.UNKNOWN, dialog))
        assertTrue(home.confirm)
        assertNotEquals(home, unknown)
        assertTrue(home.sameConfirmation(unknown))
        assertTrue(home.sameConfirmation(home.copy(panel = !home.panel, listCard = !home.listCard)))
        assertFalse(home.sameConfirmation(home.copy(confirm = false)))
        assertFalse(home.sameConfirmation(home.copy(busy = true)))
        val store = signature(frame(ScreenState.UNKNOWN, dialog +
            RecognizedBox("Pile of Gems", 300, 420, 460, 450) + RecognizedBox("$0.99", 1380, 540, 1450, 566)))
        assertTrue(store.shop)
        assertFalse(home.sameConfirmation(store))
    }
}
