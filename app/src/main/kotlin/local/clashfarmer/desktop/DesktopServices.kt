package local.clashfarmer.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import local.clashfarmer.desktop.ocr.OcrBackends
import local.clashfarmer.desktop.ocr.SidecarOcr
import local.clashfarmer.desktop.platform.ClashHost
import local.clashfarmer.desktop.platform.ClashWindowWatcher
import local.clashfarmer.desktop.platform.GameSurface
import local.clashfarmer.desktop.platform.SidecarClashHost
import local.clashfarmer.desktop.platform.SidecarGameSurface
import local.clashfarmer.desktop.sidecar.Sidecar
import local.clashfarmer.desktop.sidecar.SidecarStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The PC services FarmerService runs on, started once at launch: the sidecar, OCR through it, the
 * Clash window watcher, and the host and game surface over them. [close] stops them all.
 */
class DesktopServices(val filesDir: File, sidecarExe: File) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Plain lines from the sidecar and the game surface, kept beside the phone's event logs. */
    val log: (String) -> Unit = DesktopLog.of(File(filesDir, "logs/desktop.log"))::write

    val sidecar = Sidecar.launching(sidecarExe, log)
    private val watcher = ClashWindowWatcher({ sidecar.find() }, pcInfo = { sidecar.pcInfo() }).also { it.start(scope) }
    val host: ClashHost = SidecarClashHost(sidecar, watcher)
    val surface: GameSurface = SidecarGameSurface(sidecar, scope, log)

    init {
        OcrBackends.install(SidecarOcr(sidecar))
        SidecarStatus.watch(sidecar, scope)
    }

    override fun close() {
        scope.cancel()
        sidecar.close()
    }

    companion object {
        /** `%LOCALAPPDATA%\ClashFarmerPC\files`, in place of the phone's Context.filesDir. */
        fun defaultFilesDir(): File =
            File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "ClashFarmerPC/files")

        /** The sidecar the build put in the app's resources (docs/SIDECAR.md). */
        fun bundledSidecar(): File {
            val resources = System.getProperty("compose.application.resources.dir")
                ?: error("compose.application.resources.dir isn't set; start the app with Gradle's run task or the distributable")
            return File(resources, "sidecar/ClashFarmer.Native.exe")
        }
    }
}

/** Appends timestamped lines to [file], moving it to `.1` once it passes [maxBytes]. */
internal class DesktopLog(private val file: File, private val maxBytes: Long = 5_000_000) {
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Standard error as it was before the app sent its own here, so a failure can't loop back in. */
    private val fallback = System.err

    @Synchronized
    fun write(line: String) {
        try {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) {
                Files.move(file.toPath(), File(file.path + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            file.appendText("${format.format(Date())} $line\n")
        } catch (e: Exception) {
            fallback.println("ClashFarmer: can't write ${file.path}: ${e.message}")
        }
    }

    companion object {
        private val open = java.util.concurrent.ConcurrentHashMap<String, DesktopLog>()

        /**
         * The one log for [file] in this process. [write] locks only its own instance, so two
         * instances on one file could both rotate it at once and lose a line, such as a crash's
         * stack; CrashReport and DesktopServices both write desktop.log through this.
         */
        fun of(file: File): DesktopLog = open.computeIfAbsent(file.absoluteFile.normalize().path.lowercase(Locale.ROOT)) { DesktopLog(file) }
    }
}
