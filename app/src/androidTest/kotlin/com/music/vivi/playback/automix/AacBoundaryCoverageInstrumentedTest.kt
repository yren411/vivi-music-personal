package com.music.vivi.playback.automix

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Real encoded AAC, real platform extraction/codec, JNI and actual MusicService.
 * No commercial recordings, internet or platform timestamp mocks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AacBoundaryCoverageInstrumentedTest {
    @get:Rule val timeout:Timeout=Timeout.seconds(120)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext.also {check(it.packageName.endsWith(".decodercheck"))}
    private val positive="boundary-empty-edit6042-44100-stereo.m4a"
    private val long="boundary-rounded-end-44100-stereo.m4a"
    private val mono="boundary-exact-48000-mono.m4a"
    private val tail="boundary-padding14-44100-stereo.m4a"
    private fun source(asset:String,body:(AnalysisSource,File)->Unit) {
        val file=File.createTempFile("aac333-",".m4a",context.cacheDir)
        try {
            instrumentation.context.assets.open("automix/$asset").use {input->file.outputStream().use {input.copyTo(it)}}
            val id="generated333-"+UUID.randomUUID()
            body(AnalysisSource(Uri.fromFile(file),id,file.length(),id,DefaultDataSource.Factory(context),true),file)
        } finally {assertTrue(file.delete())}
    }
    private fun control()=AnalysisControl(System.nanoTime()+60_000_000_000)
    private fun request(s:AnalysisSource,start:Long,end:Long)=DecodeRequest(AudioIntervalUs(start,end),AnalysisSourceIdentity(s.cacheKey,s.identity))
    private fun decode(s:AnalysisSource,start:Long=0,end:Long=32_000_000):DecodedWindow {
        val result=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED).decode(s,request(s,start,end),control())
        val diagnostic=when(result){is DecodeResult.Decoded->result.window.decoderDiagnostic;is DecodeResult.Failed->result.diagnostic}
        val text="AAC_BOUNDARY_ANDROID "+diagnostic?.reportFields()
        File(context.filesDir,"phase333-aac-boundary-last.txt").writeText(text);println(text)
        assertTrue(text,result is DecodeResult.Decoded)
        return (result as DecodeResult.Decoded).window.also {
            assertNull(text,it.invalidReason());assertEquals(PcmPreprocessing.BANDLIMITED,it.preprocessingVersion)
            assertEquals(text,0L,it.repeatedOutputFrames);assertEquals(text,0L,it.preparation!!.synthesizedTimelineFrames)
        }
    }
    @Test fun genuineEmptyEditRetains137msLeadingGapAndRejectsNativeCoverage()=source(positive) {s,_ ->
        val w=decode(s);val d=requireNotNull(w.decoderDiagnostic);val p=requireNotNull(d.codecProgress)
        assertEquals(d.reportFields(),137006L,p.firstInputPtsUs)
        assertEquals(d.reportFields(),137006L,d.firstCodecPtsUs)
        assertEquals(d.reportFields(),0,p.encoderDelayFrames)
        assertEquals(d.reportFields(),137006L,w.actualStartUs)
        assertEquals(CoverageStatus.PARTIAL,w.coverageStatus)
        assertEquals(137006L,d.aacBoundary!!.missingLeadingUs)
        assertEquals(0,w.preparation!!.gapCount)
        assertTrue(BitChordNativeAnalysisEngine().analyze(w,AnalysisDetail.DETAIL,control()) is AnalysisResult.Failed)
    }
    @Test fun initialEditListPrimingAndFourteenFrameTailAreNotTrimmedTwice()=source(tail) {s,_ ->
        val w=decode(s);val p=w.decoderDiagnostic!!.codecProgress!!
        assertTrue(p.firstInputPtsUs!!<0);assertEquals(1024,p.encoderDelayFrames)
        assertEquals(14,p.encoderPaddingFrames);assertEquals(0,p.codecPaddingFrames)
        assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(0L,w.actualStartUs)
        assertEquals(705600,w.frameCount);assertEquals(1_411_200L,w.coverage.acceptedDecodedFrames)
    }
    @Test fun genuinePrerollRecoversFullNonzero32SecondAacWindowAndRunsJni()=source(tail) {s,_ ->
        val w=decode(s,3_063_000,35_063_000);val d=requireNotNull(w.decoderDiagnostic)
        assertEquals(d.reportFields(),CoverageStatus.FULL,w.coverageStatus)
        assertEquals(AudioIntervalUs(3_063_000,35_063_000),w.sampleInterval)
        assertEquals(705600,w.frameCount)
        assertTrue(d.reportFields(),d.codecProgress!!.seekTargetUs!!<3_063_000)
        assertTrue(d.reportFields(),d.aacBoundary!!.prerollInputPackets>=2)
        assertTrue(d.reportFields(),d.aacBoundary.preRequestDecodedFrames!!>0)
        val r=BitChordNativeAnalysisEngine().analyze(w,AnalysisDetail.DETAIL,control())
        assertTrue("AAC_NATIVE $r ${d.reportFields()}",r is AnalysisResult.Completed)
    }
    @Test fun seekPcmMatchesIndependentForwardCodecAtTheExactReportedStart()=source(long) {s,file ->
        val w=decode(s,123_063_000,131_063_000)
        val ref=AacBoundaryCodecReference.decode(file,request(s,123_063_000,131_063_000),control())
        assertNull("AAC_FORWARD_REFERENCE $ref",ref.failure)
        val expected=requireNotNull(ref.window)
        assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(expected.coverage,w.coverage)
        assertArrayEquals("PNS-disabled generated AAC; real codec forward/seek sample order",expected.samples,w.samples,0f)
        assertEquals(expected.sampleInterval,w.sampleInterval)
    }
    @Test fun roundedNaturalEosKeepsMissingFinalSamplesPartialAfterRecoveringTheHead()=source(long) {s,_ ->
        val w=decode(s,123_063_000,155_063_000);val d=requireNotNull(w.decoderDiagnostic)
        assertEquals(d.reportFields(),155_063_000L,w.duration.durationUs)
        assertEquals(d.reportFields(),123_063_000L,w.actualStartUs)
        assertEquals(d.reportFields(),155_062_856L,w.actualEndUs)
        assertEquals(d.reportFields(),144L,d.aacBoundary!!.missingTrailingUs)
        assertEquals(d.reportFields(),DecoderInputEnd.NATURAL_EOS,d.inputEnd)
        assertEquals(d.reportFields(),true,d.codecEos)
        assertEquals(CoverageStatus.PARTIAL,w.coverageStatus)
        assertTrue(BitChordNativeAnalysisEngine().analyze(w,AnalysisDetail.DETAIL,control()) is AnalysisResult.Failed)
    }
    @Test fun actuallyOmittingACompressedPacketStillFailsStrictSourceIntegrity()=source(tail) {s,file ->
        val ref=AacBoundaryCodecReference.decode(file,request(s,0,8_000_000),control(),dropPacket=true)
        assertEquals(DecoderFailureSubtype.SOURCE_GAP,ref.failure)
        assertTrue(ref.acceptedFrames>0)
        assertTrue(AndroidWindowDecoder().decode(s,request(s,0,8_000_000),control()) is DecodeResult.Decoded)
    }
    @Test fun fullyPartiallyAndUncachedEncodedSourcesHaveIdenticalSeekPcm()=source(tail) {s,file ->
        val expected=decode(s,3_063_000,11_063_000)
        for(prefix in listOf(0L,65536L,file.length())) {
            val dir=File(context.cacheDir,"aac333-cache-"+UUID.randomUUID()).apply {mkdirs()}
            val db=StandaloneDatabaseProvider(context);val cache=SimpleCache(dir,NoOpCacheEvictor(),db)
            val reads=AtomicLong();val upstream=DataSource.Factory {FixtureRanges(file,reads)}
            val id="aac333-range-"+UUID.randomUUID();val uri=Uri.parse("https://generated.invalid/aac")
            try {
                if(prefix>0) {
                    val data=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
                    try {data.open(DataSpec.Builder().setUri(uri).setKey(id).setLength(prefix).build())
                        val bytes=ByteArray(8192);while(data.read(bytes,0,bytes.size)!=C.RESULT_END_OF_INPUT){} }
                    finally {data.close()}
                }
                val complete=cache.isCached(id,0,file.length());assertEquals(prefix==file.length(),complete);reads.set(0)
                val factory=CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(upstream)
                val w=decode(AnalysisSource(uri,id,file.length(),id,factory,complete),3_063_000,11_063_000)
                assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(expected.coverage,w.coverage)
                assertArrayEquals(expected.samples,w.samples,0f)
                if(complete)assertEquals(0L,reads.get()) else assertTrue(reads.get()>0)
            } finally {cache.release();db.close();assertTrue(dir.deleteRecursively())}
        }
    }
    @Test fun realMono48kAndStereo44kSeekUseTheirOwnDeclaredRates() {
        for(asset in listOf(mono,tail))source(asset) {s,_ ->
            val w=decode(s,4_000_000,12_000_000);val d=requireNotNull(w.decoderDiagnostic)
            assertEquals(d.reportFields(),CoverageStatus.FULL,w.coverageStatus)
            assertEquals(176400,w.frameCount)
            assertEquals(if(asset==mono)48000 else 44100,d.aacBoundary!!.configuredRateHz)
            assertEquals(if(asset==mono)1 else 2,d.sourceFormats.last().format.channels)
        }
    }
    @Test fun actualServiceAacSeekEvidenceReachesNativePlannerWithoutFalseCoverageFallback() {
        val fixtures=List(3){mono to 36}
        NativeAutoMixServiceInstrumentedTest.Harness("aac333-native-plan",true,false,false,true,fixtures).use {h ->
            h.play()
            h.await("actual AAC service native plan",20_000){h.history().attempts.any {a->a.records.any {it.native?.event==NativeServiceEvent.PLAN}}}
            h.main {
                val records=h.history().attempts.flatMap {it.records}
                assertEquals(2,records.count {it.native?.event==NativeServiceEvent.EVIDENCE})
                assertTrue(records.filter {it.decoder!=null}.all {it.decoder!!.coverage!!.status(it.decoder.effective!!)==CoverageStatus.FULL})
                assertFalse(records.any {it.native?.reason=="PARTIAL_SOURCE_COVERAGE"})
                assertTrue(h.service.player.isPlaying)
            }
        }
    }
    @Test fun genuineLeadingGapFallsBackInActualServiceWithoutChangingSelectedMode() {
        val fixtures=listOf(mono to 36,positive to 36,mono to 36)
        NativeAutoMixServiceInstrumentedTest.Harness("aac333-native-fallback",true,false,false,true,fixtures).use {h ->
            h.play()
            h.await("actual AAC coverage fallback",20_000){h.history().attempts.any {a->a.records.any {it.native?.event==NativeServiceEvent.FALLBACK && it.native.reason=="PARTIAL_SOURCE_COVERAGE"}}}
            h.main {
                val state=h.service.nativeServiceInspection()
                assertTrue(state.nativeSelected);assertTrue(state.legacyEnabled)
                assertEquals(1,state.outputs);assertEquals(0,h.completed());assertTrue(h.service.player.isPlaying)
            }
        }
    }
    private class FixtureRanges(private val file:File,private val reads:AtomicLong):DataSource {
        private val delegate=FileDataSource();private var uri:Uri?=null;private var headers=emptyMap<String,List<String>>()
        override fun addTransferListener(listener:TransferListener)=delegate.addTransferListener(listener)
        override fun open(spec:DataSpec):Long {
            uri=spec.uri
            val length=if(spec.length==C.LENGTH_UNSET.toLong())file.length()-spec.position else spec.length
            headers=mapOf("Content-Range" to listOf("bytes ${spec.position}-${spec.position+length-1}/${file.length()}"))
            return delegate.open(spec.withUri(Uri.fromFile(file)))
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int)=delegate.read(buffer,offset,length).also {if(it>0)reads.addAndGet(it.toLong())}
        override fun getUri()=uri
        override fun getResponseHeaders()=headers
        override fun close()=delegate.close()
    }
}
