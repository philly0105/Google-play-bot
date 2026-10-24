package android.os

/**
 * The one PowerManager member RemoteServer reads, through FarmerService.getSystemService. On the
 * phone it is whether the screen is on.
 */
interface PowerManager {
    val isInteractive: Boolean
}
