package local.clashfarmer.service

/**
 * Desktop stub of the phone's notifier, which posts one result notice when a run ends or needs a
 * person, and a silent ongoing notice with Pause and Stop while a run is busy. Every member is a
 * no-op until stream B ports it to Windows notifications. What it says, [RunNotice] and
 * [LiveStatus], is the phone's, verbatim, in RunNotice.kt.
 *
 * Left out with the phone's Android plumbing: the Context parameter, the notification channels
 * and PendingIntents, and `controlAction` and `command(Intent)` with their broadcast token, which
 * the PC replaces with the notification's own button callbacks.
 */
class RunNotifier {

    companion object {
        const val CHANNEL_ID = "run_results"
        const val LIVE_CHANNEL_ID = "run_live"
        const val COMMAND_PAUSE = "pause"
        const val COMMAND_RESUME = "resume"
        const val COMMAND_STOP = "stop"

        /** Whether [withdraw] may take [notice] down while [shown] is the result notice last posted. */
        internal fun withdraws(shown: RunNotice?, notice: RunNotice): Boolean = shown == notice
    }

    /**
     * No-op. Stream B: the phone cancels the ongoing notice, holds it off for 10 s, and posts
     * [notice] under one id, so it replaces the last result notice.
     */
    fun post(notice: RunNotice) {}

    /**
     * Nothing is ever shown, so nothing is taken down. Stream B: the phone takes [notice] down
     * only while [withdraws] holds for the notice last posted, and is true when it did.
     */
    fun withdraw(notice: RunNotice): Boolean = false

    /**
     * No-op. Stream B: while [status] is busy the phone shows the ongoing notice with Pause
     * (Resume when paused) and Stop, reposting only when its state, title or phase changes, and
     * otherwise cancels it.
     */
    fun postLive(status: LiveStatus) {}

    /** No-op. Stream B: the phone takes the ongoing notice down. */
    fun cancelLive() {}
}
