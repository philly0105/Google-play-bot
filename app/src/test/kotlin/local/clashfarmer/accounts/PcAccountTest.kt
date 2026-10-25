package local.clashfarmer.accounts

import android.graphics.Bitmap
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.Region
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.RecognizedBox
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The PC's one account: the fresh install's seeding, the identity template's files, and the read of Settings. */
class PcAccountTest {
    private val dir = Files.createTempDirectory("pc-account").toFile()
    private val index = File(dir, "accounts/index.json")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a fresh install gets one neutral account, and the phone's seeding never runs`() {
        assertTrue(PcAccount.seedIfFresh(dir))
        val text = index.readText()
        // Nothing of the owner's villages: no village name, and no tag but the placeholder.
        assertFalse(text.contains("Blood"))
        assertFalse(text.contains("Sweat"))
        assertEquals(listOf(PcAccount.FRESH_PLAYER_TAG), Regex("#[0-9A-Za-z]+").findAll(text).map { it.value }.toList())
        val json = JSONObject(text)
        assertEquals(PcAccount.ID, json.getString("selectedId"))

        val accounts = AccountStore(dir).ensureMigrated()
        assertEquals(1, accounts.size)
        val account = accounts.single()
        assertEquals(PcAccount.ID, account.id)
        assertEquals(PcAccount.FRESH_VILLAGE_NAME, account.villageName)
        assertEquals(PcAccount.FRESH_SUPERCELL_NAME, account.supercellName)
        assertEquals(PcAccount.FRESH_PLAYER_TAG, account.playerTag)
        assertFalse(account.settings.wallsEnabled)
        assertEquals(PcAccount.ID, AccountStore(dir).selectedId())
        assertFalse(PcAccount.enrolled(dir, PcAccount.ID))
    }

    @Test
    fun `an existing index is left byte for byte`() {
        AccountStore(dir).ensureMigrated()
        val before = index.readBytes()
        assertFalse(PcAccount.seedIfFresh(dir))
        assertArrayEquals(before, index.readBytes())
        assertEquals(2, AccountStore(dir).load().size)
    }

    @Test
    fun `a fresh install's presets build no walls and run the one account`() {
        assertTrue(PcAccount.seedPresetsIfFresh(dir))
        val presets = PresetStore(dir).presets()
        assertEquals(listOf("Farm 40", "2 h"), presets.map { it.name })
        assertTrue(presets.none { it.walls })
        assertTrue(presets.all { it.accountIds == listOf(RunPlan.SELECTED) })
        assertEquals(PresetStore.SEEDS[0].length, presets[0].length)
        assertEquals(PresetStore.SEEDS[1].length, presets[1].length)
        assertNull(PresetStore(dir).last())
        assertFalse(PcAccount.seedPresetsIfFresh(dir))
    }

    @Test
    fun `existing presets are left byte for byte`() {
        val file = File(dir, "presets.json")
        PresetStore(dir).presets()
        val before = file.readBytes()
        assertFalse(PcAccount.seedPresetsIfFresh(dir))
        assertArrayEquals(before, file.readBytes())
        assertEquals(PresetStore.SEEDS, PresetStore(dir).presets())
    }

    @Test
    fun `the read name and tag are saved over the neutral record`() {
        PcAccount.seedIfFresh(dir)
        val store = AccountStore(dir)
        store.update(store.load().single().copy(villageName = "Farmland", playerTag = "#TEST2468"))
        val read = AccountStore(dir).load().single()
        assertEquals("Farmland", read.villageName)
        assertEquals("#TEST2468", read.playerTag)
        assertEquals(PcAccount.ID, AccountStore(dir).selectedId())
    }

    @Test
    fun `a template is cut at its own size and kept with its frame size`() {
        val screen = Bitmap.createBitmap(IntArray(1860 * 1162) { 0xFF000000.toInt() or (it % 251) }, 1860, 1162, Bitmap.Config.ARGB_8888)
        val region = Region(781, 203, 1108, 314)
        PcAccount.saveTemplate(dir, PcAccount.ID, screen, region)
        assertTrue(PcAccount.enrolled(dir, PcAccount.ID))
        val template = PcAccount.loadTemplate(dir, PcAccount.ID)!!
        assertEquals(Viewport(0, 0, 1860, 1162), template.frame)
        assertEquals(region.width, template.bitmap.width)
        assertEquals(region.height, template.bitmap.height)
        assertEquals(screen.getPixel(region.left + 5, region.top + 7), template.bitmap.getPixel(5, 7))
        val frame = JSONObject(PcAccount.templateFrameFile(dir, PcAccount.ID).readText())
        assertEquals(1860, frame.getInt("frameWidth"))
        assertEquals(1162, frame.getInt("frameHeight"))
        // No temporary files are left beside them.
        assertEquals(setOf("identity.png", "identity.json"), PcAccount.templateFile(dir, PcAccount.ID).parentFile.list()!!.toSet())
    }

    @Test
    fun `a template with no frame file was cut at 1740x978, and none at all means not read`() {
        assertNull(PcAccount.loadTemplate(dir, PcAccount.ID))
        val screen = Bitmap.createBitmap(1740, 978, Bitmap.Config.ARGB_8888)
        PcAccount.saveTemplate(dir, PcAccount.ID, screen, Region(745, 171, 1020, 264))
        assertTrue(PcAccount.templateFrameFile(dir, PcAccount.ID).delete())
        assertEquals(PcAccount.DEFAULT_TEMPLATE_FRAME, PcAccount.loadTemplate(dir, PcAccount.ID)!!.frame)
        PcAccount.templateFrameFile(dir, PcAccount.ID).writeText("not json")
        assertEquals(Viewport(0, 0, 1740, 978), PcAccount.loadTemplate(dir, PcAccount.ID)!!.frame)
    }

    @Test
    fun `a template that is not an image counts as not read, and the next read writes over it`() {
        PcAccount.templateFile(dir, PcAccount.ID).apply { parentFile.mkdirs() }.writeText("not a png")
        assertNull(PcAccount.loadTemplate(dir, PcAccount.ID))
        assertFalse(PcAccount.enrolled(dir, PcAccount.ID))
        PcAccount.saveTemplate(dir, PcAccount.ID, Bitmap.createBitmap(1740, 978, Bitmap.Config.ARGB_8888), Region(745, 171, 1020, 264))
        assertTrue(PcAccount.enrolled(dir, PcAccount.ID))
    }

    @Test
    fun `a read the check did not confirm is undone, record and template`() {
        PcAccount.seedIfFresh(dir)
        val store = AccountStore(dir)
        val before = store.load().single()
        // What enrolment writes: the record with the name and tag read, then the template.
        store.update(before.copy(villageName = "Farmlnad", playerTag = "#TEST2468"))
        PcAccount.saveTemplate(dir, PcAccount.ID, Bitmap.createBitmap(1740, 978, Bitmap.Config.ARGB_8888), Region(745, 171, 1020, 264))
        assertTrue(PcAccount.undoRead(dir, before))
        assertFalse(PcAccount.enrolled(dir, PcAccount.ID))
        assertEquals(before, AccountStore(dir).load().single())
        // Nothing left to undo, as when the read failed before writing anything.
        assertFalse(PcAccount.undoRead(dir, before))
    }

    @Test
    fun `a read that stopped after the record and before the template is undone too`() {
        PcAccount.seedIfFresh(dir)
        val store = AccountStore(dir)
        val before = store.load().single()
        store.update(before.copy(villageName = "Farmland", playerTag = "#TEST2468"))
        assertTrue(PcAccount.undoRead(dir, before))
        assertEquals(PcAccount.FRESH_VILLAGE_NAME, AccountStore(dir).load().single().villageName)
    }

    @Test
    fun `forgetting the template marks the account not read and leaves its other files`() {
        PcAccount.seedIfFresh(dir)
        PcAccount.saveTemplate(dir, PcAccount.ID, Bitmap.createBitmap(1740, 978, Bitmap.Config.ARGB_8888), Region(745, 171, 1020, 264))
        val profile = File(PcAccount.templateFile(dir, PcAccount.ID).parentFile, "profile.json").apply { writeText("{}") }
        PcAccount.forget(dir, PcAccount.ID)
        assertFalse(PcAccount.enrolled(dir, PcAccount.ID))
        assertFalse(PcAccount.templateFile(dir, PcAccount.ID).exists())
        assertFalse(PcAccount.templateFrameFile(dir, PcAccount.ID).exists())
        assertTrue(profile.exists())
        assertEquals(1, AccountStore(dir).load().size)
        // Forgetting an account never read does nothing.
        PcAccount.forget(dir, PcAccount.ID)
    }

    @Test
    fun `the Settings strip gives the name above the tag`() {
        val read = PcAccount.readIdentity(listOf(box("#TEST2468", 37), box("Farmland", 2)))
        assertEquals(SettingsIdentity("Farmland", "#TEST2468"), read)
    }

    @Test
    fun `a village name with digits is not taken for the tag`() {
        // The parser alone takes the first 8 to 15 letters and digits with a digit in them as the tag.
        val boxes = listOf(box("Farm2024x", 2), box("#TEST2468", 37))
        assertEquals("#FARM2024X", AccountIdentityParser.parseSettingsIdentity(boxes)?.playerTag?.uppercase())
        assertEquals(SettingsIdentity("Farm2024x", "#TEST2468"), PcAccount.readIdentity(boxes))
    }

    @Test
    fun `OCR's spaces leave the tag, and a tag read without its hash still parses`() {
        assertEquals(SettingsIdentity("Farmland", "#TEST2468"), PcAccount.readIdentity(listOf(box("Farmland", 2), box("# TEST 2468", 37))))
        assertEquals(SettingsIdentity("Farmland", "#TEST2468"), PcAccount.readIdentity(listOf(box("Farmland", 2), box("TEST2468", 37))))
    }

    @Test
    fun `a strip without both lines reads as nothing`() {
        assertNull(PcAccount.readIdentity(emptyList()))
        assertNull(PcAccount.readIdentity(listOf(box("Farmland", 2))))
        assertNull(PcAccount.readIdentity(listOf(box("#TEST2468", 37))))
        assertNull(PcAccount.readIdentity(listOf(box("Farmland", 2), box("#TE?!", 37))))
    }

    @Test
    fun `the refusals name no village of the owner's and say what to do`() {
        val texts = listOf(PcAccount.ENROL_FIRST, PcAccount.IDENTITY_UNREADABLE, PcAccount.NOT_THE_READ_VILLAGE,
            PcAccount.otherAccountRefused("Second village"))
        for (text in texts) {
            assertFalse(text, text.contains("Blood"))
            assertFalse(text, Regex("phone|android|accessibility", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        assertEquals("Run one supervised attack first so the bot can read your account.", PcAccount.ENROL_FIRST)
        assertEquals("Clash Farmer runs one account on this PC, and Second village is not it.",
            PcAccount.otherAccountRefused("Second village"))
    }

    @Test
    fun `the PC's account keeps the slot the owner's files are in`() {
        assertEquals(AccountStore.BLOOD_ID, PcAccount.ID)
    }

    @Test
    fun `the language row reads English as OCR gives it, and stops on another language`() {
        // Where the Settings tab at 1740x978 has the row (pc-frames, 24 October).
        fun row(value: String) = listOf(
            RecognizedBox("Music", 597, 309, 685, 348),
            RecognizedBox("LaNguage", 805, 472, 944, 510),
            RecognizedBox(".", 65, 488, 113, 517),
            RecognizedBox(value, 826, 522, 927, 559),
            RecognizedBox("Consent Choices", 1184, 605, 1359, 638)
        )
        assertEquals(true, PcAccount.englishLanguage(row("ENglisH")))
        assertEquals(true, PcAccount.englishLanguage(row("EN9lisH")))
        for (other in listOf("Deutsch", "Français", "Español", "Nederlands", "Svenska", "Italiano", "Polski")) {
            assertEquals(other, false, PcAccount.englishLanguage(row(other)))
        }
        // Not read yet: no label, or nothing under it.
        assertNull(PcAccount.englishLanguage(row("ENglisH").filter { it.text != "LaNguage" }))
        assertNull(PcAccount.englishLanguage(row("ENglisH").filter { it.text != "ENglisH" }))
        assertNull(PcAccount.englishLanguage(emptyList()))
    }

    @Test
    fun `the language and identity refusals say what to do in Clash`() {
        assertEquals("Set Clash's language to English (Settings > Language). The bot reads English text.", PcAccount.NOT_ENGLISH)
        assertTrue(PcAccount.IDENTITY_UNREADABLE.contains("Supercell ID"))
        assertTrue(PcAccount.IDENTITY_UNREADABLE.endsWith("then run one supervised attack again."))
    }

    private fun box(text: String, top: Int) = RecognizedBox(text, 50, top, 250, top + 30)
}
