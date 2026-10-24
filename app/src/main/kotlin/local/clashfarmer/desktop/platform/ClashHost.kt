package local.clashfarmer.desktop.platform

import kotlinx.coroutines.flow.StateFlow
import local.clashfarmer.desktop.sidecar.PowerState
import local.clashfarmer.desktop.sidecar.Sidecar

/**
 * What FarmerService asks of the PC in place of Android's system services: the Clash window,
 * starting and closing Clash, the battery, and keeping the PC awake through a run.
 */
interface ClashHost {
    /** The Clash window as [ClashWindowWatcher] last saw it. */
    val window: StateFlow<WindowStatus>

    /** Starts Clash through Google Play Games, which may bring its window to the front. */
    suspend fun launch()

    /** Closes the Clash window, as the phone's Home put Clash in the background. */
    suspend fun close()

    suspend fun power(): PowerState

    /** Holds the system and the display awake while [on]. */
    suspend fun keepAwake(on: Boolean)
}

class SidecarClashHost(private val sidecar: Sidecar, watcher: ClashWindowWatcher) : ClashHost {
    override val window: StateFlow<WindowStatus> = watcher.status

    override suspend fun launch() = sidecar.launch(CLASH_URL)

    override suspend fun close() = sidecar.closeClash()

    override suspend fun power(): PowerState = sidecar.power()

    override suspend fun keepAwake(on: Boolean) = sidecar.keepAwake(on)

    companion object {
        const val CLASH_URL = "googleplaygames://launch/?id=com.supercell.clashofclans&lid=1&pid=1"
    }
}
