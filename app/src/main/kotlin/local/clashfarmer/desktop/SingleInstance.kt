package local.clashfarmer.desktop

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * One app per files directory. Closing the window leaves the app in the tray, so starting it
 * again is the natural next step; a second copy would run its own sidecar and service on the same
 * Clash window and write the same logs and profiles.
 *
 * The holder keeps an OS lock on `app.lock` in the files directory for its whole life. Windows
 * drops the lock when the process ends, crash or not, so no stale lock can block the next start.
 * The holder also listens on a loopback port, written to `app.port`, and calls `onRaise` for each
 * "open" line, so a second start can ask it to show its window. Loopback listeners raise no
 * firewall prompt.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket
) : AutoCloseable {

    /** The loopback port a second start connects to. */
    val port: Int get() = server.localPort

    override fun close() {
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    /** The loopback port a second start would connect to could not be opened. */
    class LoopbackBlocked(cause: IOException) : IOException(cause.message, cause)

    companion object {
        const val LOCK_FILE = "app.lock"
        const val PORT_FILE = "app.port"
        private const val OPEN = "open"

        /** What the person reads when [acquire] failed with [error] on [dir]. */
        fun startupProblem(dir: File, error: IOException): StartupProblem = when (error) {
            is LoopbackBlocked -> StartupProblem("Clash Farmer couldn't open its connection on this PC " +
                "(${error.message}). Security software may be blocking it: allow ClashFarmerPC.exe there, then start it again.")
            else -> StartupProblem("Clash Farmer can't save its settings in ${dir.path} (${error.message}). " +
                "Check that the drive has free space and that your Windows account can write there, then start it again.")
        }

        /**
         * Takes the lock on [dir], or returns null when another app holds it. The holder calls
         * [onRaise] on its own thread each time a later start asks it to show itself. Throws
         * IOException when [dir] can't be created or written, and [LoopbackBlocked] when the
         * loopback port can't be opened.
         */
        fun acquire(dir: File, onRaise: () -> Unit): SingleInstance? {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create ${dir.path}")
            val channel = FileChannel.open(File(dir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // This JVM already holds it, which only tests do.
                null
            } catch (e: IOException) {
                // Another process holding the lock gives null, not this (SingleInstanceTest checks
                // it with a second JVM), so this is a folder that can't be written.
                channel.close()
                throw e
            }
            if (lock == null) {
                channel.close()
                return null
            }
            val server = try {
                ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            } catch (e: IOException) {
                lock.release()
                channel.close()
                throw LoopbackBlocked(e)
            }
            try {
                File(dir, PORT_FILE).writeText(server.localPort.toString())
            } catch (e: IOException) {
                server.close()
                lock.release()
                channel.close()
                throw e
            }
            thread(name = "single-instance", isDaemon = true) {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: IOException) { break }
                    runCatching {
                        client.use {
                            it.soTimeout = 2_000
                            if (it.getInputStream().bufferedReader().readLine() == OPEN) onRaise()
                        }
                    }
                }
            }
            return SingleInstance(channel, lock, server)
        }

        /**
         * Asks the app holding [dir]'s lock to show its window; true once it took the request. The
         * holder writes its port just after it takes the lock, so this tries for up to [waitMs].
         */
        fun raiseRunning(dir: File, waitMs: Long = 3_000): Boolean {
            val until = System.currentTimeMillis() + waitMs
            while (true) {
                val sent = runCatching {
                    val port = File(dir, PORT_FILE).readText().trim().toInt()
                    Socket().use {
                        it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                        it.getOutputStream().write("$OPEN\n".toByteArray())
                        it.getOutputStream().flush()
                    }
                }.isSuccess
                if (sent) return true
                if (System.currentTimeMillis() >= until) return false
                Thread.sleep(200)
            }
        }
    }
}
