package local.clashfarmer.desktop

import local.clashfarmer.core.AtomicFile
import local.clashfarmer.remote.RemoteApi
import local.clashfarmer.remote.RemoteKey
import local.clashfarmer.remote.RemoteServer
import local.clashfarmer.remote.ServiceRemoteBackend
import local.clashfarmer.service.FarmerService
import java.io.File
import java.security.SecureRandom

/**
 * The remote page's link and the key in it. The phone made the key once (RemoteKey) and kept it
 * for good, so a link that got out, through clipboard history, a browser or a chat, gave control
 * of runs until someone deleted remote_key.txt by hand. Setup's New remote link calls [renew]: a
 * fresh key goes into the file and into the running page, and the old link stops working.
 */
object RemoteLink {
    /** The phone's key file, in the files directory. */
    const val KEY_FILE = "remote_key.txt"

    /** The link Setup copies: the page on [address] with [key] in it. */
    fun url(address: String, key: String, port: Int = RemoteServer.PORT): String = "http://$address:$port/?key=$key"

    /** The key the page answers to: the file's, which the app made at start or [renew] last wrote. */
    fun key(filesDir: File): String = RemoteKey.loadOrCreate(File(filesDir, KEY_FILE))

    /**
     * Writes a fresh key in one step, then hands [page] an api built on it by [apiFor], and returns
     * the key. The file is written first, so a failed write leaves the old key working everywhere.
     */
    fun renew(filesDir: File, page: RemotePage, apiFor: (String) -> RemoteApi): String {
        val key = newKey()
        val file = File(filesDir, KEY_FILE)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$KEY_FILE.tmp")
        tmp.writeText(key)
        AtomicFile.replace(tmp, file)
        page.use(apiFor(key))
        return key
    }

    /** [renew] for the running app: the same page, backend and remote.html FarmerService serves. Null with no page. */
    fun renew(service: FarmerService): String? {
        val page = FarmerService.remotePage ?: return null
        val html = service.assets.open("remote.html").bufferedReader().use { it.readText() }
        return renew(service.filesDir, page) { RemoteApi(it, ServiceRemoteBackend(service), html) }
    }

    /** 16 random bytes as 32 hex digits, as RemoteKey makes them, so RemoteKey reads the file back. */
    internal fun newKey(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
