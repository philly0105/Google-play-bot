package local.clashfarmer.accounts

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.WallConfig
import local.clashfarmer.recognition.WallCurrencyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AccountStoreTest {

    private fun createSampleProfile(isVerified: Boolean = true): PhoneProfile {
        return PhoneProfile(
            resolutionWidth = 1600,
            resolutionHeight = 720,
            viewport = Viewport(0, 0, 1600, 720),
            stateAnchors = mapOf(
                "attack" to Point(100, 600),
                "find_match" to Point(300, 500),
                "army" to Point(500, 650),
                "return_home" to Point(800, 620),
                "village_neutral" to Point(150, 200)
            ),
            troopAnchors = mapOf(
                "valkyrie" to Point(200, 650),
                "rage" to Point(300, 650)
            ),
            deploymentPoints = listOf(
                Point(100, 100),
                Point(1500, 650)
            ),
            perimeterSegments = listOf(
                Pair(Point(10, 10), Point(1500, 10)),
                Pair(Point(1500, 10), Point(1500, 700)),
                Pair(Point(1500, 700), Point(10, 700)),
                Pair(Point(10, 700), Point(10, 10))
            ),
            wallPoints = listOf(
                Point(800, 400),
                Point(820, 410)
            ),
            wallConfig = WallConfig(
                currencies = mapOf(
                    "gold" to WallCurrencyConfig(
                        costRoi = Region(700, 400, 800, 450),
                        buttonPoint = Point(750, 425)
                    )
                ),
                bulkButton = Point(900, 425)
            ),
            isVerified = isVerified
        )
    }

    @Test
    fun testAccountSettingsDefaultsAndValidation() {
        val defaults = AccountSettings()
        defaults.validate()
        assertEquals(15, defaults.attacks)
        assertEquals(120, defaults.maxMinutes)
        assertFalse(defaults.wallsEnabled)
        assertEquals(90, defaults.triggerPercent)
        assertEquals(75, defaults.resumePercent)
        assertEquals(17, defaults.maxWallLevel)
        assertEquals(0L, defaults.reserveGold)
        assertEquals(0L, defaults.reserveElixir)
        assertEquals("auto", defaults.currencyPreference)
        assertEquals(20, defaults.maxWallsPerPhase)

        // Attacks bounds: 1..100
        AccountSettings(attacks = 1).validate()
        AccountSettings(attacks = 100).validate()
        for (bad in listOf(0, -1, 101)) {
            try {
                AccountSettings(attacks = bad).validate()
                fail("Expected failure for attacks = $bad")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }

        // Max minutes bounds: 1..120
        AccountSettings(maxMinutes = 1).validate()
        AccountSettings(maxMinutes = 120).validate()
        for (bad in listOf(0, -5, 121)) {
            try {
                AccountSettings(maxMinutes = bad).validate()
                fail("Expected failure for maxMinutes = $bad")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }

        // Trigger 1..100, resume 0..trigger-1
        AccountSettings(triggerPercent = 1, resumePercent = 0).validate()
        AccountSettings(triggerPercent = 100, resumePercent = 99).validate()
        for ((trigger, resume) in listOf(0 to 0, 101 to 75, 90 to 90, 90 to -1)) {
            try {
                AccountSettings(triggerPercent = trigger, resumePercent = resume).validate()
                fail("Expected failure for trigger $trigger resume $resume")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }

        // Max wall level bounds: 1..25
        AccountSettings(maxWallLevel = 1).validate()
        AccountSettings(maxWallLevel = 25).validate()
        for (bad in listOf(0, -1, 26)) {
            try {
                AccountSettings(maxWallLevel = bad).validate()
                fail("Expected failure for maxWallLevel = $bad")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }

        // Legacy reserve values have no validation or spending effect.
        AccountSettings(reserveGold = -1L, reserveElixir = -1L).validate()

        // Currency preferences: "auto", "gold", "elixir"
        AccountSettings(currencyPreference = "auto").validate()
        AccountSettings(currencyPreference = "gold").validate()
        AccountSettings(currencyPreference = "elixir").validate()
        for (bad in listOf("gems", "dark_elixir", "", "AUTO", "Gold")) {
            try {
                AccountSettings(currencyPreference = bad).validate()
                fail("Expected failure for currencyPreference = '$bad'")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }

        // Max walls per phase bounds: 1..100
        AccountSettings(maxWallsPerPhase = 1).validate()
        AccountSettings(maxWallsPerPhase = 100).validate()
        for (bad in listOf(0, -1, 101)) {
            try {
                AccountSettings(maxWallsPerPhase = bad).validate()
                fail("Expected failure for maxWallsPerPhase = $bad")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun testAccountRecordValidation() {
        val validId = AccountStore.SWEAT_ID
        val record = AccountRecord(
            id = validId,
            villageName = "Sweat",
            supercellName = "Philly0105",
            playerTag = "#8YQCYG2RU"
        )
        record.validate()

        // Blank villageName
        try {
            record.copy(villageName = "").validate()
            fail("Expected failure for blank villageName")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        // Blank supercellName
        try {
            record.copy(supercellName = "   ").validate()
            fail("Expected failure for blank supercellName")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        // Blank playerTag
        try {
            record.copy(playerTag = "").validate()
            fail("Expected failure for blank playerTag")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        // Invalid opaque UUID id format
        for (badId in listOf("not-a-uuid", "12345", "../../traversal", "")) {
            try {
                record.copy(id = badId).validate()
                fail("Expected failure for invalid UUID id: $badId")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }

        // Invalid nested settings
        try {
            record.copy(settings = AccountSettings(attacks = 0)).validate()
            fail("Expected failure for invalid settings in record")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testEnsureMigratedWithLegacyProfileAndCalibration() {
        val rootDir = Files.createTempDirectory("test_account_store_legacy").toFile()
        try {
            val legacyProfileFile = File(rootDir, "profile.json")
            val sampleProfile = createSampleProfile(isVerified = true)
            legacyProfileFile.writeText(sampleProfile.toJson())
            val legacyBytesBefore = legacyProfileFile.readBytes()

            val legacyCalDir = File(rootDir, "calibration").apply { mkdirs() }
            val legacyFrozenFile = File(legacyCalDir, "frozen.png")
            val fakePngData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)
            legacyFrozenFile.writeBytes(fakePngData)

            val store = AccountStore(rootDir)
            val accounts = store.ensureMigrated()

            assertEquals(2, accounts.size)
            val sweat = accounts.first { it.villageName == "Sweat" }
            val blood = accounts.first { it.villageName == "Blood" }

            // Account identity details
            assertEquals(AccountStore.SWEAT_ID, sweat.id)
            assertEquals("Sweat", sweat.villageName)
            assertEquals("Philly0105", sweat.supercellName)
            assertEquals("#8YQCYG2RU", sweat.playerTag)
            assertFalse("Initial wallsEnabled must be false", sweat.settings.wallsEnabled)

            assertEquals(AccountStore.BLOOD_ID, blood.id)
            assertEquals("Blood", blood.villageName)
            assertEquals("VastMaNiSh", blood.supercellName)
            assertEquals("#Y2G29YJQ8", blood.playerTag)
            assertFalse("Initial wallsEnabled must be false", blood.settings.wallsEnabled)

            // Legacy files must remain untouched
            assertTrue("Legacy profile.json must remain untouched", legacyProfileFile.exists())
            assertTrue("Legacy profile content must not change", legacyProfileFile.readBytes().contentEquals(legacyBytesBefore))
            assertTrue("Legacy frozen.png must remain untouched", legacyFrozenFile.exists())
            assertTrue("Legacy frozen content must not change", legacyFrozenFile.readBytes().contentEquals(fakePngData))

            // Sweat profile check
            val sweatProfile = store.readProfile(sweat.id)
            assertNotNull("Sweat profile must exist", sweatProfile)
            assertEquals(sampleProfile.resolutionWidth, sweatProfile!!.resolutionWidth)
            assertEquals(sampleProfile.viewport, sweatProfile.viewport)
            assertEquals(sampleProfile.wallPoints.size, sweatProfile.wallPoints.size)
            assertEquals(sampleProfile.isVerified, sweatProfile.isVerified)

            // Sweat calibration check: frozen.png copied only into Sweat
            val sweatCalDir = store.calibrationDir(sweat.id)
            val sweatFrozen = File(sweatCalDir, "frozen.png")
            assertTrue("Sweat must receive frozen.png", sweatFrozen.exists())
            assertTrue("Sweat frozen.png must match legacy", sweatFrozen.readBytes().contentEquals(fakePngData))

            // Blood profile check: common bindings copied, but wallPoints empty and isVerified = false
            val bloodProfile = store.readProfile(blood.id)
            assertNotNull("Blood profile must exist", bloodProfile)
            assertEquals(sampleProfile.resolutionWidth, bloodProfile!!.resolutionWidth)
            assertEquals(sampleProfile.resolutionHeight, bloodProfile.resolutionHeight)
            assertEquals(sampleProfile.viewport, bloodProfile.viewport)
            assertEquals(sampleProfile.stateAnchors, bloodProfile.stateAnchors)
            assertEquals(sampleProfile.troopAnchors, bloodProfile.troopAnchors)
            assertEquals(sampleProfile.wallConfig, bloodProfile.wallConfig)
            assertTrue("Blood wall points must be empty", bloodProfile.wallPoints.isEmpty())
            assertFalse("Blood must be unverified", bloodProfile.isVerified)

            // Blood calibration check: frozen.png must NOT be copied into Blood
            val bloodCalDir = store.calibrationDir(blood.id)
            val bloodFrozen = File(bloodCalDir, "frozen.png")
            assertFalse("Blood must NOT receive legacy frozen.png", bloodFrozen.exists())

            // Initial selected ID must be Sweat
            assertEquals(AccountStore.SWEAT_ID, store.selectedId())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testRepeatedEnsureMigratedDoesNotOverwriteEditedProfiles() {
        val rootDir = Files.createTempDirectory("test_repeated_migration").toFile()
        try {
            val legacyProfileFile = File(rootDir, "profile.json")
            legacyProfileFile.writeText(createSampleProfile().toJson())

            val store = AccountStore(rootDir)
            store.ensureMigrated()

            // Edit Blood's profile (e.g. add a calibrated wall point and verify)
            val bloodProfile = store.readProfile(AccountStore.BLOOD_ID)!!
            val editedBloodProfile = bloodProfile.copy(
                wallPoints = listOf(Point(500, 500)),
                isVerified = true
            )
            store.saveProfile(AccountStore.BLOOD_ID, editedBloodProfile)

            // Edit Sweat's settings
            val sweatAccount = store.load().first { it.id == AccountStore.SWEAT_ID }
            val updatedSweat = sweatAccount.copy(
                settings = sweatAccount.settings.copy(
                    attacks = 33, wallsEnabled = true,
                    capacities = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L)
                )
            )
            store.update(updatedSweat)

            // Run ensureMigrated again
            val reMigratedAccounts = store.ensureMigrated()
            assertEquals(2, reMigratedAccounts.size)

            // Verify Sweat settings were preserved
            val reSweat = reMigratedAccounts.first { it.id == AccountStore.SWEAT_ID }
            assertEquals(33, reSweat.settings.attacks)
            assertTrue(reSweat.settings.wallsEnabled)

            // Verify Blood's edited profile was NOT overwritten by repeated ensureMigrated
            val reBloodProfile = store.readProfile(AccountStore.BLOOD_ID)!!
            assertEquals(1, reBloodProfile.wallPoints.size)
            assertEquals(Point(500, 500), reBloodProfile.wallPoints[0])
            assertTrue(reBloodProfile.isVerified)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testInterruptedMigrationRecovery() {
        val rootDir = Files.createTempDirectory("test_interrupted_migration").toFile()
        try {
            val legacyProfileFile = File(rootDir, "profile.json")
            val sample = createSampleProfile(isVerified = true)
            legacyProfileFile.writeText(sample.toJson())

            // Simulate interruption: Sweat already had its profile written before index.json was created
            val accountsDir = File(rootDir, "accounts").apply { mkdirs() }
            val sweatDir = File(accountsDir, AccountStore.SWEAT_ID).apply { mkdirs() }
            val existingSweatProfile = File(sweatDir, "profile.json")
            val customProfile = sample.copy(resolutionWidth = 1920)
            existingSweatProfile.writeText(customProfile.toJson())

            val store = AccountStore(rootDir)
            // Ensure index does not exist yet
            assertFalse(File(accountsDir, "index.json").exists())

            // Should recover cleanly on ensureMigrated and preserve existing written profile
            val accounts = store.ensureMigrated()
            assertEquals(2, accounts.size)
            assertTrue(File(accountsDir, "index.json").exists())

            val sweatProfile = store.readProfile(AccountStore.SWEAT_ID)
            assertNotNull(sweatProfile)
            assertEquals(1920, sweatProfile!!.resolutionWidth)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testMigrationWhenNoLegacyProfileExists() {
        val rootDir = Files.createTempDirectory("test_no_legacy").toFile()
        try {
            val store = AccountStore(rootDir)
            val accounts = store.ensureMigrated()

            assertEquals(2, accounts.size)
            val sweatId = accounts.first { it.villageName == "Sweat" }.id
            val bloodId = accounts.first { it.villageName == "Blood" }.id

            // When no legacy exists, profiles may be absent; never fabricate ready calibration
            assertNull("Sweat profile must be absent when no legacy exists", store.readProfile(sweatId))
            assertNull("Blood profile must be absent when no legacy exists", store.readProfile(bloodId))
            assertFalse(store.profileFile(sweatId).exists())
            assertFalse(store.profileFile(bloodId).exists())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testProfileIsolation() {
        val rootDir = Files.createTempDirectory("test_profile_isolation").toFile()
        try {
            val legacyProfileFile = File(rootDir, "profile.json")
            legacyProfileFile.writeText(createSampleProfile(isVerified = false).toJson())
            val legacyBytes = legacyProfileFile.readBytes()

            val store = AccountStore(rootDir)
            store.ensureMigrated()

            // Update Sweat profile
            val sweatProfile = store.readProfile(AccountStore.SWEAT_ID)!!
            val modifiedSweat = sweatProfile.withAnchor("village_neutral", Point(999, 555))
            store.saveProfile(AccountStore.SWEAT_ID, modifiedSweat)

            // Verify Blood profile is unchanged
            val bloodProfile = store.readProfile(AccountStore.BLOOD_ID)!!
            assertEquals(Point(150, 200), bloodProfile.stateAnchors["village_neutral"])

            // Update Blood profile
            val modifiedBlood = bloodProfile.withAnchor("village_neutral", Point(111, 222))
            store.saveProfile(AccountStore.BLOOD_ID, modifiedBlood)

            // Verify Sweat profile is unchanged
            val reSweatProfile = store.readProfile(AccountStore.SWEAT_ID)!!
            assertEquals(Point(999, 555), reSweatProfile.stateAnchors["village_neutral"])

            // Verify legacy file is completely untouched
            assertTrue(legacyProfileFile.readBytes().contentEquals(legacyBytes))
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testSelectedIdPersistence() {
        val rootDir = Files.createTempDirectory("test_selected_id").toFile()
        try {
            val store = AccountStore(rootDir)
            store.ensureMigrated()

            // Initial selected ID is Sweat
            assertEquals(AccountStore.SWEAT_ID, store.selectedId())

            // Switch selection to Blood
            store.select(AccountStore.BLOOD_ID)
            assertEquals(AccountStore.BLOOD_ID, store.selectedId())

            // Create new instance on same root and verify persistence
            val newStoreInstance = AccountStore(rootDir)
            assertEquals(AccountStore.BLOOD_ID, newStoreInstance.selectedId())

            // Selecting unknown ID must fail
            val unknownId = java.util.UUID.randomUUID().toString()
            try {
                store.select(unknownId)
                fail("Expected failure for selecting unknown ID")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testInvalidIndexThrowsAndDoesNotReset() {
        val rootDir = Files.createTempDirectory("test_invalid_index").toFile()
        try {
            val accountsDir = File(rootDir, "accounts").apply { mkdirs() }
            val indexFile = File(accountsDir, "index.json")

            // Case 1: Corrupt JSON syntax
            val corruptText = "{ broken json: true "
            indexFile.writeText(corruptText)

            val store = AccountStore(rootDir)
            try {
                store.load()
                fail("Expected IllegalStateException for corrupt index")
            } catch (e: IllegalStateException) {
                // Expected
            }
            try {
                store.ensureMigrated()
                fail("Expected IllegalStateException on ensureMigrated with corrupt index")
            } catch (e: IllegalStateException) {
                // Expected
            }
            // Must NOT have reset index.json
            assertEquals("Index must not be reset on corruption", corruptText, indexFile.readText())

            // Case 2: Missing selectedId
            indexFile.writeText("""{"accounts": []}""")
            try {
                store.load()
                fail("Expected IllegalStateException for missing selectedId")
            } catch (e: IllegalStateException) {
                // Expected
            }

            // Case 3: Invalid setting values in index (e.g. attacks = -5)
            val invalidSettingsIndex = """
                {
                  "selectedId": "${AccountStore.SWEAT_ID}",
                  "accounts": [
                    {
                      "id": "${AccountStore.SWEAT_ID}",
                      "villageName": "Sweat",
                      "supercellName": "Philly0105",
                      "playerTag": "#8YQCYG2RU",
                      "settings": {
                        "attacks": -5
                      }
                    }
                  ]
                }
            """.trimIndent()
            indexFile.writeText(invalidSettingsIndex)
            try {
                store.load()
                fail("Expected IllegalStateException for invalid setting value in index")
            } catch (e: IllegalStateException) {
                // Expected
            }
            assertEquals(invalidSettingsIndex, indexFile.readText())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testPathValidationAndTraversalPrevention() {
        val rootDir = Files.createTempDirectory("test_path_validation").toFile()
        try {
            val store = AccountStore(rootDir)
            store.ensureMigrated()

            val traversalIds = listOf(
                "../traversal",
                "../../etc/passwd",
                "sweat/sub",
                "not-uuid",
                java.util.UUID.randomUUID().toString() // Unknown UUID not in index
            )

            for (badId in traversalIds) {
                try {
                    store.profileFile(badId)
                    fail("Expected IllegalArgumentException for bad ID: $badId in profileFile")
                } catch (e: IllegalArgumentException) {
                    // Expected
                }

                try {
                    store.calibrationDir(badId)
                    fail("Expected IllegalArgumentException for bad ID: $badId in calibrationDir")
                } catch (e: IllegalArgumentException) {
                    // Expected
                }

                try {
                    store.readProfile(badId)
                    fail("Expected IllegalArgumentException for bad ID: $badId in readProfile")
                } catch (e: IllegalArgumentException) {
                    // Expected
                }

                try {
                    store.saveProfile(badId, createSampleProfile())
                    fail("Expected IllegalArgumentException for bad ID: $badId in saveProfile")
                } catch (e: IllegalArgumentException) {
                    // Expected
                }
            }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testSettingsRoundTrip() {
        val rootDir = Files.createTempDirectory("test_settings_roundtrip").toFile()
        try {
            val store = AccountStore(rootDir)
            store.ensureMigrated()

            val accounts = store.load()
            val sweat = accounts.first { it.id == AccountStore.SWEAT_ID }

            val customSettings = AccountSettings(
                attacks = 45,
                maxMinutes = 90,
                wallsEnabled = true,
                triggerPercent = 85,
                resumePercent = 70,
                maxWallLevel = 16,
                reserveGold = 2_500_000L,
                reserveElixir = 1_800_000L,
                currencyPreference = "gold",
                maxWallsPerPhase = 12,
                capacities = mapOf(Resource.GOLD to 20_000_000L)
            )

            val updatedSweat = sweat.copy(settings = customSettings)
            store.update(updatedSweat)

            // Verify with newly instantiated store
            val newStore = AccountStore(rootDir)
            val reloadedAccounts = newStore.load()
            val reloadedSweat = reloadedAccounts.first { it.id == AccountStore.SWEAT_ID }

            assertEquals(customSettings.copy(reserveGold = 0L, reserveElixir = 0L), reloadedSweat.settings)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testAccountThatFailsTheWallChecksStillLoadsWithWallsOff() {
        val rootDir = Files.createTempDirectory("test_wall_setup_required").toFile()
        try {
            val store = AccountStore(rootDir)
            store.ensureMigrated()
            val indexFile = File(File(rootDir, "accounts"), "index.json")
            // An old account: walls on, no capacities, the retired cadence field.
            val json = org.json.JSONObject(indexFile.readText())
            val sweat = json.getJSONArray("accounts").getJSONObject(0)
            sweat.put("settings", org.json.JSONObject(
                """{"attacks":25,"wallsEnabled":true,"attacksPerWallPhase":7,"reserveGold":100}"""
            ))
            indexFile.writeText(json.toString(2))
            val before = indexFile.readText()

            val loaded = AccountStore(rootDir).load().first { it.id == AccountStore.SWEAT_ID }
            assertEquals(25, loaded.settings.attacks)
            assertFalse(loaded.settings.wallsEnabled)
            assertNotNull(loaded.settings.wallSetupRequired)
            assertTrue(loaded.settings.wallSetupRequired!!.contains("capacity"))
            // Loading never rewrites the user's file.
            assertEquals(before, indexFile.readText())

            // Fixing the setup clears the reason.
            val fixed = loaded.settings.copy(
                wallsEnabled = true, wallSetupRequired = null,
                capacities = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L)
            )
            store.update(loaded.copy(settings = fixed))
            val again = store.load().first { it.id == AccountStore.SWEAT_ID }
            assertTrue(again.settings.wallsEnabled)
            assertNull(again.settings.wallSetupRequired)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testReturnImmutableData() {
        val rootDir = Files.createTempDirectory("test_immutable_data").toFile()
        try {
            val store = AccountStore(rootDir)
            val accounts = store.ensureMigrated()

            try {
                (accounts as MutableList<AccountRecord>).add(
                    AccountRecord(
                        id = java.util.UUID.randomUUID().toString(),
                        villageName = "Fake",
                        supercellName = "Fake",
                        playerTag = "#FAKE"
                    )
                )
                fail("Expected UnsupportedOperationException on mutating loaded list")
            } catch (e: UnsupportedOperationException) {
                // Expected
            }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun twoStoresUpdatingDifferentFieldsKeepBothChanges() {
        // The wall phase moves the cursor and reorders the probe points as it goes. Each used to
        // write a copy of the profile the run started with, so the second write undid the first.
        val rootDir = Files.createTempDirectory("test_update_profile").toFile()
        try {
            File(rootDir, "profile.json").writeText(createSampleProfile().toJson())
            AccountStore(rootDir).ensureMigrated()

            val cursorWriter = AccountStore(rootDir)
            val probeWriter = AccountStore(rootDir)
            cursorWriter.updateProfile(AccountStore.SWEAT_ID) {
                it.copy(wallCursor = local.clashfarmer.core.WallCursor(2, 7))
            }
            probeWriter.updateProfile(AccountStore.SWEAT_ID) {
                it.copy(wallPoints = it.wallPoints.reversed())
            }

            val reloaded = AccountStore(rootDir).readProfile(AccountStore.SWEAT_ID)!!
            assertEquals(local.clashfarmer.core.WallCursor(2, 7), reloaded.wallCursor)
            assertEquals(listOf(Point(820, 410), Point(800, 400)), reloaded.wallPoints)
            assertTrue("Progress is not calibration", reloaded.isVerified)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun earnedVerificationSurvivesAnEarlierProgressWrite() {
        val rootDir = Files.createTempDirectory("test_update_verification").toFile()
        try {
            File(rootDir, "profile.json").writeText(createSampleProfile(isVerified = false).toJson())
            val store = AccountStore(rootDir)
            store.ensureMigrated()

            store.updateProfile(AccountStore.SWEAT_ID) {
                it.copy(wallCursor = local.clashfarmer.core.WallCursor(1, 3))
            }
            store.updateProfile(AccountStore.SWEAT_ID) { it.copy(isVerified = true) }

            val reloaded = store.readProfile(AccountStore.SWEAT_ID)!!
            assertTrue(reloaded.isVerified)
            assertEquals(local.clashfarmer.core.WallCursor(1, 3), reloaded.wallCursor)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun invalidUpdateLeavesTheProfileAlone() {
        val rootDir = Files.createTempDirectory("test_update_invalid").toFile()
        try {
            File(rootDir, "profile.json").writeText(createSampleProfile().toJson())
            val store = AccountStore(rootDir)
            store.ensureMigrated()
            val before = store.profileFile(AccountStore.SWEAT_ID).readText()

            try {
                store.updateProfile(AccountStore.SWEAT_ID) { it.copy(heroDrop = Point(5000, 10)) }
                fail("Expected validation error")
            } catch (e: Exception) {
                // Expected
            }

            assertEquals(before, store.profileFile(AccountStore.SWEAT_ID).readText())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun staleCalibrationSaveKeepsProgressMadeWhileItWasOpen() {
        // Calibration loads the profile once. A run that moves the cursor and earns verification
        // while the screen is open must not be undone by saving an unrelated anchor.
        val rootDir = Files.createTempDirectory("test_stale_calibration").toFile()
        try {
            File(rootDir, "profile.json").writeText(createSampleProfile(isVerified = false).toJson())
            val store = AccountStore(rootDir)
            store.ensureMigrated()

            val opened = store.readProfile(AccountStore.SWEAT_ID)!!
            store.updateProfile(AccountStore.SWEAT_ID) {
                it.copy(wallCursor = local.clashfarmer.core.WallCursor(4, 9), isVerified = true)
            }

            val edited = opened.withAnchor("end_battle", Point(143, 537))
            store.saveCalibration(AccountStore.SWEAT_ID, opened, edited)

            val saved = store.readProfile(AccountStore.SWEAT_ID)!!
            assertEquals(Point(143, 537), saved.stateAnchors["end_battle"])
            assertEquals(local.clashfarmer.core.WallCursor(4, 9), saved.wallCursor)
            assertFalse("An edited calibration has not been verified", saved.isVerified)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun calibrationSaveWithoutEditsKeepsEarnedVerification() {
        val rootDir = Files.createTempDirectory("test_unedited_calibration").toFile()
        try {
            File(rootDir, "profile.json").writeText(createSampleProfile(isVerified = false).toJson())
            val store = AccountStore(rootDir)
            store.ensureMigrated()

            val opened = store.readProfile(AccountStore.SWEAT_ID)!!
            store.updateProfile(AccountStore.SWEAT_ID) { it.copy(isVerified = true) }
            store.saveCalibration(AccountStore.SWEAT_ID, opened, opened)

            assertTrue(store.readProfile(AccountStore.SWEAT_ID)!!.isVerified)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun firstCalibrationSaveCreatesTheProfile() {
        val rootDir = Files.createTempDirectory("test_first_calibration").toFile()
        try {
            val store = AccountStore(rootDir)
            store.ensureMigrated()
            assertNull(store.readProfile(AccountStore.BLOOD_ID))

            val fresh = PhoneProfile.createDefault(1600, 720)
            val edited = fresh.withAnchor("attack", Point(134, 637))
            store.saveCalibration(AccountStore.BLOOD_ID, fresh, edited)

            assertEquals(Point(134, 637), store.readProfile(AccountStore.BLOOD_ID)!!.stateAnchors["attack"])
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun retiringTheCameraReferenceClearsTheSavedCollectorsWithoutASave() {
        // RT-1: Retake camera reference cleared the collectors only in memory, so a run started
        // before Save tapped the old village's collector spots.
        val rootDir = Files.createTempDirectory("test_clear_collectors").toFile()
        try {
            File(rootDir, "profile.json").writeText(createSampleProfile(isVerified = true).toJson())
            val store = AccountStore(rootDir)
            store.ensureMigrated()
            store.updateProfile(AccountStore.SWEAT_ID) {
                it.withCollectionTargets(listOf(local.clashfarmer.core.CollectionTarget("gold", Point(400, 300))))
                    .copy(wallCursor = local.clashfarmer.core.WallCursor(4, 9))
            }
            val opened = store.readProfile(AccountStore.SWEAT_ID)!!

            assertNotNull(store.clearCollectionTargets(AccountStore.SWEAT_ID))

            val cleared = store.readProfile(AccountStore.SWEAT_ID)!!
            assertTrue(cleared.collectionTargets.isEmpty())
            assertEquals(local.clashfarmer.core.WallCursor(4, 9), cleared.wallCursor)
            assertTrue(cleared.isVerified)

            // The screen's Save afterwards, with nothing measured again, leaves them cleared.
            val retired = opened.withCollectionTargets(emptyList())
            store.saveCalibration(AccountStore.SWEAT_ID, retired, retired)
            assertTrue(store.readProfile(AccountStore.SWEAT_ID)!!.collectionTargets.isEmpty())

            // An account with no saved profile has nothing to clear, and nothing is written for it.
            assertTrue(store.profileFile(AccountStore.BLOOD_ID).delete())
            assertNull(store.clearCollectionTargets(AccountStore.BLOOD_ID))
            assertNull(store.readProfile(AccountStore.BLOOD_ID))
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testAtomicWritePreservesOldFileOnFailure() {
        val rootDir = Files.createTempDirectory("test_atomic_preservation").toFile()
        try {
            val legacyProfileFile = File(rootDir, "profile.json")
            val originalProfile = createSampleProfile(isVerified = true)
            legacyProfileFile.writeText(originalProfile.toJson())

            val store = AccountStore(rootDir)
            store.ensureMigrated()

            val profileFile = store.profileFile(AccountStore.SWEAT_ID)
            val originalContent = profileFile.readText()

            // Attempt to save an invalid profile that fails profile.validate()
            val invalidProfile = originalProfile.copy(resolutionWidth = -100)
            try {
                store.saveProfile(AccountStore.SWEAT_ID, invalidProfile)
                fail("Expected validation error")
            } catch (e: Exception) {
                // Expected
            }

            // Old file must be untouched
            assertEquals("Original profile must be preserved when save fails", originalContent, profileFile.readText())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testRejectDuplicateAccountIds() {
        val rootDir = Files.createTempDirectory("test_dup_ids").toFile()
        try {
            val accountsDir = File(rootDir, "accounts").apply { mkdirs() }
            val indexFile = File(accountsDir, "index.json")
            val dupIndex = """
                {
                  "selectedId": "${AccountStore.SWEAT_ID}",
                  "accounts": [
                    {
                      "id": "${AccountStore.SWEAT_ID}",
                      "villageName": "Sweat",
                      "supercellName": "Philly0105",
                      "playerTag": "#8YQCYG2RU"
                    },
                    {
                      "id": "${AccountStore.SWEAT_ID}",
                      "villageName": "Sweat2",
                      "supercellName": "Philly0105",
                      "playerTag": "#DIFFERENT"
                    }
                  ]
                }
            """.trimIndent()
            indexFile.writeText(dupIndex)
            val store = AccountStore(rootDir)
            try {
                store.load()
                fail("Expected IllegalStateException for duplicate account IDs")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Duplicate account id"))
            }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testRejectDuplicatePlayerTags() {
        val rootDir = Files.createTempDirectory("test_dup_tags").toFile()
        try {
            val accountsDir = File(rootDir, "accounts").apply { mkdirs() }
            val indexFile = File(accountsDir, "index.json")
            val dupIndex = """
                {
                  "selectedId": "${AccountStore.SWEAT_ID}",
                  "accounts": [
                    {
                      "id": "${AccountStore.SWEAT_ID}",
                      "villageName": "Sweat",
                      "supercellName": "Philly0105",
                      "playerTag": "#8YQCYG2RU"
                    },
                    {
                      "id": "${AccountStore.BLOOD_ID}",
                      "villageName": "Blood",
                      "supercellName": "VastMaNiSh",
                      "playerTag": "#8YQCYG2RU"
                    }
                  ]
                }
            """.trimIndent()
            indexFile.writeText(dupIndex)
            val store = AccountStore(rootDir)
            try {
                store.load()
                fail("Expected IllegalStateException for duplicate player tags")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Duplicate player tag"))
            }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testCorruptedIndexFailsProfileFile() {
        val rootDir = Files.createTempDirectory("test_corrupted_index_profile_file").toFile()
        try {
            val accountsDir = File(rootDir, "accounts").apply { mkdirs() }
            val indexFile = File(accountsDir, "index.json")
            indexFile.writeText("{ corrupted json")
            val store = AccountStore(rootDir)
            try {
                store.profileFile(AccountStore.SWEAT_ID)
                fail("Expected IllegalStateException when index is corrupted")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Malformed index JSON"))
            }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun testAccountLastResultSerialization() {
        val rootDir = Files.createTempDirectory("test_last_result").toFile()
        try {
            val store = AccountStore(rootDir)
            val accounts = store.ensureMigrated()
            val sweat = accounts.first { it.id == AccountStore.SWEAT_ID }

            val result = AccountLastResult(
                timestamp = 123456789L,
                attacksCompleted = 10,
                attackLimit = 10,
                purchaseOutcomeUnknown = true,
                unknownPurchaseCurrency = "gold",
                unknownPurchaseCost = 4_000_000L,
                unknownPurchaseWalls = 1,
                status = "Complete"
            )
            store.update(sweat.copy(lastResult = result))

            val reloaded = store.load().first { it.id == AccountStore.SWEAT_ID }
            assertNotNull(reloaded.lastResult)
            assertEquals(123456789L, reloaded.lastResult!!.timestamp)
            assertEquals(10, reloaded.lastResult!!.attacksCompleted)
            assertEquals(10, reloaded.lastResult!!.attackLimit)
            assertEquals("Complete", reloaded.lastResult!!.status)
            assertTrue(reloaded.lastResult!!.purchaseOutcomeUnknown)
            assertEquals("gold", reloaded.lastResult!!.unknownPurchaseCurrency)
            assertEquals(4_000_000L, reloaded.lastResult!!.unknownPurchaseCost)
            assertEquals(1, reloaded.lastResult!!.unknownPurchaseWalls)
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
