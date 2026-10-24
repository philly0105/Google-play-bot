package local.clashfarmer.core

open class StopException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

enum class ScreenState {
    HOME,
    MENU,
    ARMY,
    SCOUT,
    /** Matchmaking: the search for an opponent that runs between the army check and the scout. */
    SEARCHING,
    BATTLE,
    REWARD,
    RESULT,
    WALL,
    UNKNOWN
}

/**
 * Which words were found where the game draws them. Whole-screen text cannot separate screens
 * that share their words: matchmaking and the result screen both say Return Home, and a player's
 * name can say anything. Built by recognition/ScreenTargets.kt from the recognised boxes.
 */
data class ScreenEvidence(
    /** Total damage, Victory, You got or Troops expended down the middle of the screen. */
    val resultAnchor: Boolean = false,
    /** A Return or Home line where the result screen draws its button, bottom centre. */
    val resultReturnButton: Boolean = false,
    /** A Return or Home line where matchmaking draws its button, bottom left. */
    val searchReturnButton: Boolean = false,
    /** "Searching for opponents" across the middle of the screen. */
    val searchText: Boolean = false,
    /** The village's Attack! button in its bottom-left corner. */
    val homeAttackButton: Boolean = false
)

enum class Action {
    OPEN_ATTACK,
    FIND_MATCH,
    CHECK_ARMY,
    DEPLOY,
    WAIT,
    REWARD,
    RETURN_HOME,
    ARRIVED_HOME,
    FINISH,
    /** Home between attacks with the run's minutes spent. */
    TIME_LIMIT
}

data class Point(val x: Int, val y: Int)

data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(point: Point): Boolean {
        return point.x in left until right && point.y in top until bottom
    }

    fun contains(x: Int, y: Int): Boolean {
        return x in left until right && y in top until bottom
    }
}

data class Viewport(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(point: Point): Boolean {
        return point.x in left until right && point.y in top until bottom
    }

    fun contains(x: Int, y: Int): Boolean {
        return x in left until right && y in top until bottom
    }
}
