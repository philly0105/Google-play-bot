package local.clashfarmer.ui.components

import kotlin.math.ceil

/** The pure size search behind [StatTile]'s value. No Android types. */
object ValueFit {
    /** Below this a tile's number stops being a headline; it ellipsizes instead. */
    const val MIN_SP = 12f

    /** The largest whole sp from [maxSp] down to [MIN_SP] at which [widthAt] fits [room] px. */
    fun sp(maxSp: Float, room: Float, widthAt: (Float) -> Float): Float {
        var sp = maxSp
        while (sp > MIN_SP && ceil(widthAt(sp)) > room) sp -= 1f
        return sp.coerceAtLeast(MIN_SP)
    }
}
