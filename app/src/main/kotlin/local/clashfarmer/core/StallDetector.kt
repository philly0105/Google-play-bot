package local.clashfarmer.core

/** One battle-wait reading. A value that did not read is absent, never zero. */
data class StallSample(
    val sinceDeployMs: Long,
    val damagePercent: Int?,
    val lootLeft: Map<Resource, Long> = emptyMap()
) {
    val readable: Boolean get() = damagePercent != null || lootLeft.isNotEmpty()
}

/** Which rule a stall was seen through, keyed for the session log. */
enum class StallRule(val key: String) {
    /** Loot rows were being read; neither they nor the damage moved for the whole window. */
    PROGRESS_STALLED("progress_stalled"),
    /** No loot was being read; the damage alone held still for its own window. */
    DAMAGE_ONLY("damage_only")
}

/** What a stall rule would have surrendered the battle over, and the quiet it formed. */
data class StallDecision(
    val rule: StallRule,
    /** Since-deploy times of the window's readable ends. */
    val windowStartMs: Long,
    val windowEndMs: Long,
    /** The quiet length the rule asked for. */
    val windowMs: Long
)

/** How long nothing may move before a stall rule surrenders the battle, unless set. */
const val DEFAULT_STALL_MS = 30_000L

/**
 * Whether a battle has stopped making progress, judged one fed sample at a time. Progress is
 * the damage going up or a read loot row going down. An unreadable sample is neither progress
 * nor proof of quiet: only the readable ends of a window count, so a stretch of unreadable
 * samples never decides anything. The first [StallDecision] is kept in [decision] and reported
 * by [feed] once; feeds after that report nothing.
 */
class StallDetector(
    /** How long nothing may move while loot rows are being read. */
    val stallMs: Long = DEFAULT_STALL_MS,
    /** The same quiet, for when the damage is the only thing being read. */
    val damageOnlyMs: Long = DEFAULT_STALL_MS,
    /** No decision is made before the battle is this old. */
    val minMsAfterDeploy: Long = MIN_MS_BEFORE_LOOT_END
) {
    private val _samples = mutableListOf<StallSample>()
    val samples: List<StallSample> get() = _samples
    val readableCount: Int get() = _samples.count { it.readable }
    var decision: StallDecision? = null
        private set

    fun feed(sinceDeployMs: Long, damagePercent: Int?, lootLeft: Map<Resource, Long>? = emptyMap()): StallDecision? {
        _samples += StallSample(sinceDeployMs, damagePercent, lootLeft.orEmpty())
        if (decision != null) return null
        return check().also { decision = it }
    }

    fun feed(sample: StallSample): StallDecision? =
        feed(sample.sinceDeployMs, sample.damagePercent, sample.lootLeft)

    /**
     * The newest sample that showed progress. The quiet a window measures can only start
     * there; anything earlier may still have been mid-fall.
     */
    private fun lastProgressIndex(): Int {
        var lastDamage: Int? = null
        val lastLoot = mutableMapOf<Resource, Long>()
        var last = -1
        for ((i, s) in _samples.withIndex()) {
            var moved = false
            s.damagePercent?.let { d ->
                if (lastDamage?.let { d > it } == true) moved = true
                lastDamage = d
            }
            for ((resource, value) in s.lootLeft) {
                if (lastLoot[resource]?.let { value < it } == true) moved = true
                lastLoot[resource] = value
            }
            if (moved) last = i
        }
        return last
    }

    private fun check(): StallDecision? {
        val quietFrom = lastProgressIndex()
        // Loot outranks the damage: while any row is being read its stillness is the rule's
        // business, and the damage is only asked on its own when there is no loot to ask.
        return window(StallRule.PROGRESS_STALLED, stallMs, quietFrom) { it.lootLeft.isNotEmpty() }
            ?: window(StallRule.DAMAGE_ONLY, damageOnlyMs, quietFrom) { it.damagePercent != null }
    }

    /**
     * The first and last [readable] samples at or after [quietFrom] bracket the longest quiet
     * on record; the rule fires once it has run [windowMs] past the minimum battle age.
     */
    private fun window(
        rule: StallRule,
        windowMs: Long,
        quietFrom: Int,
        readable: (StallSample) -> Boolean
    ): StallDecision? {
        var start = -1
        var end = -1
        for ((i, s) in _samples.withIndex()) {
            if (!readable(s)) continue
            if (i >= quietFrom && start == -1) start = i
            if (start != -1) end = i
        }
        if (start == -1) return null
        val begin = _samples[start].sinceDeployMs
        val finish = _samples[end].sinceDeployMs
        if (finish < minMsAfterDeploy || finish - begin < windowMs) return null
        return StallDecision(rule, begin, finish, windowMs)
    }

    /**
     * Every sample on one line - `12s:42%:gold=500000,elixir=250000`, a `-` wherever a reading
     * did not come back - so a battle's trajectory can be replayed offline.
     */
    fun trajectory(): String = _samples.joinToString(" ") { s ->
        val loot = if (s.lootLeft.isEmpty()) "-"
            else s.lootLeft.entries.joinToString(",") { "${it.key.key}=${it.value}" }
        "${s.sinceDeployMs / 1000}s:${s.damagePercent?.let { "$it%" } ?: "-"}:$loot"
    }
}
