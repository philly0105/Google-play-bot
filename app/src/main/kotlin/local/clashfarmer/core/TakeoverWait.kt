package local.clashfarmer.core

/** Waits without game input; the enclosing session retains its wall-clock deadline. */
suspend fun waitBeforeVillageRetry(
    background: suspend () -> Unit,
    awaitResume: suspend () -> Unit,
    remaining: (Long) -> Unit
) {
    awaitResume()
    background()
    var left = 600_000L
    while (left > 0) {
        awaitResume()
        remaining(left)
        kotlinx.coroutines.delay(1000)
        left -= 1000
    }
    // Pause in the final second must also hold the reopen.
    awaitResume()
    remaining(0)
}
