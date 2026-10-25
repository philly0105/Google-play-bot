package local.clashfarmer.desktop.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.sidecar.PcInfo
import local.clashfarmer.desktop.sidecar.SidecarException
import local.clashfarmer.desktop.sidecar.SurfaceSize

/**
 * The Clash window as the last lookup saw it. [checked] is false until the first lookup ends.
 * [surface] is the game surface's last nonzero size, kept while the window is minimized.
 * [error] is why the last lookup failed, such as a sidecar that won't start.
 * [reopenedInRunningVm] is true once the window was opened again while Google Play Games kept the
 * game running behind it; see [ClashWindowWatcher]. [pc] is what the PC last said about Windows
 * and what is installed, null until it answers. [launchError] is why the last start of Clash
 * failed, until a window shows up.
 */
data class WindowStatus(
    val checked: Boolean = false,
    val window: ClashWindow? = null,
    val surface: SurfaceSize? = null,
    val error: String? = null,
    val reopenedInRunningVm: Boolean = false,
    val pc: PcInfo? = null,
    val launchError: String? = null
)

/**
 * Looks the Clash window up every [periodMs], so the run and the UI read it without waiting on
 * the sidecar. [find] is the sidecar's `find`; after a failure the watcher waits [retryMs].
 * [pcInfo], the sidecar's `pcInfo`, is asked every [pcInfoEveryMs] by [now]'s clock.
 *
 * Google Play Games hides the window when it is closed by hand and keeps the game running. Opening
 * Clash again shows the same game in the same process, and the bot's input stopped landing after
 * that in the spike (docs/gpg-spike.md, Restarting). So a window the same process showed, hid and
 * then showed again is flagged; a window from a new process clears the flag. A window first seen
 * hidden is not enough: a freshly started Google Play Games may create its window hidden and show
 * it once the game has loaded, and a false flag would refuse every run until the next restart.
 */
class ClashWindowWatcher(
    private val find: suspend () -> ClashWindow?,
    private val periodMs: Long = 500,
    private val retryMs: Long = 5_000,
    private val pcInfo: (suspend () -> PcInfo)? = null,
    private val pcInfoEveryMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val state = MutableStateFlow(WindowStatus())
    val status: StateFlow<WindowStatus> = state

    /**
     * The process behind the window last seen, whether that process has shown the window, and
     * whether the window was hidden when last seen.
     */
    private var owner: Pair<Long, Long?>? = null
    private var shown = false
    private var hidden = false
    private var reopened = false

    private var pc: PcInfo? = null
    private var pcAskedAt: Long? = null

    @Volatile private var launchError: String? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(if (check()) periodMs else retryMs)
        }
    }

    /** One lookup; false when it failed. */
    internal suspend fun check(): Boolean {
        refreshPcInfo()
        val window = try {
            find()
        } catch (e: SidecarException) {
            state.value = WindowStatus(checked = true, error = e.message ?: e.code, reopenedInRunningVm = reopened, pc = pc, launchError = launchError)
            return false
        }
        val surface = when {
            window == null -> null
            window.width > 0 && window.height > 0 -> SurfaceSize(window.width, window.height)
            else -> state.value.surface
        }
        noteOwner(window)
        if (window != null) launchError = null
        state.value = WindowStatus(
            checked = true, window = window, surface = surface, reopenedInRunningVm = reopened, pc = pc, launchError = launchError
        )
        return true
    }

    /** Records why a start of Clash failed, or clears it with null, for Setup to show. */
    fun launchResult(error: String?) {
        launchError = error
        state.update { it.copy(launchError = error) }
    }

    /**
     * Only a shown window hidden and then shown again counts. A lookup that misses the window for
     * a moment, as while Google Play Games lays it out, leaves what was last seen as it was.
     */
    private fun noteOwner(window: ClashWindow?) {
        window ?: return
        val pid = window.ownerPid
        if (pid != null) {
            val seen = pid to window.ownerStartedMs
            when {
                owner != seen -> {
                    reopened = false
                    shown = false
                }
                shown && hidden && window.visible -> reopened = true
            }
            owner = seen
            if (window.visible) shown = true
        }
        hidden = !window.visible
    }

    private suspend fun refreshPcInfo() {
        val ask = pcInfo ?: return
        val at = now()
        val last = pcAskedAt
        if (last != null && at - last < pcInfoEveryMs) return
        pcAskedAt = at
        // A sidecar that can't answer, or one from before the call, leaves the last answer.
        pc = try {
            ask()
        } catch (e: SidecarException) {
            pc
        }
    }
}
