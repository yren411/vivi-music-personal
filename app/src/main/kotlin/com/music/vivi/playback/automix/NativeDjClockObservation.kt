// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.abs

/** A position query happened somewhere inside this monotonic bracket, not at the next query. */
data class NativeDjClockObservation(val state:NativeDjDeckState,val beforeUs:Long,val afterUs:Long) {
    init {require(beforeUs>=0 && afterUs>=beforeUs)}
    val midpointUs get()=beforeUs+(afterUs-beforeUs)/2
}
data class NativeDjClockPair(val outgoing:NativeDjClockObservation,val incoming:NativeDjClockObservation) {
    val referenceUs get()=maxOf(outgoing.afterUs,incoming.afterUs)
    val querySpanUs get()=referenceUs-minOf(outgoing.beforeUs,incoming.beforeUs)
    /** Rate-1 media positions at a common observation instant, never acoustic timestamps.
     * The common-time point assumes rate-1 progress; startup can instead hold a reported
     * clock during renderer handover. Keep BOTH raw and projected endpoints in the bounds,
     * plus query-duration uncertainty. Never approve an originally unsafe raw offset.
     * Stalls/seek/discontinuity checks still use raw positions.
     */
    fun offset(outCueUs:Long,inCueUs:Long):NativeDjClockEvidence {
        val a=outgoing.state;val b=incoming.state
        require(a.ready && a.playing && b.ready && b.playing && a.rate==1.0 && b.rate==1.0)
        val raw=Math.subtractExact(Math.subtractExact(a.positionUs,outCueUs),Math.subtractExact(b.positionUs,inCueUs))
        val adjusted=Math.addExact(raw,Math.subtractExact(incoming.midpointUs,outgoing.midpointUs))
        val uncertainty=(outgoing.afterUs-outgoing.beforeUs+1)/2+(incoming.afterUs-incoming.beforeUs+1)/2
        require(adjusted!=Long.MIN_VALUE)
        val minimum=Math.subtractExact(minOf(raw,adjusted),uncertainty)
        val maximum=Math.addExact(maxOf(raw,adjusted),uncertainty)
        require(minimum!=Long.MIN_VALUE)
        return NativeDjClockEvidence(outgoing.beforeUs,outgoing.afterUs,incoming.beforeUs,incoming.afterUs,
            a.positionUs,b.positionUs,raw,adjusted,uncertainty,maxOf(abs(minimum),abs(maximum)),minimum,maximum)
    }
    companion object {
        fun capture(a:NativeDjDeck,b:NativeDjDeck,nowUs:()->Long):NativeDjClockPair {
            fun query(deck:NativeDjDeck):NativeDjClockObservation {
                val before=nowUs();val state=deck.snapshot();return NativeDjClockObservation(state,before,nowUs())
            }
            return NativeDjClockPair(query(a),query(b))
        }
    }
}
/** Fixed last observation only; no source identity or platform strings. */
data class NativeDjClockEvidence(val outgoingBeforeUs:Long,val outgoingAfterUs:Long,
    val incomingBeforeUs:Long,val incomingAfterUs:Long,val outgoingPositionUs:Long,val incomingPositionUs:Long,
    val rawOffsetUs:Long,val commonInstantOffsetUs:Long,val uncertaintyUs:Long,val guardedDriftUs:Long,
    val minimumPossibleOffsetUs:Long,val maximumPossibleOffsetUs:Long)
