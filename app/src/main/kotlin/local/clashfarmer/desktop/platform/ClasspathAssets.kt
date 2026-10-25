package local.clashfarmer.desktop.platform

import java.io.FileNotFoundException
import java.io.InputStream

/**
 * The phone's APK assets, which the PC ships as classpath resources (`app/src/main/resources`):
 * `remote.html`. The phone's identity templates are not among them: each account's own lives in
 * its data folder (PcAccount). FarmerService.assets is this object.
 */
object ClasspathAssets {
    /** Opens the asset at [name], a path under the resources root, as AssetManager.open did. */
    fun open(name: String): InputStream =
        ClasspathAssets::class.java.getResourceAsStream("/$name") ?: throw FileNotFoundException(name)
}
