package local.clashfarmer.desktop

import java.io.File
import java.util.Properties

/**
 * The version and commit the build stamped into `build-info.properties`. The version comes from
 * `appVersion` in gradle.properties, the one place it is set; the packaged app, Setup's About line,
 * desktop.log and Package.ps1's zip name all show the same one, so a report names its build.
 */
object BuildInfo {
    private val props = Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
    }

    /** "dev" when the resource is missing, as in an IDE run that skipped Gradle's resources. */
    val version: String = props.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"

    /** The short commit, or null when the build had no git. */
    val commit: String? = props.getProperty("commit")?.trim()?.takeIf { it.isNotEmpty() }

    /** "Clash Farmer PC 1.1.0 (0b2db8e)". */
    val name: String get() = name(version, commit)

    internal fun name(version: String, commit: String?): String =
        "Clash Farmer PC $version" + (commit?.let { " ($it)" } ?: "")

    /** Setup's About line: the build, and where its settings and logs are. */
    fun about(filesDir: File): String = about(version, commit, filesDir)

    internal fun about(version: String, commit: String?, filesDir: File): String =
        "${name(version, commit)}. Settings and logs are in ${filesDir.path}."
}
