package local.clashfarmer.desktop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import local.clashfarmer.accounts.AccountIdentityParser
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.accounts.AccountSwitcher
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Viewport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account check on the PC's frames. The profile menu and its Settings tab were read on Blood
 * at 1740x978 (audit-evidence/live/20261024-134805-profile2); `pc-frames/profile` holds their OCR
 * and the identity strip's crops from the village and the profile tab.
 */
class PcAccountSwitcherTest {
    private val phone = Viewport(0, 0, 1600, 720)
    private val pc = Viewport(0, 0, 1740, 978)
    private val tall = Viewport(0, 0, 1860, 1162)

    @Test
    fun `the phone's display and a PC game surface of any size are supported`() {
        assertTrue(AccountSwitcher.supportedDisplay(phone))
        assertTrue(AccountSwitcher.supportedDisplay(pc))
        assertTrue(AccountSwitcher.supportedDisplay(tall))
        assertTrue(AccountSwitcher.supportedDisplay(Viewport(0, 0, 1565, 978)))
        assertFalse(AccountSwitcher.supportedDisplay(Viewport(0, 0, 2400, 1080)))
        assertFalse(AccountSwitcher.supportedDisplay(Viewport(0, 0, 720, 1600)))
        assertFalse(AccountSwitcher.supportedDisplay(null))
    }

    @Test
    fun `the menu's buttons are the phone's on the phone and kept to their edge on the PC`() {
        fun profile(display: Viewport?) =
            AccountSwitcher.onDisplay(display, AccountSwitcher.PROFILE_BUTTON, AccountSwitcher.PC_PROFILE_BUTTON, Anchor.LEFT)
        fun tab(display: Viewport?) =
            AccountSwitcher.onDisplay(display, AccountSwitcher.SETTINGS_TAB, AccountSwitcher.PC_SETTINGS_TAB, Anchor.CENTRE)
        fun close(display: Viewport?) =
            AccountSwitcher.onDisplay(display, AccountSwitcher.SETTINGS_CLOSE_BTN, AccountSwitcher.PC_SETTINGS_CLOSE_BTN, Anchor.CENTRE)
        fun layoutClose(display: Viewport?) =
            AccountSwitcher.onDisplay(display, AccountSwitcher.LAYOUT_EDITOR_CLOSE, AccountSwitcher.PC_LAYOUT_EDITOR_CLOSE, Anchor.RIGHT)
        assertEquals(Point(100, 116), profile(phone))
        assertEquals(Point(1173, 59), tab(phone))
        assertEquals(Point(1281, 57), close(null))
        assertEquals(Point(77, 92), profile(pc))
        assertEquals(Point(1335, 113), tab(pc))
        assertEquals(Point(1470, 111), close(pc))
        assertEquals(Point(1676, 62), layoutClose(pc))
        assertEquals(Point(91, 109), profile(tall))
        assertEquals(Point(1482, 134), tab(tall))
        assertEquals(Point(1643, 132), close(tall))
        assertEquals(Point(1784, 74), layoutClose(tall))
    }

    @Test
    fun `the PC reads Blood's identity only, from the strip on its Settings tab`() {
        assertEquals(AccountSwitcher.SETTINGS_IDENTITY_REGION, AccountSwitcher.identityRegion(phone))
        assertEquals(setOf(AccountStore.SWEAT_ID, AccountStore.BLOOD_ID), AccountSwitcher.identityTemplates(phone).keys)
        assertEquals(Region(745, 171, 1020, 264), AccountSwitcher.identityRegion(pc))
        assertEquals(mapOf(AccountStore.BLOOD_ID to "identity/pc/blood.png"), AccountSwitcher.identityTemplates(pc))
        assertEquals(Region(781, 203, 1108, 314), AccountSwitcher.identityRegion(tall))
        // The strip holds the name and the tag, and not the Supercell ID button beside it.
        val settings = lines("settings-tab")
        val strip = AccountSwitcher.identityRegion(pc)
        val inside = settings.filter { (_, box) -> strip.contains((box.left + box.right) / 2, (box.top + box.bottom) / 2) }
        assertEquals(listOf("Blood", AccountStore.BLOOD_PLAYER_TAG), inside.map { it.first })
    }

    @Test
    fun `the village opens the menu, and the menu's Settings tab is pressed where it reads`() {
        val home = text("home")
        assertTrue(AccountIdentityParser.isVillageHome(home))
        assertFalse(AccountIdentityParser.isProfileMenu(home))
        val menu = lines("profile-tab")
        assertTrue(AccountIdentityParser.isProfileMenu(menu.joinToString("\n") { it.first }))
        val settings = menu.single { it.first == "Settings" }.second
        assertTrue(settings.contains(AccountSwitcher.onDisplay(pc, AccountSwitcher.SETTINGS_TAB, AccountSwitcher.PC_SETTINGS_TAB, Anchor.CENTRE)))
    }

    @Test
    fun `Blood's Settings strip matches its template, and the village and profile tab behind it do not`() {
        val template = png("/identity/pc/blood.png")
        val strip = AccountSwitcher.identityRegion(pc)
        assertEquals(0.0, AccountSwitcher.identityDifference(frame(pc, template, strip), strip, template)!!, 0.0)
        for (name in listOf("strip-village", "strip-profile-tab")) {
            val difference = AccountSwitcher.identityDifference(frame(pc, png("/pc-frames/profile/$name.png"), strip), strip, template)
            assertFalse("$name: $difference", AccountSwitcher.settingsPanelShowing(listOf(difference)))
        }
    }

    @Test
    fun `at another height the strip is scaled to the template before it is compared`() {
        val template = png("/identity/pc/blood.png")
        val strip = AccountSwitcher.identityRegion(tall)
        val blood = AccountSwitcher.identityDifference(frame(tall, template, strip), strip, template)
        assertTrue("$blood", AccountSwitcher.settingsPanelShowing(listOf(blood)))
        val village = AccountSwitcher.identityDifference(frame(tall, png("/pc-frames/profile/strip-village.png"), strip), strip, template)
        assertFalse("$village", AccountSwitcher.settingsPanelShowing(listOf(village)))
    }

    private fun png(name: String): Bitmap = javaClass.getResourceAsStream(name)!!.use { BitmapFactory.decodeStream(it)!! }

    /** A black frame of [display]'s size with [strip] drawn into [at], scaled to fit. */
    private fun frame(display: Viewport, strip: Bitmap, at: Region): Bitmap {
        val fitted = if (strip.width == at.width && strip.height == at.height) strip
            else Bitmap.createScaledBitmap(strip, at.width, at.height, true)
        val pixels = IntArray(at.width * at.height)
        fitted.getPixels(pixels, 0, at.width, 0, 0, at.width, at.height)
        return Bitmap.createBitmap(display.width, display.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, at.width, at.left, at.top, at.width, at.height)
        }
    }

    /** The OCR lines of `pc-frames/profile/[name].json`, read at 1740x978, as (text, box). */
    private fun lines(name: String): List<Pair<String, Region>> {
        val json = JSONObject(javaClass.getResourceAsStream("/pc-frames/profile/$name.json")!!.bufferedReader().readText())
        assertEquals(1740, json.getInt("width"))
        assertEquals(978, json.getInt("height"))
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            line.getString("text") to Region(box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    private fun text(name: String) = lines(name).joinToString("\n") { it.first }
}
