// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/** Explicit desktop/offline experiment. CI skips it honestly when host JNI is unavailable.
 * Optional local PCM manifest/report directories are supplied through environment variables;
 * neither filenames nor raw audio enter the report. No network/source resolver is used. */
class NativePlannerComparisonTest {
    private data class Audio(val pcm:FloatArray,val rate:Int=22050,val origin:Long=0,val duration:Long=180_000_000,
                             val expectedBpm:Double?=null,val pattern:String="local",val phase:Double=.137,
                             val leading:Double=0.0,val drift:Double=0.0,val amplitude:Double=1.0)
    private data class PairCase(val id:String,val outgoing:Audio,val incoming:Audio)
    private data class Analyzed(val native:PlanningWindowEvidence,val legacy:AnalysisResult,
                                val nativeUs:Long,val legacyUs:Long,val correctedUs:Long,val legacyPreparationUs:Long)

    @Test fun compareRealNativeAndLegacyOnEquivalentGeneratedOrLocalSourcePcm() {
        assumeTrue("Real host JNI is needed for controlled planner comparison",!System.getProperty("tinium.hostNativeLibrary").isNullOrBlank())
        val corpus=System.getenv("TINIUM_PHASE31_CORPUS_DIR")?.let(::File)
        val cases=if(corpus==null) generated() else local(corpus)
        val salt=UUID.randomUUID().toString() // Never emitted; only opaque session-salted input IDs are exported.
        val bridge=JniBitChordBridge()
        // A configured but missing/broken library must FAIL this experiment, not yield a
        // misleadingly successful table consisting entirely of technical fallbacks.
        NativeJniChecks.loading(bridge)
        val engine=BitChordNativeAnalysisEngine(bridge)
        val rows=cases.map { case ->
            val a=analyze(case.outgoing,"${case.id}-out",engine)
            val b=analyze(case.incoming,"${case.id}-in",engine)
            val am=(a.native.analysis as? AnalysisResult.Completed)?.metadata
            val bm=(b.native.analysis as? AnalysisResult.Completed)?.metadata
            val request=NativePlanningRequest(
                PlanningTrack(AnalysisSourceIdentity("${case.id}-out","source-v1"),case.outgoing.duration,
                    AudioIntervalUs(case.outgoing.origin,case.outgoing.origin+case.outgoing.pcm.size*1_000_000L/case.outgoing.rate)),
                PlanningTrack(AnalysisSourceIdentity("${case.id}-in","source-v1"),case.incoming.duration,
                    AudioIntervalUs(case.incoming.origin,case.incoming.origin+case.incoming.pcm.size*1_000_000L/case.incoming.rate)),
                (case.outgoing.origin-5_000_000).coerceAtLeast(0),5_000_000,1,salt,placementPolicy=NativePlacementPolicy.ANALYZED_REGION)
            val experimental=NativeAwareAutoMixPlanner().plan(request,a.native,b.native)
            val began=System.nanoTime()
            val legacyA=(a.legacy as? AnalysisResult.Completed)?.let(LegacyPlanningFeatures::require)
            val legacyB=(b.legacy as? AnalysisResult.Completed)?.let(LegacyPlanningFeatures::require)
            val legacy=AutoMixPlanner().decide(legacyA,legacyB,case.outgoing.duration/1000,case.incoming.duration/1000,
                (request.earliestOutgoingUs+request.preparationLeadUs)/1000)
            val legacyPlanningUs=(System.nanoTime()-began)/1000.0
            val plan=(experimental as? NativePlanningOutcome.Selected)?.plan
            val fallback=(experimental as? NativePlanningOutcome.Fallback)?.reason
            val nativeATiming=beatMetrics(a.native,case.outgoing)
            val nativeBTiming=beatMetrics(b.native,case.incoming)
            val localA=plan?.candidate?.let {beatMetrics(a.native,case.outgoing,AudioIntervalUs(it.outgoingCueUs,it.outgoingCueUs+it.overlapUs))}
            val localB=plan?.candidate?.let {beatMetrics(b.native,case.incoming,AudioIntervalUs(it.incomingCueUs,it.incomingCueUs+it.overlapUs))}
            val suspicious=plan!=null && listOfNotNull(localA?.get("f1At30ms") as? Double,localB?.get("f1At30ms") as? Double).any {it<.8}
            val legacyIntelligent=legacy.plan?.tier?.let {it!=AutoMixTier.C} ?: false
            val category=when {
                legacyA==null || legacyB==null -> "INSUFFICIENT_COMPARISON_EVIDENCE"
                suspicious -> "NATIVE_SUSPICIOUS_METRICAL_OR_PHASE"
                plan!=null && legacyIntelligent -> "BOTH_PLANS"
                plan!=null -> "NATIVE_SUPPORTED_LEGACY_FALLBACK"
                legacyIntelligent -> "LEGACY_SUCCESS_NATIVE_REJECT"
                else -> "BOTH_FALLBACK"
            }
            val d=experimental.diagnostics
            linkedMapOf<String,Any?>(
                "pair" to case.id,"category" to category,"sourceRateOutgoing" to case.outgoing.rate,"sourceRateIncoming" to case.incoming.rate,
                "outgoingFixture" to fixtureMetadata(case.outgoing),"incomingFixture" to fixtureMetadata(case.incoming),
                "outgoingReportId" to d.outgoingReportId,"incomingReportId" to d.incomingReportId,
                "outgoingPattern" to case.outgoing.pattern,"incomingPattern" to case.incoming.pattern,
                "expectedOutgoingBpm" to case.outgoing.expectedBpm,"expectedIncomingBpm" to case.incoming.expectedBpm,
                "legacyOutgoingBpm" to legacyA?.bpm,"legacyIncomingBpm" to legacyB?.bpm,
                "nativeOutgoingBpm" to tempo(a.native),"nativeIncomingBpm" to tempo(b.native),
                "nativeOutgoingRawScore" to confidence(a.native),"nativeIncomingRawScore" to confidence(b.native),
                "nativeOutgoingBeatMetrics" to nativeATiming,"nativeIncomingBeatMetrics" to nativeBTiming,
                "selectedOutgoingBeatMetrics" to localA,"selectedIncomingBeatMetrics" to localB,
                "legacyTier" to legacy.plan?.tier?.name,"legacyReason" to legacy.plan?.reason,
                "legacyOutgoingUs" to legacy.plan?.outgoingStartMs?.times(1000),"legacyIncomingUs" to legacy.plan?.incomingCueMs?.times(1000),
                "legacyOverlapUs" to legacy.plan?.durationMs?.times(1000),"legacyRate" to legacy.plan?.incomingRate,
                "legacyAlignment" to legacyAlignment(legacy.plan,legacyA,legacyB),"legacyRejections" to legacy.rejections,
                "nativeOutcome" to if(plan!=null) "SELECTED" else "FALLBACK","nativeFallback" to fallback?.name,
                "nativeReadiness" to plan?.readiness?.name,"nativeOutgoingUs" to plan?.candidate?.outgoingCueUs,
                "nativeIncomingUs" to plan?.candidate?.incomingCueUs,"nativeOverlapUs" to plan?.candidate?.overlapUs,
                "tempoRelation" to plan?.candidate?.tempo?.relation?.name,"phaseMedianUs" to plan?.candidate?.alignment?.medianResidualUs,
                "phaseP95Us" to plan?.candidate?.alignment?.p95ResidualUs,"phaseMaxUs" to plan?.candidate?.alignment?.maxResidualUs,
                "endDriftUs" to plan?.candidate?.alignment?.signedEndDriftUs,"predictedTempoDriftUs" to plan?.candidate?.alignment?.predictedTempoDriftUs,
                "matchedPulses" to plan?.candidate?.alignment?.matchedPulses,"outgoingTransientSupport" to plan?.candidate?.outgoingTransientSupport,
                "incomingTransientSupport" to plan?.candidate?.incomingTransientSupport,"uncertainties" to plan?.candidate?.uncertainties?.map {it.name},
                "candidates" to d.candidateCount,"acceptedCandidates" to d.acceptedCount,
                "rejectedCandidates" to d.candidateCount-d.acceptedCount,"rejections" to d.rejections.mapKeys {it.key.name},
                "nativePlanningUs" to d.totalNanos/1000.0,"validationUs" to d.validationNanos/1000.0,
                "anchorGenerationUs" to d.anchorGenerationNanos/1000.0,"candidateEvaluationUs" to d.candidateEvaluationNanos/1000.0,
                "legacyPlanningUs" to legacyPlanningUs,"nativeAnalysisOutgoingUs" to a.nativeUs,"nativeAnalysisIncomingUs" to b.nativeUs,
                "legacyAnalysisOutgoingUs" to a.legacyUs,"legacyAnalysisIncomingUs" to b.legacyUs,
                "correctedPreparationOutgoingUs" to a.correctedUs,"correctedPreparationIncomingUs" to b.correctedUs,
                "legacyPreparationOutgoingUs" to a.legacyPreparationUs,"legacyPreparationIncomingUs" to b.legacyPreparationUs,
                "nativeOutgoingCoverage" to am?.coverage?.status(am.sampleInterval!!)?.name,
                "nativeIncomingCoverage" to bm?.coverage?.status(bm.sampleInterval!!)?.name,
                "requiredExecutor" to plan?.requiredFutureCapability)
        }.toList() // Retain compact reports only; stream one PCM pair at a time.
        val report=mapOf("schema" to 1,"generator" to "phase31-planning-corpus-v1","sourceKind" to if(corpus==null) "GENERATED" else "USER_SUPPLIED_LOCAL_PCM",
            "baseCommit" to "e76bfcdd0c6fa47580fd2b3b4fab427930546198","planner" to NativeAwareAutoMixPlanner.VERSION,
            "nativeBackend" to BitChordNativeAnalysisEngine.VERSION,"nativePreprocessing" to PcmPreprocessing.BANDLIMITED,
            "legacyPreprocessing" to PcmPreprocessing.LEGACY,"signalAudit" to PlanningSignalObservation.VERSION,
            "environment" to "Desktop JVM ${System.getProperty("java.version")} / ${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
            "categories" to rows.groupingBy {it["category"]}.eachCount(),"pairs" to rows)
        val json=Json {prettyPrint=true}.encodeToString(JsonElement.serializer(),element(report))
        System.getenv("TINIUM_PHASE31_REPORT_DIR")?.let { path ->
            val directory=File(path);check(directory.isDirectory || directory.mkdirs())
            File(directory,"comparison.json").writeText(json+"\n")
        }
        println("NATIVE_PLANNER_COMPARISON cases=${rows.size} categories=${report["categories"]}")
        if(corpus==null) assertEquals(31,rows.size)
        // Never assert all generated pairs succeed: preserve legitimate failures and suspicious grids.
        assertTrue(rows.isNotEmpty())
    }

