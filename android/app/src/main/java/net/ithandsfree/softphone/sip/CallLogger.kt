package net.ithandsfree.softphone.sip

import android.os.SystemClock
import net.ithandsfree.softphone.data.CallDirection
import net.ithandsfree.softphone.data.CallHistoryStore
import net.ithandsfree.softphone.data.CallRecord
import net.ithandsfree.softphone.data.normalizeDidDigits

/**
 * Follows each call by its engine key, so two calls at once (call waiting, swap) each get their own Recents row
 * when they end: incoming, outgoing or missed, with talk time. Same rules as the desktop's CallTracker. Pure logic:
 * [CallLogger] feeds it engine snapshots.
 */
class CallTracker {
    data class Seen(val key: Int, val party: String, val lineId: String?, val ringing: Boolean, val connectedAtElapsed: Long)

    data class Finished(
        val party: String,
        val lineId: String?,
        val incoming: Boolean,
        val answered: Boolean,
        val seconds: Int,
        val startedAtWall: Long,
    ) {
        val direction: CallDirection get() = when {
            incoming && !answered -> CallDirection.Missed
            incoming -> CallDirection.Incoming
            else -> CallDirection.Outgoing
        }
    }

    private class Track(var party: String, var lineId: String?, val incoming: Boolean, val startedAtWall: Long) {
        var connectedAtElapsed = 0L
    }

    private val tracks = linkedMapOf<Int, Track>()

    /** Records what is up now; returns the calls that ended since the last update. */
    fun update(calls: List<Seen>, nowElapsed: Long, nowWall: Long): List<Finished> {
        calls.forEach { seen ->
            if (seen.key == 0) return@forEach
            // A call first seen ringing is incoming (a normal one or call waiting); otherwise we placed it.
            val t = tracks.getOrPut(seen.key) { Track(seen.party, seen.lineId, seen.ringing, nowWall) }
            if (seen.party.isNotBlank()) t.party = seen.party
            if (seen.lineId != null) t.lineId = seen.lineId
            if (seen.connectedAtElapsed > 0 && t.connectedAtElapsed == 0L) t.connectedAtElapsed = seen.connectedAtElapsed
        }
        val live = calls.map { it.key }.toSet()
        return tracks.keys.filter { it !in live }.map { key ->
            val t = tracks.remove(key)!!
            Finished(
                party = t.party,
                lineId = t.lineId,
                incoming = t.incoming,
                answered = t.connectedAtElapsed > 0,
                seconds = if (t.connectedAtElapsed > 0) ((nowElapsed - t.connectedAtElapsed) / 1000).toInt() else 0,
                startedAtWall = t.startedAtWall,
            )
        }
    }
}

/** Writes Recents from engine events, whether or not the app's screen is open. */
class CallLogger(private val engine: SipEngine, private val history: CallHistoryStore) {
    private val tracker = CallTracker()

    /** Bumped after each write so an open Recents list can reload. */
    @Volatile var revision = 0
        private set

    fun start() {
        engine.addObserver { onChange() }
    }

    private fun onChange() {
        val snap = engine.callSnapshot()
        val seen = buildList {
            if (snap.active && snap.key != 0) {
                add(CallTracker.Seen(snap.key, partyOf(snap.remote), snap.lineId, snap.incoming, snap.connectedAtElapsed))
            }
            snap.waiting?.let { add(CallTracker.Seen(it.key, partyOf(it.remote), it.lineId, it.ringing, it.connectedAtElapsed)) }
        }
        val done = tracker.update(seen, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        done.forEach { f ->
            val line = f.lineId ?: return@forEach
            history.append(
                CallRecord(
                    lineId = line,
                    peerNumber = normalizeDidDigits(f.party).ifBlank { f.party },
                    direction = f.direction,
                    startedAt = f.startedAtWall,
                    durationSec = f.seconds,
                    answered = f.answered,
                ),
            )
        }
        if (done.isNotEmpty()) revision++
    }

    private fun partyOf(remote: String): String =
        remote.removePrefix("<").substringAfter(":").substringBefore("@").substringBefore(">").trim()
}
