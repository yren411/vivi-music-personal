// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.*

enum class NativeDjCurve { LINEAR, EQUAL_POWER, SMOOTH_S, RESTRAINED_DJ }
data class NativeDjContentGains(val outgoing: Double = 1.0, val incoming: Double = 1.0)
/** Compact active-block RMS. Not LUFS. No retained PCM, titles or network identifiers. */
data class NativeDjLevel(val source: AnalysisSourceIdentity, val preprocessing: String,
    val interval: AudioIntervalUs, val activeRms: Double, val activeFraction: Double, val peak: Double) {
    companion object {
        const val VERSION = "active-rms100ms-median-relative20db-v1"
        fun measure(window: DecodedWindow, control: AnalysisControl = AnalysisControl()): NativeDjLevel {
            require(window.invalidReason()==null && window.preprocessingVersion==PcmPreprocessing.BANDLIMITED)
            require(window.format==PcmFormat(22050,1,PcmEncoding.FLOAT32) && window.samples.size<=705600)
            val span=requireNotNull(window.sampleInterval)
            require(window.coverage.status(span)==CoverageStatus.FULL && window.repeatedOutputFrames==0L)
            require(window.preparation?.let {it.gapCount!=0 || it.synthesizedTimelineFrames!=0L}!=true)
            val blocks=DoubleArray((window.samples.size+2204)/2205)
            var peak=0.0
            for(b in blocks.indices) {
                control.checkpoint()
                val start=b*2205;val end=min(window.samples.size,start+2205);var sum=0.0
                for(i in start until end) { val x=window.samples[i].toDouble();require(x.isFinite() && abs(x)<=1.0);sum+=x*x;peak=max(peak,abs(x)) }
                blocks[b]=sqrt(sum/(end-start))
            }
            val floor=max(.001,(blocks.maxOrNull() ?: 0.0)*.1)
            val active=blocks.filter {it>=floor}.sorted()
            return NativeDjLevel(window.identity,window.preprocessingVersion,span,
                if(active.isEmpty())0.0 else active[active.size/2],active.size.toDouble()/blocks.size,peak)
        }
    }
}
object NativeDjGain {
    /** Missing, quiet or non-comparable evidence: unity. Never boost, never quiet-tail-normalize. */
    fun content(plan: NativeTransitionPlan, outgoing: NativeDjLevel?, incoming: NativeDjLevel?): NativeDjContentGains {
        fun valid(v:NativeDjLevel?,track:PlanningTrack,cue:Long):Boolean = v!=null && v.source==track.source &&
            v.preprocessing==PcmPreprocessing.BANDLIMITED && v.interval.valid &&
            v.interval.contains(AudioIntervalUs(cue,Math.addExact(cue,plan.candidate.overlapUs))) &&
            v.activeRms.isFinite() && v.activeRms in .01..1.0 && v.activeFraction.isFinite() && v.activeFraction in .25..1.0 &&
            v.peak.isFinite() && v.peak in v.activeRms..1.0
        val c=plan.candidate
        if(!valid(outgoing,plan.outgoing,c.outgoingCueUs) || !valid(incoming,plan.incoming,c.incomingCueUs))return NativeDjContentGains()
        val a=outgoing!!.activeRms;val b=incoming!!.activeRms;val reference=min(a,b)
        return NativeDjContentGains((reference/a).coerceIn(.707945784,1.0),(reference/b).coerceIn(.707945784,1.0))
    }
    fun gains(progress: Double, curve: NativeDjCurve, content: NativeDjContentGains = NativeDjContentGains()): MixGains {
        require(progress.isFinite() && content.outgoing.isFinite() && content.incoming.isFinite())
        require(content.outgoing in .707..1.0 && content.incoming in .707..1.0)
        val p=progress.coerceIn(0.0,1.0)
        fun smooth(x:Double)=x*x*(3-2*x)
        val incoming=when(curve) {
            NativeDjCurve.LINEAR -> p
            NativeDjCurve.EQUAL_POWER -> sin(p*PI/2)
            NativeDjCurve.SMOOTH_S -> smooth(p)
            NativeDjCurve.RESTRAINED_DJ -> when {
                p<1.0/3 -> .42*smooth(p*3)
                p<2.0/3 -> .42+.16*smooth(p*3-1)
                else -> .58+.42*smooth(p*3-2)
            }
        }
        val outgoing=if(curve==NativeDjCurve.EQUAL_POWER) cos(p*PI/2) else 1-incoming
        // Worst-case correlated full-scale sum <= 1. Continuous endpoints restore unity.
        val guard=1/max(1.0,outgoing+incoming)
        val correction=sin(PI*p).pow(2)
        return MixGains(outgoing*guard*(1-(1-content.outgoing)*correction),
            incoming*guard*(1-(1-content.incoming)*correction))
    }
}
