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
            // No EOS does not itself prove full coverage: seeking can leave a leading hole
            // while capture still reaches the endpoint. Keep the strict check while actual
            // device PTS evidence is collected; do not silently admit partial planner input.
            val context="Seek coverage validation; sampleInterval="+w.sampleInterval+
                "; sampleOriginUs="+w.sampleTimelineStartUs+"; "+w.decoderDiagnostic!!.reportFields()
            assertEquals(AudioIntervalUs(3_000_000,9_000_000),w.requested)
            assertTrue(w.decodeInterval.endUs<9_000_000)
            assertEquals(CoverageStatus.PARTIAL,w.coverageStatus)
            assertTrue(w.actualStartUs!!>=3_000_000);assertTrue(w.actualEndUs!!<=w.decodeInterval.endUs)
            assertTrue(w.sampleInterval!!.endUs<=w.actualEndUs!!)
            // Filling the interval may finish before input EOF or codec EOS. Do not require
            // an event the decoder need not observe, or mistake boundary EOS for natural EOF.
            if (w.endOfStreamReached == true) assertNotEquals(context,DecoderInputEnd.UNKNOWN,w.decoderDiagnostic.inputEnd)
            else assertEquals(context,CoverageStatus.FULL,w.coverage.status(w.decodeInterval))
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
