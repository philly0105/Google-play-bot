package local.clashfarmer.service

/**
 * Which stop of a run is its real one: the first. Stopping cancels the run, and work still in
 * flight then fails and stops it again - at 06:28:06 on 8 October a panel stop was followed in
 * the same second by "Pinch ... was cancelled by a touch on the screen". Those later stops are
 * side effects and must not replace the first reason. A stop with no run is always first.
 */
class FirstStop {
    private var stoppedRun: Any? = null

    @Synchronized
    fun isFirst(run: Any?): Boolean {
        if (run == null) return true
        if (run === stoppedRun) return false
        stoppedRun = run
        return true
    }
}
