// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Reproducible mathematical fixtures, explicitly NOT phone playback or real-song evidence. */
class NativeReliabilityEvidenceTest {
    @Test fun compareBoundedWindowRankingWithPreservationAndMeasureBoundedHostCost() {
        val rows=mutableListOf<JsonElement>()
        for((label,duration,bpm,inBpm) in listOf(
            Case("owner-duration-synthetic-beats",163_865_000,147.95433486768678,146.44518730720463),
            Case("strong-ending",180_000_000,120.0,120.0),
            Case("short",8_000_000,120.0,120.0),
            Case("long",1_200_000_000,120.0,120.0),
            Case("incompatible-tempo",180_000_000,120.0,140.0))) {
            val length=minOf(duration,32_000_000)
            val a=NativePlanningFixtures.evidence(bpm,duration-length,"out",phaseUs=232_200,durationUs=duration,lengthUs=length)
            val b=NativePlanningFixtures.evidence(inBpm,0,"in",durationUs=180_000_000)
            val request=NativePlanningFixtures.request(a,b).copy(earliestOutgoingUs=(duration-length-1_000_000).coerceAtLeast(0),preparationLeadUs=0)
            val outcomes=NativePlacementPolicy.entries.associateWith {policy ->
                NativeAwareAutoMixPlanner().plan(request.copy(placementPolicy=policy),a,b)
            }
            val result=outcomes.getValue(NativePlacementPolicy.PRESERVE_OUTGOING)
            if(label=="incompatible-tempo") assertTrue(result is NativePlanningOutcome.Fallback)
            else {
                assertTrue("$label: $result",result is NativePlanningOutcome.Selected)
                val plan=(result as NativePlanningOutcome.Selected).plan
                assertTrue(plan.candidate.placement!!.remainingUs<=plan.candidate.placement.allowedLossUs)
                assertTrue(plan.candidate.alignment.maxResidualUs<=70_000)
            }
            rows+=buildJsonObject {
                put("fixture",label);put("durationUs",duration);put("bpm",bpm);put("incomingBpm",inBpm)
                for((policy,outcome) in outcomes) put(policy.name,buildJsonObject {
                    put("hostPlannerNanos",outcome.diagnostics.totalNanos);put("candidates",outcome.diagnostics.candidateCount)
                    put("truncated",outcome.diagnostics.searchTruncated)
                    if(outcome is NativePlanningOutcome.Selected) {
                        val c=outcome.plan.candidate;val p=requireNotNull(c.placement)
                        put("outgoingCueUs",c.outgoingCueUs);put("overlapUs",c.overlapUs);put("endpointUs",p.expectedEndpointUs)
                        put("remainingUs",p.remainingUs);put("preservedFraction",p.preservedFraction);put("allowedLossUs",p.allowedLossUs)
                        put("p95ResidualUs",c.alignment.p95ResidualUs);put("predictedTempoDriftUs",c.alignment.predictedTempoDriftUs)
                    } else put("fallback",(outcome as NativePlanningOutcome.Fallback).reason.name)
                    put("rejections",buildJsonObject {outcome.diagnostics.rejections.forEach {(r,n)->put(r.name,n)}})
                    put("laterCandidateRejections",buildJsonObject {outcome.diagnostics.laterCandidateRejections.forEach {(r,n)->put(r.name,n)}})
                })
            }
        }
        val state=NativeDjDeckState(AnalysisSourceIdentity("generated","synthetic"),180_000_000,10_000_000,true,true)
        val a=NativeDjClockObservation(state,0,10);val b=NativeDjClockObservation(state.copy(positionUs=10_000_030),30,40)
        var checksum=0L
        val clockBegan=System.nanoTime()
        repeat(100_000){checksum+=NativeDjClockPair(a,b).offset(0,0).guardedDriftUs}
        val clockNanos=System.nanoTime()-clockBegan
        assertEquals(4_000_000L,checksum)
        val tracker=NativeOutputRouteTracker()
        val snapshot=NativeRouteSnapshot(NativeRouteEndpoint(1,1,NativeRouteCategory.SPEAKER),null,false)
        tracker.observe(snapshot,NativeRouteNotification.POLL)
        val routeBegan=System.nanoTime();var cancellations=0
        repeat(100_000){if(tracker.observe(snapshot,NativeRouteNotification.DEVICES_ADDED,true).material)cancellations++}
        val routeNanos=System.nanoTime()-routeBegan
        assertEquals(0,cancellations)
        val report=buildJsonObject {
            put("schema",1);put("evidence","host JVM generated mathematical beat evidence; no Android/acoustic execution")
            put("baselineRanking","v1 ranking replayed through explicit ANALYZED_REGION policy in v2; not an old-binary rerun")
            put("planner",NativeAwareAutoMixPlanner.VERSION);put("executor",NativeDjTransitionExecutor.VERSION)
            put("cases",JsonArray(rows));put("clockIterations",100_000);put("clockHostNanos",clockNanos)
            put("routeIterations",100_000);put("routeHostNanos",routeNanos);put("routeCancellations",cancellations)
        }
        System.getenv("TINIUM_PHASE332_REPORT_DIR")?.let {path ->
            File(path).mkdirs();File(path,"placement-and-observation-host.json").writeText(Json {prettyPrint=true}.encodeToString(JsonElement.serializer(),report)+"\n")
        }
    }
    private data class Case(val label:String,val duration:Long,val bpm:Double,val inBpm:Double)
}
