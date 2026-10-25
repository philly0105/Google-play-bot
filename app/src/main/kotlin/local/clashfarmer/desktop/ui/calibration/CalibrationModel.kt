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
import local.clashfarmer.core.StopException
import local.clashfarmer.desktop.SurfaceVerification
import local.clashfarmer.desktop.sidecar.SurfaceSize
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
     * has measured the surface (a 16:10 one, such as 1860x1162, keeps its own size, and a small
     * monitor caps it), else at the phone's 1600x720. A capture moves it onto the frame's own size
     * either way ([refit]).
     */
    var profile: PhoneProfile by mutableStateOf(
        host.windowStatus.value.let { status ->
            status.surface?.let { FarmerService.preferredSurface(it, status.window?.room) }
        }?.let { PhoneProfile.createDefault(it.width, it.height) }
            ?: PhoneProfile.createDefault()
    )
        private set

    /**
     * What Save asks before it replaces a saved calibration of another shape, shown as a dialog;
     * null when nothing is being asked. [confirmSave] and [cancelSave] answer it.
     */
    var saveQuestion: String? by mutableStateOf(null)
        private set

    /** Whether the account had a saved profile when this visit read it, or has saved one since. */
    private var hasSaved = false
    /** The profile as loaded, so a save writes only what was changed here. */
    private var openedProfile: PhoneProfile = profile
    private var tempPoint: Point? by mutableStateOf(null)
    /**
     * The perimeter edge ends applied on this visit. Apply fills an edge's other end with the same
     * point, so the profile alone can't tell a start just applied from one never set ([isSet]).
     */
    private var appliedEnds: Set<String> by mutableStateOf(emptySet())
    /**
     * Collection targets are only taken on the camera reference itself, never on another frame,
     * and only when it is the profile's size ([referenceMismatch]).
     */
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

    /**
     * The question shown as a dialog before an action that loses work, or null. Back asks before
     * it drops points not saved, Retake camera reference before it clears the saved collectors,
     * and Start from the standard positions before it replaces points. [confirmQuestion] and
     * [cancelQuestion] answer it.
     */
    var question: Question? by mutableStateOf(null)
        private set

    private var accountName: String? by mutableStateOf(null)
    private var referenceSaved by mutableStateOf(false)

    val target: CalibrationTarget get() = CALIBRATION_TARGETS[targetIndex]

    /** True while [task] runs: the sidebar's buttons, Back aside, are off. */
    val working: Boolean get() = task != null

    /** "Calibrate Blood": the sidebar's title. */
    val title: String get() = "Calibrate ${accountName ?: "this account"}"

    /** Whether the profile has changes Save has not written, which Back asks before dropping. */
    val unsaved: Boolean get() = profile != openedProfile

    /** Whether a point is waiting for Apply, so Enter applies it whatever button has focus. */
    val pending: Boolean get() = tempPoint != null

    /** Whether the profile being edited has a value for the target [name] ([isSet]). */
    fun targetSet(name: String): Boolean = isSet(profile, name, appliedEnds)

    /** The point saved for the target [name] on the profile being edited ([savedPoint]). */
    fun targetPoint(name: String): Point? = savedPoint(profile, name, appliedEnds)

    /** Whether "Not in my army" can remove the target shown: an optional card that is set. */
    val removable: Boolean get() = target.name in OPTIONAL_CARDS && targetSet(target.name)

    /**
     * The line under the target's description: the point Apply would save, else where the target
     * is saved, else what to do. Coordinates are frame pixels, as the arrow keys move them.
     */
    val coordText: String
        get() {
            tempPoint?.let { return "New point: x ${it.x}, y ${it.y}. Press Apply or Enter to set it." }
            if (!targetEnabled(profile, target.name)) return "Set by Capture: the game area is the whole screenshot."
            targetPoint(target.name)?.let { return "Set at x ${it.x}, y ${it.y}. Click the screenshot to move it." }
            if (target.name != COLLECTOR && targetSet(target.name)) return "Set. Click the screenshot to move it."
            if (target.name in OPTIONAL_CARDS) return "Not set, so the bot won't use this card. Click the screenshot to place it."
            return "Click the screenshot to place this point."
        }

    /** "12 of 36 points set": the targets the list checks off, the game area's corners aside. */
    val progressText: String
        get() = "${COUNTED_TARGETS.count { targetSet(it.name) }} of ${COUNTED_TARGETS.size} points set"

    /** The status block under the buttons, in plain lines. */
    val statusText: String
        get() {
            val p = profile
            val missing = CALIBRATION_TARGETS.filter { it.required && !targetSet(it.name) }
            val area = "Game area ${p.resolutionWidth}x${p.resolutionHeight}" + if (frame == null) ", no screenshot yet" else ""
            val attack = if (missing.isEmpty()) "Everything an attack needs is set."
            else "Still needed to attack: ${missing.joinToString(", ") { it.label }}."
            val collectors = when (p.collectionTargets.size) {
                0 -> "No collectors"
                1 -> "1 collector"
                else -> "${p.collectionTargets.size} collectors"
            } + if (referenceSaved) ", camera reference saved" else ", no camera reference"
            val verified = if (p.isVerified) "Confirmed by a supervised attack."
            else "Not confirmed yet: after saving, run one supervised attack."
            return listOf(area, attack, collectors, verified).joinToString("\n")
        }

    /** Whether Start from the standard positions can run: not while a read, capture or save does. */
    val standardEnabled: Boolean get() = loaded && !working

    init {
        work(READING) { load() }
    }

    /** Ends the model's work: a capture still running is cancelled, as the phone's onDestroy did. */
    fun close() {
        job.cancel()
    }

    /** "Back to dashboard", and Escape: asks first when points would be lost. */
    fun back() {
        if (question != null) return
        if (unsaved) question = Question.LEAVE else onBack()
    }

    /** The answer that goes ahead with [question]'s action. */
    fun confirmQuestion() {
        when (question ?: return) {
            Question.LEAVE -> {
                question = null
                onBack()
            }
            Question.RETAKE -> {
                question = null
                retake()
            }
            Question.STANDARD -> {
                question = null
                useStandard()
            }
        }
    }

    /** The answer that leaves everything as it was. */
    fun cancelQuestion() {
        question = null
    }

    /** A click on the frame, in frame pixels. */
    fun click(point: Point) {
        marker = point
        tempPoint = point
    }

    /**
     * An arrow key on the frame: moves the point Apply would save by [dx], [dy] frame pixels,
     * starting from the target's saved point when nothing has been clicked for it. Kept on the frame.
     */
    fun nudge(dx: Int, dy: Int) {
        val image = frame ?: return
        if (!targetEnabled(profile, target.name)) return
        val from = tempPoint ?: targetPoint(target.name) ?: return
        click(Point((from.x + dx).coerceIn(0, image.width - 1), (from.y + dy).coerceIn(0, image.height - 1)))
    }

    /**
     * Picks a target from the list. Picking the one already shown keeps the clicked point, as the
     * Spinner did; a game-area corner a capture sets can't be picked ([targetEnabled]).
     */
    fun pickTarget(index: Int) {
        if (index == targetIndex || index !in CALIBRATION_TARGETS.indices) return
        if (!targetEnabled(profile, CALIBRATION_TARGETS[index].name)) return
        targetIndex = index
        tempPoint = null
    }

    /** Moves off a target that can't be picked on the profile now, to the first that can after it. */
    private fun skipDisabled() {
        if (targetEnabled(profile, target.name)) return
        val next = CALIBRATION_TARGETS.indices.firstOrNull { it > targetIndex && targetEnabled(profile, CALIBRATION_TARGETS[it].name) }
            ?: return
        targetIndex = next
        tempPoint = null
    }

    /**
     * "Start from the standard positions" ([StarterProfile]). It asks first when it would replace
     * a saved calibration or points set since the last save.
     */
    fun startFromStandard() {
        if (!standardEnabled || question != null) return
        if (hasSaved || unsaved) question = Question.STANDARD else useStandard()
    }

    /**
     * Loads the standard positions onto the game area on show and picks the main troop card, the
     * first point the user must check against their own tray. Nothing is saved until Save.
     */
    private fun useStandard() {
        if (working) return
        work(STANDARD) {
            val starter = try {
                withContext(Dispatchers.IO) { StarterProfile.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("calibration_failed", "accountId" to accountId, "step" to "standard", "error" to e.message)
                host.toast("Could not read the standard positions. Set the points by hand.", long = true)
                return@work
            }
            val shown = frame?.let { SurfaceSize(it.width, it.height) }
            val next = try {
                StarterProfile.onto(starter, profile, StarterProfile.sizeFor(shown, profile, starter))
            } catch (e: StopException) {
                host.toast(e.message ?: "The standard positions don't fit this game area.", long = true)
                return@work
            }
            profile = next
            selectedRegion = null
            tempPoint = null
            // The standard edges have two different ends each, so none needs remembering.
            appliedEnds = emptySet()
            AppLog.log("standard_positions_loaded", "accountId" to accountId, "size" to "${next.resolutionWidth}x${next.resolutionHeight}")
            CALIBRATION_TARGETS.indexOfFirst { it.name == "valkyries" }.takeIf { it >= 0 }?.let { targetIndex = it }
            host.toast(STANDARD_LOADED, long = true)
        }
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

                    val note = fitTo(bitmap.width, bitmap.height)

                    show(image, reference = false)

                    withContext(Dispatchers.IO) {
                        val calDir = accountStore.calibrationDir(accountId)
                        if (!calDir.exists()) calDir.mkdirs()
                        val frozenFile = File(calDir, "frozen.png")
                        FileOutputStream(frozenFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    if (note != null) host.toast(note, long = true)
                    else host.toast("Captured the game (${bitmap.width}x${bitmap.height})")
                } finally {
                    bitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("calibration_failed", "accountId" to accountId, "step" to "capture", "error" to e.message)
                host.toast(CAPTURE_FAILED, long = true)
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
     * name, and the collectors measured on it are cleared on disk before it goes, not at Save. It
     * asks first when there is a reference or collectors to lose.
     */
    fun retakeCameraReference() {
        if (working || question != null) return
        val loses = referenceSaved || profile.collectionTargets.isNotEmpty() || openedProfile.collectionTargets.isNotEmpty()
        if (loses) question = Question.RETAKE else retake()
    }

    private fun retake() {
        if (working) return
        work(REFERENCE) {
            if (retireReference()) cameraReference()
        }
    }

    /**
     * Sets the saved camera reference aside under a dated name, clearing the collectors measured
     * on it first. False, with a toast, when either fails.
     */
    private suspend fun retireReference(): Boolean {
        val cleared = try {
            withContext(Dispatchers.IO) { accountStore.clearCollectionTargets(accountId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.toast("Could not clear the saved collectors: ${e.message}", long = true)
            return false
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
            return false
        }
        if (backup != null) AppLog.log("camera_reference_retired", "accountId" to accountId, "backup" to backup.name)
        profile = profile.withCollectionTargets(emptyList())
        frameIsCameraReference = false
        referenceSaved = false
        return true
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
        if (!targetEnabled(profile, target.name)) {
            host.toast("Capture sets the game area on the PC; pick another point")
            return
        }
        // These two notes name a button, so the point is dropped: one still waiting would take the
        // Enter meant for that button ([pending]).
        if (target.name == COLLECTOR && !frameIsCameraReference) {
            tempPoint = null
            host.toast("Press Show camera reference first; collectors are measured on it", long = true)
            return
        }
        // The click is a pixel of the frame on show, which can be of another size than the profile.
        // On a camera reference that old, only Retake lets collectors be marked at this size.
        frame?.let {
            if (target.name == COLLECTOR) referenceMismatch(it.width, it.height, profile)
            else frameMismatch(it.width, it.height, profile)
        }?.let {
            tempPoint = null
            host.toast(it, long = true)
            return
        }

        if (target.name == COLLECTOR) {
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
        if (isPerimeterEnd(target.name)) appliedEnds = appliedEnds + target.name

        // A card's area is centred on its tap, so the box shows a tap that missed the card's middle.
        selectedRegion = profile.troopRois[target.name]
        host.toast("${target.label}: set at x ${pt.x}, y ${pt.y}. Save when you're done.")

        // On to the next target that can be picked, if there is one.
        CALIBRATION_TARGETS.indices.firstOrNull { it > idx && targetEnabled(profile, CALIBRATION_TARGETS[it].name) }
            ?.let { pickTarget(it) }
    }

    /**
     * "Not in my army": removes the optional card shown ([withoutCard]), so the attack skips it.
     * The standard positions set the jump spell and all four heroes, which not every army has.
     */
    fun removeCard() {
        if (working || !removable) return
        val label = target.label.substringBefore(" (")
        profile = withoutCard(profile, target.name)
        selectedRegion = null
        tempPoint = null
        host.toast("$label removed. The bot won't use this card. Save when you're done.")
    }

    /**
     * "Save calibration": checks the profile and writes what changed here since it was read. A
     * game area the bot can't work at is refused, and replacing a saved calibration of another
     * shape is asked first ([saveQuestion]).
     */
    fun save() {
        if (working || saveQuestion != null) return
        saveProblem(profile)?.let {
            host.toast(it, long = true)
            return
        }
        val question = saveQuestion(openedProfile, profile, hasSaved)
        if (question != null) saveQuestion = question else write()
    }

    /** The answer Save to [saveQuestion]. */
    fun confirmSave() {
        if (saveQuestion == null) return
        saveQuestion = null
        write()
    }

    /** The answer Cancel to [saveQuestion]: nothing is written. */
    fun cancelSave() {
        saveQuestion = null
    }

    /**
     * Writes the profile, then forgets the shapes supervised attacks verified when the save
     * changed the calibration ([SurfaceVerification.calibrationSaved]).
     */
    private fun write() {
        if (working) return
        val edited = profile
        val opened = openedProfile
        work(SAVING) {
            try {
                val (saved, file) = withContext(Dispatchers.IO) {
                    edited.validate()
                    val saved = accountStore.saveCalibration(accountId, opened, edited)
                    val file = accountStore.profileFile(accountId)
                    SurfaceVerification.calibrationSaved(file, saved)
                    saved to file
                }
                profile = saved
                openedProfile = saved
                hasSaved = true
                AppLog.log("profile_saved", "accountId" to accountId, "path" to file.absolutePath)
                host.toast(if (saved.isVerified) "Saved" else "Saved. Run one supervised attack to confirm it.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("calibration_failed", "accountId" to accountId, "step" to "save", "error" to e.message)
                host.toast(saveFailure(e), long = true)
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
     * default kept, as on the phone. A frozen frame of another size than the profile, from a
     * capture whose move was not saved, is shown as it is, and Apply takes no point on it
     * ([frameMismatch]).
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
                    hasSaved = true
                }
            }
            .onFailure { AppLog.log("profile_load_failed", "accountId" to accountId, "error" to it.message) }
        accountName = saved.name
        referenceSaved = saved.reference
        saved.frozen?.let { show(it, reference = false) }
        skipDisabled()
        loaded = true
    }

    /** What [load] read on IO. */
    private class Saved(val profile: Result<PhoneProfile?>, val name: String?, val frozen: ImageBitmap?, val reference: Boolean)

    /**
     * The body of [showCameraReference], which Retake runs too once the old reference is aside. A
     * saved reference of another size than the profile, taken before the game area changed, is
     * shown with a note, and Apply takes no point on it: points marked on it would be saved where
     * that frame had them ([referenceMismatch], [frameMismatch]).
     */
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
                val mismatch = referenceMismatch(image.width, image.height, profile)
                show(image, reference = true)
                if (mismatch == null) host.toast("Showing the saved camera reference") else host.toast(mismatch, long = true)
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
                // The run moves the calibration onto the game area it finds, so the frame can be
                // another size; the profile follows it, as a capture's does.
                val note = fitTo(bitmap.width, bitmap.height)
                note?.let { host.toast(it, long = true) }
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
            AppLog.log("calibration_failed", "accountId" to accountId, "step" to "reference", "error" to e.message)
            host.toast("Could not capture the camera reference. Make sure Clash is open on the village, then try again.", long = true)
        }
    }

    /**
     * Moves the profile being edited onto a frame of [width]x[height] ([refit]) and returns what
     * the user should be told, or null.
     */
    private fun fitTo(width: Int, height: Int): String? {
        val fit = refit(profile, width, height)
        // A game area reset at the same size is not a move.
        val moved = width != profile.resolutionWidth || height != profile.resolutionHeight
        if (moved && fit.profile !== profile) AppLog.log(
            "profile_moved",
            "accountId" to accountId,
            "calibrated" to "${profile.resolutionWidth}x${profile.resolutionHeight}",
            "surface" to "${width}x$height",
            "where" to "calibrate"
        )
        profile = fit.profile
        skipDisabled()
        return fit.note
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

    /** What [question] asks, as [CalibrationScreen] shows it. */
    enum class Question(val title: String, val message: String, val confirm: String, val dismiss: String) {
        LEAVE(
            "Leave without saving?",
            "The points you set or moved since the last save will be lost.",
            "Leave without saving", "Keep editing"
        ),
        RETAKE(
            "Retake the camera reference?",
            "This clears the collectors saved for this account. Mark them again on the new reference.",
            "Retake", "Cancel"
        ),
        STANDARD(
            "Use the standard positions?",
            "This replaces the points on this screen with the standard positions. Your saved calibration stays as " +
                "it is until you press Save.",
            "Use standard positions", "Cancel"
        )
    }

    private companion object {
        const val READING = "Reading the saved calibration..."
        const val CAPTURING = "Capturing the game screen..."
        const val REFERENCE = "Capturing the camera reference..."
        const val SAVING = "Saving calibration..."
        const val STANDARD = "Loading the standard positions..."
        const val CAPTURE_FAILED = "Could not capture the game. Make sure Clash is open and not minimised, then try again."
        const val STANDARD_LOADED = "Standard positions loaded. Check each troop card against your own battle tray, " +
            "move any that differ, and press Not in my army on any hero or spell you don't have. Then save."
    }
}

/**
 * Why Save failed, in words for the user. The validator's messages name anchors and viewports
 * ("State anchor 'attack' at Point(x=..) is outside viewport ..."); every one of them means a
 * point lies off the game area.
 */
internal fun saveFailure(error: Throwable): String {
    val message = error.message.orEmpty()
    if (error is StopException && (message.contains("outside viewport") || message.contains("out of bounds"))) {
        return "Could not save: a point is outside the game area. Capture the game, check the points, then save again."
    }
    if (error is StopException && message.isNotBlank()) return "Could not save: ${message.trimEnd('.')}."
    return "Could not save the calibration. Check that the disk has space, then try again."
}
