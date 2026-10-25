package local.clashfarmer.desktop.ui

import android.graphics.Bitmap
import android.graphics.toBufferedImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Region
import local.clashfarmer.core.StopException
import local.clashfarmer.core.TrayCheck
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.Chip
import local.clashfarmer.desktop.ui.components.Divider
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.Sheet
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.ink
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.ImageMatcher
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.service.FarmerService
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.Tone
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Setup's "Check the battle tray" (spec 5.2): one frame of the scout screen the user opened, each
 * calibrated card area cut out under its slot name with a plain verdict. Nothing is tapped in the
 * game and nothing is written to the profile; the frame is kept as captures/tray-<time>.png.
 * [run] takes the frame; [ResultSheet] shows what it found.
 */
object TrayCheckSheet {

    /** What one check found: the saved frame, and either why it stopped or the report and its crops. */
    class Outcome(
        val account: AccountRecord,
        val file: File,
        val problem: String?,
        val report: TrayCheck.Report?,
        val crops: List<ImageBitmap>
    )

    /**
     * The calibration on a frame of [width]x[height], moved as a run moves it ([PcProfiles.derive]),
     * so the check reads the card areas where Clash draws them on a game area of another size or
     * shape; or the calibration as saved and why the frame can't be checked.
     */
    internal fun profileAt(profile: PhoneProfile, width: Int, height: Int): Pair<PhoneProfile, String?> = try {
        PcProfiles.derive(profile, SurfaceSize(width, height), emptySet()) to null
    } catch (e: StopException) {
        profile to e.message
    }

