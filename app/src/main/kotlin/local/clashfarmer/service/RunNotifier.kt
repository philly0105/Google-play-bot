package local.clashfarmer.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import local.clashfarmer.log.AppLog

/**
 * The phone's notifier on the PC. A result notice when a run ends or needs a person shows as a
 * Windows notification from the tray icon, and the run in progress shows as the tray icon's
 * tooltip, with Pause or Resume and Stop in its menu. Main's tray reads both from [RunTray]. What
 * it says, [RunNotice] and [LiveStatus], is the phone's, verbatim, in RunNotice.kt.
 *
 * Left out with the phone's Android plumbing: the Context parameter, the notification channels
 * and PendingIntents, and `controlAction` and `command(Intent)` with their broadcast token, which
 * the tray's menu items replace by calling FarmerService.
 */
class RunNotifier {

    companion object {
        const val CHANNEL_ID = "run_results"
        const val LIVE_CHANNEL_ID = "run_live"
        const val COMMAND_PAUSE = "pause"
        const val COMMAND_RESUME = "resume"
        const val COMMAND_STOP = "stop"
        /** A run that is still cancelling must not bring the ongoing notice back over its result. */
        private const val LIVE_HOLD_MS = 10_000L

        /** Whether [withdraw] may take [notice] down while [shown] is the result notice last posted. */
        internal fun withdraws(shown: RunNotice?, notice: RunNotice): Boolean = shown == notice
    }

    /** The ongoing notice as last shown, or null when none is showing. */
    private var live: LiveStatus? = null
    private var liveHeldUntil = 0L

    fun post(notice: RunNotice) {
        synchronized(this) {
            liveHeldUntil = System.currentTimeMillis() + LIVE_HOLD_MS
            cancelLive()
        }
        if (!RunTray.supported) {
            AppLog.log("notification_skipped", "kind" to notice.kind.key, "why" to "no notification area")
            return
        }
        RunTray.notices.trySend(notice)
        AppLog.log("notification_posted", "kind" to notice.kind.key, "title" to notice.title,
            "reason" to notice.text.take(300))
    }

    /** Windows keeps a notification once shown, and Java has no call to take one back, so nothing is withdrawn. */
    fun withdraw(notice: RunNotice): Boolean = false

    /** Shows [status] while it is busy and clears it otherwise. Shows only what changed. */
    @Synchronized
    fun postLive(status: LiveStatus) {
        if (!status.busy) {
            cancelLive()
            return
        }
        if (System.currentTimeMillis() < liveHeldUntil) return
        val shown = live
        if (shown != null && shown.state == status.state && shown.title == status.title && shown.phase == status.phase) return
        RunTray.live.value = status
        live = status
    }

    @Synchronized
    fun cancelLive() {
        if (live == null) return
        live = null
        RunTray.live.value = null
    }
}

/** What runs show in the notification area, for Main's tray icon. [RunNotifier] writes it. */
object RunTray {
    /** Whether Windows has a notification area Java can use. Main sets it before the service starts. */
    @Volatile
    var supported = false

    /** The run in progress, for the tooltip and the menu, or null when none is shown. */
    val live = MutableStateFlow<LiveStatus?>(null)

    /** Result notices the tray has yet to show, kept until it reads them. */
    val notices = Channel<RunNotice>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
