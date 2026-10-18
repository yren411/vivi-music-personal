// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.floor

internal object NativeCuePreservation {
    fun describe(durationUs:Long,cueUs:Long,overlapUs:Long,bpm:Double):NativeCuePlacement {
        // Effective pulse BPM can be half the detector's 60..220 measured BPM.
        // Input/native evidence validation still enforces the measured range.
        require(durationUs>0 && cueUs>=0 && overlapUs>0 && bpm.isFinite() && bpm in 30.0..220.0)
        val end=Math.addExact(cueUs,overlapUs)
        require(end<=durationUs)
        val remaining=durationUs-end
        val period=60_000_000.0/bpm
        // Preserve at least 95% of the outgoing timeline and leave no more than
        // the smallest supported 8-pulse overlap unheard. This is a conservative
        // product policy; no phrase/outro detector is claimed.
        val budget=minOf(durationUs/20,floor(8*period).toLong())
        return NativeCuePlacement(durationUs,end,remaining,end.toDouble()/durationUs,budget,
            floor(remaining/period).toLong())
    }
}
