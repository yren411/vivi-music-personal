// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Virtual player clocks for deterministic execution tests, never labeled Android measurements. */
internal class VirtualNativeDjHost(val plan:NativeTransitionPlan):NativeDjHost {
    var now=0L
    var generation=plan.generation
    var promotions=0
    var restores=0
    var claimed=false
    var canClaim=true
    var prepareFailure=false
    var promoteAllowed=true
    var incomingReady=true
    var startup:NativeDjStartupObservation?=null
    val a=Deck(plan.outgoing.source,plan.outgoing.durationUs,plan.candidate.outgoingCueUs-plan.assumedPreparationLeadUs-1_000_000,true)
    val b=Deck(plan.incoming.source,plan.incoming.durationUs,plan.candidate.incomingCueUs,false)
    override val outgoing:NativeDjDeck get()=a
    inner class Deck(val source:AnalysisSourceIdentity,val duration:Long,var position:Long,var playing:Boolean):NativeDjDeck {
        var ready=true;var failed=false;var ended=false;var released=false;var releaseCount=0;var applied=1.0;var speed=1.0
        var startDelayUs=0L
        private var progressesAtUs=0L
        override fun snapshot()=NativeDjDeckState(source,duration,position,ready,playing,ended,failed,true,speed)
        override fun play(){check(!released);if(!playing)progressesAtUs=now+startDelayUs;playing=true}
        override fun pause(){playing=false}
        override fun gain(value:Double){check(!released);require(value.isFinite() && value in 0.0..1.0);applied=value}
        override fun release(){releaseCount++;check(!released);released=true;playing=false}
        fun advance(fromUs:Long,toUs:Long){if(playing && ready)position+=(toUs-maxOf(fromUs,progressesAtUs)).coerceAtLeast(0)}
    }
    override fun queue()=NativeDjQueue(generation,plan.outgoing.source,plan.incoming.source)
    override fun startupObservation()=startup
    override fun claim():Boolean {if(claimed || !canClaim)return false;claimed=true;return true}
    override fun prepare(plan:NativeTransitionPlan):NativeDjDeck {check(!prepareFailure);b.ready=incomingReady;return b}
    override fun promote(expected:NativeDjQueue,incoming:NativeDjDeck):Boolean {
        if(!promoteAllowed || queue()!=expected)return false
        check(promotions==0 && incoming===b);promotions++;return true
    }
    override fun restore(){restores++;claimed=false}
    fun advance(executor:NativeDjTransitionExecutor,delta:Long) {
        val previous=now
        now+=delta
        a.advance(previous,now);b.advance(previous,now)
        executor.tick()
    }
    fun toStart(executor:NativeDjTransitionExecutor){advance(executor,plan.candidate.outgoingCueUs-a.position)}
    fun finish(executor:NativeDjTransitionExecutor,step:Long=10_000){
        var count=0
        while(!executor.terminal && count++<10000)advance(executor,step)
        check(executor.terminal)
    }
}
internal fun mathematicalExecutionPlan():NativeTransitionPlan {
    val a=NativePlanningFixtures.evidence();val b=NativePlanningFixtures.evidence(originUs=0,id="in")
    return (NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(a,b),a,b) as NativePlanningOutcome.Selected).plan
}
