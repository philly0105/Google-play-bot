package local.clashfarmer.accounts

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.StopException
import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.*
import org.junit.Test

class AccountIdentityParserTest {
    private fun box(text: String, top: Int, bottom: Int = top + 25) =
        RecognizedBox(text, 800, top, 1020, bottom)

    @Test fun settingsRequiresExactNameAndTag() {
        val account = AccountRecord(AccountStore.SWEAT_ID, "Sweat", "Philly0105", "#8YQCYG2RU")
        val detected = AccountIdentityParser.parseSettingsIdentity(listOf(box("Sweat", 130), box("#8YQCYG2RU", 165)))
        assertEquals(SettingsIdentity("Sweat", "#8YQCYG2RU"), detected)
        assertTrue(AccountIdentityParser.verifySettingsIdentity(detected, account) is IdentityVerificationResult.Matched)
        assertTrue(AccountIdentityParser.verifySettingsIdentity(detected?.copy(playerTag = "#8YQCYG2RO"), account) is IdentityVerificationResult.Mismatch)
        assertTrue(AccountIdentityParser.verifySettingsIdentity(detected?.copy(villageName = "Blood"), account) is IdentityVerificationResult.Mismatch)
    }

    @Test fun villageNameIsNeverParsedAsTag() {
        assertNull(AccountIdentityParser.parseSettingsIdentity(listOf(box("Sweat", 130), box("unknown", 165))))
        assertEquals("#8YQCYG2RU", AccountIdentityParser.parseSettingsIdentity(listOf(box("Sweat", 130), box("8YQCYG2RU", 165)))?.playerTag)
    }

    @Test fun villageHomeFallbackRequiresAttackAndShopButRejectsBattle() {
        assertTrue(AccountIdentityParser.isVillageHome("Blood Attack! Collect Shop"))
        assertFalse(AccountIdentityParser.isVillageHome("Attack! Find a Match"))
        assertFalse(AccountIdentityParser.isVillageHome("End Battle Attack Shop"))
    }

    @Test fun villageHomeRejectsLoadingTipsAndCompletedAttacks() {
        assertFalse(AccountIdentityParser.isVillageHome(
            "Loading Building good-defeNses isjust as impoRtaNt as aggRessive attackiNg CLASH of CLANS"
        ))
        assertFalse(AccountIdentityParser.isVillageHome("Loading Attack with heroes"))
        assertFalse(AccountIdentityParser.isVillageHome("Attacks Completed! 4x Star Bonus"))
        assertTrue(AccountIdentityParser.isVillageHome("258 Blood, Attack! Collect"))
    }

    @Test fun pickerMatchesBothNamesRegardlessOfRowOrder() {
        val rows = AccountIdentityParser.parsePickerRows(listOf(
            box("VastMaNiSh", 300), box("Blood", 335), box("Town Hall 18", 370),
            box("Philly0105", 145), box("Sweat", 180), box("Town Hall 16", 215)
        ))
        val target = AccountRecord(AccountStore.BLOOD_ID, "Blood", "VastMaNiSh", "#Y2G29YJQ8")
        assertEquals("Blood", AccountIdentityParser.findPickerRow(rows, target).villageName)
    }

    @Test(expected = StopException::class)
    fun missingPickerRowStops() {
        val target = AccountRecord(AccountStore.BLOOD_ID, "Blood", "VastMaNiSh", "#Y2G29YJQ8")
        AccountIdentityParser.findPickerRow(emptyList(), target)
    }

    @Test(expected = StopException::class)
    fun duplicatePickerRowStops() {
        val target = AccountRecord(AccountStore.SWEAT_ID, "Sweat", "Philly0105", "#8YQCYG2RU")
        val row = PickerRow("Philly0105", "Sweat", Point(900, 180), Region(800, 140, 1100, 220))
        AccountIdentityParser.findPickerRow(listOf(row, row.copy(clickPoint = Point(900, 330))), target)
    }

    @Test fun welcomeNeedsSpecificTitleAndOkay() {
        assertTrue(AccountIdentityParser.isWelcomeBackDialog("Welcome Back Chief!"))
        assertFalse(AccountIdentityParser.isWelcomeBackDialog("Login failed"))
        assertNotNull(AccountIdentityParser.findOkayButton(listOf(box("Okay", 580))))
        assertNull(AccountIdentityParser.findOkayButton(listOf(box("Confirm", 580))))
    }

    @Test fun layoutEditorIsRecognisedThroughItsMisreadings() {
        // Read off the phone at 04:51 UTC on 29 September, when a gear tap opened the editor.
        assertTrue(AccountIdentityParser.isLayoutEditor(
            "Lavout EdtoR HOMe Village Edit laYOUt Active Village CopY laYDUt WaR Base Set as acive X "))
        assertTrue(AccountIdentityParser.isLayoutEditor("ut Editor War Base Edit layout Copy layout Set as active"))
        assertFalse(AccountIdentityParser.isLayoutEditor("Attack! Shop Research Upgrade"))
        assertFalse(AccountIdentityParser.isLayoutEditor("Settings Edit Layout"))
    }
}
