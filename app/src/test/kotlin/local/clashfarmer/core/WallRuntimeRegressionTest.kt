package local.clashfarmer.core

import kotlinx.coroutines.runBlocking
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListFixture
import local.clashfarmer.recognition.UpgradeListReader
import local.clashfarmer.recognition.upgradePopupRegion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WallRuntimeRegressionTest {
    @Test fun aPersistentlyWrongConfirmationLevelNeverConfirms() = runBlocking {
        val phone = WallPhone(gold = 20_000_000L, level = 16)
        val device = object : WallPanelDevice by phone {
            override suspend fun capture(): UpgradeListFrame {
                val frame = phone.capture()
                return object : UpgradeListFrame {
                    override val observation = frame.observation.copy(
                        rawText = frame.observation.rawText.replace("Level 17", "Level 7"),
                        boxes = frame.observation.boxes.map { it.copy(text = it.text.replace("Level 17", "Level 7")) })
                    override fun pixels(region: Region) = frame.pixels(region)
                }
            }
        }
        val select: suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult = { _, _ ->
            UpgradeListResult.Selected(WallCandidate("Wall x80", 80, 5_000_000L, "gold"), 16)
        }
        val result = WallPhase(device, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint, select)
            .run(order().copy(maxWallsPerPhase = 1))
        assertTrue(result is WallPhaseOutcome.Failed && result.reason == WallPhaseFailure.CONFIRMATION_REFUSED)
        assertEquals(0, phone.confirmTaps)
        assertEquals(20_000_000L, phone.gold)
    }
    @Test fun aTransientWrongConfirmationLevelIsRereadBeforeRefusing() = runBlocking {
        val phone = WallPhone(gold = 20_000_000L, level = 16)
        var changed = false
        val device = object : WallPanelDevice by phone {
            override suspend fun capture(): UpgradeListFrame {
                val frame = phone.capture()
                if (changed || !frame.observation.rawText.contains("Upgrade Wall to Level")) return frame
                changed = true
                return object : UpgradeListFrame {
                    override val observation = frame.observation.copy(
                        rawText = frame.observation.rawText.replace("Level 17", "Level 7"),
                        boxes = frame.observation.boxes.map { it.copy(text = it.text.replace("Level 17", "Level 7")) })
                    override fun pixels(region: Region) = frame.pixels(region)
                }
            }
        }
        val select: suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult = { _, _ ->
            UpgradeListResult.Selected(WallCandidate("Wall x80", 80, 5_000_000L, "gold"), 16)
        }
        val result = WallPhase(device, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint, select)
            .run(order().copy(maxWallsPerPhase = 1))
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 1, 5_000_000L), result)
        assertEquals(1, phone.confirmTaps)
    }
    @get:Rule val folder = TemporaryFolder()
    private val id = "11111111-2222-3333-4444-555555555555"
    private fun order(balance: Long = 20_000_000L, capacity: Long = 25_000_000L) = WallPhaseOrder(
        id, WallPhaseRequest.AUTOMATIC, Resource.GOLD, balance, capacity, 75, 0L, 20, 20
    )

    @Test fun savedReservesAreIgnoredAndNotSavedAgain() {
        val json = AccountSettings().toJson().put("reserveGold", 99_000_000L).put("reserveElixir", 99_000_000L)
        val settings = AccountSettings.fromJson(json)
        assertEquals(0L, settings.reserveGold)
        assertEquals(0L, settings.reserveElixir)
        assertFalse(settings.toJson().has("reserveGold"))
        assertFalse(settings.toJson().has("reserveElixir"))
    }

    @Test fun automaticOrdersNeverWithholdReserves() = runBlocking {
        val coordinator = WallFarmingCoordinator(id, true, false, WallStorageRules(), "gold",
            reserveGold = 99_000_000L, capacities = mapOf(Resource.GOLD to 20_000_000L),
            purchaseStore = WallPurchaseStore(folder.root))
        var sent: WallPhaseOrder? = null
        coordinator.runAutomaticPhase(mapOf(Resource.GOLD to 19_000_000L)) {
            sent = it
            WallPhaseOutcome.Bought(Resource.GOLD, 1, 4_000_000L)
        }
        assertEquals(0L, sent!!.reserve)
    }

    @Test fun pendingPurchaseEndsAutomaticFarmingBeforeAnyPhase() = runBlocking {
        // D3 F5: no longer a StopException, which skipped a combined run's handoff. No phase runs
        // and the account ends at the checkpoint on pendingStopReason as a normal finish.
        val store = WallPurchaseStore(folder.root)
        store.markPending(PendingWallPurchase(id, Resource.GOLD, 1, 4_000_000L, 15_000_000L, 19_000_000L, 1))
        val coordinator = WallFarmingCoordinator(id, true, false, WallStorageRules(), "gold",
            capacities = mapOf(Resource.GOLD to 20_000_000L), purchaseStore = store)
        var ran = false
        assertNull(coordinator.runAutomaticPhase(mapOf(Resource.GOLD to 19_000_000L)) {
            ran = true
            WallPhaseOutcome.Empty
        })
        assertFalse(ran)
        assertNotNull(coordinator.pendingStopReason())
        assertTrue(store.isPending(id))
    }

    @Test fun widerBloodPopupIncludesWallNameAndPrice() {
        val boxes = listOf(
            RecognizedBox("Other upgrades:", 594, 142, 810, 166),
            RecognizedBox("Wall x289", 594, 424, 680, 446),
            RecognizedBox("8 000 000", 935, 424, 1040, 446)
        )
        val reading = UpgradeListReader.read(boxes, UpgradeListFixture.screen) { region ->
            IntArray(region.width * region.height) { if (region.left < 935) 0xFFC832DC.toInt() else 0xFFFFFFFF.toInt() }
        }
        val wall = reading.rows.single { it.kind == UpgradeListReader.Kind.WALL }
        assertEquals(289, wall.count)
        assertEquals(UpgradeListReader.Amount.Exact(8_000_000L), wall.amount)
        assertEquals("elixir", wall.currency)
    }

    @Test fun aCoinReadAsALetterStillMarksTheWiderPopup() {
        val plain = upgradePopupRegion(UpgradeListFixture.screen, listOf(RecognizedBox("8 000 000", 935, 424, 1040, 446)))
        for (text in listOf("O 8 000 000", "e8 000 000", "8 000 000 o")) {
            assertEquals(text, plain, upgradePopupRegion(UpgradeListFixture.screen, listOf(RecognizedBox(text, 935, 424, 1040, 446))))
        }
        // Words beside digits are not a price.
        assertEquals(upgradePopupRegion(UpgradeListFixture.screen), upgradePopupRegion(
            UpgradeListFixture.screen, listOf(RecognizedBox("Gold 8 000 000", 935, 424, 1040, 446))
        ))
    }

    @Test fun villageTimersDoNotChangeTheBuilderPopupWidth() {
        val normal = upgradePopupRegion(UpgradeListFixture.screen)
        val withTimer = upgradePopupRegion(UpgradeListFixture.screen, listOf(
            RecognizedBox("9d 12h", 1018, 251, 1050, 265)
        ))
        assertEquals(normal, withTimer)
    }

    @Test fun goldPurchaseCanUseAnElixirPricedListRow() = runBlocking {
        val phone = WallPhone(gold = 20_000_000L, start = "home", items = listOf(
            UpgradeListFixture.Header("Upgrades in progress:", 123),
            UpgradeListFixture.Line("Wall x80", 200, "4 000 000", "elixir", payable = false)
        ))
        val result = WallPhase(phone, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint).run(order())
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 1, 4_000_000L), result)
        assertEquals(16_000_000L, phone.gold)
        assertEquals(1, phone.confirmTaps)
    }

    @Test fun elixirDiscoveryCanUseARedGoldPricedRow() = runBlocking {
        val phone = WallPhone(start = "home", items = listOf(
            UpgradeListFixture.Header("Upgrades in progress:", 123),
            UpgradeListFixture.Line("Wall x80", 200, "4 000 000", "gold", payable = false)
        ))
        val result = UpgradeListNavigator(phone, UpgradeListFixture.screen)
            .findAndSelectWall(mapOf("elixir" to 20_000_000L), emptySet())
        assertTrue(result is UpgradeListResult.Selected)
        assertEquals(0, phone.confirmTaps)
    }

    @Test fun freshAgreedPanelBalanceMayIncreaseWithinCapacity() = runBlocking {
        val phone = WallPhone(gold = 20_000_000L)
        val select: suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult = { _, _ ->
            UpgradeListResult.Selected(WallCandidate("Wall x80", 80, 4_000_000L, "gold"), 15)
        }
        val result = WallPhase(phone, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint, select)
            .run(order(balance = 19_000_000L))
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 1, 4_000_000L), result)
        assertEquals(16_000_000L, phone.gold)
    }

    @Test fun outOfCapacityPanelBalanceIsNeverSpent() = runBlocking {
        val phone = WallPhone(gold = 26_000_000L)
        val select: suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult = { _, _ ->
            UpgradeListResult.Selected(WallCandidate("Wall x80", 80, 4_000_000L, "gold"), 15)
        }
        val result = WallPhase(phone, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint, select)
            .run(order(balance = 19_000_000L))
        assertTrue(result is WallPhaseOutcome.Failed)
        assertEquals(0, phone.confirmTaps)
    }

    @Test fun aTransientWrongPanelPriceIsRereadBeforeRefusing() = runBlocking {
        val phone = WallPhone(gold = 20_000_000L)
        var changed = false
        val device = object : WallPanelDevice by phone {
            override suspend fun capture(): UpgradeListFrame {
                val frame = phone.capture()
                if (changed || phone.panel != WallPhone.Panel.SINGLE) return frame
                changed = true
                val boxes = frame.observation.boxes.map { box ->
                    if (box.left in 850..1020 && box.text.contains("4")) box.copy(text = box.text.replace('4', '8')) else box
                }
                return object : UpgradeListFrame {
                    override val observation = frame.observation.copy(boxes = boxes)
                    override fun pixels(region: Region) = frame.pixels(region)
                }
            }
        }
        val select: suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult = { _, _ ->
            UpgradeListResult.Selected(WallCandidate("Wall x80", 80, 4_000_000L, "gold"), 15)
        }
        val result = WallPhase(device, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint, select)
            .run(order())
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 1, 4_000_000L), result)
        assertEquals(1, phone.confirmTaps)
    }

    @Test fun aVillageUpgradeHintDoesNotHideTheWallPurchaseButtons() = runBlocking {
        val boxes = WallPhone().capture().observation.boxes +
            RecognizedBox("Upgrade", 1200, 450, 1270, 470)
        val buttons = WallPanel.purchaseButtons(boxes, UpgradeListFixture.screen) {
            if (it.left > 1000) "elixir" else "gold"
        }
        assertEquals(setOf("gold", "elixir"), buttons.keys)
        val regions = WallPanel.priceRegions(boxes, UpgradeListFixture.screen)
        assertEquals(2, regions.size)
        assertTrue(regions.any { it.contains(buttons.getValue("gold").priceBox.center) })
        assertTrue(regions.any { it.contains(buttons.getValue("elixir").priceBox.center) })
    }
}
