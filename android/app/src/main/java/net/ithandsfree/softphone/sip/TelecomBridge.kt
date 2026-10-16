package net.ithandsfree.softphone.sip

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Registers every SIP call with Android Telecom as a self-managed call (Jetpack Core-Telecom).
 *
 * That is what lets Android route call audio to Bluetooth and the car (Android Auto), show the call on the car
 * screen, take Answer / Hang up from the steering wheel, and allow the phoneCall foreground-service type. The SIP
 * engine stays the source of truth: this class follows its snapshots and passes Telecom's requests back to it.
 */
class TelecomBridge(
    private val context: Context,
    private val engine: SipEngine,
) {
    /** Audio routes Android offers for the current call, and the one in use. Empty when Telecom is not in use. */
    data class Audio(
        val endpoints: List<CallEndpointCompat> = emptyList(),
        val current: CallEndpointCompat? = null,
    ) {
        val bluetooth: CallEndpointCompat? get() = endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
        val speaker: CallEndpointCompat? get() = endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        val onSpeaker: Boolean get() = current?.type == CallEndpointCompat.TYPE_SPEAKER
        val onBluetooth: Boolean get() = current?.type == CallEndpointCompat.TYPE_BLUETOOTH
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager: CallsManager? = runCatching {
        CallsManager(context).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
    }.onFailure { Log.w(TAG, "Telecom registration failed; calls use the app's own audio routing", it) }.getOrNull()

    private val _audio = MutableStateFlow(Audio())
    val audio: StateFlow<Audio> = _audio.asStateFlow()

    private sealed interface Action {
        data object Answer : Action
        data object SetActive : Action
        data object SetInactive : Action
        data class Disconnect(val code: Int) : Action
        data class Route(val endpoint: CallEndpointCompat) : Action
    }

    private inner class Tracked(val key: Int, val incoming: Boolean) {
        val actions = Channel<Action>(Channel.UNLIMITED)
        var answered = !incoming
        var active = false
        var ended = false
        var added = false
    }

    /** Main thread only. */
    private val calls = linkedMapOf<Int, Tracked>()

    val available: Boolean get() = manager != null

    fun start() {
        if (manager == null) return
        engine.addObserver { sync() }
    }

    /** Speaker on or off: back to Bluetooth when it is connected, else the earpiece or wired headset. */
    fun toggleSpeaker(): Boolean {
        val a = _audio.value
        val target = if (a.onSpeaker) {
            a.bluetooth ?: a.endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                ?: a.endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
        } else {
            a.speaker
        } ?: return false
        return route(target)
    }

    /** Sends call audio to [endpoint] (Bluetooth, car, speaker, earpiece, wired). False when Telecom is not in use. */
    fun route(endpoint: CallEndpointCompat): Boolean {
        val primaryKey = engine.callSnapshot().key
        val tracked = calls[primaryKey]?.takeIf { it.added && !it.ended } ?: return false
        tracked.actions.trySend(Action.Route(endpoint))
        return true
    }

    private fun sync() {
        if (manager == null) return
        val snap = engine.callSnapshot()
        data class Live(val key: Int, val ringing: Boolean, val held: Boolean, val connected: Boolean, val remote: String, val lineId: String?)
        val live = buildList {
            if (snap.active && snap.key != 0) {
                add(Live(snap.key, snap.incoming, snap.held, snap.connectedAtElapsed > 0, snap.remote, snap.lineId))
            }
            snap.waiting?.let { add(Live(it.key, it.ringing, !it.ringing, it.connectedAtElapsed > 0, it.remote, it.lineId)) }
        }
        val liveKeys = live.map { it.key }.toSet()
        // Ended calls.
        calls.values.filter { it.key !in liveKeys }.forEach { t ->
            t.ended = true
            t.actions.trySend(Action.Disconnect(DisconnectCause.LOCAL))
            calls.remove(t.key)
        }
        // New calls, and state changes the engine made (answered in the app, hold, swap).
        live.forEach { c ->
            val t = calls[c.key] ?: Tracked(c.key, incoming = c.ringing).also {
                calls[c.key] = it
                add(it, c.remote)
            }
            if (!c.ringing && !t.answered) {
                t.answered = true
                t.actions.trySend(Action.Answer)
            }
            val wantActive = !c.ringing && !c.held && (c.connected || t.incoming)
            if (wantActive && !t.active) {
                t.active = true
                t.actions.trySend(Action.SetActive)
            } else if (c.held && t.active) {
                t.active = false
                t.actions.trySend(Action.SetInactive)
            }
        }
        engine.setAudioManagedByTelecom(calls.values.any { it.added })
        if (calls.isEmpty()) _audio.value = Audio()
        SipRegistrationService.callStateChanged(calls.values.any { it.added })
    }

    private fun add(t: Tracked, remote: String) {
        val mgr = manager ?: return
        val number = IncomingCallNotifier.displayRemote(remote)
        val digits = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val attrs = CallAttributesCompat(
            displayName = number,
            address = Uri.fromParts("tel", digits.ifBlank { "unknown" }, null),
            direction = if (t.incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        scope.launch {
            try {
                mgr.addCall(
                    attrs,
                    onAnswer = { _ -> fromTelecom(t) { answerFromTelecom(t) } },
                    onDisconnect = { _ -> fromTelecom(t) { hangupFromTelecom(t) } },
                    onSetActive = { fromTelecom(t) { resumeFromTelecom(t) } },
                    onSetInactive = { fromTelecom(t) { holdFromTelecom(t) } },
                ) {
                    t.added = true
                    engine.setAudioManagedByTelecom(true)
                    SipRegistrationService.callStateChanged(true)
                    launch { currentCallEndpoint.collect { e -> _audio.value = _audio.value.copy(current = e) } }
                    launch { availableEndpoints.collect { list -> _audio.value = _audio.value.copy(endpoints = list) } }
                    launch {
                        // Mute pressed in the car or by the system: mirror it on the SIP microphone.
                        isMuted.collect { m -> if (m != engine.callSnapshot().muted) engine.setMuted(m) }
                    }
                    val control: CallControlScope = this
                    launch { process(control, t) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telecom addCall failed for call ${t.key}; app routing stays in use", e)
                t.added = false
                engine.setAudioManagedByTelecom(calls.values.any { it.added })
            }
        }
    }

    private suspend fun process(scope: CallControlScope, t: Tracked) {
        t.actions.receiveAsFlow().collect { action ->
            val result: CallControlResult? = when (action) {
                Action.Answer -> scope.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                Action.SetActive -> scope.setActive()
                Action.SetInactive -> scope.setInactive()
                is Action.Route -> scope.requestEndpointChange(action.endpoint)
                is Action.Disconnect -> {
                    scope.disconnect(DisconnectCause(action.code))
                    t.actions.close()
                    null
                }
            }
            if (result is CallControlResult.Error) Log.w(TAG, "Telecom $action on call ${t.key}: error ${result.errorCode}")
        }
    }

    /** Requests from Telecom (car, Bluetooth, system UI) run on the main thread against the engine. */
    private inline fun fromTelecom(t: Tracked, block: () -> Unit) {
        if (t.ended) return
        runCatching(block).onFailure { Log.w(TAG, "Telecom request on call ${t.key} failed: ${it.message}") }
    }

    private fun answerFromTelecom(t: Tracked) {
        t.answered = true
        val snap = engine.callSnapshot()
        when {
            snap.key == t.key && snap.incoming -> engine.answer()
            snap.waiting?.key == t.key && snap.waiting.ringing -> engine.waitingAnswer()
        }
    }

    private fun hangupFromTelecom(t: Tracked) {
        t.ended = true
        val snap = engine.callSnapshot()
        when {
            snap.key == t.key && snap.incoming -> engine.decline()
            snap.key == t.key -> engine.hangup()
            snap.waiting?.key == t.key -> engine.waitingEnd()
        }
    }

    private fun resumeFromTelecom(t: Tracked) {
        t.active = true
        val snap = engine.callSnapshot()
        when {
            snap.key == t.key && snap.held -> engine.setHold(false)
            snap.waiting?.key == t.key && !snap.waiting.ringing -> engine.swap()
        }
    }

    private fun holdFromTelecom(t: Tracked) {
        t.active = false
        val snap = engine.callSnapshot()
        if (snap.key == t.key && !snap.held && !snap.incoming) engine.setHold(true)
    }

    companion object {
        private const val TAG = "TelecomBridge"
    }
}
