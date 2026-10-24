package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private fun finishAttack(session: Session) {
        assertEquals(Action.FIND_MATCH, session.accept(ScreenState.MENU))
        assertEquals(Action.CHECK_ARMY, session.accept(ScreenState.ARMY))
        assertEquals(Action.DEPLOY, session.accept(ScreenState.SCOUT))
        session.deployed()
        assertEquals(Action.WAIT, session.accept(ScreenState.BATTLE))
        assertEquals(Action.RETURN_HOME, session.accept(ScreenState.RESULT))
    }

    private fun attack(session: Session) {
        val action = session.accept(ScreenState.HOME)
        assertEquals(Action.OPEN_ATTACK, action)
        finishAttack(session)
    }

    @Test
    fun testStartingMidBattleAndUnexpectedStatesStop() {
        val unexpected = listOf(
            ScreenState.BATTLE,
            ScreenState.RESULT,
            ScreenState.SCOUT,
            ScreenState.UNKNOWN,
            ScreenState.WALL,
            ScreenState.ARMY,
            ScreenState.MENU,
            ScreenState.REWARD
        )
        for (state in unexpected) {
            val session = Session(5)
            var caught = false
            try {
                session.accept(state)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException when starting session in state: $state", caught)
        }
    }

    @Test
    fun testCannotSkipArmyValidation() {
        val session = Session(5)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        var caught = false
        try {
            session.accept(ScreenState.SCOUT)
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException when skipping menu/army to scout", caught)
    }

    @Test
    fun testResultCannotBeCountedTwice() {
        val session = Session(5)
        session.accept(ScreenState.HOME)
        session.accept(ScreenState.MENU)
        session.accept(ScreenState.ARMY)
        session.accept(ScreenState.SCOUT)
        session.deployed()
        session.accept(ScreenState.RESULT)

        var caught = false
        try {
            session.accept(ScreenState.RESULT)
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException when result is accepted twice", caught)
    }

    @Test
    fun testRewardDoesNotCountAsCompletedAttack() {
        val session = Session(5)
        session.accept(ScreenState.HOME)
        session.accept(ScreenState.MENU)
        session.accept(ScreenState.ARMY)
        session.accept(ScreenState.SCOUT)
        session.deployed()

        assertEquals(Action.REWARD, session.accept(ScreenState.REWARD))
        assertEquals(0, session.counter.completed)
        assertEquals(Action.WAIT, session.accept(ScreenState.BATTLE))
        assertEquals(Action.RETURN_HOME, session.accept(ScreenState.RESULT))
        assertEquals(1, session.counter.completed)
    }

    @Test
    fun troopsGoIntoABattleThatStartedWithoutUs() {
        // The scout screen went unread for its whole thirty seconds on 17 September and the match
        // began on its own. The army was still in the barracks, so the answer is to drop it.
        val session = Session(28)
        session.accept(ScreenState.HOME)
        session.accept(ScreenState.MENU)
        session.accept(ScreenState.ARMY)
        assertEquals(Action.DEPLOY, session.accept(ScreenState.BATTLE))
        session.deployed()
        assertEquals(Action.WAIT, session.accept(ScreenState.BATTLE))
        assertEquals(Action.RETURN_HOME, session.accept(ScreenState.RESULT))
        assertEquals(1, session.counter.completed)
    }

    @Test
    fun testDeployedWithoutScoutThrowsStop() {
        val session = Session(5)
        var caught = false
        try {
            session.deployed()
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException on deployed() before scout", caught)
    }

    @Test
    fun testHomeCheckpointDueAtStartAndOncePerCompletedAttack() {
        val session = Session(3)
        // At start (completed = 0, phase = HOME)
        assertTrue(session.homeCheckpointDue())
        session.checkpointDone()
        assertFalse(session.homeCheckpointDue())

        // Moving to MENU -> not HOME, so not due
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertEquals(ScreenState.MENU, session.phase)
        assertFalse(session.homeCheckpointDue())

        // Complete attack 1
        finishAttack(session)
        assertEquals(1, session.counter.completed)
        assertEquals(ScreenState.HOME, session.phase)

        // After attack 1 completes at HOME: checkpoint is due again!
        assertTrue(session.homeCheckpointDue())
        session.checkpointDone()
        assertFalse(session.homeCheckpointDue())

        // Start and finish attack 2
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        finishAttack(session)
        assertEquals(2, session.counter.completed)
        assertTrue(session.homeCheckpointDue())
        session.checkpointDone()

        // Start and finish attack 3 (reaches quota)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        finishAttack(session)
        assertEquals(3, session.counter.completed)

        // Quota reached: checkpoint must NOT be due!
        assertTrue("Final storage checkpoint is due when quota completed", session.homeCheckpointDue())
        // Next accept(HOME) finishes session
        assertEquals(Action.FINISH, session.accept(ScreenState.HOME))
    }

    @Test
    fun testHomeCheckpointNotDueOutsideHome() {
        val session = Session(2)
        assertTrue(session.homeCheckpointDue())
        session.accept(ScreenState.HOME)
        assertEquals(ScreenState.MENU, session.phase)
        assertFalse(session.homeCheckpointDue())
    }

    @Test
    fun testSessionFinishesAtQuotaWithoutWallsAction() {
        val session = Session(2)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        finishAttack(session)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        finishAttack(session)
        assertEquals(Action.FINISH, session.accept(ScreenState.HOME))
    }

    @Test
    fun aBattleThatGoesStraightHomeCountsAsAnAttack() {
        // 23 September: the village came back with no result screen read in between.
        val session = Session(2)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertEquals(Action.FIND_MATCH, session.accept(ScreenState.MENU))
        assertEquals(Action.CHECK_ARMY, session.accept(ScreenState.ARMY))
        assertEquals(Action.DEPLOY, session.accept(ScreenState.SCOUT))
        session.deployed()
        assertEquals(Action.ARRIVED_HOME, session.accept(ScreenState.HOME))
        assertEquals(1, session.counter.completed)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
    }

    @Test
    fun aTimeLimitPassingMidBattleLetsTheAttackFinishThenEndsAtHome() {
        var now = 0L
        val deadline = 60_000L
        val session = Session(5) { now >= deadline }
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertEquals(Action.FIND_MATCH, session.accept(ScreenState.MENU))
        assertEquals(Action.CHECK_ARMY, session.accept(ScreenState.ARMY))
        assertEquals(Action.DEPLOY, session.accept(ScreenState.SCOUT))
        session.deployed()
        assertEquals(Action.WAIT, session.accept(ScreenState.BATTLE))

        now = deadline + 1  // the limit passes while the battle runs
        assertEquals(Action.WAIT, session.accept(ScreenState.BATTLE))
        assertEquals(Action.RETURN_HOME, session.accept(ScreenState.RESULT))
        assertEquals("The battle under way still counts", 1, session.counter.completed)

        // Home again: no new attack, the run ends on its time limit.
        assertEquals(Action.TIME_LIMIT, session.accept(ScreenState.HOME))
    }

    @Test
    fun aRunAlreadyOutOfTimeAttacksNothing() {
        // A restart after the limit passed starts at home and ends there.
        val session = Session(5) { true }
        assertEquals(Action.TIME_LIMIT, session.accept(ScreenState.HOME))
        assertEquals(0, session.counter.completed)
    }

    @Test
    fun theWaitBetweenAttacksIsOwedOncePerCompletedAttack() {
        val session = Session(3)
        assertFalse("Nothing to wait for before the first attack", session.pacingDue())
        attack(session)
        assertTrue(session.pacingDue())
        session.pacingDone()
        assertFalse(session.pacingDue())
        // Still owed nothing at home after the wait; the next attack opens from a fresh frame.
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertFalse("Not at home", session.pacingDue())
        finishAttack(session)
        assertTrue(session.pacingDue())
        session.pacingDone()
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        finishAttack(session)
        assertFalse("The quota is met; the run finishes without a wait", session.pacingDue())
        assertEquals(Action.FINISH, session.accept(ScreenState.HOME))
    }

    @Test
    fun noWaitIsOwedOnceTheTimeIsUp() {
        var timeUp = false
        val session = Session(5) { timeUp }
        attack(session)
        assertTrue(session.pacingDue())
        timeUp = true
        assertFalse(session.pacingDue())
        assertEquals(Action.TIME_LIMIT, session.accept(ScreenState.HOME))
    }

    @Test
    fun theCooldownIsWaitedOutOnTheMenuBeforeItIsAccepted() {
        // The cooldown used to be found after accept(MENU) had moved the phase on to ARMY, and the
        // menu read after the wait then stopped the run: "Unexpected screen MENU during phase ARMY".
        val before = Session(5)
        before.accept(ScreenState.HOME)
        before.accept(ScreenState.MENU)
        val thrown = runCatching { before.accept(ScreenState.MENU) }.exceptionOrNull()
        assertEquals("Unexpected screen MENU during phase ARMY", thrown?.message)

        val session = Session(5)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertEquals(ScreenState.MENU, session.phase)
        // The runner waits on the menu without accepting it, then reads the menu again.
        assertEquals(Action.FIND_MATCH, session.accept(ScreenState.MENU))
        assertEquals(ScreenState.ARMY, session.phase)
    }

    @Test
    fun aQuotaMetAtTheTimeLimitStillReadsAsTheQuota() {
        var timeUp = false
        val session = Session(1) { timeUp }
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        finishAttack(session)
        timeUp = true
        assertEquals(Action.FINISH, session.accept(ScreenState.HOME))
    }
}
