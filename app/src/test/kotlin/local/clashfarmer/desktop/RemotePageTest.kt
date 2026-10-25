package local.clashfarmer.desktop

import local.clashfarmer.desktop.RemotePage.Adapter
import local.clashfarmer.remote.RemoteApi
import local.clashfarmer.remote.RemoteBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.InetAddress

class RemotePageTest {
    private val tailnetV4 = InetAddress.getByAddress(byteArrayOf(100, 101, 102, 103))
    private val otherV4 = InetAddress.getByAddress(byteArrayOf(100, 101, 102, 104))
    private val tailnetV6 = InetAddress.getByName("fd7a:115c:a1e0::1")
    private val tailnet = RemotePage.Tailnet(tailnetV4)
    private val other = RemotePage.Tailnet(otherV4)
    private val loopback = InetAddress.getLoopbackAddress()

    private val inUse get() = BindException("Address already in use: bind")
    private val notReady get() = BindException("Cannot assign requested address: bind")

    /** A page over a fake Tailscale and fake listeners, recording what it opened and closed. */
    private inner class Fixture(var address: RemotePage.Tailnet?) {
        var now = 0L
        val opened = mutableListOf<InetAddress>()
        val closed = mutableListOf<InetAddress>()
        /** Addresses that fail to bind, with the failure. */
        val refused = mutableMapOf<InetAddress, Exception>()
        /** Addresses whose listener stopped answering. */
        val dead = mutableSetOf<InetAddress>()
        val page = RemotePage({ address }, { now }) { at ->
            refused[at]?.let { throw it }
            opened += at
            object : RemotePage.Listener {
                override fun close() { closed += at }
                override val listening: Boolean get() = at !in dead
            }
        }
    }

    @Test
    fun `without Tailscale nothing listens`() {
        val f = Fixture(null)
        f.page.refresh()
        assertTrue(f.opened.isEmpty())
        assertEquals(RemotePage.Status(null, false), f.page.status)
    }

