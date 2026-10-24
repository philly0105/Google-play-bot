package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.ui.components.AccountColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class AccountColorTest {
    @Test
    fun sweatAndBloodKeepTheirSeriesColours() {
        assertEquals(FarmerTheme.acctA, AccountColor.of(AccountStore.SWEAT_ID))
        assertEquals(FarmerTheme.acctB, AccountColor.of(AccountStore.BLOOD_ID))
        assertEquals(0xFF5EC8FF.toInt(), AccountColor.of(AccountStore.SWEAT_ID))
        assertEquals(0xFFFF8A5C.toInt(), AccountColor.of(AccountStore.BLOOD_ID))
    }

    @Test
    fun theTwoAccountsNeverShareAColour() {
        assertNotEquals(AccountColor.of(AccountStore.SWEAT_ID), AccountColor.of(AccountStore.BLOOD_ID))
    }

    @Test
    fun anyIdAlwaysGetsTheSameColour() {
        val ids = List(20) { UUID.nameUUIDFromBytes("account-$it".toByteArray()).toString() }
        val first = ids.map { AccountColor.of(it) }
        repeat(3) { assertEquals(first, ids.map { AccountColor.of(it) }) }
        // A fresh String with the same text is the same account.
        ids.forEach { assertEquals(AccountColor.of(it), AccountColor.of(String(it.toCharArray()))) }
    }

    @Test
    fun otherAccountsDoNotBorrowSweatOrBloodColours() {
        val ids = List(50) { UUID.nameUUIDFromBytes("other-$it".toByteArray()).toString() }
        ids.forEach {
            assertNotEquals(FarmerTheme.acctA, AccountColor.of(it))
            assertNotEquals(FarmerTheme.acctB, AccountColor.of(it))
        }
    }
}
