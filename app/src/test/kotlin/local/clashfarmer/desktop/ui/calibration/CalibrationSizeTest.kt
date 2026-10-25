package local.clashfarmer.desktop.ui.calibration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.CollectionTarget
import local.clashfarmer.core.Point
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.SurfaceVerification
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.desktop.ui.DashboardHost
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

class CalibrationSizeTest {
    private val filesDir = Files.createTempDirectory("calibration-size").toFile()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    /** What the calibration screen said, oldest first. */
    private val toasts = CopyOnWriteArrayList<String>()

    private val host = object : DashboardHost {
        override val scope: CoroutineScope get() = testScope
        override val filesDir: File get() = this@CalibrationSizeTest.filesDir
        override val windowStatus = MutableStateFlow(WindowStatus(checked = true))
        override fun act(action: String) {}
        override fun refresh() {}
        override fun openTab(tab: Tab) {}
        override fun openCalibration(accountId: String?) {}
        override fun inspection(block: suspend () -> Unit): Boolean = false
        override fun toast(text: String, long: Boolean) {
            toasts += text
        }
    }

    @After
    fun stop() {
        testScope.cancel()
        filesDir.deleteRecursively()
    }

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
    fun `a frame at the same size makes a shrunk game area the whole frame again`() {
        // A stray Apply on a corner in an earlier build left the game area inset but PC-shaped.
        val inset = calibrated.copy(viewport = Viewport(12, 40, 1740, 978))
        val fit = refit(inset, 1740, 978)
        assertEquals(Viewport(0, 0, 1740, 978), fit.profile.viewport)
        assertFalse(fit.profile.isVerified)
        // The points and collectors stay where they are: nothing moved.
        assertEquals(inset.stateAnchors, fit.profile.stateAnchors)
        assertEquals(inset.collectionTargets, fit.profile.collectionTargets)
        assertTrue(fit.note!!, fit.note!!.contains("whole screenshot"))
        // A phone-shaped frame keeps its corners, which can be picked there.
        val phone = PhoneProfile.createDefault().let { it.copy(viewport = Viewport(10, 10, it.resolutionWidth, it.resolutionHeight)) }
        assertSame(phone, refit(phone, phone.resolutionWidth, phone.resolutionHeight).profile)
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
        assertTrue(note, note.contains("no points can be marked on it"))
        assertTrue(note, note.endsWith("press Retake camera reference."))
    }

    @Test
    fun `a point clicked on a saved screenshot of another size is not applied`() {
        // A capture at 16:10 wrote frozen.png at 1860x1162 and its move was never saved, so the
        // screen opens again with that screenshot over the saved 1740x978 calibration.
        blood(calibrated, "calibration/frozen.png", 1860, 1162)
        val model = open()
        assertEquals(1860 to 1162, model.frame!!.let { it.width to it.height })
        model.pickTarget(CALIBRATION_TARGETS.indexOfFirst { it.name == "attack" })
        model.click(Point(261, 820))
        model.applyPoint()
        assertEquals(calibrated.stateAnchors, model.profile.stateAnchors)
        assertFalse(model.unsaved)
        val note = toasts.last()
        assertTrue(note, note.contains("taken at 1860x1162") && note.contains("1740x978"))
        assertTrue(note, note.endsWith("Press Capture game screen first."))

        // At the calibration's own size the same click is set.
        blood(calibrated, "calibration/frozen.png", 1740, 978)
        val same = open()
        same.pickTarget(CALIBRATION_TARGETS.indexOfFirst { it.name == "attack" })
        same.click(Point(261, 820))
        same.applyPoint()
        assertEquals(Point(261, 820), same.profile.stateAnchors["attack"])
    }

    @Test
    fun `a camera reference of another size takes no points`() {
        // The calibration was moved to 16:10 and saved; the reference is still the 1740x978 one.
        val moved = refit(calibrated, 1860, 1162).profile
        blood(moved, "wall_camera.png", 1740, 978)
        val model = open()
        model.showCameraReference()
        settle(model)
        assertEquals(1740 to 978, model.frame!!.let { it.width to it.height })
        assertEquals(referenceMismatch(1740, 978, moved), toasts.last())
        model.pickTarget(CALIBRATION_TARGETS.indexOfFirst { it.name == "attack" })
        model.click(Point(96, 885))
        model.applyPoint()
        assertEquals(moved.stateAnchors, model.profile.stateAnchors)
        assertTrue(toasts.last(), toasts.last().contains("taken at 1740x978"))
        // Nor a collector.
        model.pickTarget(CALIBRATION_TARGETS.indexOfFirst { it.name == COLLECTOR })
        model.click(Point(900, 500))
        model.applyPoint()
        assertTrue(model.profile.collectionTargets.isEmpty())
    }

    /** Blood with [saved] as its calibration and a black PNG of [width]x[height] at [path] in its folder. */
    private fun blood(saved: PhoneProfile, path: String, width: Int, height: Int) {
        val store = AccountStore(filesDir)
        store.ensureMigrated()
        store.saveProfile(AccountStore.BLOOD_ID, saved)
        val file = File(store.profileFile(AccountStore.BLOOD_ID).parentFile, path)
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file)
    }

    /** Blood's calibration screen once it has read what is saved. */
    private fun open(): CalibrationModel = CalibrationModel(host, AccountStore.BLOOD_ID, onBack = {}).also { settle(it) }

    /** Waits until [model] has read its files and finished what it was doing. */
    private fun settle(model: CalibrationModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!model.loaded || model.working) {
            assertTrue("the calibration screen is still busy", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}
