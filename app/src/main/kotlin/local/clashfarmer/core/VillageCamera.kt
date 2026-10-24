package local.clashfarmer.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.service.FarmerService
import java.io.File

/**
 * Pushes the village camera against its own limits: zoomed out until Clash stops scaling, then
 * dragged toward the bottom right until it stops panning. The collection phase registers the
 * result against a reference frame before it taps anything by it.
 *
 * [afterPause] is the look a gesture takes when a pause came before it; one that throws HELD ends
 * the normalisation with the rest unsent, and the caller reads the village and starts it over.
 */
internal suspend fun normalizeVillageCamera(
    service: FarmerService, vp: Viewport, runToken: Long, afterPause: (suspend () -> Unit)?
) {
    val center = Point(vp.left + vp.width / 2, vp.top + vp.height / 2)
    for (i in 1..WallRunner.VILLAGE_ZOOM_PINCHES) {
        service.transport.pinch(
            center = center,
            startRadius = WallRunner.ZOOM_START_RADIUS,
            endRadius = WallRunner.ZOOM_END_RADIUS,
            durationMs = WallRunner.ZOOM_DURATION_MS,
            runToken = runToken,
            afterPause = afterPause
        )
        delay(400)
    }
    delay(WallRunner.CAMERA_SETTLE_MS)

    val from = Point(vp.left + vp.width * 31 / 100, vp.top + vp.height * 28 / 100)
    val to = Point(vp.left + vp.width * 84 / 100, vp.top + vp.height * 86 / 100)
    for (i in 1..WallRunner.CAMERA_CLAMP_DRAGS) {
        service.transport.swipe(from, to, WallRunner.CAMERA_DRAG_MS, runToken, afterPause = afterPause)
        delay(600)
    }
    delay(WallRunner.CAMERA_SETTLE_MS)

    AppLog.log(
        "village_camera_normalized",
        "pinches" to WallRunner.VILLAGE_ZOOM_PINCHES, "drags" to WallRunner.CAMERA_CLAMP_DRAGS
    )
}

/**
 * The camera gestures' look after a pause: the village with no popup over it, or HELD. A pinch
 * or drag sent onto the gem store or a menu the person left open would land on whatever is drawn
 * there, as the 15 October Attack tap landed on the store's $0.99.
 */
internal suspend fun villageStillUp(service: FarmerService, reader: ScreenReader, vp: Viewport, runToken: Long) {
    val captured = service.transport.capture(runToken)
    val obs = try { reader.observe(captured.bitmap, vp, captured.timestampMs) } finally { captured.bitmap.recycle() }
    if (obs.state != ScreenState.HOME || PopupGuard.inspect(obs.boxes, vp, obs.rawText) != PopupGuard.Verdict.None) {
        throw StopException(TapAfterHold.HELD)
    }
}

/**
 * Where [reference]'s points sit on the live camera, or null when they cannot be trusted: no
 * reference, a frame of another size, or a difference above [maxDifference]. Logged as
 * "<event>_camera_registered", "<event>_camera_unmatched" or "<event>_camera_unreferenced".
 */
internal suspend fun registerVillageCamera(
    service: FarmerService, runToken: Long, reference: File?, maxDifference: Double, event: String
): CameraTransform? {
    val refBitmap = reference?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    if (refBitmap == null) {
        AppLog.log("${event}_camera_unreferenced", "file" to reference?.path)
        return null
    }
    val ref = try { grayOf(refBitmap) } finally { refBitmap.recycle() }
    val captured = service.transport.capture(runToken)
    val live = try { grayOf(captured.bitmap) } finally { captured.bitmap.recycle() }
    if (ref.width != live.width || ref.height != live.height) {
        AppLog.log("${event}_camera_unmatched", "reason" to "size")
        return null
    }
    val reg = withContext(Dispatchers.Default) { CameraRegistration.register(ref, live) }
    val t = reg.transform
    val trusted = reg.matched && reg.difference <= maxDifference
    AppLog.log(
        if (trusted) "${event}_camera_registered" else "${event}_camera_unmatched",
        "scale" to "%.3f".format(t.scale), "dx" to t.dx, "dy" to t.dy,
        "difference" to "%.1f".format(reg.difference)
    )
    return if (trusted) t else null
}

private fun grayOf(bitmap: Bitmap): GrayImage {
    val argb = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return GrayImage.fromArgb(bitmap.width, bitmap.height, argb)
}
