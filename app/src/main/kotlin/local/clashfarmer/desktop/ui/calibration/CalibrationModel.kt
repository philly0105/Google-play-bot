package local.clashfarmer.desktop.ui.calibration

import android.graphics.Bitmap
import android.graphics.toBufferedImage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.CollectionPolicy
import local.clashfarmer.core.CollectionTarget
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.ui.DashboardHost
import local.clashfarmer.desktop.ui.NOT_RUNNING
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.FarmerService
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO

/**
 * The phone's CalibrationActivity without its views: the profile being edited, the frame it is
 * edited on, and the sidebar's actions. [CalibrationScreen] shows it in place of the tabs. The
 * controller makes one per visit and calls [close] when it ends; [onBack] asks it to end.
 *
 * Unlike the phone it never sets FarmerService.helperActivityVisible or the overlay's visibility,
 * and it reads and writes files on IO, with every button but Back off until that work is done.
 */
class CalibrationModel(private val host: DashboardHost, val accountId: String, private val onBack: () -> Unit) {

    private val job = SupervisorJob(host.scope.coroutineContext[Job])
    private val scope = CoroutineScope(host.scope.coroutineContext + job)
    private val accountStore = AccountStore(host.filesDir)

    /**
     * The profile being edited. Until the saved one is read, and for an account without one, it is
     * the default at the preferred size ([FarmerService.preferredSurface]) when the window watcher
     * has measured the surface (a 16:10 one, such as 1860x1162, keeps its own size), else at the
     * phone's 1600x720. A capture sets the frame's own size either way.
     */
    var profile: PhoneProfile by mutableStateOf(
        host.windowStatus.value.surface?.let { FarmerService.preferredSurface(it) }?.let { PhoneProfile.createDefault(it.width, it.height) }
            ?: PhoneProfile.createDefault()
    )
        private set
    /** The profile as loaded, so a save writes only what was changed here. */
    private var openedProfile: PhoneProfile = profile
    private var tempPoint: Point? by mutableStateOf(null)
    /** Collection targets are only taken on the camera reference itself, never on another frame. */
    private var frameIsCameraReference = false

    /** The frame on show, or null before the first capture. */
    var frame: ImageBitmap? by mutableStateOf(null)
        private set

    /** The card area saved by the last Apply, drawn on the frame until the next frame. */
    var selectedRegion: Region? by mutableStateOf(null)
        private set

    /** The last point clicked on the frame. It stays drawn after Apply and on a new frame, as the phone's did. */
    var marker: Point? by mutableStateOf(null)
        private set

    /** The target the list shows; Apply saves the clicked point for it. */
    var targetIndex by mutableStateOf(0)
        private set

    /** What the model is doing on IO or in the game, shown under the buttons; null when idle. */
    var task: String? by mutableStateOf(null)
        private set

    /** False until the saved profile and frame have been read. */
    var loaded by mutableStateOf(false)
        private set

    private var accountName: String? by mutableStateOf(null)
    private var referenceSaved by mutableStateOf(false)

    val target: CalibrationTarget get() = CALIBRATION_TARGETS[targetIndex]

    /** True while [task] runs: the sidebar's buttons, Back aside, are off. */
    val working: Boolean get() = task != null

    /** The phone's coordinate line, which follows the point Apply would save. */
    val coordText: String
        get() = tempPoint?.let { "Selected: (${it.x}, ${it.y})" } ?: "Selected: None (click the image)"

    /** The phone's status block. */
    val statusText: String
        get() {
            val p = profile
            val vp = p.viewport
            return "Account: ${accountName ?: accountId}\n" +
                "Res: ${p.resolutionWidth}x${p.resolutionHeight} | VP: [${vp.left}, ${vp.top}, ${vp.right}, ${vp.bottom}]\n" +
                "State Anchors: ${p.stateAnchors.size} | Troop Anchors: ${p.troopAnchors.size}\n" +
                "Perimeter: ${p.perimeterSegments.size} edges\n" +
                "Collectors: ${p.collectionTargets.size} | Camera reference: ${if (referenceSaved) "saved" else "none"}\n" +
                "Verified: ${p.isVerified} (earned locally via live attack)"
        }

    init {
        work(READING) { load() }
    }

    /** Ends the model's work: a capture still running is cancelled, as the phone's onDestroy did. */
    fun close() {
        job.cancel()
    }

    /** "Back to dashboard". */
    fun back() {
        onBack()
    }

    /** A click on the frame, in frame pixels. */
    fun click(point: Point) {
        marker = point
        tempPoint = point
    }

    /** Picks a target from the list. Picking the one already shown keeps the clicked point, as the Spinner did. */
    fun pickTarget(index: Int) {
        if (index == targetIndex || index !in CALIBRATION_TARGETS.indices) return
        targetIndex = index
        tempPoint = null
    }

