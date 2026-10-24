package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPolicyTest {

    private val viewport = Viewport(0, 0, 1600, 720)
    /** A gold collector on Blood's village, 24 September. */
    private val mine = Point(1217, 300)

    private fun decide(point: Point = mine, camera: Boolean = true, paused: Boolean = false, home: Boolean = true, panel: Boolean = false) =
        CollectionPolicy.decide(point, viewport, cameraMatched = camera, pausedSinceCamera = paused, atHome = home, panelOpen = panel)

    @Test fun aTargetOnAClearVillageIsTapped() {
        assertEquals(CollectDecision(CollectStep.TAP, null), decide())
    }

    @Test fun aStaleCameraEndsTheCollectionWithoutATap() {
        assertEquals(CollectDecision(CollectStep.END, "camera_unmatched"), decide(camera = false))
    }

    /** A person may move the camera in a pause; the village read after it says nothing of that. */
    @Test fun aPauseSinceTheCameraWasMatchedEndsTheCollectionWithoutATap() {
        assertEquals(CollectDecision(CollectStep.END, "paused"), decide(paused = true))
    }

    @Test fun anOpenBuildingPanelEndsTheCollectionWithoutATap() {
        assertEquals(CollectDecision(CollectStep.END, "panel_open"), decide(panel = true))
    }

    @Test fun offTheVillageScreenEndsTheCollectionWithoutATap() {
        assertEquals(CollectDecision(CollectStep.END, "not_home"), decide(home = false))
    }

    @Test fun aTargetOutsideTheViewportIsSkipped() {
        assertEquals(CollectDecision(CollectStep.SKIP, "outside_village"), decide(Point(1700, 300)))
        assertEquals(CollectDecision(CollectStep.SKIP, "outside_village"), decide(Point(800, -5)))
    }

    /** The side buttons, the Attack button and the gem "+" beside the balances are not village. */
    @Test fun aTargetUnderTheButtonsIsSkipped() {
        for (p in listOf(Point(100, 440), Point(130, 640), Point(1500, 520), Point(1365, 240), Point(800, 700))) {
            assertEquals("$p", CollectStep.SKIP, decide(p).step)
        }
    }

    /** Where Blood's and Sweat's collectors sit on their camera references of 24 September. */
    @Test fun villageBetweenTheButtonsIsTapped() {
        for (p in listOf(Point(866, 150), Point(900, 690), Point(390, 567), Point(1250, 250))) {
            assertEquals("$p", CollectStep.TAP, decide(p).step)
        }
    }

    /** The helper's own panel (top left), the name and builder row, and the bottom corner buttons. */
    @Test fun theOverlayAndCornerButtonsAreSkipped() {
        for (p in listOf(Point(600, 150), Point(800, 50), Point(350, 650), Point(1350, 650))) {
            assertEquals("$p", CollectStep.SKIP, decide(p).step)
        }
    }

    /** The 18.647 bottom bar: the event bar left of x 615, chest, book and Build right of x 1195. */
    @Test fun theWiderBottomBarOf18647IsSkipped() {
        for (p in listOf(Point(560, 650), Point(1210, 650), Point(1330, 665))) {
            assertEquals("$p", CollectStep.SKIP, decide(p).step)
        }
        assertEquals(CollectStep.TAP, decide(Point(800, 650)).step)
    }

    @Test fun aBuildingPanelIsItsBandOfButtons() {
        val info = RecognizedBox("Info", 640, 600, 700, 625)
        val title = RecognizedBox("Gold Mine (Level 16)", 660, 470, 940, 500)
        assertTrue(CollectionPolicy.buildingPanelOpen(listOf(info), viewport))
        assertTrue(CollectionPolicy.buildingPanelOpen(listOf(title), viewport))
        // The village draws dashed Upgrade hints and building levels over itself, above the band.
        assertFalse(CollectionPolicy.buildingPanelOpen(listOf(RecognizedBox("Upgrade", 1160, 335, 1220, 352)), viewport))
        assertFalse(CollectionPolicy.buildingPanelOpen(listOf(RecognizedBox("Attack!", 80, 665, 190, 695)), viewport))
    }

    @Test fun onlyAgreedReadingsAreBalances() {
        val first = mapOf(Resource.GOLD to 2_139_835L, Resource.ELIXIR to 3_593_948L)
        val second = mapOf(Resource.GOLD to 2_139_835L, Resource.ELIXIR to 3_593_940L, Resource.DARK_ELIXIR to 451_572L)
        assertEquals(mapOf(Resource.GOLD to 2_139_835L), CollectionPolicy.agreed(first, second))
    }

    @Test fun aCollectionIsVerifiedOnlyByAKnownRise() {
        val before = mapOf(Resource.GOLD to 2_139_835L, Resource.ELIXIR to 3_593_948L)
        assertTrue(CollectionPolicy.collected(before, before + (Resource.GOLD to 2_180_000L)))
        assertFalse(CollectionPolicy.collected(before, before))
        // Elixir unread afterwards and gold gone: nothing is known to have risen.
        assertFalse(CollectionPolicy.collected(before, mapOf(Resource.GOLD to 2_100_000L)))
        // A resource that was not read before cannot show a rise.
        assertFalse(CollectionPolicy.collected(emptyMap(), mapOf(Resource.GOLD to 1L)))
    }

    @Test fun theDeltaCoversResourcesReadBothTimes() {
        val before = mapOf(Resource.GOLD to 100L, Resource.ELIXIR to 50L)
        val after = mapOf(Resource.GOLD to 180L, Resource.DARK_ELIXIR to 9L)
        assertEquals(mapOf(Resource.GOLD to 80L), CollectionPolicy.delta(before, after))
    }

    @Test fun thePhaseIsBounded() {
        assertEquals(30, CollectionPolicy.MAX_TARGETS)
        assertEquals(120_000L, CollectionPolicy.MAX_PHASE_MS)
    }

    /**
     * The bars are read around the whole phase; a read per tap cost 18 s a target on 24 September.
     * A tap that selected a building collected nothing, so only the others share the rise.
     */
    @Test fun unselectedTapsAreVerifiedTogetherByTheirPhaseRise() {
        val before = mapOf(Resource.GOLD to 498_500L, Resource.ELIXIR to 518_425L)
        assertEquals(2, CollectionPolicy.verifiedTaps(2, before, before + (Resource.GOLD to 1_024_944L)))
        assertEquals(0, CollectionPolicy.verifiedTaps(2, before, before))
        assertEquals(0, CollectionPolicy.verifiedTaps(2, before, emptyMap()))
        assertEquals(0, CollectionPolicy.verifiedTaps(0, before, before + (Resource.GOLD to 1_024_944L)))
    }

    /**
     * Good matches scored 5.2-15.4 (walls on 23 September, collection on 24 September); Blood's
     * reference after its layout changed scored 28 and was trusted under the wall phase's 40.
     */
    @Test fun aLooseCameraMatchIsNotTrustedForCollection() {
        assertTrue(CollectionPolicy.cameraTrusted(15.4))
        assertTrue(CollectionPolicy.cameraTrusted(20.0))
        assertFalse(CollectionPolicy.cameraTrusted(28.0))
    }

    @Test fun homeStepBacksOutOfTheStoreAndMenusAndTapsGroundOnlyUnderAPanel() {
        // The store is Back whatever else the frame reads, a panel included.
        for (state in ScreenState.values()) {
            assertEquals(state.name, HomeStep.BACK, CollectionPolicy.homeStep(state, panel = false, shop = true))
            assertEquals(state.name, HomeStep.BACK, CollectionPolicy.homeStep(state, panel = true, shop = true))
        }
        assertEquals(HomeStep.BACK, CollectionPolicy.homeStep(ScreenState.MENU, panel = false, shop = false))
        assertEquals(HomeStep.BACK, CollectionPolicy.homeStep(ScreenState.ARMY, panel = false, shop = false))
        assertEquals(HomeStep.NEUTRAL, CollectionPolicy.homeStep(ScreenState.HOME, panel = true, shop = false))
        assertEquals(HomeStep.NEUTRAL, CollectionPolicy.homeStep(ScreenState.WALL, panel = false, shop = false))
        for (state in listOf(ScreenState.HOME, ScreenState.RESULT, ScreenState.BATTLE, ScreenState.UNKNOWN)) {
            assertEquals(state.name, HomeStep.NONE, CollectionPolicy.homeStep(state, panel = false, shop = false))
        }
    }
}
