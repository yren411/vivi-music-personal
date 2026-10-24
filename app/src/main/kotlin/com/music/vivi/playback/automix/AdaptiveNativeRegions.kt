// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Strategy identity is separate from unchanged v8 PCM and the native backend. */
object AdaptiveNativeRegions {
    const val VERSION = "native-regions-v1-verified-inset"
    const val MIN_US = 8_000_000L
    const val MAX_US = 32_000_000L
    const val MAX_REGIONS = 6
    const val MAX_OPTIONAL_ATTEMPTS = 3
    const val OPTIONAL_DEADLINE_US = 6_000_000L

    /** Only a successfully decoded, single continuous interval may guide a second request.
     * Millisecond insets match the decoder's existing request contract, not a shifted clock.
     * Unknown coverage, interior gaps and failed decoding never authorize this recovery. */
    fun supportedInset(window: DecodedWindow): AudioIntervalUs? {
        if (window.invalidReason()!=null || window.format!=PcmFormat(22050,1,PcmEncoding.FLOAT32) || window.preprocessingVersion!=PcmPreprocessing.BANDLIMITED ||
            window.repeatedOutputFrames!=0L || window.preparation?.let {it.gapCount!=0 || it.synthesizedTimelineFrames!=0L}==true)
            return null
        val span=window.coverage.intervals?.singleOrNull()?.takeIf {it.valid} ?: return null
        if ((window.coverage.acceptedDecodedFrames ?: 0)<=0 || window.coverage.status(window.requested)==CoverageStatus.FULL) return null
        val start=maxOf(window.decodeInterval.startUs,span.startUs)
        if(start>Long.MAX_VALUE-999)return null
        val inset=AudioIntervalUs((start+999)/1000*1000,minOf(window.decodeInterval.endUs,span.endUs)/1000*1000)
        return inset.takeIf {it.valid && it.endUs-it.startUs in MIN_US..MAX_US && it!=window.requested && span.contains(it)}
    }

    fun key(request:DecodeRequest,detail:AnalysisDetail,immutable:Boolean,adaptive:Boolean):String {
        val digest=BitChordNativeAnalysisEngine.PROFILE.cacheKey(request.identity,0,0,detail)
        return "$digest:${PlanningSignalObservation.VERSION}:${NativeDjLevel.VERSION}:${if(adaptive)VERSION else "strict-window-v1"}:${request.interval.startUs}:${request.interval.endUs}:$immutable"
    }

    /** Selective progressive scan; no promise of full-track coverage on long recordings. */
    fun candidates(durationUs:Long):List<AudioIntervalUs> {
        if(durationUs !in MIN_US..1_800_000_000)return emptyList()
        val end=durationUs/1000*1000
        val length=minOf(MAX_US,end)
        val starts=if(end<=MAX_US*MAX_REGIONS) {
            (listOf(0L,end-length)+(1 until MAX_REGIONS).map {it*MAX_US}.filter {it<end})
        } else listOf(0L,end-length,(end-length)/2,(end-length)/4,(end-length)*3/4)
        return starts.map {start->AudioIntervalUs(start/1000*1000,minOf(end,start/1000*1000+length))}
            .filter {it.endUs-it.startUs>=MIN_US}.distinct().take(MAX_REGIONS)
    }
}

data class NativeRegionAudit(val strategy:String,val intent:AudioIntervalUs,val selected:AudioIntervalUs,
    val decodeExecutions:Int,val elapsedUs:Long,val decodedSourceFrames:Long) {
    val adapted get()=intent!=selected
}

