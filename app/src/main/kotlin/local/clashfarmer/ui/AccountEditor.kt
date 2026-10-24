package local.clashfarmer.ui

/** The phone's account editor sheet is not ported; this is its one pure rule, which a test pins. */
object AccountEditor {
    /** Save is on between runs, while no save is in flight. */
    internal fun saveEnabled(busy: Boolean, saving: Boolean) = !busy && !saving
}
