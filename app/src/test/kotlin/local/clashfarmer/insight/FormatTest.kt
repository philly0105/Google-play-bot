package local.clashfarmer.insight

import local.clashfarmer.core.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class FormatTest {
    private val utc = ZoneOffset.UTC
    private val at = Format.parseTime("2026-10-08T06:51:04Z")!!

    @Test
    fun amounts() {
        assertEquals("-", Format.amount(null))
        assertEquals("950", Format.amount(950))
        assertEquals("2.1k", Format.amount(2_100))
        assertEquals("412k", Format.amount(412_345))
        assertEquals("5.1M", Format.amount(5_100_000))
        assertEquals("29.0M", Format.amount(29_000_000))
        assertEquals("1.42B", Format.amount(1_420_000_000))
        assertEquals("10k", Format.amount(9_999))
        assertEquals("413k", Format.amount(412_999))
        assertEquals("1.0M", Format.amount(999_999))
        assertEquals("1.00B", Format.amount(999_999_999))
        assertEquals("-24.0M", Format.amount(-24_000_000))
        assertEquals("+412k", Format.signed(412_000))
        assertEquals("17.8M/h", Format.rate(17_800_000))
        assertEquals("-", Format.rate(null))
        assertEquals("29.0M / -", Format.ofCapacity(29_000_000, null))
    }

    @Test
    fun counts() {
        assertEquals("1 battle", Format.count(1, "battle"))
        assertEquals("12 battles", Format.count(12, "battle"))
        assertEquals("12 of 40 attacks", Format.attacks(12, 40))
        assertEquals("12 attacks", Format.attacks(12, null))
        assertEquals(98, Format.percentOf(25_480_000, 26_000_000))
        assertNull(Format.percentOf(1, 0))
        assertEquals("Dark elixir", Format.resourceName(Resource.DARK_ELIXIR))
    }

    @Test
    fun durations() {
        assertEquals("1:12:05", Format.clock(4_325_000))
        assertEquals("0:42", Format.clock(42_000))
        assertEquals("1h04", Format.span(64 * 60_000L))
        assertEquals("42 min", Format.span(42 * 60_000L))
        assertEquals("9.5 h", Format.hours(34_200_000L))
        assertEquals("31 h", Format.hours(31 * 3_600_000L))
        assertEquals("2 h", Format.minutes(120))
        assertEquals("1 h 30 min", Format.minutes(90))
    }

    @Test
    fun times() {
        assertEquals("06:51", Format.time(at, utc))
        assertEquals("Oct 8 06:51", Format.dayTime(at, utc))
        assertEquals("06:51-07:40", Format.timeRange(at, at + 49 * 60_000L, utc))
        assertEquals("06:51-now", Format.timeRange(at, null, utc))
        assertEquals("2 s ago", Format.ago(at, at + 2_000))
        assertEquals("6 min ago", Format.ago(at, at + 6 * 60_000L))
        assertEquals("2 h ago", Format.ago(at, at + 2 * 3_600_000L))
        assertEquals("just now", Format.ago(at, at - 5_000))
        assertNull(Format.parseTime("yesterday"))
    }

    @Test
    fun lootText() {
        assertEquals(mapOf(Resource.GOLD to 681488L, Resource.ELIXIR to 372373L, Resource.DARK_ELIXIR to 9060L),
            Format.parseLoot("gold=681488,elixir=372373,dark_elixir=9060"))
        assertTrue(Format.parseLoot("").isEmpty())
        assertEquals(mapOf(Resource.GOLD to 5L), Format.parseLoot("gold=5,gems=9,elixir=x"))
    }
}
