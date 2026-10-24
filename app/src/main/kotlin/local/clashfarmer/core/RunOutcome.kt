package local.clashfarmer.core

enum class CompletionReason(val key: String) {
    QUOTA_REACHED("quota_reached"), GOAL_REACHED("goal_reached"),
    /** Storage too full to hold more loot with nothing left to spend it on. */
    STORAGE_FULL("storage_full"),
    /** An earlier wall purchase was never proven, so this account may run no wall phase. */
    WALL_PENDING("wall_pending"),
    /** The run's minutes ran out; it ended at home, never mid-battle. */
    TIME_LIMIT("time_limit"),
    /** Ended at home on a low battery off the charger or a hot phone; see [DeviceGuard]. */
    DEVICE_GUARD("device_guard");

    companion object {
        fun fromKey(key: String): CompletionReason? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How a run ended normally. Cancellation and failures stay exceptions; they are never this.
 * [detail] says more than the reason where a person should know it, such as why a full storage
 * found no wall to spend on, or the battery or heat reading behind [CompletionReason.DEVICE_GUARD].
 */
data class RunOutcome(val completedAttacks: Int, val reason: CompletionReason, val detail: String? = null)
