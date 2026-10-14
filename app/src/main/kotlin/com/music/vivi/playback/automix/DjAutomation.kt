package com.music.vivi.playback.automix

import kotlin.math.*

data class DeckAutomation(val gain: Double,val bassCut: Double=0.0,val trebleCut: Double=0.0)
data class DjAutomation(val outgoing: DeckAutomation,val incoming: DeckAutomation)

/** The SAME automation feeds Media3 deck volume and PCM filters, and numerical fixture tests. */
fun djAutomation(plan: AutoMixPlan, progress: Double, levelRamp: Double=1.0): DjAutomation {
    val p=progress.coerceIn(0.0,1.0)
    fun smooth(x: Double): Double { val v=x.coerceIn(0.0,1.0);return v*v*(3-2*v) }
    val dj=plan.tier==AutoMixTier.A
    // Full mixes introduce the deck gently, hold a groove through the handoff and finish
    // at the measured bar. Safe/moderate fades retain their previous envelope.
    val shaped=if(dj) smooth(p) else p
    val gains=mixGains(shaped,1+(plan.outgoingLevel-1)*levelRamp.coerceIn(0.0,1.0),plan.incomingLevel)
    val bass=plan.technique==MixTechnique.BASS_HANDOFF
    val transfer=if(dj) smooth((p-0.35)/0.25) else smooth((p-0.55)/0.30)
    val incomingBass=if(dj) 1-smooth((p-0.15)/0.20) else 1-smooth((p-0.30)/0.25)
    val outgoingTreble=if(dj) smooth((p-0.60)/0.40)*0.30
        else if(plan.technique==MixTechnique.GENTLE_FILTER) smooth(p)*0.65 else 0.0
    return DjAutomation(DeckAutomation(gains.outgoing,if(bass)transfer*0.85 else 0.0,outgoingTreble),
        DeckAutomation(gains.incoming,if(bass)incomingBass*0.85 else 0.0,if(dj)(1-smooth(p/0.30))*0.20 else 0.0))
}
