package local.clashfarmer.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

/**
 * The game-area shapes an account's calibration has worked at, besides its own: surfaces.json
 * beside its profile.json, `{"verifiedAspects":[1.6]}`.
 *
 * A calibration moved onto another shape ([PcProfiles.derive]) is unverified until one supervised
 * attack works there, as a new calibration is. That success is kept here rather than in
 * profile.json, which holds only the points the user tapped, at the size they were tapped at.
 * Saving a calibration clears the file, since the shapes were checked against the old points.
 */
object SurfaceVerification {
    const val FILE_NAME = "surfaces.json"

    /** surfaces.json for the account whose profile is [profileFile]. */
    fun file(profileFile: File): File = File(profileFile.absoluteFile.parentFile, FILE_NAME)

    /** The aspects verified for the account whose profile is [profileFile]; none when unreadable. */
    fun aspects(profileFile: File): Set<Double> {
        val file = file(profileFile)
        if (!file.isFile) return emptySet()
        return try {
            val array = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("verifiedAspects") ?: return emptySet()
            (0 until array.length()).mapNotNull { array.optDouble(it).takeIf { a -> a.isFinite() && a > 0 } }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Adds the aspect of a [width]x[height] game area, rounded to 0.01, unless one like it is there. */
    @Synchronized
    fun record(profileFile: File, width: Int, height: Int) {
        val aspect = (PcProfiles.aspect(width, height) * 100).roundToInt() / 100.0
        val known = aspects(profileFile)
        if (known.any { PcProfiles.sameAspect(it, aspect) }) return
        val array = JSONArray()
        (known + aspect).sorted().forEach { array.put(it) }
        write(file(profileFile), JSONObject().put("verifiedAspects", array).toString(2))
    }

    /** Forgets every verified shape, for a calibration being saved. */
    @Synchronized
    fun clear(profileFile: File) {
        file(profileFile).delete()
    }

    private fun write(target: File, content: String) {
        val parent = target.parentFile
        parent.mkdirs()
        val temp = File.createTempFile(target.name + ".", ".tmp", parent)
        try {
            temp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
