package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AnalysisPipelineContractsTest {
    private fun window() = AnalysisPcmFixtures.window(AnalysisPcmFixtures.silence(4))
    private fun pipeline(decoder: AnalysisWindowDecoder<Unit>) =
        WindowAnalysisPipeline(decoder, LegacyKotlinAnalysisEngine(), PcmPreprocessing.IDENTITY)

    @Test fun productionDefaultIsLegacyAndUnchangedFeaturesReachActualPlanner() {
        val w = AnalysisPcmFixtures.window(AnalysisPcmFixtures.groove(120.0, 30))
        val p = pipeline { _, _, _ -> DecodeResult.Decoded(w) }
        val result = p.analyze(Unit, DecodeRequest(w.requested, w.identity), AnalysisDetail.SCAN, AnalysisControl())
        val features = LegacyPlanningFeatures.require(result)
        val old = TrackFeaturesAnalyzer().analyze(w.samples, 22050, 0, 30000, detail = AnalysisDetail.SCAN)
        assertEquals(old, features)
        val oldDecision = AutoMixPlanner().decide(old, old, 30000, 30000)
        assertNotNull(oldDecision.plan)
        assertEquals(oldDecision, AutoMixPlanner().decide(features, features, 30000, 30000))
        assertEquals(LegacyKotlinAnalysisEngine.BACKEND, p.profile.backend)
        assertEquals(LegacyKotlinAnalysisEngine.BACKEND, result.backend)
    }

    @Test fun pipelineChecksWindowIdentityIntervalAndPreprocessorBeforeAnalyzing() {
        val w = window()
        for (bad in listOf(w.copy(identity = AnalysisSourceIdentity("other", "source")),
            w.copy(requested = AudioIntervalUs(0, 5_000_000)), w.copy(preprocessingVersion = "other-v1"))) {
            val result = pipeline { _, _, _ -> DecodeResult.Decoded(bad) }.analyze(Unit,
                DecodeRequest(w.requested, w.identity), AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Failed
            assertEquals(AutoMixFailure.SOURCE_CHANGED, result.failure.category)
            assertEquals(AnalysisFailureStage.INPUT_VALIDATION, result.failure.stage)
        }
    }

    @Test fun decoderFailuresPreserveStructuredSubtypeAndOriginalRetryException() {
        val w = window()
        val original = AnalysisUnavailable("truncated-audio-window")
        val result = pipeline { _, _, _ -> DecodeResult.Failed(AnalysisFailure(
            AnalysisFailureStage.DECODING, AutoMixFailure.INSUFFICIENT_AUDIO_DATA, original.reason, original)) }
            .analyze(Unit, DecodeRequest(w.requested, w.identity), AnalysisDetail.DETAIL, AnalysisControl()) as AnalysisResult.Failed
        assertEquals("truncated-audio-window", result.failure.code)
        assertEquals(AnalysisFailureStage.DECODING, result.failure.stage)
        try { LegacyPlanningFeatures.require(result); fail() } catch (error: AnalysisUnavailable) { assertSame(original, error) }
    }

    @Test fun deadlineReachesDecoderAndExpiredOrCanceledRequestsDoNotDecode() {
        val w = window()
        val calls = AtomicInteger()
        val control = AnalysisControl(Long.MAX_VALUE - 123)
        val p = pipeline { _, _, actual -> assertSame(control, actual); calls.incrementAndGet(); DecodeResult.Decoded(w) }
        p.analyze(Unit, DecodeRequest(w.requested, w.identity), AnalysisDetail.SCAN, control)
        assertEquals(1, calls.get())
        val result = p.analyze(Unit, DecodeRequest(w.requested, w.identity), AnalysisDetail.SCAN,
            AnalysisControl(0)) as AnalysisResult.Failed
        assertEquals(AutoMixFailure.ANALYSIS_TIMEOUT, result.failure.category)
        assertEquals(1, calls.get())
        val token = AnalysisCancellationToken().apply { cancel() }
        val canceled = p.analyze(Unit, DecodeRequest(w.requested, w.identity), AnalysisDetail.SCAN,
            AnalysisControl(cancellation = token)) as AnalysisResult.Failed
        assertEquals(AutoMixFailure.ANALYSIS_CANCELED, canceled.failure.category)
        assertEquals(1, calls.get())
    }

    @Test fun backendVersionPreprocessingIdentityPositionLengthAndDetailIsolateCache() {
        val id = AnalysisSourceIdentity("song", "signed-source?token=private")
        val profile = LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE
        val base = profile.cacheKey(id, 1000, 8000, AnalysisDetail.SCAN)
        val keys = listOf(
            profile.copy(backend = profile.backend.copy(id = "native")).cacheKey(id, 1000, 8000, AnalysisDetail.SCAN),
            profile.copy(backend = profile.backend.copy(version = "6")).cacheKey(id, 1000, 8000, AnalysisDetail.SCAN),
            profile.copy(preprocessingVersion = "sinc-v1").cacheKey(id, 1000, 8000, AnalysisDetail.SCAN),
            profile.cacheKey(id.copy(recordingId = "other"), 1000, 8000, AnalysisDetail.SCAN),
            profile.cacheKey(id.copy(renditionKey = "other"), 1000, 8000, AnalysisDetail.SCAN),
            profile.cacheKey(id, 2000, 8000, AnalysisDetail.SCAN),
            profile.cacheKey(id, 1000, 9000, AnalysisDetail.SCAN),
            profile.cacheKey(id, 1000, 8000, AnalysisDetail.DETAIL),
        )
        assertEquals(9, (keys + base).distinct().size)
        assertEquals(base, profile.cacheKey(id, 1000, 8000, AnalysisDetail.SCAN))
        assertFalse(base.contains("token")); assertFalse(base.contains("song"))
    }

    @Test fun completedResultsAreReusedAfterInvalidationWithoutRepeatingDecode() {
        val w = window()
        val decodes = AtomicInteger()
        val p = pipeline { _, _, _ -> decodes.incrementAndGet(); DecodeResult.Decoded(w) }
        val key = p.profile.cacheKey(w.identity, 0, 4000, AnalysisDetail.SCAN)
        AnalysisWorkers<TrackFeatures>().use { workers ->
            fun submit() = workers.submit(key) { LegacyPlanningFeatures.require(p.analyze(Unit,
                DecodeRequest(w.requested, w.identity), AnalysisDetail.SCAN, AnalysisControl())) }
            val first = submit().get(5, TimeUnit.SECONDS)
            workers.cancelAll()
            assertSame(first, submit().get(5, TimeUnit.SECONDS))
            assertEquals(1, decodes.get())
        }
    }

    @Test fun initialPrerollVersionSeparatesInflightAndCompletedResultsFromV2() {
        // Preserve the historical v2/v3 contract while the selected pipeline advances to v4.
        val profile=BitChordNativeAnalysisEngine.PROFILE.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V3)
        val w=window()
        assertEquals("sinc64-blackman-initial-preroll-mono-22050-v3",profile.preprocessingVersion)
        val oldKey=profile.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V2)
            .cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)
        val newKey=profile.cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)
        assertNotEquals(oldKey,newKey)
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        val oldValue=Any();val newValue=Any();val calls=AtomicInteger()
        AnalysisWorkers<Any>(AutoMixPerformance.BALANCED).use { workers ->
            fun work(value:Any):Any {
                calls.incrementAndGet();entered.countDown()
                check(release.await(5,TimeUnit.SECONDS));return value
            }
            val old=workers.submit(oldKey){work(oldValue)}
            val current=workers.submit(newKey){work(newValue)}
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                assertNotSame(old,current)
                assertSame(current,workers.submit(newKey){error("Duplicate v3 work")})
            } finally {release.countDown()}
            assertSame(oldValue,old.get(5,TimeUnit.SECONDS))
            assertSame(newValue,current.get(5,TimeUnit.SECONDS))
            workers.cancelAll()
            assertSame(oldValue,workers.submit(oldKey){error("V2 cache miss")}.get(5,TimeUnit.SECONDS))
            assertSame(newValue,workers.submit(newKey){error("V3 cache miss")}.get(5,TimeUnit.SECONDS))
            assertEquals(2,calls.get())
        }
    }

    @Test fun nearestFrameClockVersionSeparatesInflightAndCompletedResultsFromV3() {
        val profile=BitChordNativeAnalysisEngine.PROFILE.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V4);val w=window()
        assertEquals("sinc64-blackman-preroll-frameclock-mono-22050-v4",profile.preprocessingVersion)
        val profiles=listOf(PcmPreprocessing.LEGACY,PcmPreprocessing.BANDLIMITED_V2,PcmPreprocessing.BANDLIMITED_V3,
            PcmPreprocessing.BANDLIMITED_V4).map {profile.copy(preprocessingVersion=it)}
        assertEquals(4,profiles.map{it.cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)}.distinct().size)
        val oldKey=profiles[2].cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)
        val newKey=profile.cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)
        val entered=CountDownLatch(2);val release=CountDownLatch(1);val calls=AtomicInteger()
        val oldValue=Any();val newValue=Any()
        AnalysisWorkers<Any>(AutoMixPerformance.BALANCED).use {workers ->
            fun work(v:Any):Any {calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS));return v}
            val old=workers.submit(oldKey){work(oldValue)};val current=workers.submit(newKey){work(newValue)}
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertNotSame(old,current)
                assertSame(current,workers.submit(newKey){error("Duplicate v4 work")})
            }finally{release.countDown()}
            assertSame(oldValue,old.get(5,TimeUnit.SECONDS));assertSame(newValue,current.get(5,TimeUnit.SECONDS))
            workers.cancelAll()
            assertSame(oldValue,workers.submit(oldKey){error("V3 cache miss")}.get(5,TimeUnit.SECONDS))
            assertSame(newValue,workers.submit(newKey){error("V4 cache miss")}.get(5,TimeUnit.SECONDS))
            assertEquals(2,calls.get())
        }
    }
    @Test fun aacTailClockVersionSeparatesV5CacheAndInflightResultsAndRejectsOldPcm() {
        val profile=BitChordNativeAnalysisEngine.PROFILE;val w=window()
        assertEquals("sinc64-blackman-aac-tailclock-mono-22050-v6",profile.preprocessingVersion)
        val versions=listOf(PcmPreprocessing.LEGACY,PcmPreprocessing.BANDLIMITED_V2,PcmPreprocessing.BANDLIMITED_V3,PcmPreprocessing.BANDLIMITED_V4,PcmPreprocessing.BANDLIMITED_V5,PcmPreprocessing.BANDLIMITED)
        assertEquals(6,versions.map{profile.copy(preprocessingVersion=it).cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)}.distinct().size)
        val oldKey=profile.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V5).cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)
        val newKey=profile.cacheKey(w.identity,0,4000,AnalysisDetail.SCAN)
        val entered=CountDownLatch(2);val release=CountDownLatch(1);val calls=AtomicInteger();val oldValue=Any();val newValue=Any()
        AnalysisWorkers<Any>(AutoMixPerformance.BALANCED).use {workers ->
            fun work(v:Any):Any {calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS));return v}
            val old=workers.submit(oldKey){work(oldValue)};val current=workers.submit(newKey){work(newValue)}
            try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertNotSame(old,current);assertSame(current,workers.submit(newKey){error("Duplicate v6 work")})}
            finally{release.countDown()}
            assertSame(oldValue,old.get(5,TimeUnit.SECONDS));assertSame(newValue,current.get(5,TimeUnit.SECONDS));workers.cancelAll()
            assertSame(oldValue,workers.submit(oldKey){error("V5 cache miss")}.get(5,TimeUnit.SECONDS))
            assertSame(newValue,workers.submit(newKey){error("V6 cache miss")}.get(5,TimeUnit.SECONDS));assertEquals(2,calls.get())
        }
        val old=w.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V5)
        val engine=BitChordNativeAnalysisEngine(NativeDspBridge {_,_->error("V5 PCM reached current native DSP")})
        val p=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(old)},engine,PcmPreprocessing.BANDLIMITED)
        assertEquals("WINDOW_IDENTITY_MISMATCH",(p.analyze(Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed).failure.code)
        assertEquals("NATIVE_REQUIRES_CORRECTED_PCM",(engine.analyze(old,AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed).failure.code)
    }
    @Test fun historicalV3PcmCannotEnterTheCurrentPipelineOrNativeBackend() {
        val w=window().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V3)
        val engine=BitChordNativeAnalysisEngine(NativeDspBridge {_,_->error("Historical PCM reached native DSP")})
        val p=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(w)},engine,PcmPreprocessing.BANDLIMITED)
        val result=p.analyze(Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed
        assertEquals("WINDOW_IDENTITY_MISMATCH",result.failure.code)
        val native=engine.analyze(w,AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed
        assertEquals("NATIVE_REQUIRES_CORRECTED_PCM",native.failure.code)
        // Historical corrected windows remain structurally honest; version compatibility is separate.
        val origin=NativeAnalysisFixtures.window(origin=12_345_678).copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V3)
        assertNull(origin.invalidReason())
        assertEquals("NATIVE_REQUIRES_CORRECTED_PCM",(engine.analyze(origin,AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed).failure.code)
    }
    @Test fun historicalV2DecodedPcmCannotBeRelabeledAsCurrentPrerollInput() {
        val w=window().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V2)
        val engine=object:AnalysisEngine {
            override val backend=BitChordNativeAnalysisEngine.BACKEND
            override fun analyze(window:DecodedWindow,detail:AnalysisDetail,control:AnalysisControl):AnalysisResult =
                throw AssertionError("V2 PCM reached the current engine")
        }
        val p=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(w)},
            engine,PcmPreprocessing.BANDLIMITED)
        val r=p.analyze(Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed
        assertEquals(AutoMixFailure.SOURCE_CHANGED,r.failure.category)
        assertEquals("WINDOW_IDENTITY_MISMATCH",r.failure.code)
    }

    @Test fun canceledOldGenerationCannotCacheOverNewWorkEvenIfDecoderIgnoresInterrupt() {
        val w = window()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val key = LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE.cacheKey(w.identity, 0, 4000, AnalysisDetail.SCAN)
        AnalysisWorkers<Int>(AutoMixPerformance.BALANCED).use { workers ->
            val old = workers.submit(key) {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
                done.countDown(); 1
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            workers.cancelAll()
            assertTrue(old.isCancelled)
            assertEquals(2, workers.submit(key) { 2 }.get(5, TimeUnit.SECONDS))
            release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(2, workers.submit(key) { 3 }.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun pipelineHasNoIndependentSchedulerAndRunsWithinEveryExistingMode() {
        val w = window()
        for (mode in AutoMixPerformance.entries) AnalysisWorkers<TrackFeatures>(mode).use { workers ->
            val entered = CountDownLatch(mode.workers); val release = CountDownLatch(1)
            val maximum = AtomicInteger(); val active = AtomicInteger()
            val p = pipeline { _, _, _ ->
                assertTrue(Thread.currentThread().name.startsWith("Tinium-AutoMix-"))
                val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }; entered.countDown()
                try { release.await(); DecodeResult.Decoded(w) } finally { active.decrementAndGet() }
            }
            val jobs = (0 until mode.workers + 2).map { i -> workers.submit("pair-$i", false) {
                LegacyPlanningFeatures.require(p.analyze(Unit, DecodeRequest(w.requested, w.identity),
                    AnalysisDetail.SCAN, AnalysisControl()))
            } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(mode.workers, maximum.get())
                assertTrue(workers.queuedCount <= 8)
            } finally { release.countDown() }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(maximum.get() <= mode.workers)
        }
    }
}
