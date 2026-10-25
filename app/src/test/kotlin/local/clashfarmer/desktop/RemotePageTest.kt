package local.clashfarmer.desktop

import local.clashfarmer.desktop.RemotePage.Adapter
import local.clashfarmer.remote.RemoteApi
import local.clashfarmer.remote.RemoteBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RemotePageTest {
    private val tailnet = InetAddress.getByAddress(byteArrayOf(100, 101, 102, 103))
    private val other = InetAddress.getByAddress(byteArrayOf(100, 101, 102, 104))
    private val loopback = InetAddress.getLoopbackAddress()

    /** A page over a fake Tailscale and fake listeners, recording what it opened and closed. */
    private inner class Fixture(var address: InetAddress?) {
        var now = 0L
        val opened = mutableListOf<InetAddress>()
        val closed = mutableListOf<InetAddress>()
        val refused = mutableSetOf<InetAddress>()
        val page = RemotePage({ address }, { now }) { at ->
            if (at in refused) null else {
                opened += at
                RemotePage.Listener { closed += at }
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
        assertEquals(listOf(tailnet, loopback), f.opened)
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
        assertEquals(listOf(tailnet, loopback), f.opened)
        assertTrue(f.page.status.listening)
    }

    @Test
    fun `Tailscale disconnecting turns the page off`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.address = null
        f.page.refresh()
        assertEquals(listOf(tailnet, loopback), f.closed)
        assertEquals(RemotePage.Status(null, false), f.page.status)
    }

    @Test
    fun `a new Tailscale address moves the page`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.address = other
        f.page.refresh()
        assertEquals(listOf(tailnet, loopback), f.closed)
        assertEquals(listOf(tailnet, loopback, other, loopback), f.opened)
        assertEquals("100.101.102.104", f.page.status.tailscale)
    }

    @Test
    fun `a port taken on the tailnet is tried again only after the retry time`() {
        val f = Fixture(tailnet)
        f.refused += tailnet
        f.page.refresh()
        assertEquals(RemotePage.Status("100.101.102.103", false), f.page.status)
        f.refused.clear()
        f.now = RemotePage.RETRY_MS - 1
        f.page.refresh()
        assertTrue(f.opened.isEmpty())
        f.now = RemotePage.RETRY_MS
        f.page.refresh()
        assertEquals(listOf(tailnet, loopback), f.opened)
        assertTrue(f.page.status.listening)
    }

    @Test
    fun `a loopback port taken elsewhere still leaves the page on`() {
        val f = Fixture(tailnet)
        f.refused += loopback
        f.page.refresh()
        assertEquals(listOf(tailnet), f.opened)
        assertTrue(f.page.status.listening)
    }

    @Test
    fun `stop closes the listeners and keeps them closed`() {
        val f = Fixture(tailnet)
        f.page.refresh()
        f.page.stop()
        f.page.refresh()
        assertEquals(listOf(tailnet, loopback), f.closed)
        assertEquals(2, f.opened.size)
        assertEquals(RemotePage.Status(null, false), f.page.status)
    }

    @Test
    fun `Tailscale is found by its adapter's name, not by the address range`() {
        val hotspot = Adapter("wlan0", "Intel Wi-Fi 6 AX201", up = true, addresses = listOf(other))
        val tunnel = Adapter("iftype53_32768", "Tailscale Tunnel", up = true, addresses = listOf(tailnet))
        assertNull(RemotePage.tailscaleAddress(listOf(hotspot)))
        assertEquals(tailnet, RemotePage.tailscaleAddress(listOf(hotspot, tunnel)))
    }

    @Test
    fun `a Tailscale adapter that is down or has no IPv4 address does not count`() {
        val v6 = InetAddress.getByName("fd7a:115c:a1e0::1")
        assertNull(RemotePage.tailscaleAddress(listOf(Adapter("tailscale0", null, up = false, addresses = listOf(tailnet)))))
        assertNull(RemotePage.tailscaleAddress(listOf(Adapter("tailscale0", null, up = true, addresses = listOf(v6)))))
        assertEquals(tailnet, RemotePage.tailscaleAddress(listOf(Adapter("tailscale0", null, up = true, addresses = listOf(v6, tailnet)))))
    }

    @Test
    fun `the real server listens on the address it is given and stops`() {
        // Loopback stands in for the tailnet address, on a free port, so the test needs no Tailscale.
        // No request reaches the backend: the test only connects.
        val backend = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RemoteBackend::class.java)) { _, _, _ ->
            throw UnsupportedOperationException()
        } as RemoteBackend
        val api = RemoteApi("0123456789abcdef0123456789abcdef", backend, "<html></html>")
        val port = java.net.ServerSocket(0, 1, loopback).use { it.localPort }
        val page = RemotePage.serving(api, port) { loopback }
        try {
            page.refresh()
            assertTrue(page.status.listening)
            java.net.Socket(loopback, port).close()
        } finally {
            page.stop()
        }
        assertFalse(runCatching { java.net.Socket(loopback, port).close() }.isSuccess)
    }
}
