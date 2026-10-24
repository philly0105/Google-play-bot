package local.clashfarmer.core

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RunGuard(
    val clock: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    },
    var displayWidth: Int = 1600,
    var displayHeight: Int = 720
) {

    companion object {
        const val CLASH_PACKAGE = "com.supercell.clashofclans"
    }

    private val runCounter = AtomicLong(1)
    private val activeRunToken = AtomicLong(0)
    private val activeActionToken = AtomicLong(0)
    private val stopped = AtomicBoolean(true)
    private val paused = AtomicBoolean(false)
    private var pausedAt: Long = 0
    private var deadlineMs: Long = 0
    private var expectedViewport: Viewport? = null

    /** Whether the current pause is a person's press rather than the shade's hold. */
    private var personPause = false

    /** Set by an internal stop between two runs of one job; see [stop]. */
    private var betweenRuns = false

    /** A person's pause kept across an internal stop, for the next run to begin with. */
    private var carriedPause = false

    /**
     * How many pauses have taken effect, ever. A capture notes it, and a gesture built on that
     * capture compares it again before it is sent, so a pause and a resume that both fall inside
     * one OCR pass still count as a pause between the frame and the tap.
     */
    @Volatile
    var pauses: Long = 0
        private set

    @Volatile
    var stopReason: String? = "Not started"
        private set

    @Synchronized
    fun startRun(
        timeoutMs: Long,
        expectedViewport: Viewport,
        width: Int = this.displayWidth,
        height: Int = this.displayHeight
    ): Long {
        if (!stopped.get()) {
            throw IllegalStateException("A run is already active with token ${activeRunToken.get()}")
        }
        if (timeoutMs <= 0) {
            throw StopException("Session timeout must be positive (requested: $timeoutMs ms)")
        }
        this.displayWidth = width
        this.displayHeight = height

        val newToken = runCounter.getAndIncrement()
        activeRunToken.set(newToken)
        activeActionToken.set(0)
        stopped.set(false)
        // A person's pause carried over an internal stop holds the new run from its first gesture.
        val carried = carriedPause
        carriedPause = false
        betweenRuns = false
        paused.set(carried)
        personPause = carried
        pausedAt = if (carried) clock() else 0
        if (carried) pauses++
        stopReason = null
        this.expectedViewport = expectedViewport
        this.deadlineMs = clock() + timeoutMs
        return newToken
    }

    /**
     * Ends the current run. [carryPause] is for a stop between two runs of one job: the account
     * check before the farming, a recovery, the takeover wait, the app-switch recovery. A person's
     * pause then outlives the stop, and the next [startRun] begins paused, so a Pause pressed
     * before or during the stop is not undone by the next run's first tap. The shade's hold is
     * not carried. The person's Stop and the end of the job pass false and drop the carry.
     */
    @Synchronized
    fun stop(reason: String, expectedRunToken: Long? = null, carryPause: Boolean = false) {
        if (expectedRunToken != null && expectedRunToken != activeRunToken.get()) {
            // Stale callback from older run; do not stop active run
            return
        }
        if (stopped.compareAndSet(false, true)) {
            stopReason = reason
            activeRunToken.set(0)
            activeActionToken.set(0)
        } else if (stopReason == null) {
            stopReason = reason
        }
        // A second internal stop after the first one cleared the flag keeps what the first carried.
        carriedPause = carryPause && (carriedPause || (paused.get() && personPause))
        betweenRuns = carryPause
        // A run that is over is not a run that is holding. Leaving the flag up would strand
        // the next reader of it, and there is no longer a deadline worth crediting.
        paused.set(false)
        pausedAt = 0
        personPause = false
    }

    fun isStopped(): Boolean = stopped.get()

    /**
     * Holds the run where it stands without ending it. Nothing here interrupts work already
     * under way; it is a flag for the runner to read between actions. [byPerson] is false only
     * for the shade's hold. Between two runs of one job a person's pause is kept for the next.
     */
    @Synchronized
    fun pause(byPerson: Boolean = true) {
        if (stopped.get()) {
            if (betweenRuns && byPerson) carriedPause = true
            return
        }
        if (paused.get()) {
            // A Pause pressed during the shade's hold makes the pause the person's.
            if (byPerson) personPause = true
            return
        }
        paused.set(true)
        personPause = byPerson
        pausedAt = clock()
        pauses++
    }

    /**
     * Lets the run carry on, and gives it back the time it spent waiting. A session is allowed
     * so many minutes of farming, not so many minutes of being watched, so a pause that runs
     * long would otherwise end the run on a deadline it never had the chance to use.
     */
    @Synchronized
    fun resume() {
        // A Resume between runs drops the carried pause.
        if (stopped.get()) carriedPause = false
        if (!paused.getAndSet(false)) return
        deadlineMs += clock() - pausedAt
        pausedAt = 0
        personPause = false
    }

    /** True while the run is paused, and between runs while a person's pause is carried. */
    fun isPaused(): Boolean = paused.get() || (stopped.get() && carriedPause)

    fun isTokenValid(token: Long): Boolean {
        return !stopped.get() && token != 0L && activeRunToken.get() == token
    }

    fun validatePoint(point: Point) {
        if (point.x < 0 || point.x >= displayWidth || point.y < 0 || point.y >= displayHeight) {
            stop("Point $point is out of display bounds (${displayWidth}x${displayHeight})")
            throw StopException("Point $point is out of display bounds (${displayWidth}x${displayHeight})")
        }
    }

    @Synchronized
    fun nextActionToken(runToken: Long): Long {
        if (!isTokenValid(runToken)) return 0L
        return activeActionToken.incrementAndGet()
    }

    @Synchronized
    fun validateCallback(runToken: Long, actionToken: Long): Boolean {
        if (!isTokenValid(runToken)) return false
        if (actionToken == 0L) return false
        return activeActionToken.get() == actionToken
    }

    fun checkCanAct(
        token: Long,
        foregroundPackage: String?,
        isUnlocked: Boolean,
        currentViewport: Viewport?
    ) {
        if (stopped.get()) {
            throw StopException("Session is stopped: ${stopReason ?: "Unknown reason"}")
        }
        if (token == 0L || activeRunToken.get() != token) {
            throw StopException("Invalid or expired run token: $token (active=${activeRunToken.get()})")
        }
        if (clock() > deadlineMs) {
            stop("Session deadline elapsed")
            throw StopException("Session deadline elapsed")
        }
        if (foregroundPackage != CLASH_PACKAGE) {
            stop("Clash is not in foreground (detected: $foregroundPackage)")
            throw StopException("Clash is not in foreground (detected: $foregroundPackage)")
        }
        if (!isUnlocked) {
            stop("Device is locked")
            throw StopException("Device is locked")
        }
        if (currentViewport == null || currentViewport != expectedViewport) {
            stop("Viewport mismatch: expected $expectedViewport, got $currentViewport")
            throw StopException("Viewport mismatch: expected $expectedViewport, got $currentViewport")
        }
    }
}
