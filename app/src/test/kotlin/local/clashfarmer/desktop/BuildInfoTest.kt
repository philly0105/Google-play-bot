package local.clashfarmer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class BuildInfoTest {

    @Test
    fun `the name gives the version and the commit when there is one`() {
        assertEquals("Clash Farmer PC 1.1.0 (0b2db8e)", BuildInfo.name("1.1.0", "0b2db8e"))
        assertEquals("Clash Farmer PC 1.1.0", BuildInfo.name("1.1.0", null))
    }

    @Test
    fun `the about line names the build and where settings and logs are`() {
        val dir = File("C:\\Users\\someone\\AppData\\Local\\ClashFarmerPC\\files")
        assertEquals("Clash Farmer PC 1.1.0. Settings and logs are in ${dir.path}.", BuildInfo.about("1.1.0", null, dir))
    }

    @Test
    fun `the build stamps the version from gradle properties`() {
        val expected = File("../gradle.properties").readLines()
            .single { it.startsWith("appVersion=") }.substringAfter('=').trim()
        assertNotEquals("dev", BuildInfo.version)
        assertEquals(expected, BuildInfo.version)
    }
}
