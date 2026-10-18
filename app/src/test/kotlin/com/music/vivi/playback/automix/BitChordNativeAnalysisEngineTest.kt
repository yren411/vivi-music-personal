// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class BitChordNativeAnalysisEngineTest {
    private fun w()=NativeAnalysisFixtures.window()
    private fun engine(change: (NativeDspPacket)->NativeDspPacket = {it}) =
        BitChordNativeAnalysisEngine(NativeDspBridge { window,_ -> change(NativeAnalysisFixtures.packet(window)) })
    private fun failed(result: AnalysisResult)=(result as AnalysisResult.Failed).failure
    private fun packet(p: NativeDspPacket, schema:Int=p.schema,status:String=p.status,values:DoubleArray=p.scalars,
                       beats:DoubleArray=p.beatsSeconds,down:DoubleArray=p.heuristicDownbeatsSeconds)=NativeDspPacket(schema,status,values,beats,down)

    @Test fun nativeProfileIsVersionedAndCannotCollideWithLegacy() {
        val native=BitChordNativeAnalysisEngine.PROFILE;val legacy=LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE
        assertNotEquals(native,legacy);assertEquals(PcmPreprocessing.BANDLIMITED,native.preprocessingVersion)
        assertTrue(native.backend.version.contains("internal11025"));assertTrue(native.backend.version.contains("evidence1"))
        val id=w().identity
        val keys=listOf(native,legacy,native.copy(preprocessingVersion=PcmPreprocessing.LEGACY),
            native.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V2),
            native.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V3),
            native.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V4),native.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V5),native.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V6),
            native.copy(backend=native.backend.copy(version="future-jni2"))).map { it.cacheKey(id,0,8000,AnalysisDetail.SCAN) }
        assertEquals(9,keys.distinct().size);assertFalse(keys.any { it.contains(id.renditionKey) })
    }
    @Test fun evidenceUsesNativeSemanticsAndNeverInventsOtherFeatures() {
        val r=engine().analyze(w(),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Completed
        assertEquals(EvidenceMethod.BITCHORD_UNCALIBRATED_CONFIDENCE,r.evidence.gridConfidence.method)
        assertEquals(EvidenceMethod.BITCHORD_FOUR_BEAT_BASS_ACCENT,r.evidence.downbeatsUs.method)
        for (e in listOf(r.evidence.phaseConfidence,r.evidence.tempoStability,r.evidence.gridSupport,r.evidence.key,
            r.evidence.keyConfidence,r.evidence.vocalRisk,r.evidence.energy))assertEquals(EvidenceNature.UNAVAILABLE,e.nature)
        assertTrue(r.evidence.confidenceSemantics.contains("NOT a probability"));assertNull(r.legacyFeatures)
        assertEquals(listOf(60.0,120.0),r.metadata.nativeDiagnostic!!.unverifiedMetricalHypotheses)
        val rounded=engine { packet(it,values=it.scalars.copyOf().also {v->v[10]=.500000000000001}) }
            .analyze(w(),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Completed
        assertEquals(.500000000000001,rounded.evidence.rms.value!!,0.0) // no normalization/clamp
    }
    @Test fun plannerBridgeRejectsNativeCompletedEvidence() {
        val result=engine().analyze(w(),AnalysisDetail.SCAN,AnalysisControl())
        assertThrows(IllegalStateException::class.java) {LegacyPlanningFeatures.require(result)}
    }
    @Test fun fractionalOriginIsAddedExactlyOnce() {
        val r=engine().analyze(NativeAnalysisFixtures.window(origin=12_345_678),AnalysisDetail.DETAIL,AnalysisControl()) as AnalysisResult.Completed
        assertEquals(12_482_678L,r.evidence.beatsUs.value!!.first())
        assertEquals(12_345_678L,r.metadata.sampleInterval!!.startUs)
        assertNull(ExperimentalAnalysisAttempt(r,listOf(BitChordNativeAnalysisEngine.PROFILE)).completedCacheKey())
    }
    @Test fun largeOriginAndBoundaryRoundingAreChecked() {
        val interval=AudioIntervalUs(Long.MAX_VALUE-8_000_000,Long.MAX_VALUE)
        assertEquals(interval.startUs+123_457,BitChordNativeAnalysisEngine.relativeBeatUs(.1234567,interval))
        for (s in listOf(Double.NaN,-.01,8.0))assertThrows(IllegalArgumentException::class.java) {
            BitChordNativeAnalysisEngine.relativeBeatUs(s,interval)
        }
        assertThrows(ArithmeticException::class.java) {
            BitChordNativeAnalysisEngine.relativeBeatUs(1.0,AudioIntervalUs(Long.MAX_VALUE-1,Long.MAX_VALUE))
        }
        assertEquals(100_001L,BitChordNativeAnalysisEngine.relativeBeatUs(.000001,AudioIntervalUs(100_000,100_002)))
    }
    @Test fun fullCoverageIsRequiredBeforeCallingBridge() {
        val base=w();var calls=0
        val e=BitChordNativeAnalysisEngine(NativeDspBridge { _,_->calls++;throw AssertionError("Unexpected native call") })
        val variants=listOf(base.copy(coverage=AudioCoverage(null,null)),base.copy(coverage=AudioCoverage(emptyList(),0)),
            base.copy(coverage=AudioCoverage(listOf(AudioIntervalUs(1000,base.decodeInterval.endUs)),base.samples.size.toLong())),
            base.copy(repeatedOutputFrames=1))
        for (v in variants)assertEquals(AutoMixFailure.INSUFFICIENT_AUDIO_DATA,failed(e.analyze(v,AnalysisDetail.SCAN,AnalysisControl())).category)
        assertEquals(0,calls)
    }
    @Test fun effectiveEosCoverageRemainsDistinctFromRequestedCoverage() {
        val base=w().copy(requested=AudioIntervalUs(0,10_000_000),endOfStreamReached=true)
        val r=engine().analyze(base,AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Completed
        assertEquals(CoverageStatus.PARTIAL,r.metadata.coverageStatus)
        assertEquals(CoverageStatus.FULL,r.metadata.coverage.status(r.metadata.sampleInterval!!))
    }
    @Test fun invalidInputFormatAndLegacyPreprocessingAreRejected() {
        val variants=listOf(w().copy(preprocessingVersion=PcmPreprocessing.LEGACY),
            w().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V2),w().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V3),w().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V4),w().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V5),w().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V6),w().copy(format=PcmFormat(44100,1,PcmEncoding.FLOAT32)),
            w().copy(format=PcmFormat(22050,2,PcmEncoding.FLOAT32)),w().copy(samples=floatArrayOf()),
            w().copy(format=PcmFormat(22050,1,PcmEncoding.PCM16)))
        for (v in variants)assertTrue(engine().analyze(v,AnalysisDetail.SCAN,AnalysisControl()) is AnalysisResult.Failed)
    }
    @Test fun nativeMetadataAndOrderingAreValidated() {
        val changes=listOf<(NativeDspPacket)->NativeDspPacket>(
            {packet(it,schema=99)}, {packet(it,values=doubleArrayOf())}, {packet(it,status="arbitrary secret URL")},
            {packet(it,values=it.scalars.copyOf().also {v->v[0]++})},
            {packet(it,values=it.scalars.copyOf().also {v->v[6]=Double.NaN})},
            {packet(it,values=it.scalars.copyOf().also {v->v[9]=1.1})},
            {packet(it,values=it.scalars.copyOf().also {v->v[10]=.6})},
            {packet(it,values=it.scalars.copyOf().also {v->v[3]=22050.0})},
            {packet(it,values=it.scalars.copyOf().also {v->v[4]=9.0})},
            {packet(it,beats=doubleArrayOf(.137,.137))}, {packet(it,beats=doubleArrayOf(.137,9.0))},
            {packet(it,beats=DoubleArray(513))}, {packet(it,down=doubleArrayOf(.2))},
            {packet(it,values=it.scalars.copyOf().also {v->v[8]=.3})})
        for (change in changes) {
            val failure=failed(engine(change).analyze(w(),AnalysisDetail.SCAN,AnalysisControl()))
            assertEquals(AutoMixFailure.NATIVE_INVALID_OUTPUT,failure.category)
            assertFalse(failure.toString().contains("secret URL"))
        }
    }
    @Test fun statusClassificationsAreDistinct() {
        for ((status,category) in mapOf("CANCELED" to AutoMixFailure.ANALYSIS_CANCELED,"DEADLINE" to AutoMixFailure.ANALYSIS_TIMEOUT,
            "INTERNAL_ERROR" to AutoMixFailure.NATIVE_FAILED,"ALLOCATION_FAILURE" to AutoMixFailure.NATIVE_FAILED,
            "INVALID_INPUT" to AutoMixFailure.UNSUPPORTED_FORMAT,"INVALID_NATIVE_OUTPUT" to AutoMixFailure.NATIVE_INVALID_OUTPUT))
            assertEquals(category,failed(engine {packet(it,status=status)}.analyze(w(),AnalysisDetail.SCAN,AnalysisControl())).category)
    }
    @Test fun cancellationBeforeAndAfterBridgePreventsStaleSuccess() {
        var calls=0;val token=AnalysisCancellationToken()
        val e=BitChordNativeAnalysisEngine(NativeDspBridge {window,_->calls++;token.cancel();NativeAnalysisFixtures.packet(window)})
        val control=AnalysisControl(cancellation=token)
        assertEquals(AutoMixFailure.ANALYSIS_CANCELED,failed(e.analyze(w(),AnalysisDetail.SCAN,control)).category)
        assertEquals(AutoMixFailure.ANALYSIS_CANCELED,failed(e.analyze(w(),AnalysisDetail.SCAN,control)).category)
        assertEquals(1,calls)
        val race=AnalysisCancellationToken()
        val failing=BitChordNativeAnalysisEngine(NativeDspBridge {_,_->race.cancel();throw NativeBridgeFailure("NATIVE_LIBRARY_UNAVAILABLE")})
        assertEquals(AutoMixFailure.ANALYSIS_CANCELED,failed(failing.analyze(w(),AnalysisDetail.SCAN,AnalysisControl(cancellation=race))).category)
    }
    @Test fun deadlinesAndThreadInterruptsRemainPriorityFailures() {
        assertEquals(AutoMixFailure.ANALYSIS_TIMEOUT,failed(engine().analyze(w(),AnalysisDetail.SCAN,AnalysisControl(System.nanoTime()-1))).category)
        try {
            Thread.currentThread().interrupt()
            assertEquals(AutoMixFailure.ANALYSIS_CANCELED,failed(engine().analyze(w(),AnalysisDetail.SCAN,AnalysisControl())).category)
        } finally {Thread.interrupted()}
    }
    @Test fun lazyLibraryFailureCannotAffectLegacyInitialization() {
        var loads=0
        val e=BitChordNativeAnalysisEngine(JniBitChordBridge({loads++;throw UnsatisfiedLinkError("sensitive path") }))
        assertEquals(0,loads)
        repeat(2) {assertEquals("NATIVE_LIBRARY_UNAVAILABLE",failed(e.analyze(w(),AnalysisDetail.SCAN,AnalysisControl())).code)}
        assertEquals(1,loads)
        val legacy=w().copy(preprocessingVersion=PcmPreprocessing.LEGACY)
        assertTrue(LegacyKotlinAnalysisEngine().analyze(legacy,AnalysisDetail.SCAN,AnalysisControl()) is AnalysisResult.Completed)
    }
    @Test fun productionDefaultsRemainLegacy() {
        assertEquals(AnalysisPreprocessing.LEGACY,AnalysisPreprocessing.PRODUCTION)
        assertEquals(PcmPreprocessing.LEGACY,LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE.preprocessingVersion)
        assertEquals("tinium-legacy-kotlin",LegacyKotlinAnalysisEngine().backend.id)
    }
    @Test fun decoderAndProfileMismatchFailsBeforeNative() {
        val base=w();val pipeline=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(base.copy(preprocessingVersion=PcmPreprocessing.LEGACY))},engine(),PcmPreprocessing.BANDLIMITED)
        assertEquals("WINDOW_IDENTITY_MISMATCH",failed(pipeline.analyze(Unit,DecodeRequest(base.requested,base.identity),AnalysisDetail.SCAN,AnalysisControl())).code)
    }
    @Test fun workerCacheAndInFlightKeysDistinguishBothProfiles() {
        AnalysisWorkers<String>(AutoMixPerformance.EFFICIENT).use { workers ->
            val id=w().identity
            val n=BitChordNativeAnalysisEngine.PROFILE.cacheKey(id,0,8000,AnalysisDetail.SCAN)
            val l=LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE.cacheKey(id,0,8000,AnalysisDetail.SCAN)
            val a=workers.submit(n){"native"};val b=workers.submit(l){"legacy"}
            assertEquals("native",a.get(5,TimeUnit.SECONDS));assertEquals("legacy",b.get(5,TimeUnit.SECONDS))
            assertEquals("native",workers.submit(n){"wrong"}.get())
            assertEquals("legacy",workers.submit(l){"wrong"}.get())
        }
    }
}
