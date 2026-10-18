package com.music.vivi.playback.automix

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Real generated encoded AAC/Opus and actual MediaExtractor/MediaCodec/JNI. No internet/audio playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RealWorldDecoderInstrumentedTest {
    @get:Rule val timeout:Timeout=Timeout.seconds(120)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val aac="boundary-padding14-44100-stereo.m4a"
    private val opus="rhythm-128-48000-40s.webm"
    private fun source(asset:String=aac,body:(AnalysisSource,File)->Unit) {
        val file=File.createTempFile("decoder331-","."+asset.substringAfterLast('.'),context.cacheDir)
        try {
            instrumentation.context.assets.open("automix/$asset").use {input->file.outputStream().use {input.copyTo(it)}}
            val id="generated331-"+UUID.randomUUID()
            body(AnalysisSource(Uri.fromFile(file),id,file.length(),id,DefaultDataSource.Factory(context),true),file)
        } finally {assertTrue(file.delete())}
    }
    private fun request(s:AnalysisSource,start:Long=0,end:Long=32_000_000)=
        DecodeRequest(AudioIntervalUs(start,end),AnalysisSourceIdentity(s.cacheKey,s.identity))
    private fun control()=AnalysisControl(System.nanoTime()+45_000_000_000)
    private fun decode(s:AnalysisSource,start:Long=0,end:Long=32_000_000):DecodedWindow {
        val result=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED).decode(s,request(s,start,end),control())
        val diag=when(result){is DecodeResult.Decoded->result.window.decoderDiagnostic;is DecodeResult.Failed->result.diagnostic}
        val safe="REAL_WORLD_DECODER_ANDROID "+diag?.reportFields()
        println(safe);File(context.filesDir,"phase331-decoder-last.txt").writeText(safe)
        assertTrue(safe,result is DecodeResult.Decoded)
        val w=(result as DecodeResult.Decoded).window
        assertNull(safe,w.invalidReason());assertEquals(PcmPreprocessing.BANDLIMITED,w.preprocessingVersion)
        assertEquals(safe,0L,w.repeatedOutputFrames);assertEquals(safe,0,w.preparation!!.gapCount)
        assertEquals(safe,0L,w.preparation.synthesizedTimelineFrames)
        return w
    }
    @Test fun aac32SecondWindowHasFullCoverageAndNegativePrimingPacket()=source {s,_ ->
        val w=decode(s);val d=requireNotNull(w.decoderDiagnostic)
        assertEquals(d.reportFields(),CoverageStatus.FULL,w.coverageStatus)
        assertEquals(AudioIntervalUs(0,32_000_000),w.sampleInterval)
        assertEquals(705600,w.frameCount);assertEquals(1_411_200L,w.coverage.acceptedDecodedFrames)
        assertTrue(d.reportFields(),d.codecProgress!!.firstInputPtsUs!!<0)
        assertEquals(0,d.codecProgress.codecPaddingFrames)
        assertTrue(d.reportFields(),d.codecProgress.encoderPaddingFrames!!>0)
    }
    @Test fun actualCodecPadding14ReproducesTheReportedBoundaryAndStrictRejection()=source {s,file ->
        val probe=raw(s,file,14)
        assertEquals("RAW_AAC_BOUNDARY ${probe.buffers}",listOf(0L to 1010,23219L to 1024),probe.buffers.take(2))
        assertEquals(DecoderFailureSubtype.SOURCE_GAP,probe.failure)
        assertEquals(1010L,probe.accepted)
    }
    @Test fun correctedAacMatchesIndependentZeroPaddingCodecPcmExactly()=source {s,file ->
        val actual=decode(s)
        val ref=raw(s,file,0)
        assertNull("RAW_AAC_REFERENCE ${ref.failure}",ref.failure)
        val expected=requireNotNull(ref.window)
        assertArrayEquals(expected.samples,actual.samples,0f)
        assertEquals(expected.coverage,actual.coverage)
        assertEquals(expected.sampleInterval,actual.sampleInterval)
    }
    @Test fun fullPartialAndUncachedRangeBackedAacProduceIdenticalPcm()=source {s,file ->
        val baseline=decode(s)
        for(prefix in listOf(0L,65536L,file.length())) {
            val dir=File(context.cacheDir,"decoder331-cache-"+UUID.randomUUID()).apply {mkdirs()}
            val db=StandaloneDatabaseProvider(context)
            val cache=SimpleCache(dir,NoOpCacheEvictor(),db)
            val reads=AtomicLong()
            val upstream=DataSource.Factory {FixtureRanges(file,reads)}
            val id="range331-"+UUID.randomUUID();val uri=Uri.parse("https://generated.invalid/audio")
            try {
                if(prefix>0) {
                    val writer=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
                    try {writer.open(DataSpec.Builder().setUri(uri).setKey(id).setLength(prefix).build())
                        val b=ByteArray(8192);while(writer.read(b,0,b.size)!=C.RESULT_END_OF_INPUT){} }
                    finally {writer.close()}
                }
                val complete=cache.isCached(id,0,file.length())
                assertEquals(prefix==file.length(),complete)
                reads.set(0)
                val factory=CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(upstream)
                val actual=decode(AnalysisSource(uri,id,file.length(),id,factory,complete))
                assertArrayEquals(baseline.samples,actual.samples,0f)
                assertEquals(baseline.coverage,actual.coverage)
                assertEquals(if(complete)DecoderRangeStatus.NOT_REQUIRED else DecoderRangeStatus.VERIFIED,actual.decoderDiagnostic!!.rangeStatus)
                if(complete)assertEquals(0L,reads.get()) else assertTrue(reads.get()>0)
            } finally {cache.release();db.close();assertTrue(dir.deleteRecursively())}
        }
    }
    @Test fun nonzeroAacSeekPreservesHonestCoverageAndDoesNotInventTheHead()=source {s,_ ->
        val w=decode(s,3_000_000,11_000_000)
        val accepted=requireNotNull(w.coverage.intervals).single()
        assertTrue(accepted.startUs>=3_000_000);assertEquals(11_000_000L,accepted.endUs)
        assertTrue(w.sampleTimelineStartUs>=accepted.startUs)
        assertEquals(if(accepted.startUs==3_000_000L)CoverageStatus.FULL else CoverageStatus.PARTIAL,w.coverageStatus)
    }
    @Test fun opusEarlierAndNearEndWindowsContainActualDecodedAudio()=source(opus) {s,_ ->
        for(start in listOf(2_000_000L,34_000_000L)) {
            val w=decode(s,start,start+5_000_000)
            assertTrue(w.frameCount!!>22050*4)
            assertTrue(w.coverage.acceptedDecodedFrames!!>0)
            assertEquals(start+5_000_000,w.actualEndUs)
            assertEquals("audio/opus",w.decoderDiagnostic!!.codecProgress!!.extractorMime)
        }
    }
    @Test fun opusEosClampsTheEffectiveRequestWithoutInventingLongerSourceAudio()=source(opus) {s,_ ->
        val w=decode(s,35_000_000,45_000_000)
        assertEquals(CoverageStatus.PARTIAL,w.coverageStatus)
        assertTrue(w.decodeInterval.endUs<=w.duration.durationUs!!)
        assertTrue(w.actualEndUs!!<=w.decodeInterval.endUs)
        assertTrue(w.decoderDiagnostic!!.inputEnd==DecoderInputEnd.NATURAL_EOS || w.endOfStreamReached==false)
    }
    @Test fun actualAacDecodeCancellationAndDeadlineReleaseResources()=source {s,_ ->
        val token=AnalysisCancellationToken()
        val r=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED,onDecodedOutput={token.cancel()}).decode(s,request(s),
            AnalysisControl(System.nanoTime()+45_000_000_000,cancellation=token)) as DecodeResult.Failed
        assertEquals(DecoderFailureSubtype.CANCELED,r.diagnostic!!.subtype)
        val expired=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED).decode(s,request(s),AnalysisControl(0)) as DecodeResult.Failed
        assertEquals(DecoderFailureSubtype.DEADLINE,expired.diagnostic!!.subtype)
        assertEquals(CoverageStatus.FULL,decode(s).coverageStatus)
    }
    @Test fun incompleteEvidenceCannotEnterNativeAnalysisAndLegacyRemainsAvailable()=source {s,file ->
        // Remove one real compressed AAC access unit, keeping the remaining extractor PTS.
        // No fake PCM/timestamp object creates this source discontinuity.
        val dropped=raw(s,file,0,dropPacket=true)
        assertEquals("AAC_MISSING_PACKET ${dropped.buffers}",DecoderFailureSubtype.SOURCE_GAP,dropped.failure)
        val w=decode(s);val missing=w.copy(coverage=AudioCoverage(listOf(AudioIntervalUs(0,1_000_000),AudioIntervalUs(1_000_023,32_000_000)),1_411_199))
        var calls=0
        val engine=object:AnalysisEngine {
            override val backend=BitChordNativeAnalysisEngine.BACKEND
            override fun analyze(window:DecodedWindow,detail:AnalysisDetail,control:AnalysisControl):AnalysisResult {calls++;return BitChordNativeAnalysisEngine().analyze(window,detail,control)}
        }
        val result=WindowAnalysisPipeline(AnalysisWindowDecoder<AnalysisSource>{_,_,_->DecodeResult.Decoded(missing)},engine,PcmPreprocessing.BANDLIMITED)
            .analyze(s,request(s),AnalysisDetail.DETAIL,control()) as AnalysisResult.Failed
        assertEquals("PARTIAL_SOURCE_COVERAGE",result.failure.code);assertEquals(0,calls)
        assertEquals(AnalysisPreprocessing.LEGACY,AndroidWindowDecoder().preprocessing)
        assertTrue(AndroidWindowDecoder().decode(s,request(s,0,5_000_000),control()) is DecodeResult.Decoded)
    }
    @Test fun realAacNativeEvidenceReachesTheUnchangedPlanner()=source {s,_ ->
        val analysis=AndroidNativeAnalysis.createPlanningAnalysis()
        val aId=AnalysisSourceIdentity(s.cacheKey,s.identity)
        val b=s.copy(cacheKey=s.cacheKey+"-incoming",identity=s.identity+"-incoming")
        val bId=AnalysisSourceIdentity(b.cacheKey,b.identity)
        val a=analysis.analyze(s,request(s),AnalysisDetail.DETAIL,control())
        val incoming=analysis.analyze(b,request(b),AnalysisDetail.DETAIL,control())
        assertTrue("OUTGOING $a",a.analysis is AnalysisResult.Completed)
        assertTrue("INCOMING $incoming",incoming.analysis is AnalysisResult.Completed)
        val duration=requireNotNull((a.analysis as AnalysisResult.Completed).metadata.duration.durationUs)
        val wanted=NativePlanningRequest(PlanningTrack(aId,duration,AudioIntervalUs(4_000_000,32_000_000)),
            PlanningTrack(bId,duration,AudioIntervalUs(0,32_000_000)),3_000_000,1_000_000,1,"generated331")
        val result=NativeAwareAutoMixPlanner().plan(wanted,a,incoming,control())
        assertTrue("GENERATED_AAC_PLANNER $result",result is NativePlanningOutcome.Selected)
    }

    private data class Probe(val window:DecodedWindow?,val failure:DecoderFailureSubtype?,val buffers:List<Pair<Long,Int>>,val accepted:Long)
    /** Independent real codec path: explicitly choose codec tail padding, directly map PCM.
     * This reproduces Android SkipCutBuffer and checks the production tail queue's parity. */
    private fun raw(s:AnalysisSource,file:File,padding:Int,dropPacket:Boolean=false):Probe {
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        val points=mutableListOf<Pair<Long,Int>>();val c=control()
        val capture=CorrectedPcmWindowCapture(request(s),request(s).interval,AudioDuration(36_000_000,DurationBasis.FIXTURE))
        try {
            extractor.setDataSource(file.absolutePath);extractor.selectTrack(0)
            val f=extractor.getTrackFormat(0);f.setInteger(MediaFormat.KEY_ENCODER_PADDING,padding)
            val info=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { !it.isEncoder && it.supportedTypes.contains("audio/mp4a-latm") &&
                (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) }
            codec=MediaCodec.createByCodecName(info.name);codec.configure(f,null,null,0);codec.start()
            var ended=false;var skipped=false;var rate=44100;var channels=2;var encoding=PcmEncoding.PCM16
            val b=MediaCodec.BufferInfo()
            while(!capture.reachedEnd) {
                c.checkpoint()
                if(!ended) {
                    val index=codec.dequeueInputBuffer(5000)
                    if(index>=0) {
                        val buffer=codec.getInputBuffer(index)!!
                        if(dropPacket && !skipped && extractor.sampleTime>=92879) {
                            assertTrue(extractor.advance());skipped=true
                        }
                        val n=extractor.readSampleData(buffer,0);val pts=extractor.sampleTime
                        if(n<0 || pts>=32_000_000) {codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);ended=true}
                        else {codec.queueInputBuffer(index,0,n,pts,0);extractor.advance()}
                    }
                }
                val index=codec.dequeueOutputBuffer(b,5000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate=codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if(codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) && codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)==C.ENCODING_PCM_FLOAT)encoding=PcmEncoding.FLOAT32
                } else if(index>=0) {
                    try {
                        val bytes=DecoderPcmBuffers.readable(if(b.size==0)null else codec.getOutputBuffer(index),b.offset,b.size,true)
                        if(b.size>0 && points.size<8)points+=b.presentationTimeUs to bytes.remaining()/(channels*if(encoding==PcmEncoding.PCM16)2 else 4)
                        capture.accept(bytes,b.presentationTimeUs,SourcePcmFormat(PcmFormat(rate,channels,encoding),SourceFormatBasis.DECODER_OUTPUT),c)
                        if(b.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)break
                    } finally {codec.releaseOutputBuffer(index,false)}
                }
            }
            return Probe(capture.finish(false,c),null,points,capture.acceptedFrames)
        } catch(e:PcmPreparationException) {return Probe(null,e.subtype,points,capture.acceptedFrames)}
        finally {try {codec?.stop()}finally {codec?.release();extractor.release()}}
    }

    /** Real Media3 cache/range reads over local encoded bytes. This is not an HTTP/network benchmark. */
    private class FixtureRanges(private val file:File,private val reads:AtomicLong):DataSource {
        private val delegate=FileDataSource();private var uri:Uri?=null;private var headers=emptyMap<String,List<String>>()
        override fun addTransferListener(listener:TransferListener)=delegate.addTransferListener(listener)
        override fun open(spec:DataSpec):Long {
            uri=spec.uri
            val n=if(spec.length==C.LENGTH_UNSET.toLong())file.length()-spec.position else spec.length
            headers=mapOf("Content-Range" to listOf("bytes ${spec.position}-${spec.position+n-1}/${file.length()}"))
            return delegate.open(spec.withUri(Uri.fromFile(file)))
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int=delegate.read(buffer,offset,length).also {if(it>0)reads.addAndGet(it.toLong())}
        override fun getUri()=uri
        override fun getResponseHeaders()=headers
        override fun close()=delegate.close()
    }
}
