package local.clashfarmer.core

enum class Resource(val key: String) {
    GOLD("gold"), ELIXIR("elixir"), DARK_ELIXIR("dark_elixir");

    companion object {
        /** The resource stored under [key], or null for anything else. Case matters. */
        fun fromKey(key: String): Resource? = entries.firstOrNull { it.key == key }
    }
}

enum class GoalMatch(val key: String) {
    ALL("all"), ANY("any");

    companion object {
        fun fromKey(key: String): GoalMatch? = entries.firstOrNull { it.key == key }
    }
}

data class FarmingGoal(
    val targets: Map<Resource, Long> = emptyMap(),
    val match: GoalMatch = GoalMatch.ALL
) {
    val isSet: Boolean get() = targets.values.any { it > 0L }
}

/** Village balances read off one HOME frame. A resource that did not read is absent, not zero. */
data class ResourceSample(
    val capturedAtMs: Long,
    val values: Map<Resource, Long>
)

/**
 * Whether the two latest samples each meet [goal] on their own.
 *
 * One frame is never enough: a single inflated reading once looked like nine million gold on an
 * account holding nine hundred thousand. The two must come from different moments, and a frame
 * holding more of anything than its storage can is a misread that spoils the whole frame. A
 * target without a known capacity cannot be checked, so it is never met.
 */
fun goalReached(goal: FarmingGoal, samples: List<ResourceSample>, capacities: Map<Resource, Long>): Boolean {
    val targets = goal.targets.filterValues { it > 0L }
    if (targets.isEmpty() || samples.size < 2) return false
    val (earlier, latest) = samples.takeLast(2)
    if (latest.capturedAtMs <= earlier.capturedAtMs) return false

    fun meets(sample: ResourceSample): Boolean {
        val plausible = sample.values.all { (resource, value) ->
            val capacity = capacities[resource]
            value >= 0L && (capacity == null || value <= capacity)
        }
        if (!plausible) return false
        val met = targets.map { (resource, target) ->
            val value = sample.values[resource]
            capacities.containsKey(resource) && value != null && value >= target
        }
        return if (goal.match == GoalMatch.ALL) met.all { it } else met.any { it }
    }
    return meets(earlier) && meets(latest)
}
