package local.clashfarmer.service

/**
 * The newest encoded frame the bot captured, for the dashboard and the remote page. One slot,
 * in memory only. Encoding runs off the capture thread, so an older frame can arrive after a
 * newer one; it is dropped.
 */
class FrameStore {
    /**
     * [accountId] is the account whose session was running when the frame was captured, or null
     * when no session had settled on one, as during an account switch.
     */
    class Snapshot(val bytes: ByteArray, val atMs: Long, val accountId: String? = null)

    @Volatile var snapshot: Snapshot? = null
        private set

    /** Keeps [bytes] when they are newer than what is held. Returns whether they were kept. */
    @Synchronized
    fun offer(bytes: ByteArray, atMs: Long, accountId: String? = null): Boolean {
        val current = snapshot
        if (current != null && atMs <= current.atMs) return false
        snapshot = Snapshot(bytes, atMs, accountId)
        return true
    }

    @Synchronized
    fun clear() {
        snapshot = null
    }
}