    private fun generated():Sequence<PairCase> = sequence {
        fun audio(bpm:Double=128.0,pattern:String="kick",phase:Double=.137,leading:Double=0.0,drift:Double=0.0,
                  seconds:Int=30,origin:Long=0,amplitude:Double=1.0,rate:Int=22050):Audio {
            val canonical=NativePlanningFixtures.pcm(bpm,seconds,phase,pattern,leading,drift,amplitude)
            // Source-rate cases use a generated continuous-time fixture, not arbitrary resampling.
            val pcm=if(rate==22050) canonical else AnalysisPcmFixtures.groove(bpm,seconds=seconds,rate=rate,phaseSeconds=phase).samples
            return Audio(pcm,rate,origin,180_000_000,if(pattern in setOf("kick","backbeat","hats"))bpm else null,
                if(rate==22050)pattern else "phase21-groove",phase,leading,drift,amplitude)
        }
        for(bpm in listOf(90.0,107.0,120.0,128.0,140.0,150.0,174.0)) {
            yield(PairCase("same-${bpm.toInt()}-offset",audio(bpm,origin=150_000_000),audio(bpm,phase=.333)))
        }
        for((a,b) in listOf(90.0 to 120.0,120.0 to 128.0,128.0 to 140.0,150.0 to 174.0,128.0 to 129.0,120.0 to 123.0,
            87.0 to 174.0,174.0 to 87.0)) yield(PairCase("tempo-${a.toInt()}-${b.toInt()}",audio(a,origin=150_000_000),audio(b)))
        for(pattern in listOf("backbeat","hats")) yield(PairCase("174-kick-to-$pattern",audio(174.0,origin=150_000_000),audio(174.0,pattern)))
        for(pattern in listOf("silence","noise","chord","sweep")) yield(PairCase("negative-$pattern",audio(origin=150_000_000),audio(pattern=pattern)))
        for(pattern in listOf("silence","noise","chord","sweep")) yield(PairCase("same-negative-$pattern",audio(pattern=pattern,origin=150_000_000),audio(pattern=pattern)))
        yield(PairCase("leading-18s",audio(origin=150_000_000),audio(leading=18.0)))
        yield(PairCase("drift-3bpm",audio(origin=150_000_000),audio(drift=3.0)))
        yield(PairCase("extended-64-pulse-128",audio(seconds=32,origin=148_000_000),audio(seconds=32,phase=.333)))
        yield(PairCase("quiet-128-hats",audio(pattern="hats",origin=150_000_000,amplitude=.1),audio(pattern="hats",amplitude=.1)))
        yield(PairCase("source-48000-44100",audio(origin=150_000_000,rate=48000),audio(rate=44100)))
        yield(PairCase("short-window",audio(seconds=3,origin=177_000_000),audio(seconds=3)))
    }

