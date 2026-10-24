package local.clashfarmer.core

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.recognition.UpgradeListReader
import local.clashfarmer.recognition.upgradeListClosePoint
import local.clashfarmer.recognition.upgradePopupRegion
import local.clashfarmer.service.FarmerService

/**
 * What a wall-phase frame shows, as far as a tap read off it depends on it. A look taken before a
 * tap that a pause crossed or that waited too long must show the same, or the tap is held. It
 * does not hold where the list has scrolled; the row tap's look checks its own row for that.
 */
internal data class WallSignature(
    val state: ScreenState,
    val panel: Boolean,
    val confirm: Boolean,
    val listCard: Boolean,
    val busy: Boolean,
    val shop: Boolean
) {
    /**
     * Whether [other] shows the purchase dialog as far as its accept button depends on it: the
     * button, and no store or busy dialog. The state, the panel and a list card are left out: a
     * confirmation frame is about 2 s old when it goes out, so it is usually looked at, and the
     * dimmed Battle and Build behind the dialog read HOME on 108 of its 109 frames of 20 September
     * and UNKNOWN on one, so comparing the state could hold a confirmation with no pause (UO-21
     * review, 15 October). The confirmation's own check looks at the button's place and its price.
     */
    fun sameConfirmation(other: WallSignature): Boolean =
        confirm == other.confirm && shop == other.shop && busy == other.busy
}

internal fun wallSignature(observation: Observation, viewport: Viewport): WallSignature = WallSignature(
    state = observation.state,
    panel = WallPanel.isPanel(observation.boxes, viewport),
    confirm = WallPanel.findConfirmButton(observation.boxes) != null,
    listCard = upgradeListCardShowing(observation.boxes, viewport),
    busy = Builders.isBusyDialog(observation.boxes, viewport),
    shop = Policy.showsShop(observation.rawText)
)

/**
 * The phone behind [WallPanelDevice]: the accessibility service's gestures and screenshots, the
 * recogniser, and the resource bar readers. It is the only place the wall phase touches Android.
 */
