package android.content

/** Only the service key RemoteServer passes to FarmerService.getSystemService. */
abstract class Context {
    companion object {
        const val POWER_SERVICE = "power"
    }
}
