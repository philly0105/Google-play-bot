package local.clashfarmer.desktop.ui

import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.ui.FarmingSettingsEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountEditorInputTest {

    @Test
    fun `a typed letter is dropped`() {
        assertEquals("12", digitsTyped("12", "1a2"))
    }

    @Test
    fun `pasted digits keep only the digits`() {
        assertEquals("1000", digitsTyped("", "1,000"))
        assertEquals("25000", digitsTyped("", " 25 000 "))
    }

    @Test
    fun `text already in the box stays when a character is deleted`() {
        assertEquals("2.", digitsTyped("2.5", "2."))
        assertEquals("2.5", digitsTyped("2.5", "2.5"))
    }

    @Test
    fun `a selection replaced by a letter loses the selection only`() {
        assertEquals("13", digitsTyped("123", "1x3"))
        assertEquals("3", digitsTyped("2.5", "3"))
    }

    @Test
    fun `digits typed anywhere are kept`() {
        assertEquals("120", digitsTyped("10", "120"))
        assertEquals("9", digitsTyped("", "9"))
        assertEquals("", digitsTyped("5", ""))
    }

    @Test
    fun `the box order names every box once`() {
        val order = AccountEditorModel.BOX_ORDER
        assertEquals(order.size, order.toSet().size)
        val keys = FarmingSettingsEditor.textsOf(FarmingSettingsEditor.fieldsOf(AccountSettings())).keys
        assertEquals(keys, order.toSet())
    }

    @Test
    fun `the box order follows the sections down the sheet`() {
        val sections = AccountEditorModel.BOX_ORDER.map { key ->
            assertNotNull("no section shows $key", FarmingSettingsEditor.sectionOf(key))
            FarmingSettingsEditor.sectionOf(key)!!.ordinal
        }
        assertTrue(sections.zipWithNext().all { (a, b) -> a <= b })
    }
}
