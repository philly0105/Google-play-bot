package local.clashfarmer.core

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.service.FarmerService
import java.io.File

/**
 * Taps the account's calibrated collectors once each, on the camera the wall phase uses.
 *
 * Nothing here presses a button on a panel: a tap that selects a building instead of collecting
 * is closed on the village's empty ground, and a panel that will not close ends the phase. The
 * resource bars are read, two frames at a time, before and after the phase; only a rise counts.
 */
class CollectionRunner(
    private val service: FarmerService,
    private val profile: PhoneProfile,
    private val screenReader: ScreenReader,
    private val runToken: Long,
    /** The village at the camera the targets were calibrated on (wall_camera.png). */
    private val cameraReference: File?
) {

    private companion object {
        /** Between a tap and the bars it may have raised; they count up for a moment. */
        const val TAP_SETTLE_MS = 1_200L
        const val SAMPLE_GAP_MS = 400L
    }

    private val viewport get() = profile.viewport

    /** Never throws for a collection problem; a Stop or cancellation still ends the run. */
    suspend fun run(): CollectionResult = try {
        collect()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (service.runGuard.isStopped()) throw e
        AppLog.log("collection_abandoned", "reason" to (e.message ?: e.toString()))
        CollectionResult(0, 0, 0, "error")
    }

    private suspend fun collect(): CollectionResult {
        val targets = profile.collectionTargets.take(CollectionPolicy.MAX_TARGETS)
        if (targets.isEmpty()) return finish(CollectionResult(0, 0, 0, "no_targets"), null, null)
        val neutral = profile.stateAnchors["village_neutral"]
            ?: return finish(CollectionResult(0, 0, 0, "no_village_anchor"), null, null)
        val deadline = SystemClock.elapsedRealtime() + CollectionPolicy.MAX_PHASE_MS

        // A pause that left the village behind holds the rest of the camera gestures; the village
        // is found again and the normalisation, which only pushes against limits, starts over.
        do {
            if (!returnHome(neutral, deadline)) return finish(CollectionResult(0, 0, 0, "not_home"), null, null)
            val normalized = sentOrHeld {
                normalizeVillageCamera(service, viewport, runToken,
                    afterPause = { villageStillUp(service, screenReader, viewport, runToken) })
            }
        } while (!normalized)
        // Taken before the registration's own capture, so a pause during it counts too.
        val pausesAtCamera = service.runGuard.pauses
        val transform = register() ?: return finish(CollectionResult(0, 0, 0, "camera_unmatched"), null, null)

        val first = balances()
        var attempted = 0
        var unselected = 0
        var skipped = 0
        var reason: String? = null
        // Each tap's own reobservation is the next target's evidence; nothing is read twice.
        var obs = observe()
        for (target in targets) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                reason = "phase_timeout"
                break
            }
            val point = transform.apply(target.point)
            // A fresh frame after a pause proves the screen is the village, not that the camera
            // is where it was matched, so the targets carried onto it end there (UO-21 review).
            val decision = CollectionPolicy.decide(
                point, viewport, cameraMatched = true,
                pausedSinceCamera = service.runGuard.pauses != pausesAtCamera,
                atHome = obs.state == ScreenState.HOME, panelOpen = panelOpen(obs)
            )
            when (decision.step) {
                CollectStep.END -> {
                    reason = decision.reason
                    break
                }
                CollectStep.SKIP -> {
                    skipped++
                    AppLog.log("collection_target_skipped", "name" to target.name,
                        "x" to point.x, "y" to point.y, "reason" to decision.reason)
                    continue
                }
                CollectStep.TAP -> Unit
            }

            // Decided on the frame before it: a pause since, or a frame gone stale, holds the tap,
            // and the target is skipped on a fresh read rather than sent blind (UO-21).
            if (!sentOrHeld { service.transport.tap(point, runToken, decidedOn = obs.timestampMs) }) {
                skipped++
                AppLog.log("collection_target_skipped", "name" to target.name,
                    "x" to point.x, "y" to point.y, "reason" to "held")
                obs = observe()
                continue
            }
            attempted++
            delay(TAP_SETTLE_MS)
            obs = observe()
            // An empty collector is selected instead of collected. Close it on the village's
            // own ground; anything that stays open ends the phase before another tap.
            val selected = panelOpen(obs)
            if (!selected) unselected++
            if (selected) {
                // A held close is read again below; a panel still open ends the phase.
                sentOrHeld { service.transport.tap(neutral, runToken, decidedOn = obs.timestampMs) }
                delay(700)
                obs = observe()
            }
            AppLog.log("collection_target_tapped", "name" to target.name, "x" to point.x, "y" to point.y,
                "selected" to selected)
        }

        val home = returnHome(neutral, SystemClock.elapsedRealtime() + WallRunner.RETURN_HOME_TIMEOUT_MS)
        if (!home && reason == null) reason = "not_home"
        val last = if (home) balances() else emptyMap()
        val verified = CollectionPolicy.verifiedTaps(unselected, first, last)
        return finish(CollectionResult(attempted, verified, skipped, reason), first, last)
    }

    private fun finish(result: CollectionResult, first: Map<Resource, Long>?, last: Map<Resource, Long>?): CollectionResult {
        // Collection is not battle loot; it is logged on its own and never recorded as a battle.
        AppLog.log(
            "collection_finished",
            "attempted" to result.attempted, "verified" to result.verified, "skipped" to result.skipped,
            "reason" to result.reason,
            "before" to first?.let { deltaText(it) },
            "after" to last?.let { deltaText(it) },
            "delta" to if (first != null && last != null) deltaText(CollectionPolicy.delta(first, last)) else null
        )
        return result
    }

    private fun deltaText(values: Map<Resource, Long>) = values.entries.joinToString(",") { "${it.key.key}=${it.value}" }

    private fun panelOpen(obs: Observation): Boolean =
        CollectionPolicy.buildingPanelOpen(obs.boxes, viewport) ||
            WallPanel.isPanel(obs.boxes, viewport) ||
            WallPanel.findConfirmButton(obs.boxes) != null

    /**
     * The village with nothing selected, read clean twice running. A panel or a selected wall gets
     * the neutral anchor; the gem store, the attack menu and the army screen get Back, the store
     * [ReturnHome.MAX_SHOP_BACKS] times at most; anything else is waited on. Before UO-21 every
     * screen but UNKNOWN got the neutral tap, which on the store is a card: on 15 October at
     * 05:31 UTC an Attack tap landed on the store under its $0.99 card.
     */
    private suspend fun returnHome(neutral: Point, deadline: Long): Boolean {
        var clearReads = 0
        var shopBacks = 0
        var last = ScreenState.UNKNOWN
        while (SystemClock.elapsedRealtime() < deadline) {
            val obs = observe()
            last = obs.state
            val panel = panelOpen(obs)
            val shop = Policy.showsShop(obs.rawText)
            if (obs.state == ScreenState.HOME && !panel) {
                if (++clearReads >= 2) return true
                delay(500)
                continue
            }
            clearReads = 0
            when (CollectionPolicy.homeStep(obs.state, panel, shop)) {
                HomeStep.NEUTRAL -> sentOrHeld { service.transport.tap(neutral, runToken, decidedOn = obs.timestampMs) }
                HomeStep.BACK -> if (!shop) {
                    service.backIfClear(runToken, "collection_home")
                } else if (shopBacks < ReturnHome.MAX_SHOP_BACKS &&
                    // Back goes only if one more frame still shows the store (UO-21 review).
                    service.shopStillUp("collection_home", PopupGuard.Verdict.Shop) { observe() }) {
                    AppLog.log("gem_shop_left", "where" to "collection_home", "text" to obs.rawText.take(200))
                    if (service.backIfClear(runToken, "gem_shop")) shopBacks++
                }
                HomeStep.NONE -> Unit
            }
            delay(1000)
        }
        AppLog.log("collection_home_timeout", "lastState" to last.name)
        return false
    }

    /** Where the reference frame's points sit on the live camera, or null when they cannot be trusted. */
    private suspend fun register(): CameraTransform? = registerVillageCamera(
        service, runToken, cameraReference, CollectionPolicy.MAX_CAMERA_DIFFERENCE, "collection"
    )

    /** The bars two village frames read alike. A frame that is not the village reads nothing. */
    private suspend fun balances(): Map<Resource, Long> {
        val samples = mutableListOf<Map<Resource, Long>>()
        for (i in 0 until 2) {
            if (i > 0) delay(SAMPLE_GAP_MS)
            val cap = service.transport.capture(runToken)
            try {
                if (screenReader.observe(cap.bitmap, viewport, cap.timestampMs).state != ScreenState.HOME) return emptyMap()
                samples += profile.homeResourceRois.mapNotNull { (resource, roi) ->
                    screenReader.readBalance(cap.bitmap, roi)?.let { resource to it }
                }.toMap()
            } finally {
                cap.bitmap.recycle()
            }
        }
        return CollectionPolicy.agreed(samples[0], samples[1])
    }

    private suspend fun observe(): Observation {
        val captured = service.transport.capture(runToken)
        return try { screenReader.observe(captured.bitmap, viewport, captured.timestampMs) }
            finally { captured.bitmap.recycle() }
    }
}