    @Test
    fun `with Tailscale the page listens on its address and loopback only`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.opened)
        assertEquals(RemotePage.Status("100.101.102.103", true), f.page.status)
    }

    @Test
    fun `a tailnet IPv6 address gets its own listener`() {
        val f = Fixture(RemotePage.Tailnet(tailnetV4, tailnetV6))
        f.page.refresh()
        assertEquals(listOf(tailnetV4, tailnetV6, loopback), f.opened)
        assertEquals(RemotePage.Status("100.101.102.103", true), f.page.status)
    }

    @Test
    fun `a steady address keeps the same listeners`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.page.refresh()
        assertEquals(2, f.opened.size)
        assertTrue(f.closed.isEmpty())
    }

    @Test
    fun `Tailscale connecting later turns the page on`() {
        val f = Fixture(null)
        f.page.refresh()
        f.address = tailnet
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.opened)
        assertTrue(f.page.status.listening)
    }

    @Test
    fun `Tailscale disconnecting turns the page off`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.address = null
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.closed)
        assertEquals(RemotePage.Status(null, false), f.page.status)
    }

    @Test
    fun `a new Tailscale address moves the page`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.address = other
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.closed)
        assertEquals(listOf(tailnetV4, loopback, otherV4, loopback), f.opened)
        assertEquals("100.101.102.104", f.page.status.tailscale)
    }

    @Test
    fun `a port taken on the tailnet is tried again only after the retry time`() {
        val f = Fixture(tailnet)
        f.refused[tailnetV4] = inUse
        f.page.refresh()
        assertEquals(RemotePage.Status("100.101.102.103", false, RemotePage.Failure.PORT_IN_USE), f.page.status)
        f.refused.clear()
        f.now = RemotePage.RETRY_MS - 1
        f.page.refresh()
        assertTrue(f.opened.isEmpty())
        assertEquals(RemotePage.Failure.PORT_IN_USE, f.page.status.failure)
        f.now = RemotePage.RETRY_MS
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.opened)
        assertEquals(RemotePage.Status("100.101.102.103", true), f.page.status)
    }

    @Test
    fun `an address Windows won't bind yet is tried again on the next check`() {
        val f = Fixture(tailnet)
        f.refused[tailnetV4] = notReady
        f.page.refresh()
        assertEquals(RemotePage.Status("100.101.102.103", false, RemotePage.Failure.OTHER), f.page.status)
        f.refused.clear()
        f.now = RemotePage.CHECK_MS
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.opened)
        assertEquals(RemotePage.Status("100.101.102.103", true), f.page.status)
    }

    @Test
    fun `a listener that stopped answering is opened again on the same address`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.dead += tailnetV4
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.closed)
        assertEquals(listOf(tailnetV4, loopback, tailnetV4, loopback), f.opened)
    }

    @Test
    fun `a loopback or IPv6 port taken elsewhere still leaves the page on`() {
        val f = Fixture(RemotePage.Tailnet(tailnetV4, tailnetV6))
        f.refused[loopback] = inUse
        f.refused[tailnetV6] = notReady
        f.page.refresh()
        assertEquals(listOf(tailnetV4), f.opened)
        assertTrue(f.page.status.listening)
    }

    @Test
    fun `only an address in use counts as a taken port`() {
        assertTrue(RemotePage.portInUse(inUse))
        assertFalse(RemotePage.portInUse(notReady))
        assertFalse(RemotePage.portInUse(java.io.IOException("Address already in use")))
    }

    @Test
    fun `Java's own failures on a taken port and a foreign address read as such`() {
        val taken = java.net.ServerSocket(0, 1, loopback)
        try {
            val second = runCatching { java.net.ServerSocket(taken.localPort, 1, loopback) }
            assertTrue(RemotePage.portInUse(second.exceptionOrNull()!!))
        } finally {
            taken.close()
        }
        // 192.0.2.1 is a documentation address that no adapter here has.
        val foreign = runCatching { java.net.ServerSocket(0, 1, InetAddress.getByName("192.0.2.1")) }
        assertFalse(RemotePage.portInUse(foreign.exceptionOrNull()!!))
    }

    @Test
    fun `stop closes the listeners and keeps them closed`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.page.stop()
        f.page.refresh()
        assertEquals(listOf(tailnetV4, loopback), f.closed)
        assertEquals(2, f.opened.size)
        assertEquals(RemotePage.Status(null, false), f.page.status)
    }

    @Test
    fun `Tailscale is found by its adapter's name, not by the address range`() {
        val hotspot = Adapter("wlan0", "Intel Wi-Fi 6 AX201", up = true, addresses = listOf(otherV4))
        val tunnel = Adapter("iftype53_32768", "Tailscale Tunnel", up = true, addresses = listOf(tailnetV4))
        assertNull(RemotePage.tailnet(listOf(hotspot)))
        assertEquals(tailnet, RemotePage.tailnet(listOf(hotspot, tunnel)))
    }

    @Test
    fun `a Tailscale adapter that is down or has no IPv4 address does not count`() {
        assertNull(RemotePage.tailnet(listOf(Adapter("tailscale0", null, up = false, addresses = listOf(tailnetV4)))))
        assertNull(RemotePage.tailnet(listOf(Adapter("tailscale0", null, up = true, addresses = listOf(tailnetV6)))))
    }

    @Test
    fun `the tailnet IPv6 address is kept and the link-local one skipped`() {
        val linkLocal = InetAddress.getByName("fe80::1")
        val withV6 = Adapter("tailscale0", null, up = true, addresses = listOf(linkLocal, tailnetV6, tailnetV4))
        assertEquals(RemotePage.Tailnet(tailnetV4, tailnetV6), RemotePage.tailnet(listOf(withV6)))
        val linkLocalOnly = Adapter("tailscale0", null, up = true, addresses = listOf(linkLocal, tailnetV4))
        assertEquals(tailnet, RemotePage.tailnet(listOf(linkLocalOnly)))
    }

    @Test
    fun `the real server listens on the address it is given and stops`() {
        // Loopback stands in for the tailnet address, on a free port, so the test needs no Tailscale.
        // No request reaches the backend: the test only connects.
        val port = freePort()
        val page = RemotePage.serving(api(), port) { RemotePage.Tailnet(loopback) }
        try {
            page.refresh()
            assertTrue(page.status.listening)
            java.net.Socket(loopback, port).close()
        } finally {
            page.stop()
        }
        assertFalse(runCatching { java.net.Socket(loopback, port).close() }.isSuccess)
    }

    @Test
    fun `the real server reports a port another program holds`() {
        val taken = java.net.ServerSocket(0, 1, loopback)
        val page = RemotePage.serving(api(), taken.localPort) { RemotePage.Tailnet(loopback) }
        try {
            page.refresh()
            assertEquals(RemotePage.Status(loopback.hostAddress, false, RemotePage.Failure.PORT_IN_USE), page.status)
        } finally {
            page.stop()
            taken.close()
        }
    }

    private fun api(): RemoteApi {
        val backend = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RemoteBackend::class.java)) { _, _, _ ->
            throw UnsupportedOperationException()
        } as RemoteBackend
        return RemoteApi("0123456789abcdef0123456789abcdef", backend, "<html></html>")
    }

    private fun freePort(): Int = java.net.ServerSocket(0, 1, loopback).use { it.localPort }
}
