package local.clashfarmer.desktop.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.sidecar.SidecarException
import local.clashfarmer.desktop.sidecar.SurfaceSize

/**
 * The Clash window as the last lookup saw it. [checked] is false until the first lookup ends.
 * [surface] is the game surface's last nonzero size, kept while the window is minimized.
 * [error] is why the last lookup failed, such as a sidecar that won't start.
 */
data class WindowStatus(
    val checked: Boolean = false,
    val window: ClashWindow? = null,
    val surface: SurfaceSize? = null,
    val error: String? = null
)

/**
 * Looks the Clash window up every [periodMs], so the run and the UI read it without waiting on
 * the sidecar. [find] is the sidecar's `find`; after a failure the watcher waits [retryMs].
 */
class ClashWindowWatcher(
    private val find: suspend () -> ClashWindow?,
    private val periodMs: Long = 500,
    private val retryMs: Long = 5_000
) {
    private val state = MutableStateFlow(WindowStatus())
    val status: StateFlow<WindowStatus> = state

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(if (check()) periodMs else retryMs)
        }
    }

    /** One lookup; false when it failed. */
    internal suspend fun check(): Boolean {
        val window = try {
            find()
        } catch (e: SidecarException) {
            state.value = WindowStatus(checked = true, error = e.message ?: e.code)
            return false
        }
        val surface = when {
            window == null -> null
            window.width > 0 && window.height > 0 -> SurfaceSize(window.width, window.height)
            else -> state.value.surface
        }
        state.value = WindowStatus(checked = true, window = window, surface = surface)
        return true
    }
}
