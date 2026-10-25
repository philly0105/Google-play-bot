package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.core.CollectionTarget
import local.clashfarmer.core.Point
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.SurfaceVerification
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CalibrationSizeTest {

    /** A verified calibration at the owner's 1740x978, with Battle and Next and one collector. */
    private val calibrated = PhoneProfile.createDefault(1740, 978).copy(
        stateAnchors = mapOf("attack" to Point(96, 885), "next" to Point(1600, 800)),
        collectionTargets = listOf(CollectionTarget("collector 1", Point(900, 500))),
        isVerified = true
    )

    @Test
    fun `a frame at the calibration's size leaves it alone`() {
        val fit = refit(calibrated, 1740, 978)
        assertSame(calibrated, fit.profile)
        assertNull(fit.note)
    }

    @Test
    fun `a 16 by 10 frame moves the points the way a run does, unverified`() {
        val fit = refit(calibrated, 1860, 1162)
        val run = PcProfiles.derive(calibrated, SurfaceSize(1860, 1162), emptySet())
        assertEquals(run.copy(isVerified = false), fit.profile)
        assertFalse(fit.profile.isVerified)
        assertEquals(Viewport(0, 0, 1860, 1162), fit.profile.viewport)
        // Battle keeps its distance from the left edge; the phone's relabel left it at (96, 885).
        assertEquals(114, fit.profile.stateAnchors.getValue("attack").x)
        assertTrue(fit.profile.collectionTargets.isEmpty())
        assertNotNull(fit.note)
        val note = fit.note!!
        assertTrue(note, note.contains("moved from 1740x978 to 1860x1162"))
        assertTrue(note, note.contains("collectors were cleared"))
    }

    @Test
    fun `a capped frame of the same shape moves the points too`() {
        val fit = refit(calibrated.copy(collectionTargets = emptyList()), 1600, 899)
        assertEquals(1600, fit.profile.resolutionWidth)
        assertEquals(899, fit.profile.resolutionHeight)
        assertFalse(fit.profile.isVerified)
        assertFalse(fit.note!!.contains("collectors"))
    }

    @Test
    fun `an empty default takes the frame's size without a word`() {
        val fit = refit(PhoneProfile.createDefault(1740, 978), 1565, 978)
        assertEquals(PhoneProfile.createDefault(1565, 978), fit.profile)
        assertNull(fit.note)
        val phone = refit(PhoneProfile.createDefault(), 1740, 978)
        assertEquals(PhoneProfile.createDefault(1740, 978), phone.profile)
        assertNull(phone.note)
    }

    @Test
    fun `a profile that can't be moved is relabelled and the user told why`() {
        val phoneShaped = PhoneProfile.createDefault().copy(stateAnchors = mapOf("attack" to Point(80, 650)))
        val fit = refit(phoneShaped, 1740, 978)
        assertEquals(Point(80, 650), fit.profile.stateAnchors.getValue("attack"))
        assertEquals(Viewport(0, 0, 1740, 978), fit.profile.viewport)
        assertTrue(fit.note!!, fit.note!!.contains("Open Calibrate, capture the game, and save the points again"))
    }

    @Test
    fun `a frame too small to read says so and can't be saved`() {
        val fit = refit(calibrated, 1245, 700)
        assertEquals(1245, fit.profile.resolutionWidth)
        assertTrue(fit.note!!, fit.note!!.contains("too small"))
        assertEquals(fit.note, saveProblem(fit.profile))
        assertNull(saveProblem(calibrated))
    }

    @Test
    fun `save asks only when it replaces a saved calibration of another shape`() {
        val moved = refit(calibrated, 1565, 978).profile
        assertNull(saveQuestion(calibrated, moved, hasSaved = false))
        assertNull(saveQuestion(calibrated, refit(calibrated, 1600, 899).profile, hasSaved = true))
        assertNull(saveQuestion(calibrated, calibrated, hasSaved = true))
        val question = saveQuestion(calibrated, moved, hasSaved = true)!!
        assertTrue(question, question.contains("16:9"))
        assertTrue(question, question.contains("16:10"))
        assertTrue(question, question.contains("supervised attack"))
    }

    @Test
    fun `saving changed points forgets the verified shapes, saving collectors keeps them`() {
        val dir = Files.createTempDirectory("surfaces").toFile()
        try {
            val profileFile = dir.resolve("profile.json")
            SurfaceVerification.record(profileFile, 1860, 1162)
            SurfaceVerification.calibrationSaved(profileFile, calibrated)
            assertEquals(1, SurfaceVerification.aspects(profileFile).size)
            SurfaceVerification.calibrationSaved(profileFile, calibrated.copy(isVerified = false))
            assertTrue(SurfaceVerification.aspects(profileFile).isEmpty())
            assertFalse(SurfaceVerification.file(profileFile).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a saved camera reference of another size points to Retake`() {
        assertNull(referenceMismatch(1740, 978, calibrated))
        // A capture at 16:10 moved the profile being edited; nothing is saved yet.
        val moved = refit(calibrated, 1860, 1162).profile
        val note = referenceMismatch(1740, 978, moved)!!
        assertTrue(note, note.contains("taken at 1740x978") && note.contains("now 1860x1162"))
        assertTrue(note, note.endsWith("press Retake camera reference."))
    }
}
