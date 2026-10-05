package net.ithandsfree.softphone.sip

/**
 * A locked-screen incoming call stays on top of the keyguard. Dismissing the
 * keyguard unlocks into the app, so hangup leaves IHF Phone in front.
 *
 * Show the call with the screen on. When it ends, leave only if the keyguard
 * is still locked. If the user unlocked during the call, stay in the app.
 */
data class LockScreenArrival(
    val showWhenLocked: Boolean,
    val turnScreenOn: Boolean,
    val keepScreenOn: Boolean,
    val dismissKeyguard: Boolean,
    val returnToLockWhenCallEnds: Boolean,
)

data class LockScreenDeparture(
    val clearWindowFlags: Boolean,
    val moveTaskToBack: Boolean,
)

object LockScreenCallPolicy {
    fun onIncoming(keyguardLocked: Boolean): LockScreenArrival = LockScreenArrival(
        showWhenLocked = true,
        turnScreenOn = true,
        keepScreenOn = true,
        dismissKeyguard = false,
        returnToLockWhenCallEnds = keyguardLocked,
    )

    fun onCallEnded(
        returnToLockWhenCallEnds: Boolean,
        keyguardLocked: Boolean,
    ): LockScreenDeparture = LockScreenDeparture(
        clearWindowFlags = true,
        moveTaskToBack = returnToLockWhenCallEnds && keyguardLocked,
    )
}
