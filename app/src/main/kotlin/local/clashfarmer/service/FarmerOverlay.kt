package local.clashfarmer.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.core.UserStop
import local.clashfarmer.insight.EventText
import local.clashfarmer.insight.Format
import local.clashfarmer.log.AppLog
import local.clashfarmer.log.RunContextSnapshot
import org.json.JSONObject

/**
 * The panel over Clash (spec 3). Desktop: a small window over the others, which Main draws from
 * [panel] (desktop/ui/StatusPanel.kt). While a run is busy it is a pill, or a card when the ring
 * is clicked. The phone also showed a "Ready" pill while idle; the PC leaves that to the tray
 * icon, since a pill always on top would cover whatever window is in front.
 *
 * The phone's ticker is kept: once a second it reads FarmerService.instance and AppLog into a
 * [LiveStatus], and [onLiveStatus] hears each new one, so the tray follows the same ticker. The
 * phone's Context parameter is gone; the callbacks are the phone's.
 */
class FarmerOverlay(
    private val onStopRequested: (String) -> Unit,
    private val onPauseToggled: () -> Unit,
    private val onLiveStatus: (LiveStatus) -> Unit = {}
) {
    /** What the panel draws: the card's [phase] is an [updateStatus] text for five seconds, else the run's own. */
    data class Panel(
        val status: LiveStatus = LiveStatus.IDLE,
        val phase: String = "",
        val last: String = "",
        val visible: Boolean = true,
        val collapsed: Boolean = true
    ) {
        val expanded: Boolean get() = OverlayGeometry.expanded(status.state, collapsed)
    }

    /** The Swing thread, where the phone used its main looper. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var clearTransient: Job? = null

    private val _panel = MutableStateFlow(Panel())
    val panel: StateFlow<Panel> = _panel

    private var requestedVisible = true
    /** The phone kept this in its preferences; the PC starts every launch folded, the phone's default. */
    private var collapsed = true

    /** The status on screen, and the inputs it was made from, so a tick that changes nothing allocates nothing. */
    private var status = LiveStatus.IDLE
    private var shownBusy = false
    private var shownPaused = false
    private var shownHeld = false
    private var shownSnapshot: SessionSnapshot? = null
    private var shownContext: RunContextSnapshot? = null
    private var shownPending: PendingStart? = null
    private var shownRunStartedAt: Long? = null
    private var shownTick = -1L
    private var raidCurrent = false
    private var raidStartedAt: Long? = null
    private var raidCompleted = -1
    private var raidWalls = -1
    private var lastLine = ""

    /** [updateStatus] text, shown in place of the phase for five seconds. */
    private var transient: String? = null

    private fun tick() {
        val service = FarmerService.instance
        val busy = service?.isBusy == true
        val paused = busy && service?.runGuard?.isPaused() == true
        val held = paused && service?.isHeldBySystemWindow == true
        val pending = service?.pendingStart
        val snapshot = AppLog.sessionSnapshot()
        val now = System.currentTimeMillis()
        val runStartedAt = service?.runStartedAtMs
        // A busy run's clock moves every second; a held start's countdown every minute.
        val tickKey = when {
            busy -> now / 1000
            pending != null -> now / 60_000
            else -> 0L
        }
        val changed = busy != shownBusy || paused != shownPaused || held != shownHeld || snapshot !== shownSnapshot ||
            pending !== shownPending || runStartedAt != shownRunStartedAt
        if (!changed && tickKey == shownTick) return
        // Until the new run's session starts, the snapshot and the log's last result are the last run's.
        val current = snapshot.startedAt?.let { runStartedAt == null || it >= runStartedAt } == true
        // The run context moves with the session (an attack, a switch), so once the session is
        // under way a tick that only moves the clock reuses it. While the run is still starting
        // nothing in the session moves, so it is read each tick then; an idle tick reads none.
        if (changed || (busy && !current)) shownContext = if (busy) AppLog.runContextSnapshot() else null
        shownBusy = busy
        shownPaused = paused
        shownHeld = held
        shownSnapshot = snapshot
        shownPending = pending
        shownRunStartedAt = runStartedAt
        shownTick = tickKey
        status = LiveStatus.of(snapshot, shownContext ?: NO_CONTEXT, busy, paused, runStartedAt, pending, now, held = held)
        if (busy && (current != raidCurrent || snapshot.startedAt != raidStartedAt ||
                snapshot.completed != raidCompleted || snapshot.wallsUpgraded != raidWalls)
        ) {
            raidCurrent = current
            raidStartedAt = snapshot.startedAt
            raidCompleted = snapshot.completed
            raidWalls = snapshot.wallsUpgraded
            lastLine = if (!current) LiveStatus.lastLine(null, 0, wallsOn = false)
                else LiveStatus.lastLine(if (snapshot.completed > 0) lastEarned() else null,
                    snapshot.wallsUpgraded, snapshot.wallsEnabled || snapshot.wallOnly)
        }
        render()
        onLiveStatus(status)
    }

    /** The newest result's loot text still in the log's memory, read only when an attack or wall lands. */
    private fun lastEarned(): String? {
        val lines = AppLog.readRecentLogs(150)
        for (i in lines.indices.reversed()) {
            if (!lines[i].contains("\"battle_result\"")) continue
            return runCatching { JSONObject(lines[i]).optString("earned").ifBlank { null } }.getOrNull()
        }
        return null
    }

    /** Starts the ticker. The phone attached its windows here too; Main draws the PC's from [panel]. */
    fun show() {
        scope.launch {
            if (ticker != null) return@launch
            ticker = launch {
                while (isActive) {
                    tick()
                    delay(1000)
                }
            }
        }
    }

    private fun render() {
        _panel.value = Panel(
            status = status,
            phase = transient ?: status.phase,
            last = if (status.state == LiveStatus.State.PENDING) "Stop cancels it" else lastLine,
            visible = requestedVisible,
            collapsed = collapsed
        )
    }

    /**
     * The ring, on the Swing thread: it folds the card down to the pill, which still shows
     * progress and whether the run is going, and opens it again.
     */
    fun setCollapsed(value: Boolean) {
        collapsed = value
        render()
    }

    /** The panel's Pause or Resume, on the Swing thread. Drawn again at once, so a press shows what it did. */
    fun pressPause() {
        onPauseToggled()
        tick()
    }

    /** The panel's Stop. */
    fun pressStop() = onStopRequested(UserStop.FLOATING_PANEL)

    /**
     * Shows [status] in place of the phase for five seconds, then the run's own phase returns.
     * The panel and the tray tooltip show it in [plainStatus]'s words.
     */
    fun updateStatus(status: String) {
        val text = plainStatus(status)
        scope.launch {
            transient = text
            clearTransient?.cancel()
            clearTransient = launch {
                delay(TRANSIENT_MS)
                transient = null
                render()
            }
            render()
        }
    }

    fun setVisible(visible: Boolean) {
        scope.launch {
            requestedVisible = visible
            render()
        }
    }

    /**
     * Runs [action]. The phone drew its panel at alpha 0 for each capture; the PC captures the
     * Clash window alone, so a window over it never shows in a frame.
     */
    suspend fun <T> hideForCapture(action: suspend () -> T): T = action()

    /** Stops the ticker; the panel shows nothing after it. */
    fun dismiss() {
        scope.cancel()
        _panel.value = Panel(visible = false)
    }

    private companion object {
        const val TRANSIENT_MS = 5_000L
        val NO_CONTEXT = RunContextSnapshot()
    }
}

