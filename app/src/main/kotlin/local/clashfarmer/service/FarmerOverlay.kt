package local.clashfarmer.service

/**
 * Desktop stub of the phone's panel over Clash (spec 3): a pill while a run is busy, a card when
 * opened, a "Ready" pill when idle, with Pause and Stop. Every member is a no-op until stream B
 * ports it as a small always-on-top window. The phone's Context parameter is gone; the callbacks
 * are the phone's.
 */
class FarmerOverlay(
    private val onStopRequested: (String) -> Unit,
    private val onPauseToggled: () -> Unit,
    private val onLiveStatus: (LiveStatus) -> Unit = {}
) {
    /**
     * No-op. Stream B: the phone attaches the panel and ticks it every second from
     * FarmerService.instance and AppLog, rendering LiveStatus.of and passing each new status to
     * [onLiveStatus].
     */
    fun show() {}

    /** No-op. Stream B: the phone shows [status] in place of the run's phase for five seconds. */
    fun updateStatus(status: String) {}

    /** No-op. Stream B: the phone shows or hides the panel. */
    fun setVisible(visible: Boolean) {}

    /**
     * Runs [action]. Stream B: the phone drops both panel windows to alpha 0, waits 60 ms, runs
     * [action], then restores alpha 1, so the capture never shows the panel. A capture of the
     * Clash window alone may make that unneeded on the PC.
     */
    suspend fun <T> hideForCapture(action: suspend () -> T): T = action()

    /** No-op. Stream B: the phone stops the ticker and removes the panel's windows. */
    fun dismiss() {}
}

/**
 * Where the panel's windows sit, in pixels at a display density, for [FarmerOverlay] and for a
 * test of what the bot's own gestures meet. The pill and the card's header row take touches; the
 * card's rows under the header do not. Pixels round as FarmerTheme.dp does.
 */
object OverlayGeometry {
    /** Both windows' left edge and the header's top, from the display's top left corner. */
    const val MARGIN_DP = 12
    const val HEADER_HEIGHT_DP = 40
    const val INFO_HEIGHT_DP = 72
    const val CARD_WIDTH_DP = 280
    const val PILL_WIDTH_DP = 132

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int, val touchable: Boolean) {
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    /** The card is open: the header at card width with the info window under it. */
    fun expanded(state: LiveStatus.State, collapsed: Boolean): Boolean = state != LiveStatus.State.IDLE && !collapsed

    fun px(dp: Int, density: Float): Int = (dp * density).toInt()

    /**
     * The windows shown in [state] at [density]. Idle, and pending with the card folded, the pill
     * wraps its text; [wrapWidthPx] stands in for that width.
     */
    fun windows(state: LiveStatus.State, collapsed: Boolean, density: Float, wrapWidthPx: Int = 0): List<Box> {
        val margin = px(MARGIN_DP, density)
        val headerBottom = margin + px(HEADER_HEIGHT_DP, density)
        val expanded = expanded(state, collapsed)
        val busy = state == LiveStatus.State.RUNNING || state == LiveStatus.State.PAUSED || state == LiveStatus.State.HELD
        val width = when {
            expanded -> px(CARD_WIDTH_DP, density)
            busy -> px(PILL_WIDTH_DP, density)
            else -> wrapWidthPx
        }
        val header = Box(margin, margin, margin + width, headerBottom, touchable = true)
        if (!expanded) return listOf(header)
        val info = Box(margin, headerBottom, margin + px(CARD_WIDTH_DP, density), headerBottom + px(INFO_HEIGHT_DP, density), touchable = false)
        return listOf(header, info)
    }
}
