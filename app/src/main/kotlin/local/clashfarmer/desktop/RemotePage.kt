package local.clashfarmer.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.clashfarmer.remote.RemoteApi
import local.clashfarmer.remote.RemoteServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Where the remote page listens on the PC: on the Tailscale adapter's address and on loopback,
 * never on every interface, and only while Tailscale is connected. The phone listened on all of
 * them, which on Windows asks for a firewall exception on first start and opens the port to the
 * local network for nothing, since the peer check lets only Tailscale and loopback in.
 *
 * Tailscale is found by its adapter's name, not by the 100.64.0.0/10 range RemoteAccess checks
 * peers against: that range is also the carrier-grade NAT range some ISPs and hotspots hand out.
 * [watch] checks again every minute, so a Tailscale that connects after the app starts still gets
 * the page, and one that disconnects closes it.
 *
 * [open] starts a listener on one address and returns null when the address can't be bound.
 */
class RemotePage(
    private val tailscale: () -> InetAddress? = ::tailscaleAddress,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val open: (InetAddress) -> Listener?
) {
    /** One bound address. */
    fun interface Listener {
        fun close()
    }

    /** What Setup shows: the Tailscale address, and whether the page listens on it. */
    data class Status(val tailscale: String?, val listening: Boolean)

    @Volatile
    var status = Status(null, false)
        private set

    private var served: InetAddress? = null
    private var listeners: List<Listener> = emptyList()
    private var retryAtMs = 0L
    private var job: Job? = null
    private var stopped = false

    /**
     * Opens, keeps or closes the listeners to match Tailscale now. A failed bind on the Tailscale
     * address, such as another program holding the port, is tried again after [RETRY_MS] rather
     * than every minute, since each try writes a line to the log. Loopback is a convenience for a
     * browser on this PC, so the page counts as on without it.
     */
    @Synchronized
    fun refresh() {
        if (stopped) return
        val address = runCatching { tailscale() }.getOrNull()
        if (address == served && (listeners.isNotEmpty() || address == null || nowMs() < retryAtMs)) {
            status = Status(address?.hostAddress, listeners.isNotEmpty())
            return
        }
        closeAll()
        served = address
        if (address != null) {
            val tailnet = open(address)
            if (tailnet == null) {
                retryAtMs = nowMs() + RETRY_MS
            } else {
                listeners = listOfNotNull(tailnet, open(InetAddress.getLoopbackAddress()))
            }
        }
        status = Status(address?.hostAddress, listeners.isNotEmpty())
    }

    /** Checks Tailscale now and then every [CHECK_MS] on [scope] until [stop]. */
    fun watch(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh()
                delay(CHECK_MS)
            }
        }
    }

    /** Closes the page for good; a [refresh] after this does nothing. */
    @Synchronized
    fun stop() {
        stopped = true
        job?.cancel()
        job = null
        closeAll()
        served = null
        status = Status(null, false)
    }

    private fun closeAll() {
        listeners.forEach { runCatching { it.close() } }
        listeners = emptyList()
    }

    /** One network adapter as [tailscaleAddress] reads it. */
    data class Adapter(val name: String, val displayName: String?, val up: Boolean, val addresses: List<InetAddress>)

    companion object {
        const val CHECK_MS = 60_000L
        const val RETRY_MS = 600_000L

        /** The IPv4 address of a connected adapter whose name says Tailscale ("Tailscale Tunnel" on Windows). */
        fun tailscaleAddress(adapters: List<Adapter>): InetAddress? = adapters
            .filter { it.up && (TAILSCALE in it.name.lowercase() || TAILSCALE in it.displayName.orEmpty().lowercase()) }
            .flatMap { it.addresses }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }

        /** [tailscaleAddress] over this PC's adapters; null when they can't be read. */
        fun tailscaleAddress(): InetAddress? = runCatching {
            tailscaleAddress(NetworkInterface.getNetworkInterfaces().toList().map {
                Adapter(it.name, it.displayName, it.isUp, it.inetAddresses.toList())
            })
        }.getOrNull()

        /** The page over [api], each address its own [RemoteServer] on [port]. */
        fun serving(
            api: RemoteApi,
            port: Int = RemoteServer.PORT,
            tailscale: () -> InetAddress? = ::tailscaleAddress
        ): RemotePage = RemotePage(tailscale) { address ->
            val server = RemoteServer(port, api, address).also { it.start() }
            if (server.isListening) Listener(server::stop) else null
        }

        private const val TAILSCALE = "tailscale"
    }
}
