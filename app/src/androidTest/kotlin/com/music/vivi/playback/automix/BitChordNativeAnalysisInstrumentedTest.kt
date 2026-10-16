// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import java.io.File

/** Fourteen real Android tests. No mocks replace JNI or the DSP in NativeJniChecks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BitChordNativeAnalysisInstrumentedTest {
    private val loader: () -> Unit = BitChordNativeBindings::loadPackagedLibrary
    private val bridge=JniBitChordBridge(loader)
    @get:Rule val codecResults = ErrorCollector()
    @Before fun requireActualLibrary()=NativeJniChecks.loading(bridge)
    @Test fun libraryLoadsOnDeviceAbi()=NativeJniChecks.loading(bridge)
    @Test fun sevenKnownTemposAndOneToOneBeatAccuracy()=NativeJniChecks.tempos(bridge)
    @Test fun nonzeroWindowOriginIsAppliedExactlyOnce()=NativeJniChecks.timestamps(bridge)
    @Test fun silenceDoesNotBecomeTrustedBeatEvidence()=NativeJniChecks.silence(bridge)
    @Test fun rawJniRejectsMalformedPcmAndMetadata()=NativeJniChecks.rawInputs()
    @Test fun cancellationInterruptsActualDspAndRecovers()=NativeJniChecks.cancelDuringDsp(loader)
    @Test fun deadlineInterruptsActualDsp()=NativeJniChecks.deadlineDuringDsp()
    @Test fun fourConcurrentNativeWorkersRemainIsolated()=NativeJniChecks.concurrent(bridge)
    @Test fun cancelingOneJobDoesNotCancelAnother()=NativeJniChecks.cancellationIsolation(loader)
    @Test fun repeatedCallsRemainDeterministic()=NativeJniChecks.repeated(bridge)

    private fun withSource(asset:String, block:(AnalysisSource)->Unit) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val file=File.createTempFile("native-dsp-","."+asset.substringAfterLast('.'),context.cacheDir)
        try {
            instrumentation.context.assets.open("automix/$asset").use { input->file.outputStream().use {input.copyTo(it)} }
            block(AnalysisSource(Uri.fromFile(file),"native-fixture",file.length(),"generated-rhythm-v1",DefaultDataSource.Factory(context),true))
        } finally {assertTrue(file.delete())}
    }
    @Test fun correctedAacAndFlacDecodeThroughNativePipeline() {
        // Collect both formats' real failures instead of aborting at AAC and leaving FLAC
        // untested. The collector still fails this test if ANY original assertion fails.
        for(asset in listOf("rhythm-128-48000.m4a","rhythm-128-44100.flac"))codecResults.checkSucceeds {
            withSource(asset){ source->
                val request=DecodeRequest(AudioIntervalUs(0,24_000_000),AnalysisSourceIdentity(source.cacheKey,source.identity))
                val events=mutableListOf<DecoderDiagnostic>()
                var decodedWindow:DecodedWindow?=null
                val decoder=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED)
                val observedDecoder=AnalysisWindowDecoder<AnalysisSource>{ input,wanted,control->
                    decoder.decode(input,wanted,control).also { decoded->
                        if(decoded is DecodeResult.Decoded) decodedWindow=decoded.window
                    }
                }
                val pipeline=WindowAnalysisPipeline(observedDecoder,
                    BitChordNativeAnalysisEngine(bridge),PcmPreprocessing.BANDLIMITED)
                val actual=pipeline.analyze(source,request,AnalysisDetail.DETAIL,
                    AnalysisControl(System.nanoTime()+30_000_000_000,
                        decoderDiagnostics=DecoderDiagnosticSink{events+=it}))
                // Generated asset names and controlled PCM fields only: no cache-file URI,
                // arbitrary exception text, raw PCM, song metadata or source credentials.
                val context="NATIVE_ANDROID_CODEC asset=$asset sampleInterval=${decodedWindow?.sampleInterval}"+
                    " sampleOriginUs=${decodedWindow?.sampleTimelineStartUs}"+
                    " outcome="+(if(actual is AnalysisResult.Failed) "${actual.failure.stage}/${actual.failure.category}/${actual.failure.code}" else "COMPLETED")+
                    " diagnostics="+events.joinToString("; "){it.reportFields()}
                println(context)
                assertEquals(context,1,events.size)
                assertTrue(context,actual is AnalysisResult.Completed)
                val result=NativeJniChecks.completed(actual)
                val window=requireNotNull(decodedWindow){context}
                assertEquals(context,BitChordNativeAnalysisEngine.BACKEND,result.backend)
                assertEquals(context,PcmPreprocessing.BANDLIMITED,result.metadata.preprocessingVersion)
                assertEquals(context,CoverageStatus.FULL,window.coverage.status(window.decodeInterval))
                assertEquals(context,CoverageStatus.FULL,result.metadata.coverage.status(result.metadata.sampleInterval!!))
                assertEquals(context,0L,result.metadata.preparation!!.synthesizedTimelineFrames)
                assertNotNull(context,result.metadata.decoderDiagnostic)
                assertEquals(context,128.0,result.evidence.tempoBpm.value!!,1.0)
                assertTrue(context,result.evidence.beatsUs.value!!.isNotEmpty())
                println("NATIVE_ANDROID_CODEC backend=${result.backend.id} preprocessing=${result.metadata.preprocessingVersion} native=${result.metadata.nativeDiagnostic}")
            }
        }
    }
    @Test fun partialAndUnknownSourceCoverageAreRejected() {
        val w=NativeAnalysisFixtures.window()
        for(coverage in listOf(AudioCoverage(null,null),AudioCoverage(listOf(AudioIntervalUs(8000,w.decodeInterval.endUs)),w.samples.size.toLong()))) {
            val r=BitChordNativeAnalysisEngine(bridge).analyze(w.copy(coverage=coverage),AnalysisDetail.SCAN,AnalysisControl())
            assertEquals(AutoMixFailure.INSUFFICIENT_AUDIO_DATA,(r as AnalysisResult.Failed).failure.category)
        }
    }
    @Test fun missingLibraryUsesExplicitLegacyRedecode() {
        withSource("rhythm-128-44100.flac"){ source->
            val native=WindowAnalysisPipeline(AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED),
                BitChordNativeAnalysisEngine(JniBitChordBridge({throw UnsatisfiedLinkError("simulated missing binary")})),PcmPreprocessing.BANDLIMITED)
            val legacy=WindowAnalysisPipeline(AndroidWindowDecoder(AnalysisPreprocessing.LEGACY),LegacyKotlinAnalysisEngine(),PcmPreprocessing.LEGACY)
            val request=DecodeRequest(AudioIntervalUs(0,8_000_000),AnalysisSourceIdentity(source.cacheKey,source.identity))
            val attempt=ExperimentalNativeAnalysis(native,legacy).analyze(source,request,AnalysisDetail.SCAN,AnalysisControl(System.nanoTime()+30_000_000_000))
            assertEquals(listOf(BitChordNativeAnalysisEngine.PROFILE,LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE),attempt.attemptedProfiles)
            assertEquals("NATIVE_LIBRARY_UNAVAILABLE",attempt.nativeFailure!!.code)
            assertEquals(LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE,attempt.effectiveProfile)
            assertNotNull(LegacyPlanningFeatures.require(attempt.result))
        }
    }
    @Test fun productionSelectionAndLegacyInitializationRemainUnchanged() {
        assertEquals(AnalysisPreprocessing.LEGACY,AndroidWindowDecoder().preprocessing)
        assertEquals(LegacyKotlinAnalysisEngine.BACKEND,LegacyKotlinAnalysisEngine().backend)
        val result=LegacyKotlinAnalysisEngine().analyze(NativeAnalysisFixtures.window().copy(preprocessingVersion=PcmPreprocessing.LEGACY),AnalysisDetail.SCAN,AnalysisControl())
        assertNotNull(LegacyPlanningFeatures.require(result))
    }
}
