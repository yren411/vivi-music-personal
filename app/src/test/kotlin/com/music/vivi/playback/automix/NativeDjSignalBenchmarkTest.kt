// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class NativeDjSignalBenchmarkTest {
    @Test fun reportCurveAndSignalMeasurementsAndVirtualFailureRecovery() {
        val rows=mutableListOf<JsonObject>()
        for(curve in NativeDjCurve.entries)for(kind in listOf("correlated","uncorrelated","quiet-tail","quiet-incoming","offset-kicks","aligned-kicks","leading-silence","silence","amplitude-step")) {
            val seconds=16;val rate=22050;var peak=0.0;var square=0.0;var inSquare=0.0;var outSquare=0.0;var maxGainStep=0.0
            var previous=NativeDjGain.gains(0.0,curve)
            for(i in 0 until seconds*rate) {
                val t=i.toDouble()/rate;val progress=i.toDouble()/(seconds*rate-1)
                fun kick(offset:Double):Double {val phase=(t+offset)%.5;return .9*sin(2*PI*70*phase)*min(1.0,phase/.002)*exp(-phase*36)}
                val a=when(kind){"silence"->0.0;"quiet-tail"->.0001*sin(2*PI*220*t);"aligned-kicks","offset-kicks"->kick(0.0);else->.8*sin(2*PI*220*t)}
                val b=when(kind){"silence"->0.0;"quiet-incoming"->.0001*sin(2*PI*220*t);"leading-silence"->if(t<4)0.0 else .9*sin(2*PI*220*t);"aligned-kicks"->kick(0.0);"offset-kicks"->kick(.2);"uncorrelated"->.9*sin(2*PI*337*t);"amplitude-step"->(if(t<8).1 else .9)*sin(2*PI*220*t);else->.9*sin(2*PI*220*t)}
                val gains=NativeDjGain.gains(progress,curve)
                val x=a*gains.outgoing;val y=b*gains.incoming;val mixed=x+y
                peak=max(peak,abs(mixed));square+=mixed*mixed;outSquare+=x*x;inSquare+=y*y
                maxGainStep=max(maxGainStep,max(abs(gains.outgoing-previous.outgoing),abs(gains.incoming-previous.incoming)));previous=gains
            }
            assertTrue("$curve/$kind peak=$peak",peak<=1.0+1e-12);assertTrue(maxGainStep<.001)
            rows+=buildJsonObject {put("scenario",kind);put("method","offline Float PCM sum; not captured Android audio");put("curve",curve.name)
                put("durationSeconds",seconds);put("combinedPeak",peak);put("combinedRms",sqrt(square/(seconds*rate)))
                put("outgoingContributionRms",sqrt(outSquare/(seconds*rate)));put("incomingContributionRms",sqrt(inSquare/(seconds*rate)))
                put("maximumPerSampleGainStep",maxGainStep);put("contentOutgoing",1);put("contentIncoming",1)}
        }
        val timings=mutableListOf<Long>()
        repeat(20){
            val p=mathematicalExecutionPlan();val h=VirtualNativeDjHost(p);val e=NativeDjTransitionExecutor(h,{h.now})
            val start=System.nanoTime();e.start(p);h.toStart(e);h.finish(e);timings+=System.nanoTime()-start
            assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage)
        }
        val failures=mutableListOf<JsonObject>()
        for(reason in listOf(NativeDjExecutionReason.CANCELED,NativeDjExecutionReason.USER_SEEK,NativeDjExecutionReason.USER_SKIP,NativeDjExecutionReason.ROUTE_CHANGED,NativeDjExecutionReason.SHUTDOWN)) {
            val p=mathematicalExecutionPlan();val h=VirtualNativeDjHost(p);val e=NativeDjTransitionExecutor(h,{h.now});e.start(p);h.toStart(e);h.advance(e,2_000_000);e.cancel(reason)
            assertTrue(h.a.playing && !h.b.playing);assertEquals(0,h.promotions)
            failures+=buildJsonObject{put("scenario",reason.name);put("method","virtual player clocks");put("status",e.status.stage.name);put("reason",e.status.reason!!.name);put("promotions",h.promotions);put("outgoingContinued",h.a.playing);put("outgoingGain",h.a.applied);put("incomingReleased",h.b.released)}
        }
        val report=buildJsonObject {
            put("schema",1);put("executor",NativeDjTransitionExecutor.VERSION);put("audioCapturePerformed",false)
            put("signals",JsonArray(rows));put("recoveries",JsonArray(failures))
            put("virtualExecutionHostNanos",JsonArray(timings.map(::JsonPrimitive)))
            put("timedWork","1600 simulated 10ms ticks per 16s virtual transition; excludes analysis, Android scheduling and playback")
            put("memoryEstimate","At most two caller-owned Media3 players, 128 compact event records, no PCM retained by executor; player/native/Android RSS not measured")
        }
        System.getenv("TINIUM_PHASE32_REPORT_DIR")?.let {directory->File(directory).mkdirs();File(directory,"signal-and-virtual-execution.json").writeText(Json {prettyPrint=true}.encodeToString(JsonElement.serializer(),report)+"\n")}
    }
}
