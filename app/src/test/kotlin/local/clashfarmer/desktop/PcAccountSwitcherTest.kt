package local.clashfarmer.desktop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import local.clashfarmer.accounts.AccountIdentityParser
import local.clashfarmer.accounts.AccountSwitcher
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.accounts.SettingsIdentity
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.RecognizedBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The account check on the PC's frames. The profile menu and its Settings tab were read on Blood
 * at 1740x978 (audit-evidence/live/20261024-134805-profile2); `pc-frames/profile` holds their OCR
 * and the identity strip's crops from the village and the profile tab, and `identity/pc/blood.png`
 * the Settings strip. The Settings tab and village at 1860x1162 (20261025-034323-profile3) give
 * `identity/pc/blood-1860x1162.png` and `pc-frames/profile/strip-village-1860x1162.png`.
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
    fun `the PC reads the identity from the strip on its Settings tab`() {
        assertEquals(AccountSwitcher.SETTINGS_IDENTITY_REGION, AccountSwitcher.identityRegion(phone))
        assertEquals(Region(745, 171, 1020, 264), AccountSwitcher.identityRegion(pc))
        assertEquals(Region(781, 203, 1108, 314), AccountSwitcher.identityRegion(tall))
        // The strip holds the name and the tag, and not the Supercell ID button beside it.
        val settings = lines("settings-tab")
        val strip = AccountSwitcher.identityRegion(pc)
        val inside = settings.filter { (_, box) -> strip.contains((box.left + box.right) / 2, (box.top + box.bottom) / 2) }
        assertEquals(2, inside.size)
        assertEquals("Blood", inside[0].first)
        assertTrue(inside[1].first.startsWith("#"))
        // What enrolment reads off it: the name, and the tag as read.
        val read = PcAccount.readIdentity(inside.map { (text, box) -> RecognizedBox(text, box.left, box.top, box.right, box.bottom) })
        assertEquals(SettingsIdentity("Blood", inside[1].first), read)
    }

    @Test
    fun `the Settings tab is known by its words, and the profile tab and village are not it`() {
        assertTrue(AccountSwitcher.settingsTabShowing(text("settings-tab")))
        assertFalse(AccountSwitcher.settingsTabShowing(text("profile-tab")))
        assertFalse(AccountSwitcher.settingsTabShowing(text("home")))
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
    fun `the real Settings strips at 1740x978 and 1860x1162 match each other, both ways`() {
        // Cut from real captures: audit-evidence/live/20261024-134805-profile2/02-settings.png and
        // 20261025-034323-profile3/01-menu.png. Scaling blurs letters, so these differ by about 11.
        val small = png("/identity/pc/blood.png")
        val large = png("/identity/pc/blood-1860x1162.png")
        val smallStrip = AccountSwitcher.identityRegion(pc)
        val largeStrip = AccountSwitcher.identityRegion(tall)
        assertEquals(largeStrip.width, large.width)
        assertEquals(largeStrip.height, large.height)
        val onLarge = frame(tall, large, largeStrip)
        val onSmall = frame(pc, small, smallStrip)
        // The template cut at 16:9 on Clash at 16:10, and the other way round.
        val down = AccountSwitcher.identityDifference(onLarge, largeStrip, small)!!
        val up = AccountSwitcher.identityDifference(onSmall, smallStrip, large)!!
        assertTrue("$down", down > AccountSwitcher.MAX_IDENTITY_DIFFERENCE)
        assertTrue("$up", up > AccountSwitcher.MAX_IDENTITY_DIFFERENCE)
        assertTrue("$down", AccountSwitcher.identityMatches(onLarge, largeStrip, small))
        assertTrue("$up", AccountSwitcher.identityMatches(onSmall, smallStrip, large))
        assertEquals(AccountSwitcher.MAX_CROSS_SCALE_IDENTITY_DIFFERENCE, AccountSwitcher.identityLimit(largeStrip, small), 0.0)
        // At its own size each keeps the old limit.
        assertEquals(AccountSwitcher.MAX_IDENTITY_DIFFERENCE, AccountSwitcher.identityLimit(largeStrip, large), 0.0)
        assertTrue(AccountSwitcher.identityMatches(onLarge, largeStrip, large))
    }

    @Test
    fun `across sizes the village behind the strip still does not match`() {
        val small = png("/identity/pc/blood.png")
        val large = png("/identity/pc/blood-1860x1162.png")
        val largeStrip = AccountSwitcher.identityRegion(tall)
        val smallStrip = AccountSwitcher.identityRegion(pc)
        // The real 16:10 village under the strip (20261025-034323-profile3/00-home.png).
        val village = frame(tall, png("/pc-frames/profile/strip-village-1860x1162.png"), largeStrip)
        assertFalse(AccountSwitcher.identityMatches(village, largeStrip, small))
        assertFalse(AccountSwitcher.identityMatches(village, largeStrip, large))
        for (name in listOf("strip-village", "strip-profile-tab")) {
            val other = frame(pc, png("/pc-frames/profile/$name.png"), smallStrip)
            assertFalse(name, AccountSwitcher.identityMatches(other, smallStrip, large))
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

    @Test
    fun `a template read at 1740x978 matches its strip there and at 16 by 10`() {
        val dir = Files.createTempDirectory("pc-identity").toFile()
        try {
            val native = AccountSwitcher.identityRegion(pc)
            PcAccount.saveTemplate(dir, PcAccount.ID, frame(pc, png("/identity/pc/blood.png"), native), native)
            val template = PcAccount.loadTemplate(dir, PcAccount.ID)!!
            assertEquals(pc, template.frame)
            assertMatches(template.bitmap, pc)
            assertMatches(template.bitmap, tall)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `a template read at 16 by 10 matches its strip there and at 1740x978`() {
        val dir = Files.createTempDirectory("pc-identity").toFile()
        try {
            val strip = AccountSwitcher.identityRegion(tall)
            PcAccount.saveTemplate(dir, PcAccount.ID, frame(tall, png("/identity/pc/blood.png"), strip), strip)
            val template = PcAccount.loadTemplate(dir, PcAccount.ID)!!
            assertEquals(tall, template.frame)
            assertEquals(strip.width, template.bitmap.width)
            assertEquals(strip.height, template.bitmap.height)
            assertMatches(template.bitmap, tall)
            // Larger than the 1740x978 strip, so the template is scaled down to it.
            assertMatches(template.bitmap, pc)
        } finally { dir.deleteRecursively() }
    }

    /** [template] matches Blood's strip on a frame of [display]'s size, and the village and profile tab there do not. */
    private fun assertMatches(template: Bitmap, display: Viewport) {
        val strip = AccountSwitcher.identityRegion(display)
        assertTrue("$display", AccountSwitcher.identityMatches(frame(display, png("/identity/pc/blood.png"), strip), strip, template))
        for (name in listOf("strip-village", "strip-profile-tab")) {
            val other = frame(display, png("/pc-frames/profile/$name.png"), strip)
            assertFalse("$display $name", AccountSwitcher.identityMatches(other, strip, template))
        }
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
