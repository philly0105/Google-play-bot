package local.clashfarmer.core

class Session(
    attacks: Int,
    /**
     * Whether the run's minutes are spent. Asked only at home between attacks, so a battle under
     * way when they run out is finished and brought home first.
     */
    private val timeUp: () -> Boolean = { false }
) {
    val counter = Policy.AttackCounter(attacks)
    var phase: ScreenState = ScreenState.HOME
        private set
    private var checkpointAt: Int = -1

    /** Includes the final return home, so storage and goal outcomes are recorded. */
    fun homeCheckpointDue(): Boolean = phase == ScreenState.HOME &&
        checkpointAt != counter.completed

    fun checkpointDone() { checkpointAt = counter.completed }

    private var pacedAt: Int = 0

    /**
     * Whether the wait between attacks is still owed: an attack has completed since the last
     * wait, and the next accept at home would open another. Asked before [accept], so the Attack
     * tap is read from a frame taken after the wait. On 15 October at 05:31 UTC it was read
     * before, and went out 19 s later onto the store.
     */
    fun pacingDue(): Boolean = phase == ScreenState.HOME && counter.completed in 1 until counter.limit &&
        pacedAt != counter.completed && !timeUp()

    /** The wait has run; it is owed again once the next attack completes. */
    fun pacingDone() { pacedAt = counter.completed }

    fun accept(screen: ScreenState): Action {
        if (phase == ScreenState.HOME && screen == ScreenState.HOME) {
            val completed = counter.completed
            if (completed == counter.limit) {
                return Action.FINISH
            }
            if (timeUp()) return Action.TIME_LIMIT
            phase = ScreenState.MENU
            return Action.OPEN_ATTACK
        }
        if (phase == ScreenState.MENU && screen == ScreenState.MENU) {
            phase = ScreenState.ARMY
            return Action.FIND_MATCH
        }
        if (phase == ScreenState.ARMY && screen == ScreenState.ARMY) {
            phase = ScreenState.SCOUT
            return Action.CHECK_ARMY
        }
        if (phase == ScreenState.SCOUT && screen == ScreenState.SCOUT) {
            return Action.DEPLOY
        }
        // The scout screen is up for thirty seconds and then the match starts whether anything
        // was dropped or not. One enemy village in nineteen read as nothing at all for the whole
        // window - the army was waiting, the base was on screen - and the run died rather than
        // attack, ten short of the twenty-eight it was asked for. Troops go down just as well
        // after the clock starts, so a battle found here is one to deploy into, not to stop over.
        if (phase == ScreenState.SCOUT && screen == ScreenState.BATTLE) {
            return Action.DEPLOY
        }
        if (phase == ScreenState.BATTLE && screen == ScreenState.BATTLE) {
            return Action.WAIT
        }
        if (phase == ScreenState.BATTLE && screen == ScreenState.REWARD) {
            return Action.REWARD
        }
        if (phase == ScreenState.BATTLE && screen == ScreenState.RESULT) {
            counter.result()
            phase = ScreenState.HOME
            return Action.RETURN_HOME
        }
        // The game can go from a battle straight back to the village with no result screen
        // the reader caught. At 01:56 on 23 September it did, the loot had already landed, and
        // the run stopped with twelve attacks left. The battle is over either way.
        if (phase == ScreenState.BATTLE && screen == ScreenState.HOME) {
            counter.result()
            phase = ScreenState.HOME
            return Action.ARRIVED_HOME
        }
        throw StopException("Unexpected screen $screen during phase $phase")
    }

    fun deployed() {
        if (phase != ScreenState.SCOUT) {
            throw StopException("Deployment was not authorized by a scout screen (current phase: $phase)")
        }
        counter.deploy()
        phase = ScreenState.BATTLE
    }
}
