package com.music.vivi.playback.automix

import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

/** Real extractor/software-codec tests. Local generated assets isolate networking from decoding. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidWindowDecoderInstrumentedTest {
    private fun withSource(asset: String, block: (AnalysisSource) -> Unit) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val file=File.createTempFile("automix-",asset.substringAfterLast('.'),context.cacheDir)
        try {
            instrumentation.context.assets.open("automix/"+asset).use { input -> file.outputStream().use { input.copyTo(it) } }
            block(AnalysisSource(Uri.fromFile(file),"fixture",file.length(),"generated-v1",DefaultDataSource.Factory(context),true))
        } finally { assertTrue(file.delete()) }
    }
    private fun decode(source: AnalysisSource,mode:AnalysisPreprocessing,start:Long=0,end:Long=4_000_000):DecodedWindow {
        val events=mutableListOf<DecoderDiagnostic>()
        val result=AndroidWindowDecoder(mode).decode(source,DecodeRequest(AudioIntervalUs(start,end),
            AnalysisSourceIdentity(source.cacheKey,source.identity)),AnalysisControl(System.nanoTime()+25_000_000_000,
            decoderDiagnostics=DecoderDiagnosticSink{events+=it}))
        assertEquals(1,events.size)
        assertEquals(mode.version,events.single().preprocessing.version)
        assertEquals(DecoderRangeStatus.NOT_REQUIRED,events.single().rangeStatus)
        assertTrue(events.single().sourceBytesRead!!>0)
        assertTrue("Decoder failure: "+(result as? DecodeResult.Failed)?.failure+"; "+events.single().reportFields(),result is DecodeResult.Decoded)
        return (result as DecodeResult.Decoded).window
    }
    @Test fun aacAndFlacActualCodecFormatsAndPreprocessingProvenance() {
        for(asset in listOf("tone-48000-stereo.m4a","tone-44100-mono.flac"))withSource(asset){source ->
            for(mode in AnalysisPreprocessing.entries) {
                val w=decode(source,mode)
                assertNull(w.invalidReason());assertEquals(PcmFormat(22050,1,PcmEncoding.FLOAT32),w.format)
                assertTrue(w.sourceFormats.any { it.basis==SourceFormatBasis.DECODER_OUTPUT })
                assertTrue(w.samples.any { kotlin.math.abs(it)>0.01 });assertTrue(w.samples.all { it.isFinite() })
                assertEquals(mode.version,w.preprocessingVersion)
                if(mode==AnalysisPreprocessing.BANDLIMITED){assertEquals(0L,w.repeatedOutputFrames);assertEquals(0L,w.preparation!!.synthesizedTimelineFrames)}
            }
        }
    }
    @Test fun seekAndEndpointClampKeepPartialCoverageHonest() {
        withSource("tone-48000-stereo.m4a"){source ->
            val w=decode(source,AnalysisPreprocessing.BANDLIMITED,3_000_000,9_000_000)
            // Include only controlled decoder fields, not a source URI or arbitrary logcat.
            // Codec EOS, reaching the endpoint, and complete source coverage are independent.
            // The Samsung run starts at 3.008s after a 3s seek. Preserve that missing head;
            // test the actual coverage contract instead of requiring invented leading PCM.
            val diagnostic=requireNotNull(w.decoderDiagnostic)
            val context="Seek coverage validation; sampleInterval="+w.sampleInterval+
                "; sampleOriginUs="+w.sampleTimelineStartUs+"; "+diagnostic.reportFields()
            assertNull(context,w.invalidReason())
            assertEquals(context,AudioIntervalUs(3_000_000,9_000_000),w.requested)
            assertEquals(context,AudioIntervalUs(3_000_000,7_000_000),w.decodeInterval)
            assertEquals(context,CoverageStatus.PARTIAL,w.coverageStatus)
            val accepted=requireNotNull(w.coverage.intervals).single()
            assertTrue(context,accepted.startUs>=w.decodeInterval.startUs)
            // Keep the fixture's endpoint requirement strict. A late start is not permission
            // to silently truncate the tail, invent internal continuity, or add padded audio.
            assertEquals(context,w.decodeInterval.endUs,accepted.endUs)
            assertEquals(context,0,w.preparation!!.gapCount)
            assertEquals(context,0L,w.preparation.synthesizedTimelineFrames)
            assertEquals(context,0L,w.repeatedOutputFrames)
            assertEquals(context,w.coverage.acceptedDecodedFrames,w.preparation.acceptedSourceFrames)
            assertTrue(context,w.sampleTimelineStartUs>=accepted.startUs)
            assertTrue(context,w.sampleTimelineStartUs-accepted.startUs<1000)
            val materialized=requireNotNull(w.sampleInterval)
            assertTrue(context,materialized.endUs<=accepted.endUs)
            assertTrue(context,accepted.endUs-materialized.endUs<1_000_000L/22050+1)
            val status=w.coverage.status(w.decodeInterval)
            assertEquals(context,if(accepted.startUs==w.decodeInterval.startUs) CoverageStatus.FULL else CoverageStatus.PARTIAL,status)
            assertEquals(context,w.endOfStreamReached,diagnostic.codecEos)
            if(w.endOfStreamReached==true) assertNotEquals(context,DecoderInputEnd.UNKNOWN,diagnostic.inputEnd)
            if(status==CoverageStatus.FULL) {
                assertEquals(context,DecoderFailureSubtype.NONE,diagnostic.subtype)
                assertNull(context,diagnostic.category)
            } else {
                assertEquals(context,DecoderFailureSubtype.PARTIAL_SOURCE_COVERAGE,diagnostic.subtype)
                assertEquals(context,AutoMixFailure.INSUFFICIENT_AUDIO_DATA,diagnostic.category)
                // Exercise the real pipeline with the ACTUAL codec result. No incomplete
                // audio may reach musical analysis or the planner, even without codec EOS.
                val engine=object:AnalysisEngine {
                    override val backend=LegacyKotlinAnalysisEngine.BACKEND
                    override fun analyze(window:DecodedWindow,detail:AnalysisDetail,control:AnalysisControl):AnalysisResult =
                        throw AssertionError("Partial seek window reached musical analysis; "+context)
                }
                val pipeline=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(w)},engine,PcmPreprocessing.BANDLIMITED)
                val result=pipeline.analyze(Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl())
                assertTrue(context,result is AnalysisResult.Failed)
                val failure=(result as AnalysisResult.Failed).failure
                assertEquals(context,AnalysisFailureStage.INPUT_VALIDATION,failure.stage)
                assertEquals(context,AutoMixFailure.INSUFFICIENT_AUDIO_DATA,failure.category)
                assertEquals(context,"PARTIAL_SOURCE_COVERAGE",failure.code)
            }
        }
    }
    @Test fun cancellationDuringActiveDecodeReleasesResourcesBeforeNextRequest() {
        withSource("tone-44100-mono.flac"){source ->
            val token=AnalysisCancellationToken()
            val events=mutableListOf<DecoderDiagnostic>()
            val control=AnalysisControl(System.nanoTime()+25_000_000_000,
                cancellation=token,
                decoderDiagnostics=DecoderDiagnosticSink{events+=it})
            val result=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED,onDecodedOutput={token.cancel()}).decode(source,
                DecodeRequest(AudioIntervalUs(0,4_000_000),AnalysisSourceIdentity(source.cacheKey,source.identity)),control)
            assertTrue(result is DecodeResult.Failed)
            assertEquals(DecoderFailureSubtype.CANCELED,events.single().subtype)
            assertEquals(DecoderStage.PCM_CONVERSION,events.single().stage)
            assertTrue(events.single().coverage!!.acceptedDecodedFrames!!>0)
            assertNotNull(decode(source,AnalysisPreprocessing.BANDLIMITED))
        }
    }
    @Test fun canceledAndExpiredRequestsDoNotLeakDecoderResources() {
        withSource("tone-44100-mono.flac"){source ->
            repeat(5){
                val request=DecodeRequest(AudioIntervalUs(0,4_000_000),AnalysisSourceIdentity(source.cacheKey,source.identity))
                val decoder=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED)
                val expired=decoder.decode(source,request,AnalysisControl(0)) as DecodeResult.Failed
                assertEquals(DecoderFailureSubtype.DEADLINE,expired.diagnostic!!.subtype)
                val canceled=decoder.decode(source,request,AnalysisControl(cancellation=AnalysisCancellation{throw CancellationException()})) as DecodeResult.Failed
                assertEquals(DecoderFailureSubtype.CANCELED,canceled.diagnostic!!.subtype)
                assertNotNull(decode(source,AnalysisPreprocessing.BANDLIMITED))
            }
        }
    }
}
