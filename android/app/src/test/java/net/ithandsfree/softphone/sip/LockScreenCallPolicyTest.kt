package net.ithandsfree.softphone.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenCallPolicyTest {
    @Test
    fun incomingOverLockShowsCallWithoutDismissingKeyguard() {
        val arrival = LockScreenCallPolicy.onIncoming(keyguardLocked = true)
        assertTrue(arrival.showWhenLocked)
        assertTrue(arrival.turnScreenOn)
        assertTrue(arrival.keepScreenOn)
        assertFalse(arrival.dismissKeyguard)
        assertTrue(arrival.returnToLockWhenCallEnds)
    }

    @Test
    fun incomingWhileUnlockedDoesNotPlanAReturnToTheLockScreen() {
        val arrival = LockScreenCallPolicy.onIncoming(keyguardLocked = false)
        assertFalse(arrival.dismissKeyguard)
        assertFalse(arrival.returnToLockWhenCallEnds)
    }

    @Test
    fun hangupWhileStillLockedLeavesTheTask() {
        val departure = LockScreenCallPolicy.onCallEnded(
            returnToLockWhenCallEnds = true,
            keyguardLocked = true,
        )
        assertTrue(departure.clearWindowFlags)
        assertTrue(departure.moveTaskToBack)
    }

    @Test
    fun hangupAfterUserUnlocksStaysInTheApp() {
        val departure = LockScreenCallPolicy.onCallEnded(
            returnToLockWhenCallEnds = true,
            keyguardLocked = false,
        )
        assertTrue(departure.clearWindowFlags)
        assertFalse(departure.moveTaskToBack)
    }

    @Test
    fun hangupOfAnUnlockedCallStaysInTheApp() {
        val departure = LockScreenCallPolicy.onCallEnded(
            returnToLockWhenCallEnds = false,
            keyguardLocked = false,
        )
        assertFalse(departure.moveTaskToBack)
    }
}
