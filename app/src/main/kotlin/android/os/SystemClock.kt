package android.os

/**
 * android.os.SystemClock on the JVM's monotonic clock. Both read System.nanoTime(), so Android's
 * difference between them (uptimeMillis stops in deep sleep) does not exist here. RunGuard's
 * fallback clock reads the same value, so its tests see no change.
 */
object SystemClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
    fun uptimeMillis(): Long = System.nanoTime() / 1_000_000L
}