    /**
     * Checks the tray for the account picked on Home. [checking] hears true when the check starts
     * and false when it ends, on the UI thread; [onResult] hears what it found, before the false.
     */
    fun run(host: DashboardHost, state: DashboardState, checking: (Boolean) -> Unit, onResult: (Outcome) -> Unit) {
        val account = state.selected
        val profile = state.profile
        val refusal = if (account == null) "Pick an account on Home first." else TrayCheck.refusal(profile)
        if (refusal != null || account == null || profile == null) {
            host.toast(refusal ?: "Pick an account on Home first.", long = true)
            return
        }
        val service = FarmerService.instance
        if (service == null) {
            host.toast(NOT_RUNNING, long = true)
            return
        }
        // Refused while a run or another inspection is on, silently, as on the phone; Setup's
        // Check button is off then anyway.
        host.inspection {
            checking(true)
            try {
                val outcome = withContext(Dispatchers.IO) {
                    // The phone passed its activity to come back to; the PC window never left.
                    val captured = service.captureClashGame()
                    val bitmap = captured.bitmap
                    try {
                        val file = save(bitmap, host.filesDir)
                        ScreenReader().use { reader ->
                            val (here, sizeProblem) = profileAt(profile, bitmap.width, bitmap.height)
                            val seen = reader.observe(bitmap, here.viewport, captured.timestampMs).state
                            val problem = sizeProblem ?: TrayCheck.frameProblem(seen, bitmap.width, bitmap.height, here)
                            // Logged as an inspection, which it is, tagged so a grep for "check":"tray" finds it.
                            if (problem != null) {
                                AppLog.log("inspect_completed", "check" to "tray", "accountId" to account.id,
                                    "state" to seen.name, "width" to bitmap.width, "height" to bitmap.height, "file" to file.name)
                                Outcome(account, file, problem, null, emptyList())
                            } else {
                                val report = TrayCheck.check(here, { reader.readTrayBadge(bitmap, it) },
                                    { ImageMatcher.meanSaturation(bitmap, it) })
                                AppLog.log("inspect_completed", "check" to "tray", "state" to seen.name, "accountId" to account.id,
                                    "layout" to report.layout.name.lowercase(), "wrong" to report.wrong, "blank" to report.blank,
                                    "slots" to report.slots.joinToString(" ") {
                                        "${it.name}=${it.badge ?: "-"}/${it.saturation.roundToInt()}/${level(it).name.lowercase()}"
                                    },
                                    "raw" to report.slots.joinToString(" / ") { "${it.name}:${it.raw.joinToString(",")}" },
                                    "file" to file.name)
                                Outcome(account, file, null, report, report.slots.map { picture(bitmap, TrayCheck.pictureArea(it.roi)) })
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                onResult(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The reason stays in the log; the timeout's text names packages and windows.
                AppLog.log("inspect_failed", "check" to "tray", "error" to e.message)
                host.toast("The tray check stopped. Open a scout screen in Clash and try again.", long = true)
            } finally {
                checking(false)
            }
        }
    }

    /**
     * The check's result over the window. "Open calibration" closes it and opens calibration for
     * the account checked. Show it by composing it; [onClose] must stop composing it.
     */
    @Composable
    fun ResultSheet(outcome: Outcome, host: DashboardHost, onClose: () -> Unit) {
        val account = outcome.account
        Sheet("Tray check · ${account.displayName}", onClose) {
            val report = outcome.report
            if (report == null) {
                Label(outcome.problem.orEmpty(), Modifier.padding(top = 8.dp), size = FarmerTheme.BODY)
            } else {
                Label(report.headline, Modifier.padding(top = 8.dp), size = FarmerTheme.TITLE, color = tone(report.level).ink, bold = true)
                Label(report.layoutLine, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted)
                Pictures(report.slots.zip(outcome.crops))
                Card(padding = 0.dp, bottom = 0.dp) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        report.slots.forEachIndexed { index, slot ->
                            if (index > 0) Divider()
                            SlotRow(slot)
                        }
                    }
                }
                report.advice?.let { advice ->
                    Label(advice, Modifier.padding(top = 12.dp), size = FarmerTheme.SMALL + 1)
                    PrimaryButton("Open calibration", Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        onClose()
                        host.openCalibration(account.id)
                    }
                }
            }
            Label("Nothing was tapped in the game and nothing was saved to the profile.", Modifier.padding(top = 16.dp),
                size = FarmerTheme.SMALL, color = FarmerColors.muted)
            Label("Frame saved as ${outcome.file.name}", Modifier.padding(top = 6.dp), size = FarmerTheme.LABEL,
                color = FarmerColors.faint)
        }
    }

    /**
     * The picture cards, 76 dp each with 6 dp between them. The phone fitted four to a row; the
     * desktop sheet is wider, so they wrap where its width ends.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Pictures(cards: List<Pair<TrayCheck.Slot, ImageBitmap>>) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cards.forEach { (slot, crop) -> PictureCard(slot, crop) }
        }
    }

    /** One slot in the picture rows: its name, the card as the frame showed it, and its tone word. */
    @Composable
    private fun PictureCard(slot: TrayCheck.Slot, crop: ImageBitmap) {
        val level = level(slot)
        Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Label(TrayCheck.label(slot.name), Modifier.fillMaxWidth(), size = FarmerTheme.LABEL, color = FarmerColors.muted,
                maxLines = 1, align = TextAlign.Center)
            Image(
                bitmap = crop,
                contentDescription = slot.verdict.text,
                modifier = Modifier.padding(top = 4.dp).width(64.dp).aspectRatio(crop.width.toFloat() / crop.height),
                contentScale = ContentScale.Fit
            )
            Label(word(level), Modifier.fillMaxWidth().padding(top = 4.dp), size = FarmerTheme.LABEL, color = tone(level).ink,
                bold = true, align = TextAlign.Center)
        }
    }

    /** One slot's sentence, its chip, and the tap note when the saved tap misses the card. */
    @Composable
    private fun SlotRow(slot: TrayCheck.Slot) {
        val level = level(slot)
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(slot.verdict.text, Modifier.weight(1f).padding(end = 8.dp), size = FarmerTheme.SMALL + 1)
                Chip(word(level), tone(level))
            }
            slot.tapNote?.let {
                Label(it, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL, color = Tone.BAD.ink)
            }
        }
    }

    private fun level(slot: TrayCheck.Slot) = if (slot.wrong) TrayCheck.Level.BAD else slot.verdict.level

    private fun tone(level: TrayCheck.Level) = Tone.valueOf(level.name)

    private fun word(level: TrayCheck.Level) = when (level) {
        TrayCheck.Level.OK -> "Right"
        TrayCheck.Level.WARN -> "Check"
        TrayCheck.Level.BAD -> "Wrong"
    }

    private fun save(bitmap: Bitmap, filesDir: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(File(filesDir, "captures").apply { mkdirs() }, "tray-$timestamp.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** The phone's crop of [area], kept inside the frame, as an image the sheet can draw. */
    private fun picture(bitmap: Bitmap, area: Region): ImageBitmap {
        val left = area.left.coerceIn(0, bitmap.width - 1)
        val top = area.top.coerceIn(0, bitmap.height - 1)
        val crop = Bitmap.createBitmap(bitmap, left, top,
            (area.right - left).coerceIn(1, bitmap.width - left), (area.bottom - top).coerceIn(1, bitmap.height - top))
        return try {
            crop.toBufferedImage().toComposeImageBitmap()
        } finally {
            crop.recycle()
        }
    }
}