    private fun shift(w:DecodedWindow,input:Audio,id:String):DecodedWindow {
        fun interval(span:AudioIntervalUs)=AudioIntervalUs(Math.addExact(span.startUs,input.origin),Math.addExact(span.endUs,input.origin))
        return w.copy(requested=interval(w.requested),decodeInterval=interval(w.decodeInterval),sampleTimelineStartUs=Math.addExact(w.sampleTimelineStartUs,input.origin),
            coverage=w.coverage.copy(intervals=w.coverage.intervals?.map(::interval)),duration=AudioDuration(input.duration,DurationBasis.FIXTURE),
            identity=AnalysisSourceIdentity(id,"source-v1"))
    }
    private fun fixtureMetadata(input:Audio):Map<String,Any?> = mapOf("rate" to input.rate,"frames" to input.pcm.size,
        "originUs" to input.origin,"declaredTrackDurationUs" to input.duration,"pattern" to input.pattern,
        "expectedBpm" to input.expectedBpm,"phaseSeconds" to input.phase,"leadingSeconds" to input.leading,
        "driftBpm" to input.drift,"amplitude" to input.amplitude)
    private fun analyze(input:Audio,id:String,engine:AnalysisEngine):Analyzed {
        val start=System.nanoTime()
        val corrected=shift(PcmTestPreparation.prepare(input.pcm,input.rate,AnalysisPreprocessing.BANDLIMITED),input,id)
        val correctedUs=(System.nanoTime()-start)/1000
        val began=System.nanoTime()
        val native=PlanningWindowEvidence(engine.analyze(corrected,AnalysisDetail.DETAIL,AnalysisControl()),
            PlanningSignalObservation.measure(corrected,AnalysisControl()))
        val nativeUs=(System.nanoTime()-began)/1000
        val legacyBegan=System.nanoTime()
        val legacyWindow=shift(PcmTestPreparation.prepare(input.pcm,input.rate,AnalysisPreprocessing.LEGACY),input,id)
        val legacyPreparationUs=(System.nanoTime()-legacyBegan)/1000
        val analysisBegan=System.nanoTime()
        val legacy=LegacyKotlinAnalysisEngine().analyze(legacyWindow,AnalysisDetail.DETAIL,AnalysisControl())
        return Analyzed(native,legacy,nativeUs,(System.nanoTime()-analysisBegan)/1000,correctedUs,legacyPreparationUs)
    }
    private fun tempo(w:PlanningWindowEvidence)=(w.analysis as? AnalysisResult.Completed)?.evidence?.tempoBpm?.value
    private fun confidence(w:PlanningWindowEvidence)=(w.analysis as? AnalysisResult.Completed)?.evidence?.gridConfidence?.value

