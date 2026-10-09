package net.ithandsfree.softphone.win

/**
 * Follows each call by its voice-engine id (0.1.39), so two calls at once (call waiting, swap) each get their own
 * timer and their own Recents row when they end. Fed with what the voice engine reports every refresh. GPL-2.0.
 */
internal class CallTracker {
    /** One call as the voice engine shows it right now. */
    data class Seen(val id: Int, val party: String, val extension: String, val ringing: Boolean, val connected: Boolean)

    /** A call that has ended: the Recents row it becomes. */
    data class Finished(val party: String, val extension: String, val incoming: Boolean, val answered: Boolean, val seconds: Int) {
        val kind: String get() = when {
            incoming && !answered -> "Missed"
            incoming -> "Incoming"
            else -> "Outgoing"
        }
    }

    private class Track(var party: String, var extension: String, val incoming: Boolean, var connectedAt: Long?)

    private val tracks = linkedMapOf<Int, Track>()

    /** Records what is up now and returns the calls that ended since the last update. */
    fun update(calls: List<Seen>, now: Long): List<Finished> {
        calls.forEach { seen ->
            if (seen.id < 0) return@forEach
            // A call first seen ringing is incoming (a normal one, or call waiting); otherwise we placed it.
            val track = tracks.getOrPut(seen.id) { Track(seen.party, seen.extension, seen.ringing, null) }
            if (seen.party.isNotBlank()) track.party = seen.party
            if (seen.extension.isNotBlank()) track.extension = seen.extension
            if (seen.connected && track.connectedAt == null) track.connectedAt = now
        }
        val live = calls.map { it.id }.toSet()
        val ended = tracks.keys.filter { it !in live }
        return ended.map { id ->
            val track = tracks.remove(id)!!
            Finished(
                party = track.party,
                extension = track.extension,
                incoming = track.incoming,
                answered = track.connectedAt != null,
                seconds = track.connectedAt?.let { ((now - it) / 1000).toInt() } ?: 0,
            )
        }
    }

    /** When the call was answered (epoch ms), for its live timer; null while it rings or dials. */
    fun connectedAt(id: Int): Long? = tracks[id]?.connectedAt
}