    /** "Capture game screen": one frame of Clash, kept as the account's calibration/frozen.png. */
    fun capture() {
        if (working) return
        val service = FarmerService.instance
        if (service == null) {
            host.toast(NOT_RUNNING, long = true)
            return
        }

        // The overlay is hidden for capture; its status line says what is happening. The phone's
        // "Switching to Clash" is gone: the PC captures the window where it is.
        service.overlay?.updateStatus(CAPTURING)
        work(CAPTURING) {
            try {
                // The phone passed CalibrationActivity to come back to; the PC window never left.
                val bitmap = withContext(Dispatchers.IO) { service.captureClashGame().bitmap }
                try {
                    val image = withContext(Dispatchers.IO) { bitmap.toBufferedImage().toComposeImageBitmap() }

                    if (bitmap.width != profile.resolutionWidth || bitmap.height != profile.resolutionHeight) {
                        profile = profile.copy(
                            resolutionWidth = bitmap.width,
                            resolutionHeight = bitmap.height,
                            viewport = Viewport(0, 0, bitmap.width, bitmap.height),
                            isVerified = false
                        )
                    }

                    show(image, reference = false)

                    withContext(Dispatchers.IO) {
                        val calDir = accountStore.calibrationDir(accountId)
                        if (!calDir.exists()) calDir.mkdirs()
                        val frozenFile = File(calDir, "frozen.png")
                        FileOutputStream(frozenFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    host.toast("Captured real game screen (${bitmap.width}x${bitmap.height})")
                } finally {
                    bitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.toast("Capture failed: ${e.message}", long = true)
            }
        }
    }

    /**
     * Shows the frame collection targets are measured on. A saved reference is never replaced
     * here; Retake camera reference does that. Without one, the camera is normalised and the
     * village captured as the new reference.
     */
    fun showCameraReference() {
        if (working) return
        work(REFERENCE) { cameraReference() }
    }

    /** "Clear collectors": empties the list on the profile being edited; Save writes it. */
    fun clearCollectors() {
        if (working) return
        profile = profile.withCollectionTargets(emptyList())
    }

    /**
     * For a village that was rebuilt: the old reference is kept beside the new one under a dated
     * name, and the collectors measured on it are cleared on disk before it goes, not at Save.
     */
    fun retakeCameraReference() {
        if (working) return
        work(REFERENCE) {
            val cleared = try {
                withContext(Dispatchers.IO) { accountStore.clearCollectionTargets(accountId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.toast("Could not clear the saved collectors: ${e.message}", long = true)
                return@work
            }
            if (cleared != null) AppLog.log("collection_targets_cleared", "accountId" to accountId)
            openedProfile = openedProfile.withCollectionTargets(emptyList())
            val backup = try {
                withContext(Dispatchers.IO) {
                    val file = cameraReferenceFile()
                    if (!file.exists()) {
                        null
                    } else {
                        val backup = File(file.parentFile, "wall_camera-${System.currentTimeMillis()}.png")
                        if (!file.renameTo(backup)) throw IllegalStateException("rename failed")
                        backup
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                host.toast("Could not set the old camera reference aside", long = true)
                return@work
            }
            if (backup != null) AppLog.log("camera_reference_retired", "accountId" to accountId, "backup" to backup.name)
            profile = profile.withCollectionTargets(emptyList())
            frameIsCameraReference = false
            referenceSaved = false
            cameraReference()
        }
    }

    /** "Apply selected point": saves the clicked point for the target shown, then moves to the next one. */
    fun applyPoint() {
        if (working) return
        val pt = tempPoint
        if (pt == null) {
            host.toast("Click a point on the screenshot first")
            return
        }

        val idx = targetIndex
        val target = CALIBRATION_TARGETS[idx]

        if (target.name == COLLECTOR) {
            if (!frameIsCameraReference) {
                host.toast("Press Show camera reference first; collectors are measured on it", long = true)
                return
            }
            if (profile.collectionTargets.size >= CollectionPolicy.MAX_TARGETS) {
                host.toast("At most ${CollectionPolicy.MAX_TARGETS} collectors", long = true)
                return
            }
            val name = "collector ${profile.collectionTargets.size + 1}"
            profile = profile.withCollectionTargets(profile.collectionTargets + CollectionTarget(name, pt))
            host.toast("Added $name at (${pt.x}, ${pt.y})")
            tempPoint = null
            return
        }

        profile = applyTarget(profile, target.name, pt)

        // A card's area is centred on its tap, so the box shows a tap that missed the card's middle.
        selectedRegion = profile.troopRois[target.name]
        host.toast("Saved ${target.label} to (${pt.x}, ${pt.y})")

        // Advance to next target if available
        if (idx < CALIBRATION_TARGETS.size - 1) {
            pickTarget(idx + 1)
        }
    }

    /** "Save calibration": checks the profile and writes what changed here since it was read. */
    fun save() {
        if (working) return
        val edited = profile
        val opened = openedProfile
        work(SAVING) {
            try {
                val (saved, file) = withContext(Dispatchers.IO) {
                    edited.validate()
                    accountStore.saveCalibration(accountId, opened, edited) to accountStore.profileFile(accountId)
                }
                profile = saved
                openedProfile = saved
                AppLog.log("profile_saved", "accountId" to accountId, "path" to file.absolutePath)
                host.toast("Profile saved (Verified: ${saved.isVerified})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.toast("Validation error: ${e.message}", long = true)
            }
        }
    }

    /**
     * Runs [block] on the model's scope with [label] shown and the buttons off. One at a time: on
     * the phone a capture took the user into Clash, so nothing else could be pressed meanwhile.
     */
    private fun work(label: String, block: suspend () -> Unit) {
        if (task != null) return
        task = label
        scope.launch {
            try {
                block()
            } finally {
                task = null
            }
        }
    }

    /**
     * The phone's onCreate reads: the saved profile, the account's name, the last frozen frame
     * and whether a camera reference is saved. A profile that will not read is logged and the
     * default kept, as on the phone.
     */
    private suspend fun load() {
        val saved = withContext(Dispatchers.IO) {
            val read = runCatching { accountStore.readProfile(accountId) }
            val name = runCatching { accountStore.load().firstOrNull { it.id == accountId }?.displayName }.getOrNull()
            val frozenFile = runCatching { File(accountStore.calibrationDir(accountId), "frozen.png") }.getOrNull()
            val frozen = frozenFile?.takeIf { it.exists() }?.let { decode(it) }
            val reference = runCatching { cameraReferenceFile().exists() }.getOrDefault(false)
            Saved(read, name, frozen, reference)
        }
        saved.profile
            .onSuccess { read ->
                if (read != null) {
                    profile = read
                    openedProfile = read
                }
            }
            .onFailure { AppLog.log("profile_load_failed", "accountId" to accountId, "error" to it.message) }
        accountName = saved.name
        referenceSaved = saved.reference
        saved.frozen?.let { show(it, reference = false) }
        loaded = true
    }

    /** What [load] read on IO. */
    private class Saved(val profile: Result<PhoneProfile?>, val name: String?, val frozen: ImageBitmap?, val reference: Boolean)

    /** The body of [showCameraReference], which Retake runs too once the old reference is aside. */
    private suspend fun cameraReference() {
        try {
            val (file, exists) = withContext(Dispatchers.IO) {
                val file = cameraReferenceFile()
                file to file.exists()
            }
            if (exists) {
                val image = withContext(Dispatchers.IO) { decode(file) }
                if (image == null) {
                    host.toast("The camera reference will not decode", long = true)
                    return
                }
                show(image, reference = true)
                host.toast("Showing the saved camera reference")
                return
            }
            val service = FarmerService.instance
            if (service == null) {
                host.toast(NOT_RUNNING, long = true)
                return
            }
            val current = profile
            val bitmap = withContext(Dispatchers.IO) {
                service.captureNormalizedVillage(current).bitmap
            }
            try {
                if (bitmap.width != profile.resolutionWidth || bitmap.height != profile.resolutionHeight) {
                    throw IllegalStateException("Frame is ${bitmap.width}x${bitmap.height}, not the calibrated display")
                }
                val image = withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    val temp = File(file.parentFile, "${file.name}.tmp")
                    FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (!temp.renameTo(file)) throw IllegalStateException("Could not save ${file.name}")
                    bitmap.toBufferedImage().toComposeImageBitmap()
                }
                AppLog.log("camera_reference_saved", "accountId" to accountId, "path" to file.absolutePath)
                show(image, reference = true)
                referenceSaved = true
                host.toast("Camera reference saved")
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.toast("Reference failed: ${e.message}", long = true)
        }
    }

    /** The phone's CalibrationView.bitmap setter: a new frame drops the card box drawn on the last one. */
    private fun show(image: ImageBitmap, reference: Boolean) {
        frame = image
        selectedRegion = null
        frameIsCameraReference = reference
    }

    /** Reads the account index, so call it on IO. */
    private fun cameraReferenceFile() = File(accountStore.profileFile(accountId).parentFile, "wall_camera.png")

    /** The PNG at [file] as a frame, or null when it will not decode, as BitmapFactory gave. */
    private fun decode(file: File): ImageBitmap? = runCatching { ImageIO.read(file)?.toComposeImageBitmap() }.getOrNull()

    private companion object {
        const val READING = "Reading the saved calibration..."
        const val CAPTURING = "Capturing the game screen..."
        const val REFERENCE = "Capturing the camera reference..."
        const val SAVING = "Saving calibration..."
    }
}
