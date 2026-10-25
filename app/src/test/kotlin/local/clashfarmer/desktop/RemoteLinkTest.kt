package local.clashfarmer.desktop

import local.clashfarmer.remote.RemoteApi
import local.clashfarmer.remote.RemoteBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI

class RemoteLinkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun `a new key is 32 hex digits and differs each time`() {
        val a = RemoteLink.newKey()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(a))
        assertNotEquals(a, RemoteLink.newKey())
    }

    @Test
    fun `the link is the page's address with the key in it`() {
        assertEquals("http://100.101.102.103:8765/?key=abc", RemoteLink.url("100.101.102.103", "abc", 8765))
    }

    @Test
    fun `a new link writes the key file and the old link stops working`() {
        // Loopback stands in for the tailnet address, on a free port, so the test needs no Tailscale.
        val port = java.net.ServerSocket(0, 1, loopback).use { it.localPort }
        val old = RemoteLink.key(folder.root)
        val page = RemotePage.serving(api(old), port) { RemotePage.Tailnet(loopback) }
        try {
            page.refresh()
            assertTrue(page.status.listening)
            assertEquals(200, get(port, old))
            val fresh = RemoteLink.renew(folder.root, page) { api(it) }
            assertNotEquals(old, fresh)
            assertEquals(fresh, File(folder.root, RemoteLink.KEY_FILE).readText())
            assertEquals(fresh, RemoteLink.key(folder.root))
            assertEquals(401, get(port, old))
            assertEquals(200, get(port, fresh))
        } finally {
            page.stop()
        }
    }

    @Test
    fun `a listener opened after a new link answers to the new key`() {
        val port = java.net.ServerSocket(0, 1, loopback).use { it.localPort }
        val old = RemoteLink.key(folder.root)
        var connected = false
        val page = RemotePage.serving(api(old), port) { if (connected) RemotePage.Tailnet(loopback) else null }
        try {
            page.refresh()
            val fresh = RemoteLink.renew(folder.root, page) { api(it) }
            connected = true
            page.refresh()
            assertTrue(page.status.listening)
            assertEquals(401, get(port, old))
            assertEquals(200, get(port, fresh))
        } finally {
            page.stop()
        }
    }

    /** The page's status code for a GET of the page with [key]. */
    private fun get(port: Int, key: String): Int {
        val connection = URI("http://${loopback.hostAddress}:$port/?key=$key").toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** An api whose page needs no backend; any other call fails the test. */
    private fun api(key: String): RemoteApi {
        val backend = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RemoteBackend::class.java)) { _, _, _ ->
            throw UnsupportedOperationException()
        } as RemoteBackend
        return RemoteApi(key, backend, "<html></html>")
    }
}