/** What the remote page's start modes are called on the panel. */
private val REMOTE_MODE_NAMES = mapOf(
    "attacks" to "attacks only", "farm" to "farming", "attacks40" to "40 attacks", "farm40" to "farm 40",
    "farm2h" to "2 h farming", "event" to "event troops", "supervised" to "supervised attack",
    "walls" to "walls only", "switch" to "account switch"
)

private val SESSION_COMPLETE = Regex("""^Session Complete: (\d+) attacks$""")
private val REMOTE_START = Regex("""^Remote start \(([a-z0-9]+)\) for (.+)$""")

/**
 * A status line in the PC's words. The verbatim runner and remote server still say the phone's
 * "Stopped: <raw reason>", "Session Complete: 3 attacks", "Waiting for Clash foreground..." and
 * "Remote start (farm2h) for Blood..."; these read as the Activity tab does. Anything else is
 * already plain and passes through.
 */
internal fun plainStatus(status: String): String {
    if (status.startsWith("Stopped: ")) return "Stopped: " + EventText.stopLine(status.removePrefix("Stopped: "))
    SESSION_COMPLETE.find(status)?.let { return "Session complete: " + Format.count(it.groupValues[1].toInt(), "attack") }
    REMOTE_START.find(status)?.let {
        val mode = REMOTE_MODE_NAMES[it.groupValues[1]] ?: it.groupValues[1]
        return "Remote start: $mode for ${it.groupValues[2]}"
    }
    return when (status) {
        "Waiting for Clash foreground..." -> "Waiting for the Clash window..."
        // FarmerService's hold for a window over Clash; the PC has no notification shade.
        "Paused: notification shade open" -> "Paused until Clash is back in front"
        else -> status
    }
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
