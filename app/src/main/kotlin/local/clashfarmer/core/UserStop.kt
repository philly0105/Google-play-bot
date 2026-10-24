package local.clashfarmer.core

/**
 * The stop reasons a person causes. The stop sites pass these and the failure snapshot reads them,
 * so rewording one cannot quietly turn a person's Stop into a saved failure.
 */
object UserStop {
    const val FLOATING_PANEL = "Stopped from floating panel"
    const val DASHBOARD = "Stopped from dashboard"
    const val REMOTE = "Stopped from remote"
    const val SCREEN_OFF = "Screen turned off (latched stop)"
    const val NOTIFICATION = "Stopped from notification"

    val ALL = listOf(FLOATING_PANEL, DASHBOARD, REMOTE, SCREEN_OFF, NOTIFICATION)

    /** Also true for the guard's "Session is stopped: ..." wrapping of one of these. */
    fun matches(reason: String?): Boolean = reason != null && ALL.any { reason.contains(it) }
}
