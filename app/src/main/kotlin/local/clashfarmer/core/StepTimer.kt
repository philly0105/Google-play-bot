package local.clashfarmer.core

/**
 * Where one deployment's seconds went.
 *
 * A battle spends 37 to 44 seconds between the first pinch and the screen settling, and the
 * session log could not say on what. A timer opens at the top of the deployment, each stretch
 * of work the time can belong to runs inside [step], and [deployTimingSummary] writes the
 * accounting as one event. An elapsed millisecond is charged once, to the innermost step open
 * on it: a capture taken while a reward banner is being handled lands in "capture", and
 * "reward" keeps only what the handling spent apart from the frames it read. Steps are
 * block-scoped, so two of them either nest or follow one another - an overlap that ends out of
 * order cannot be expressed.
 *
 * The clock is handed in by the caller so the class never touches android.os, which keeps it
 * testable off the device. Timing is bookkeeping, never control flow: a step that throws is
 * still recorded, and a timer that fails mid-step records nothing rather than take the run
 * with it.
 */
class StepTimer(private val clock: () -> Long) {

    /** A closed step: the name it opened under and the elapsed-clock window it covered. */
    data class Step(val name: String, val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    private class Frame(val name: String, val startMs: Long, var childMs: Long = 0L)

    private val startedAt = clock()
    private val open = ArrayDeque<Frame>()
    private val steps = mutableListOf<Step>()
    private val buckets = LinkedHashMap<String, Long>()
    private val counters = mutableMapOf<String, Int>()

    /**
     * Runs [block] as step [name] and records it even when the block throws. Only closed steps
     * feed the buckets; time under a step still open at summary lands in `otherMs`.
     */
    inline fun <T> step(name: String, block: () -> T): T {
        val began = beginStep(name)
        try {
            return block()
        } finally {
            endStep(began)
        }
    }

    /** Adds [by] to a named counter - captures, verification attempts, retry groups. */
    fun mark(name: String, by: Int = 1) {
        counters[name] = (counters[name] ?: 0) + by
    }

    fun count(name: String): Int = counters[name] ?: 0

    /** Milliseconds on the clock since this timer was created. */
    fun elapsedMs(): Long = clock() - startedAt

    /** The closed steps, in the order they closed. */
    fun steps(): List<Step> = steps.toList()

    fun stepCount(name: String): Int = steps.count { it.name == name }

    /**
     * Exclusive milliseconds per step name, in the order each name first saw a step: a step's
     * window minus the windows of the steps opened inside it. The values plus the untimed
     * remainder always add up to [elapsedMs].
     */
    fun bucketMs(): Map<String, Long> = LinkedHashMap(buckets)

    @PublishedApi
    internal fun beginStep(name: String): Boolean = try {
        open.addLast(Frame(name, clock()))
        true
    } catch (e: Exception) {
        false
    }

    @PublishedApi
    internal fun endStep(began: Boolean) {
        if (!began) return
        try {
            val frame = open.removeLastOrNull() ?: return
            val step = Step(frame.name, frame.startMs, clock())
            steps += step
            buckets[step.name] =
                (buckets[step.name] ?: 0L) + (step.durationMs - frame.childMs).coerceAtLeast(0L)
            open.lastOrNull()?.let { it.childMs += step.durationMs }
        } catch (e: Exception) {
            // Timing is never allowed to take the run with it.
        }
    }
}

/**
 * Runs [block] under step [name] when there is a timer, and plainly when there is not, so a
 * call site shared with un-timed paths pays only a null check outside a deployment.
 */
inline fun <T> StepTimer?.timed(name: String, block: () -> T): T =
    this?.step(name, block) ?: block()

/**
 * The fields of the `deploy_timing` event: totalMs for the whole deployment, one `<name>Ms`
 * per bucket in the order each first saw a step, otherMs for whatever no step covered, then
 * the counters and the per-phase strings. The bucket fields plus otherMs always add up to
 * totalMs, so the trace reconciles.
 */
fun deployTimingSummary(timer: StepTimer, phaseDetails: List<String>): List<Pair<String, Any?>> {
    val buckets = timer.bucketMs()
    val total = timer.elapsedMs()
    val fields = ArrayList<Pair<String, Any?>>(buckets.size + 6)
    fields += "totalMs" to total
    for ((name, ms) in buckets) fields += "${name}Ms" to ms
    fields += "otherMs" to (total - buckets.values.sum())
    fields += "captures" to timer.stepCount("capture")
    fields += "verifyAttempts" to timer.count("verifyAttempts")
    fields += "retryGroups" to timer.count("retryGroups")
    if (phaseDetails.isNotEmpty()) fields += "phases" to phaseDetails.joinToString("|")
    return fields
}
