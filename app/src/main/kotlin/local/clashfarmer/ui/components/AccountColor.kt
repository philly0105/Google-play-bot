package local.clashfarmer.ui.components

import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.ui.FarmerTheme

/**
 * The chart colour of an account. Sweat is always acctA and Blood always acctB; any other id gets
 * a colour from its own text, so the same id draws the same colour on every screen and every run.
 */
object AccountColor {
    private val extra = intArrayOf(
        0xFF7BE0C8.toInt(), // teal
        0xFFE0D25E.toInt(), // straw
        0xFFC48BFF.toInt(), // lilac
        0xFF8FB0FF.toInt()  // periwinkle
    )

    fun of(accountId: String): Int = when (accountId) {
        AccountStore.SWEAT_ID -> FarmerTheme.acctA
        AccountStore.BLOOD_ID -> FarmerTheme.acctB
        else -> extra[Math.floorMod(stableHash(accountId), extra.size)]
    }

    /** FNV-1a over the UTF-16 units: fixed by the text alone, unlike identity hash codes. */
    private fun stableHash(text: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (ch in text) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return hash
    }
}