/** Compact bounded evidence only; no PCM or source handles, no persisted private identity. */
class NativeRegionCatalog {
    private data class Source(val regions:LinkedHashMap<AudioIntervalUs,PlanningWindowEvidence> = linkedMapOf(),
        val attempted:MutableSet<AudioIntervalUs> = linkedSetOf(),var optionalAttempts:Int=0)
    private val sources=object:LinkedHashMap<AnalysisSourceIdentity,Source>(8,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<AnalysisSourceIdentity,Source>)=size>8
    }
    fun remember(evidence:PlanningWindowEvidence) {
        val result=evidence.analysis as? AnalysisResult.Completed ?: return
        val m=result.metadata
        if(m.backend!=BitChordNativeAnalysisEngine.BACKEND || m.preprocessingVersion!=PcmPreprocessing.BANDLIMITED ||
            m.coverageStatus!=CoverageStatus.FULL || m.coverage.status(m.requested)!=CoverageStatus.FULL ||
            m.sampleInterval==null || !m.requested.contains(m.sampleInterval) ||
            m.preparation?.let {it.gapCount!=0 || it.synthesizedTimelineFrames!=0L}==true)return
        val source=sources.getOrPut(m.source){Source()}
        val intent=evidence.region?.intent ?: m.requested
        source.regions[intent]=evidence
        while(source.regions.size>AdaptiveNativeRegions.MAX_REGIONS)source.regions.remove(source.regions.keys.first())
    }
    fun next(identity:AnalysisSourceIdentity,durationUs:Long):AudioIntervalUs? {
        val source=sources.getOrPut(identity){Source()}
        if(source.optionalAttempts>=AdaptiveNativeRegions.MAX_OPTIONAL_ATTEMPTS)return null
        return AdaptiveNativeRegions.candidates(durationUs).firstOrNull {it !in source.regions && it !in source.attempted && source.regions.values.none {e ->
            (e.analysis as? AnalysisResult.Completed)?.metadata?.sampleInterval?.contains(it)==true}}
    }
    fun started(identity:AnalysisSourceIdentity,interval:AudioIntervalUs) {
        val source=sources.getOrPut(identity){Source()};source.attempted+=interval;source.optionalAttempts++
    }
    fun summary(identity:AnalysisSourceIdentity):NativeRegionSummary {
        val values=sources[identity]?.regions?.values.orEmpty()
        val spans=values.mapNotNull {(it.analysis as? AnalysisResult.Completed)?.metadata?.sampleInterval}.sortedBy {it.startUs}
        var total=0L;var edge=0L
        for(span in spans) {total+=(span.endUs-maxOf(edge,span.startUs)).coerceAtLeast(0);edge=maxOf(edge,span.endUs)}
        val bpms=values.mapNotNull {(it.analysis as? AnalysisResult.Completed)?.evidence?.tempoBpm?.value}
        val comparison=when {
            bpms.size<2 -> NativeTempoAgreement.NOT_COMPARED
            bpms.any {a->bpms.any {b-> kotlin.math.abs(a-b)/a>.03}} -> NativeTempoAgreement.CONFLICT_OR_OCTAVE_UNVERIFIED
            else -> NativeTempoAgreement.REGIONAL_AGREEMENT_NOT_GLOBAL_PROOF
        }
        return NativeRegionSummary(values.size,total,spans.firstOrNull()?.startUs,spans.maxOfOrNull {it.endUs},comparison)
    }
    fun clear()=sources.clear()
}
enum class NativeTempoAgreement { NOT_COMPARED, REGIONAL_AGREEMENT_NOT_GLOBAL_PROOF, CONFLICT_OR_OCTAVE_UNVERIFIED }
data class NativeRegionSummary(val regions:Int,val verifiedUnionUs:Long,val earliestUs:Long?,val latestUs:Long?,val tempo:NativeTempoAgreement)
data class NativeReuseSnapshot(val strategy:String,val workers:AnalysisWorkerSnapshot,val outgoing:NativeRegionSummary,
    val incoming:NativeRegionSummary,val seekReplans:Long,val routeReplans:Long,val optionalCompleted:Long,
    val optionalCanceled:Long,val adaptedWindows:Long,val optionalInFlight:Boolean=false,
    val fullyCachedOutgoing:Boolean=false,val fullyCachedIncoming:Boolean=false,
    val actualDecoderCalls:Long=0,val measuredDecodedSourceFrames:Long=0)