class AndroidWallDevice(
    private val service: FarmerService,
    private val profile: PhoneProfile,
    private val screenReader: ScreenReader,
    private val runToken: Long
) : WallPanelDevice {

    /**
     * One capture. Its bitmap lives until the next capture replaces it. [signature] is taken from
     * the first read, so a look, which has no second read, is compared like for like. [pauses] is
     * [RunGuard.pauses] just before the screenshot was asked for.
     */
    private class Frame(
        val bitmap: Bitmap,
        override var observation: Observation,
        val signature: WallSignature,
        val pauses: Long
    ) : UpgradeListFrame {
        override var paused = false

        override fun pixels(region: Region): IntArray {
            val x0 = region.left.coerceIn(0, bitmap.width - 1)
            val x1 = region.right.coerceIn(x0 + 1, bitmap.width)
            val y0 = region.top.coerceIn(0, bitmap.height - 1)
            val y1 = region.bottom.coerceIn(y0 + 1, bitmap.height)
            val pixels = IntArray((x1 - x0) * (y1 - y0))
            bitmap.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
            return pixels
        }

        fun release() { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private var lastFrame: Frame? = null

    fun close() {
        lastFrame?.release()
        lastFrame = null
    }

    /**
     * A frame, [UpgradeListFrame.paused] when a pause came while it was taken or read again: the
     * navigator's own checkpoint comes before this, so a pause waited out in here never reached
     * it, and it could agree a frame after the pause with one before (UO-18 round 2 review).
     */
    override suspend fun capture(): UpgradeListFrame {
        val pauses = service.runGuard.pauses
        val frame = read()
        frame.paused = service.runGuard.pauses != pauses
        return frame
    }

    private suspend fun read(): Frame {
        checkpoint()
        lastFrame?.release()
        lastFrame = null
        val pauses = service.runGuard.pauses
        val captured = service.transport.capture(runToken)
        val observation = try {
            screenReader.observe(captured.bitmap, profile.viewport, captured.timestampMs)
        } catch (e: Throwable) {
            captured.bitmap.recycle()
            throw e
        }
        val frame = Frame(captured.bitmap, observation, wallSignature(observation, profile.viewport), pauses)
        try {
            val reading = UpgradeListReader.read(observation.boxes, profile.viewport, frame::pixels)
            // One decision per frame: a wall panel's prices pass for list rows, and the list's
            // crop cut the elixir one at x 985, so a wall panel has only its own prices read
            // again. The two never run together: the price pass starts again from the first read.
            val second = secondRead(reading, observation.boxes, profile.viewport)
            if (second == SecondRead.LIST_CROP) {
                if (checkpoint()) {
                    frame.release()
                    return read()
                }
                val roi = upgradePopupRegion(profile.viewport, observation.boxes)
                val list = screenReader.observeRegion(captured.bitmap, roi, scale = 3,
                    acquisitionTimeMs = captured.timestampMs)
                frame.observation = observation.copy(boxes = observation.boxes.filterNot {
                    roi.contains(it.center)
                } + list.boxes)
            }
            if (WallPanel.findConfirmButton(observation.boxes) != null) {
                val titles = observation.boxes.filter {
                    it.text.contains("wall", ignoreCase = true) && it.text.contains("level", ignoreCase = true)
                }
                val refined = mutableListOf<local.clashfarmer.recognition.RecognizedBox>()
                for (title in titles) {
                    if (checkpoint()) {
                        frame.release()
                        return read()
                    }
                    val roi = Region((title.left - 24).coerceAtLeast(profile.viewport.left),
                        (title.top - 12).coerceAtLeast(profile.viewport.top),
                        (title.right + 24).coerceAtMost(profile.viewport.right),
                        (title.bottom + 12).coerceAtMost(profile.viewport.bottom))
                    val agreed = screenReader.readWallDialogLevel(captured.bitmap, roi)
                    if (agreed != null) refined += title.copy(text = "Upgrade Wall to Level $agreed?")
                }
                if (titles.isNotEmpty()) frame.observation = observation.copy(
                    boxes = observation.boxes.filterNot { it in titles } + refined)
                val confirm = WallPanel.findConfirmButton(observation.boxes)!!
                if (checkpoint()) {
                    frame.release()
                    return read()
                }
                val priceRoi = Region((confirm.left - 50).coerceAtLeast(profile.viewport.left),
                    (confirm.top - 10).coerceAtLeast(profile.viewport.top),
                    (confirm.right + 50).coerceAtMost(profile.viewport.right),
                    (confirm.bottom + 65).coerceAtMost(profile.viewport.bottom))
                val button = screenReader.observeRegion(captured.bitmap, priceRoi, scale = 3,
                    acquisitionTimeMs = captured.timestampMs)
                frame.observation = frame.observation.copy(boxes = frame.observation.boxes.filterNot {
                    priceRoi.contains(it.center)
                } + button.boxes)
                // The enlarged read sometimes loses the button's label, which left the dialog with
                // no accept button and no row to price: on 30 September the same dialog passed at
                // 02:36 and was refused at 02:46 and 03:45. The first read already found it there.
                if (WallPanel.findConfirmButton(frame.observation.boxes) == null) {
                    frame.observation = frame.observation.copy(
                        boxes = frame.observation.boxes + confirm.copy(text = "Okay"))
                }
                // The price line alone, under the label and short of the coin on its right.
                if (checkpoint()) {
                    frame.release()
                    return read()
                }
                val priceLine = Region((confirm.left - 50).coerceAtLeast(profile.viewport.left),
                    confirm.bottom.coerceAtMost(profile.viewport.bottom - 1),
                    (confirm.right + 5).coerceAtMost(profile.viewport.right),
                    (confirm.bottom + 38).coerceAtMost(profile.viewport.bottom))
                val yellow = screenReader.readYellowPrice(captured.bitmap, priceLine)
                if (yellow != null) {
                    frame.observation = frame.observation.copy(boxes = frame.observation.boxes.filterNot {
                        priceRoi.contains(it.center) && WallPanel.embeddedPrice(it.text) != null
                    } + local.clashfarmer.recognition.RecognizedBox(
                        yellow.toString(), priceLine.left, priceLine.top, priceLine.right, priceLine.bottom))
                }
            }
            if (second == SecondRead.PRICE_REGIONS) {
                val reads = mutableListOf<Pair<Region, List<local.clashfarmer.recognition.RecognizedBox>>>()
                for (roi in WallPanel.priceRegions(observation.boxes, profile.viewport)) {
                    if (checkpoint()) {
                        frame.release()
                        return read()
                    }
                    reads += roi to screenReader.observeRegion(captured.bitmap, roi, scale = 4,
                        acquisitionTimeMs = captured.timestampMs).boxes
                }
                frame.observation = observation.copy(boxes = WallPanel.withPriceReads(observation.boxes, reads))
            }
            return frame.also { lastFrame = it }
        } catch (e: Throwable) {
            frame.release()
            throw e
        }
    }

    override suspend fun checkpoint(): Boolean {
        val guard = service.runGuard
        var paused = false
        if (guard.isStopped()) throw StopException(guard.stopReason ?: "The run was stopped")
        // A hold under the shade is a pause too: what the wall phase read before it may be gone.
        if (service.awaitClearToAct("wall phase")) {
            paused = true
            if (guard.isStopped()) throw StopException(guard.stopReason ?: "The run was stopped")
        }
        if (guard.isPaused()) {
            service.awaitResume("wall phase")
            paused = true
            if (guard.isStopped()) throw StopException(guard.stopReason ?: "The run was stopped")
        }
        return paused
    }

    /**
     * Every tap goes out on the frame the phase last read ([lastFrame]). One that frame is too old
     * for, or that a pause crossed, is sent only after [look] finds the screen the same, and held
     * otherwise: on 15 October at 05:31 UTC an Attack tap sent 19 s after its frame, past a pause,
     * landed on the gem store. A pause that cancelled a row tap or the list's X sent it again where
     * the row or the list had been before the pause, so those two also check their row or the list.
     */
    override suspend fun tap(point: Point) =
        service.transport.tap(point, runToken, afterPause = { look(tapCheck(point)) }, decidedOn = decidedOn())

    override suspend fun tapButton(point: Point) =
        service.transport.tapButton(point, runToken, afterPause = { look() }, decidedOn = decidedOn())

    /**
     * Refused or held before it went out, this throws [ConfirmationNotSent], and the phase knows
     * nothing was bought; it says whether a pause came since the dialog's frame was asked for. A
     * pause that cuts it short after it went out stays a stop with the marker in place, since the
     * purchase may have happened.
     */
    override suspend fun tapConfirmation(point: Point) {
        var sent = false
        val decided = lastFrame
        try {
            // The transport re-sends a gesture a pause cancelled. That is right for a navigation tap
            // and wrong for a purchase, so a pause that is already up refuses.
            if (service.runGuard.isPaused()) throw StopException(TapAfterHold.REFUSED)
            service.transport.tapButtonOnce(point, runToken, decidedOn = decidedOn(),
                afterPause = { look(confirmationCheck(point), same = WallSignature::sameConfirmation) },
                onSend = { sent = true })
        } catch (e: StopException) {
            if (!sent && (TapAfterHold.isHeld(e) || e.message == TapAfterHold.REFUSED)) {
                throw ConfirmationNotSent(e, paused = decided == null || service.runGuard.pauses != decided.pauses)
            }
            throw e
        }
    }

    override suspend fun tapButtonOnce(point: Point) =
        service.transport.tapButtonOnce(point, runToken, decidedOn = decidedOn(), afterPause = { look() })

    override suspend fun back(where: String): Boolean = service.backIfClear(runToken, where)

    /** A swipe after a pause goes only if a look still finds the list it scrolls. */
    override suspend fun swipe(from: Point, to: Point, durationMs: Long, holdMs: Long) =
        service.transport.swipe(from, to, durationMs, runToken, holdMs, afterPause = { look(listShowing) })

    private fun decidedOn(): Long? = lastFrame?.observation?.timestampMs

    /**
     * Captures and reads one frame, compares it with [lastFrame] by [wallSignature] ([same]) and
     * [check], and drops it; [lastFrame] stays the frame the caller decided on. Throws
     * [TapAfterHold.HELD] unless both agree. Nothing on the screen is touched.
     */
    private suspend fun look(
        check: suspend (decided: Frame, look: Frame) -> Boolean = { _, _ -> true },
        same: (WallSignature, WallSignature) -> Boolean = { decided, look -> decided == look }
    ) {
        val decided = lastFrame ?: throw StopException(TapAfterHold.HELD)
        val pauses = service.runGuard.pauses
        val captured = service.transport.capture(runToken)
        val look = try {
            val observation = screenReader.observe(captured.bitmap, profile.viewport, captured.timestampMs)
            Frame(captured.bitmap, observation, wallSignature(observation, profile.viewport), pauses)
        } catch (e: Throwable) {
            captured.bitmap.recycle()
            throw e
        }
        try {
            if (!same(decided.signature, look.signature) || !check(decided, look)) throw StopException(TapAfterHold.HELD)
        } finally {
            look.release()
        }
    }

    /**
     * A row tap's look finds the same row where it will tap (UpgradeListReader.sameRow: kind,
     * count, price and currency, within a few pixels), read as [capture] reads the list. The
     * list's X needs the list still open. Any other tap needs only the signature.
     */
    private fun tapCheck(point: Point): suspend (Frame, Frame) -> Boolean {
        if (point == upgradeListClosePoint(profile.viewport)) return listShowing
        val decided = lastFrame ?: return { _, _ -> true }
        val row = UpgradeListReader.read(decided.observation.boxes, profile.viewport, decided::pixels)
            .rows.firstOrNull { it.tap == point } ?: return { _, _ -> true }
        return { _, look -> listRows(look).any { UpgradeListReader.sameRow(row, it) } }
    }

    private val listShowing: suspend (Frame, Frame) -> Boolean = { _, look ->
        UpgradeListReader.read(look.observation.boxes, profile.viewport, look::pixels).hasListEvidence
    }

    /** The rows of a look, with the list's crop read again as [capture] does for a list frame. */
    private suspend fun listRows(look: Frame): List<UpgradeListReader.Row> {
        val roi = upgradePopupRegion(profile.viewport, look.observation.boxes)
        val list = screenReader.observeRegion(look.bitmap, roi, scale = 3, acquisitionTimeMs = look.observation.timestampMs)
        val boxes = look.observation.boxes.filterNot { roi.contains(it.center) } + list.boxes
        return UpgradeListReader.read(boxes, profile.viewport, look::pixels).rows
    }

    /**
     * The accept button still where it was pressed, within [CONFIRM_DRIFT_PX], and no premium
     * spend or shortfall on the dialog, as WallPhase's readConfirmation checked it.
     */
    private fun confirmationCheck(point: Point): suspend (Frame, Frame) -> Boolean = { _, look ->
        val raw = look.observation.rawText
        val confirm = WallPanel.findConfirmButton(look.observation.boxes)?.center
        confirm != null &&
            kotlin.math.hypot((confirm.x - point.x).toDouble(), (confirm.y - point.y).toDouble()) <= CONFIRM_DRIFT_PX &&
            !Policy.spendsPremium(WallPanel.withoutGoldPassNote(raw)) && !WallPanel.isInsufficientFunds(raw)
    }

    override suspend fun wait(ms: Long) = delay(ms)

    override fun now(): Long = SystemClock.elapsedRealtime()

    private suspend fun nextFrame(): Bitmap {
        delay(300)
        checkpoint()
        return service.transport.capture(runToken).bitmap
    }

    override suspend fun readBalance(currency: String): Long? {
        val roi = profile.homeResourceRois[Resource.fromKey(currency)] ?: return null
        checkpoint()
        val cap = service.transport.capture(runToken)
        return try {
            screenReader.readBalanceWithAgreement(cap.bitmap, roi, nextFrame = ::nextFrame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StopException) {
            throw e
        } catch (e: Exception) {
            AppLog.log("wall_balance_unreadable", "currency" to currency, "reason" to e.message)
            null
        } finally {
            cap.bitmap.recycle()
        }
    }

    /**
     * Waits for the resource bar's number to stop rolling and says where it settled.
     *
     * A reading above [starting] is not a late frame, it is a misread, and believing it costs the
     * session: at 05:53 on 19 September an elixir balance of 13 708 513 paid 5 600 000 and
     * settled at 8 108 513, which came back as 81 085 513. Impossible readings are passed over
     * so the frames after them still get their chance. -1 means it never read or never settled.
     *
     * A settle that ends anywhere but [expected], a stop part way included, logs every read's
     * value and what each mask read on the first and the last. On 15 October the elixir settled
     * at 5 000 000 and was read as 50 000 000 for 22 s, and nothing in the log said which masks
     * had read it that way.
     */
    override suspend fun settledBalance(currency: String, expected: Long, starting: Long): Long {
        val roi = profile.homeResourceRois[Resource.fromKey(currency)] ?: return -1L
        val reads = mutableListOf<String>()
        var firstRaw: List<String> = emptyList()
        val lastRaw = mutableListOf<String>()
        fun logReads(lastRead: String) = AppLog.log(
            "wall_settle_reads", "currency" to currency, "expected" to expected, "started" to starting,
            "reads" to (reads + lastRead).joinToString(","), "firstRaw" to firstRaw.joinToString(" | "),
            "lastRaw" to lastRaw.joinToString(" | ")
        )
        try {
            for (attempt in 1..SETTLE_ATTEMPTS) {
                delay(SETTLE_GAP_MS)
                checkpoint()
                val cap = service.transport.capture(runToken)
                val raw = mutableListOf<String>()
                try {
                    val amount = screenReader.readBalanceWithAgreement(cap.bitmap, roi, raw, ::nextFrame)
                    if (amount == expected) return expected
                    reads += amount?.toString() ?: "unread"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StopException) {
                    throw e
                } catch (_: Exception) {
                    // The number is still rolling.
                    reads += "error"
                } finally {
                    if (attempt == 1) firstRaw = raw
                    cap.bitmap.recycle()
                }
            }
            checkpoint()
            val last = service.transport.capture(runToken)
            return try {
                val amount = screenReader.readBalanceWithAgreement(last.bitmap, roi, lastRaw, ::nextFrame)
                if (amount != expected) logReads(amount?.toString() ?: "unread")
                if (amount == null) return -1L
                if (amount > starting) {
                    AppLog.log("wall_settle_impossible", "read" to amount, "started" to starting)
                    -1L
                } else {
                    amount
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StopException) {
                throw e
            } catch (e: Exception) {
                logReads("error")
                AppLog.log("wall_settle_unreadable", "reason" to e.message)
                -1L
            } finally {
                last.bitmap.recycle()
            }
        } catch (e: CancellationException) {
            if (reads.isNotEmpty()) logReads("stopped")
            throw e
        } catch (e: StopException) {
            if (reads.isNotEmpty()) logReads("stopped")
            throw e
        }
    }

    private companion object {
        const val SETTLE_ATTEMPTS = 10
        const val SETTLE_GAP_MS = 800L

        /** How far the accept button's centre may sit from where it was read, on a look. */
        const val CONFIRM_DRIFT_PX = 24.0
    }
}
