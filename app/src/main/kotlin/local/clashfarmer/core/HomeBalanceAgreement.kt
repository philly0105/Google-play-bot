package local.clashfarmer.core

/** An absent or changing OCR value cannot trigger spending. */
fun agreedHomeBalances(samples: List<ResourceSample>, capacities: Map<Resource, Long>): Map<Resource, Long> {
    if (samples.size < 2) return emptyMap()
    val (first, second) = samples.takeLast(2)
    if (second.capturedAtMs <= first.capturedAtMs) return emptyMap()
    return first.values.filter { (resource, value) ->
        value >= 0L && second.values[resource] == value &&
            (capacities[resource]?.let { value <= it } ?: true)
    }
}
