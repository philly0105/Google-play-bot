package local.clashfarmer.core

/**
 * When walls start and stop spending, as percentages of a resource's capacity. A phase starts
 * once a resource is at [triggerPercent] and buys until it would be at or under [resumePercent],
 * so one phase clears the top of the storage instead of spending a token amount every checkpoint.
 */
data class WallStorageRules(val triggerPercent: Int = 90, val resumePercent: Int = 75) {
    init {
        require(triggerPercent in 1..100) { "Trigger percent must be between 1 and 100 (got: $triggerPercent)" }
        require(resumePercent in 0 until triggerPercent) {
            "Resume percent must be between 0 and ${triggerPercent - 1} (got: $resumePercent)"
        }
    }
}

enum class WallPhaseRequest {
    /** Storage decided: the trigger starts the phase and the resume level ends it. */
    AUTOMATIC,
    /** The operator asked for Walls only: spend down to the reserves, whatever the fill. */
    MANUAL
}

/** The currencies a wall can be bought with: only gold and elixir. */
fun wallSpendable(preference: String): Set<Resource> = when (preference) {
    "gold" -> setOf(Resource.GOLD)
    "elixir" -> setOf(Resource.ELIXIR)
    "auto" -> setOf(Resource.GOLD, Resource.ELIXIR)
    else -> emptySet()
}

/**
 * True when no currency a wall can be bought with was read. A dark elixir read alone, or the
 * currency the preference leaves out, says nothing about whether the walls could be paid for.
 */
fun wallBalancesUnread(balances: Map<Resource, Long>, preference: String): Boolean =
    wallSpendable(preference).none { it in balances }

/**
 * floor(capacity * percent / 100) without the intermediate product, which overflows for a
 * capacity within a factor of 100 of Long.MAX_VALUE. [percent] is 0..100.
 */
fun percentOfFloor(capacity: Long, percent: Int): Long =
    capacity / 100 * percent + capacity % 100 * percent / 100

/** ceil(capacity * percent / 100), overflow-safe like [percentOfFloor]. */
fun percentOfCeil(capacity: Long, percent: Int): Long {
    val remainder = capacity % 100 * percent
    return capacity / 100 * percent + remainder / 100 + if (remainder % 100 != 0L) 1 else 0
}

/**
 * The currencies a wall phase may spend right now, from verified samples. Pure: agreement between
 * the two reads and their freshness are settled before anything gets here.
 *
 * A balance that is missing, negative or above its capacity is a misread and is never eligible,
 * and so is a currency with no positive capacity. [active] holds the currencies the phase already
 * chose to spend; those stay eligible until they fall to the resume level, which is the
 * hysteresis that keeps a phase from stopping the moment the balance dips below the trigger.
 * [WallPhaseRequest.MANUAL] ignores trigger and resume; reserves are applied by [wallBulkCount].
 */
fun wallCurrencies(
    rules: WallStorageRules,
    balances: Map<Resource, Long>,
    capacities: Map<Resource, Long>,
    preference: String,
    active: Set<Resource>,
    request: WallPhaseRequest
): Set<Resource> = wallSpendable(preference).filterTo(linkedSetOf()) { resource ->
    val capacity = capacities[resource] ?: return@filterTo false
    val balance = balances[resource] ?: return@filterTo false
    if (capacity <= 0L || balance < 0L || balance > capacity) return@filterTo false
    when {
        request == WallPhaseRequest.MANUAL -> balance > 0L
        // Integer balance > real resume level exactly when it is above the floored level.
        resource in active -> balance > percentOfFloor(capacity, rules.resumePercent)
        else -> balance >= percentOfCeil(capacity, rules.triggerPercent)
    }
}

/**
 * Whether an agreed reading proves a storage cannot take more: a known, positive capacity and a
 * reading exactly at it. An unknown capacity, an unread balance or a reading under capacity is
 * not full; a reading above capacity is a misread and not full either.
 */
fun storageFull(resource: Resource, balances: Map<Resource, Long>, capacities: Map<Resource, Long>): Boolean {
    val capacity = capacities[resource] ?: return false
    return capacity > 0L && balances[resource] == capacity
}

/**
 * How many walls one purchase should select, before the panel's own limit on how far the
 * selection can grow. With [unit] the per-wall price read from the panel:
 * `min(ceil((balance - resumeLevel) / unit), floor((balance - reserve) / unit), maxWallsPerPhase)`.
 * The last wall may take the balance below the resume level because walls are indivisible, but
 * never below [reserve]. [WallPhaseRequest.MANUAL] drops the resume term. Zero means buy nothing.
 */
fun wallBulkCount(
    rules: WallStorageRules,
    balance: Long,
    capacity: Long,
    reserve: Long,
    unit: Long,
    maxWallsPerPhase: Int,
    request: WallPhaseRequest
): Int {
    if (unit <= 0L || balance < 0L || maxWallsPerPhase <= 0) return 0
    val toReserve = if (balance > reserve) (balance - reserve) / unit else 0L
    var count = minOf(toReserve, maxWallsPerPhase.toLong())
    if (request == WallPhaseRequest.AUTOMATIC) {
        if (capacity <= 0L) return 0
        val excess = balance - percentOfFloor(capacity, rules.resumePercent)
        // Ceiling division that cannot overflow: excess and unit are both positive here.
        val toResume = if (excess <= 0L) 0L else (excess - 1) / unit + 1
        count = minOf(count, toResume)
    }
    return count.coerceAtLeast(0L).toInt()
}
