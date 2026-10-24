package local.clashfarmer.core

import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.service.FarmerService

/**
 * The Android side of one wall phase: builds the phone adapter and hands the phase its order.
 * Everything that decides what is bought lives in [WallPhase], which a test drives without a phone.
 */
class WallRunner(
    private val service: FarmerService? = null,
    private val profile: PhoneProfile,
    private val screenReader: ScreenReader? = null,
    private val runToken: Long = 0L,
    val maxWallLevel: Int = Policy.DEFAULT_MAX_WALL_LEVEL,
    val reserveGold: Long = Policy.DEFAULT_RESERVE_GOLD,
    val reserveElixir: Long = Policy.DEFAULT_RESERVE_ELIXIR,
    private val currencyPreference: String = "auto",
    private val maxWallsPerPhase: Int = 20,
    private val onWallAccepted: (() -> Unit)? = null,
    private val purchaseStore: WallPurchaseStore? = null,
    private val wallDevice: WallPanelDevice? = null,
    private val selectWall: (suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult)? = null
) {

    companion object {
        /** Clash reloads from its loading screen whenever the helper takes the foreground. */
        const val RETURN_HOME_TIMEOUT_MS = 90_000L

        /**
         * Pinches to flatten the village camera; the game clamps at its limit. The same gesture
         * the attack phase uses, at the same radii - LocalRunner keeps its copies private. Used
         * by [normalizeVillageCamera], which the collection phase still runs.
         */
        const val VILLAGE_ZOOM_PINCHES = 8
        const val ZOOM_START_RADIUS = 300
        const val ZOOM_END_RADIUS = 40
        const val ZOOM_DURATION_MS = 350L

        /** Drags against the pan limit, after zooming out, to bring the village near the reference. */
        const val CAMERA_CLAMP_DRAGS = 6
        const val CAMERA_DRAG_MS = 700L
        const val CAMERA_SETTLE_MS = 1_500L

        /** How long a press on the bulk panel is given to change the totals. */
        const val BULK_PRESS_MS = 1_000L

        /** Remove Wall presses at most, trimming a selection the balance turned out not to cover. */
        const val MAX_TRIMS = 10

        /** A second look at a panel that had not finished sliding in. */
        const val PANEL_REREAD_MS = 600L

        /** How far past a price box's right edge its currency icon may be drawn. */
        const val ICON_SAMPLE_OUTSIDE = 20

        /**
         * Whether a purchase cost what the dialog said it would: exactly that price left the
         * account. A settle that could not read is -1 and never equals a balance.
         */
        fun spendVerified(expected: Long, settled: Long): Boolean = expected == settled
    }

    /** False once a purchase could not be proven or refused to run; attacking continues, buying does not. */
    @Volatile
    private var wallsDisabled = false

    val wallsEnabled: Boolean get() = !wallsDisabled

    /**
     * One wall phase for [order]. A stop, including an unproven purchase, propagates; everything
     * else comes back as an outcome. Recording it in the run's tracker is the caller's job.
     */
    suspend fun runPhase(order: WallPhaseOrder): WallPhaseOutcome {
        val villageNeutral = profile.stateAnchors["village_neutral"]
        val store = purchaseStore ?: return WallPhaseOutcome.Failed(
            WallPhaseFailure.INVALID_ORDER, "no purchase store: the phase cannot record a pending purchase"
        )
        val device = wallDevice ?: AndroidWallDevice(
            service ?: throw StopException("No FarmerService provided for WallRunner"),
            profile,
            screenReader ?: throw StopException("No ScreenReader provided for WallRunner"),
            runToken
        )
        try {
            val outcome = WallPhase(device, profile.viewport, store, villageNeutral, selectWall).run(order)
            if (outcome is WallPhaseOutcome.Bought) onWallAccepted?.invoke()
            return outcome
        } finally {
            if (wallDevice == null) (device as AndroidWallDevice).close()
        }
    }
}
