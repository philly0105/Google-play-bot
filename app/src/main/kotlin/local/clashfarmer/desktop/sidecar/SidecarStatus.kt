package local.clashfarmer.desktop.sidecar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the sidecar's hello last said, for Setup's Text reader line. DesktopServices keeps it
 * current; it is null before the first answer and while the sidecar can't be reached.
 */
object SidecarStatus {
    @Volatile
    var hello: Hello? = null
        private set

    /**
     * Asks [sidecar] for hello every [LOADING_MS] while the text reader loads, then every
     * [SETTLED_MS], since a restarted sidecar loads it again.
     */
    fun watch(sidecar: Sidecar, scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            val answer = runCatching { sidecar.hello() }.getOrNull()
            hello = answer
            delay(if (settled(answer)) SETTLED_MS else LOADING_MS)
        }
    }

    /** Whether the reader is past loading: ready, or failed for good. */
    internal fun settled(hello: Hello?): Boolean = hello != null && (hello.ocrReady || hello.ocrError != null)

    private const val LOADING_MS = 3_000L
    private const val SETTLED_MS = 60_000L
}
