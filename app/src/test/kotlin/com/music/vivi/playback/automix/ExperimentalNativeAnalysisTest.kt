// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class ExperimentalNativeAnalysisTest {
    @Test fun identicalExperimentalRequestsShareInflightWorkWithoutCachingFallbacks() {
        val w=NativeAnalysisFixtures.window();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var decodes=0
        val decoder=AnalysisWindowDecoder<Unit>{_,_,_->decodes++;entered.countDown();check(release.await(5,TimeUnit.SECONDS));DecodeResult.Decoded(w)}
        val engine=BitChordNativeAnalysisEngine(NativeDspBridge {window,_->NativeAnalysisFixtures.packet(window)})
        val experiment=ExperimentalNativeAnalysis(WindowAnalysisPipeline(decoder,engine,PcmPreprocessing.BANDLIMITED),
            WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->throw AssertionError("No fallback")},LegacyKotlinAnalysisEngine(),PcmPreprocessing.LEGACY))
        AnalysisWorkers<ExperimentalAnalysisAttempt>(AutoMixPerformance.EFFICIENT).use { workers ->
            try {
                val request=DecodeRequest(w.requested,w.identity)
                val first=experiment.submit(workers,Unit,request,AnalysisDetail.SCAN,AnalysisControl())
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                val second=experiment.submit(workers,Unit,request,AnalysisDetail.SCAN,AnalysisControl())
                assertSame(first,second);release.countDown()
                val attempt=first.get(5,TimeUnit.SECONDS)
                assertEquals(1,decodes)
                assertFalse(workers.hasCached(attempt.completedCacheKey()!!))
            } finally {release.countDown()}
        }
    }
    private fun run(code:String,category:AutoMixFailure=AutoMixFailure.NATIVE_FAILED,
                    cancelBeforeFallback:Boolean=false):Pair<ExperimentalAnalysisAttempt,List<String>> {
        val calls=mutableListOf<String>();val w=NativeAnalysisFixtures.window();val token=AnalysisCancellationToken()
        val decoder: (String)->AnalysisWindowDecoder<Unit> = { preprocessing -> AnalysisWindowDecoder {_,_,_->
            calls+=preprocessing;DecodeResult.Decoded(w.copy(preprocessingVersion=preprocessing))
        } }
        val engine=object:AnalysisEngine {
            override val backend=BitChordNativeAnalysisEngine.BACKEND
            override fun analyze(window:DecodedWindow,detail:AnalysisDetail,control:AnalysisControl):AnalysisResult {
                if(cancelBeforeFallback)token.cancel()
                return AnalysisResult.Failed(backend,AnalysisFailure(AnalysisFailureStage.ANALYSIS,category,code))
            }
        }
        val experiment=ExperimentalNativeAnalysis(WindowAnalysisPipeline(decoder(PcmPreprocessing.BANDLIMITED),engine,PcmPreprocessing.BANDLIMITED),
            WindowAnalysisPipeline(decoder(PcmPreprocessing.LEGACY),LegacyKotlinAnalysisEngine(),PcmPreprocessing.LEGACY))
        return experiment.analyze(Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl(cancellation=token)) to calls
    }
    @Test fun technicalFailureRedecodesOnceAndReturnsActualLegacyIdentity() {
        for(code in listOf("NATIVE_LIBRARY_UNAVAILABLE","NATIVE_INTERNAL_ERROR","NATIVE_INVALID_OUTPUT")) {
            val (attempt,calls)=run(code)
            assertEquals(listOf(PcmPreprocessing.BANDLIMITED,PcmPreprocessing.LEGACY),calls)
            assertEquals(LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE,attempt.effectiveProfile)
            assertNotNull(attempt.nativeFailure)
            val r=attempt.result as AnalysisResult.Completed
            assertEquals(LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE.cacheKey(r.metadata.source,0,8000,AnalysisDetail.SCAN),attempt.completedCacheKey())
            assertNotEquals(BitChordNativeAnalysisEngine.PROFILE.cacheKey(r.metadata.source,0,8000,AnalysisDetail.SCAN),attempt.completedCacheKey())
        }
    }
    @Test fun cancellationTimeoutMemoryAndInputFailuresNeverRestartWork() {
        for ((code,category) in listOf("NATIVE_CANCELED" to AutoMixFailure.ANALYSIS_CANCELED,
            "NATIVE_DEADLINE" to AutoMixFailure.ANALYSIS_TIMEOUT,"NATIVE_ALLOCATION_FAILURE" to AutoMixFailure.NATIVE_FAILED,
            "PARTIAL_SOURCE_COVERAGE" to AutoMixFailure.INSUFFICIENT_AUDIO_DATA,"NATIVE_INVALID_INPUT" to AutoMixFailure.UNSUPPORTED_FORMAT)) {
            val (attempt,calls)=run(code,category)
            assertEquals(1,calls.size);assertTrue(attempt.result is AnalysisResult.Failed);assertNull(attempt.completedCacheKey())
        }
    }
    @Test fun cancellationDuringNativeFailureSuppressesFallback() {
        val (attempt,calls)=run("NATIVE_INTERNAL_ERROR",cancelBeforeFallback=true)
        assertEquals(1,calls.size);assertEquals(AutoMixFailure.ANALYSIS_CANCELED,(attempt.result as AnalysisResult.Failed).failure.category)
    }
    @Test fun validLowConfidenceIsNotTechnicalFailure() {
        val w=NativeAnalysisFixtures.window();var legacyCalls=0
        val native=BitChordNativeAnalysisEngine(NativeDspBridge {window,_->NativeAnalysisFixtures.packet(window).let {
            NativeDspPacket(it.schema,it.status,it.scalars.copyOf().also {v->v[9]=.001},it.beatsSeconds,it.heuristicDownbeatsSeconds)
        } })
        val experiment=ExperimentalNativeAnalysis(WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(w)},native,PcmPreprocessing.BANDLIMITED),
            WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->legacyCalls++;throw AssertionError("Must not fallback")},LegacyKotlinAnalysisEngine(),PcmPreprocessing.LEGACY))
        AnalysisWorkers<ExperimentalAnalysisAttempt>(AutoMixPerformance.EFFICIENT).use { workers ->
            val attempt=experiment.submit(workers,Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl()).get(5,TimeUnit.SECONDS)
            assertEquals(BitChordNativeAnalysisEngine.PROFILE,attempt.effectiveProfile);assertEquals(0,legacyCalls)
            assertEquals(.001,(attempt.result as AnalysisResult.Completed).evidence.gridConfidence.value!!,0.0)
        }
    }
}
