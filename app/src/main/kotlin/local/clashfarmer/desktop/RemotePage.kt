package local.clashfarmer.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.clashfarmer.remote.RemoteAccess
import local.clashfarmer.remote.RemoteApi
import local.clashfarmer.remote.RemoteServer
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Where the remote page listens on the PC: on the Tailscale adapter's addresses and on loopback,
 * never on every interface, and only while Tailscale is connected. The phone listened on all of
 * them, which on Windows asks for a firewall exception on first start and opens the port to the
 * local network for nothing, since the peer check lets only Tailscale and loopback in.
 *
 * Tailscale is found by its adapter's name, not by the 100.64.0.0/10 range RemoteAccess checks
 * peers against: that range is also the carrier-grade NAT range some ISPs and hotspots hand out.
 * [watch] checks again every minute, so a Tailscale that connects after the app starts still gets
 * the page, one that disconnects closes it, and a listener that stopped answering is opened again.
 *
 * [open] starts a listener on one address and throws why when the address can't be bound.
 */
class RemotePage(
    private val tailscale: () -> Tailnet? = ::tailnet,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val open: (InetAddress) -> Listener
) {
    /** One bound address. */
    fun interface Listener {
        fun close()

        /** False once the listener stopped answering on its own, as after a failed accept. */
        val listening: Boolean get() = true

        /** Answers with [api] from now on, on the socket already open. */
        fun use(api: RemoteApi) {}
    }

    /**
     * The api [use] last handed over, which listeners opened later answer with too; null until
     * then, when [open] decides.
     */
    @Volatile
    var api: RemoteApi? = null
        private set

    /**
     * Swaps [api] into every open listener and the ones opened later, without closing a socket:
     * Windows can refuse to bind the port again for minutes after a close, so a new key would
     * otherwise turn the page off. The old api, and the key it checks, stop answering at once.
     */
    @Synchronized
    fun use(api: RemoteApi) {
        this.api = api
        listeners.forEach { it.use(api) }
    }

    /**
     * Tailscale's addresses on this PC: the IPv4 one, which the copied link uses and Setup shows,
     * and the tailnet IPv6 one (fd7a:115c:a1e0::/48) when the adapter has it, which the phone's
     * all-interfaces listen also answered on.
     */
    data class Tailnet(val v4: InetAddress, val v6: InetAddress? = null)

    /** Why the page could not listen on the Tailscale address. */
    enum class Failure { PORT_IN_USE, OTHER }

    /** What Setup shows: the Tailscale address, whether the page listens on it, and if not, why. */
    data class Status(val tailscale: String?, val listening: Boolean, val failure: Failure? = null)

    @Volatile
    var status = Status(null, false)
        private set

    private var served: Tailnet? = null
    private var listeners: List<Listener> = emptyList()
    private var retryAtMs = 0L
    private var job: Job? = null
    private var stopped = false

    /**
     * Opens, keeps or closes the listeners to match Tailscale now. When another program holds the
     * port on the Tailscale address, the next try waits [RETRY_MS] rather than a minute, since
     * each try writes a line to the log and that program rarely goes away by itself. Any other
     * failed bind, such as an address Windows doesn't accept yet after sleep or a Tailscale
     * reconnect, is tried again on the next check. The IPv6 and loopback listeners are
     * conveniences, so the page counts as on without them.
     */
    @Synchronized
    fun refresh() {
        if (stopped) return
        val address = runCatching { tailscale() }.getOrNull()
        val alive = listeners.all { it.listening }
        if (address == served && alive && (listeners.isNotEmpty() || address == null || nowMs() < retryAtMs)) {
            status = Status(address?.v4?.hostAddress, listeners.isNotEmpty(), status.failure)
            return
        }
        closeAll()
        served = address
        var failure: Failure? = null
        if (address != null) {
            try {
                val tailnet = open(address.v4)
                listeners = listOf(tailnet) + listOfNotNull(address.v6, InetAddress.getLoopbackAddress())
                    .mapNotNull { runCatching { open(it) }.getOrNull() }
            } catch (e: Exception) {
                failure = if (portInUse(e)) Failure.PORT_IN_USE else Failure.OTHER
                retryAtMs = if (failure == Failure.PORT_IN_USE) nowMs() + RETRY_MS else 0L
            }
        }
        status = Status(address?.v4?.hostAddress, listeners.isNotEmpty(), failure)
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

    /** One network adapter as [tailnet] reads it. */
    data class Adapter(val name: String, val displayName: String?, val up: Boolean, val addresses: List<InetAddress>)

    companion object {
        const val CHECK_MS = 60_000L
        const val RETRY_MS = 600_000L

        /**
         * The addresses of a connected adapter whose name says Tailscale ("Tailscale Tunnel" on
         * Windows); null without an IPv4 one. Its link-local IPv6 address is skipped.
         */
        fun tailnet(adapters: List<Adapter>): Tailnet? {
            val addresses = adapters
                .filter { it.up && (TAILSCALE in it.name.lowercase() || TAILSCALE in it.displayName.orEmpty().lowercase()) }
                .flatMap { it.addresses }
                .filter { !it.isLoopbackAddress }
            val v4 = addresses.firstOrNull { it is Inet4Address } ?: return null
            return Tailnet(v4, addresses.firstOrNull { it is Inet6Address && RemoteAccess.peerAllowed(it) })
        }

        /** [tailnet] over this PC's adapters; null when they can't be read. */
        fun tailnet(): Tailnet? = runCatching {
            tailnet(NetworkInterface.getNetworkInterfaces().toList().map {
                Adapter(it.name, it.displayName, it.isUp, it.inetAddresses.toList())
            })
        }.getOrNull()

        /**
         * Whether a failed bind means another program holds the port. Java gives both a taken port
         * and an address Windows won't bind as a BindException, told apart only by the message
         * ("Address already in use" against "Cannot assign requested address").
         */
        fun portInUse(error: Throwable): Boolean =
            error is BindException && error.message.orEmpty().contains("in use", ignoreCase = true)

        /** The page over [api], each address its own [RemoteServer] on [port]; [use] replaces the api. */
        fun serving(
            api: RemoteApi,
            port: Int = RemoteServer.PORT,
            tailscale: () -> Tailnet? = ::tailnet
        ): RemotePage {
            lateinit var page: RemotePage
            page = RemotePage(tailscale) { address ->
                val server = RemoteServer(port, page.api ?: api, address).also { it.start() }
                if (!server.isListening) throw server.startError ?: IOException("The remote page did not start on ${address.hostAddress}")
                object : Listener {
                    override fun close() = server.stop()
                    override val listening: Boolean get() = server.isListening
                    override fun use(api: RemoteApi) { server.api = api }
                }
            }
            return page
        }

        private const val TAILSCALE = "tailscale"
    }
}