    private fun beatMetrics(window:PlanningWindowEvidence,input:Audio,region:AudioIntervalUs?=null):Map<String,Any?> {
        val detected=((window.analysis as? AnalysisResult.Completed)?.evidence?.beatsUs?.value ?: emptyList())
            .filter {region==null || it in region.startUs until region.endUs}
        val bpm=input.expectedBpm ?: return mapOf("expected" to if(input.pattern=="local")null else 0,
            "detected" to detected.size,"f1At30ms" to null)
        val truth=ArrayList<Long>();var time=input.leading+input.phase
        val seconds=input.pcm.size.toDouble()/input.rate
        while(time<seconds) {
            val beat=input.origin+(time*input.rate).roundToLong()*1_000_000/input.rate
            if(region==null || beat in region.startUs until region.endUs)truth+=beat
            time+=60/(bpm+input.drift*time/seconds)
        }
        fun metric(tolerance:Long):Map<String,Any?> {
            var i=0;var j=0;val errors=ArrayList<Long>()
            while(i<truth.size && j<detected.size) {
                val error=detected[j]-truth[i]
                if(error < -tolerance)j++ else if(error>tolerance)i++ else {errors+=abs(error);i++;j++}
            }
            errors.sort()
            val precision=if(detected.isEmpty())0.0 else errors.size.toDouble()/detected.size
            val recall=if(truth.isEmpty())0.0 else errors.size.toDouble()/truth.size
            return mapOf("precision" to precision,"recall" to recall,"f1" to if(precision+recall==0.0)0.0 else 2*precision*recall/(precision+recall),
                "medianUs" to errors.getOrNull(errors.size/2),"p95Us" to errors.getOrNull(((errors.size-1)*.95).roundToLong().toInt().coerceAtLeast(0)))
        }
        val tight=metric(30_000)
        return mapOf("expected" to truth.size,"detected" to detected.size,"f1At30ms" to tight["f1"],"at30ms" to tight,"at70ms" to metric(70_000),
            "rawTempoErrorBpm" to tempo(window)?.let {abs(it-bpm)},"halfDoubleRelationUnverified" to tempo(window)?.let { measured ->
                listOf(.5,1.0,2.0).minBy {abs(measured*it-bpm)} })
    }
    private fun legacyAlignment(plan:AutoMixPlan?,a:TrackFeatures?,b:TrackFeatures?):Map<String,Any?>? {
        if(plan==null || plan.tier==AutoMixTier.C || a==null || b==null)return null
        val x=a.beatsMs.filter {it in plan.outgoingStartMs..plan.outgoingStartMs+plan.durationMs}.map { (it-plan.outgoingStartMs)*1000.0 }
        val y=b.beatsMs.filter {it>=plan.incomingCueMs && (it-plan.incomingCueMs)/plan.incomingRate<=plan.durationMs}
            .map {(it-plan.incomingCueMs)*1000.0/plan.incomingRate}
        val residual=x.zip(y) {u,v ->abs(u-v)}.sorted()
        return mapOf("method" to "first-following-beats-fixed-index-with-legacy-planned-rate","matched" to residual.size,
            "medianUs" to residual.getOrNull(residual.size/2),"maxUs" to residual.lastOrNull())
    }
    private fun local(root:File):Sequence<PairCase> {
        val manifest=File(root,"pairs.json")
        require(manifest.length() in 1..65536)
        val pairs=Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("pairs").jsonArray
        require(pairs.size in 1..64)
        fun read(element:JsonElement):Audio {
            val o=element.jsonObject
            val rate=o.getValue("sampleRate").jsonPrimitive.int
            require(rate in 8000..96000)
            val file=File(root,o.getValue("pcm").jsonPrimitive.content).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.length() in 4..(rate*32L*4) && file.length()%4==0L)
            val buffer=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val samples=FloatArray(buffer.remaining());buffer.get(samples);require(samples.all {it.isFinite()})
            val origin=o.getValue("originUs").jsonPrimitive.long;val duration=o.getValue("trackDurationUs").jsonPrimitive.long
            require(origin>=0 && origin%1000==0L && samples.size*1_000_000L/rate<=duration-origin)
            return Audio(samples,rate,origin,duration)
        }
        return pairs.asSequence().mapIndexed {index, pair ->PairCase("local-$index",read(pair.jsonObject.getValue("outgoing")),read(pair.jsonObject.getValue("incoming")))}
    }
    private fun element(value:Any?):JsonElement=when(value) {
        null -> JsonNull
        is Map<*,*> -> JsonObject(value.entries.associate {it.key.toString() to element(it.value)})
        is Iterable<*> -> JsonArray(value.map(::element))
        is Boolean -> JsonPrimitive(value)
        is Number -> if(value.toDouble().isFinite())JsonPrimitive(value) else JsonNull
        else -> JsonPrimitive(value.toString())
    }
}
