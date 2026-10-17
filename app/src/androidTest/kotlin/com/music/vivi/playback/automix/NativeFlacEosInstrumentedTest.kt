package com.music.vivi.playback.automix

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/** Silent diagnosis of actual extractor/codec EOS. Not a substitute for the service mix tests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeFlacEosInstrumentedTest {
    @get:Rule val deadline:Timeout=Timeout.seconds(90)

    @Test fun fullFlacWindowPreservesExtractorFramesThroughCodec() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".decodercheck")){"Use -PautomixDecoderTests=true"}
        val report=StringBuilder("NATIVE_FLAC_EOS probe=v1 generated=${Instant.now()}\n")
        val reportFile=File(context.filesDir,"phase33-flac-eos-diagnostic.txt")
        val file=File.createTempFile("phase33-eos-",".flac",context.cacheDir)
        try {
            instrumentation.context.assets.open("automix/rhythm-128-44100.flac").use {input->file.outputStream().use {input.copyTo(it)}}
            report.append("fixtureBytes=${file.length()} expectedFrames=1323000 expectedRate=44100 expectedDurationUs=30000000\n")
            var extractorChecks=true
            for(seekZero in listOf(false,true)) {
                try {report.append(extract(file,seekZero)).append('\n')}
                catch(error:Exception){extractorChecks=false;report.append("EXTRACTOR seekZero=$seekZero errorType=${error.javaClass.simpleName}\n")}
                reportFile.writeText(report.toString())
            }
            val id="phase33-eos-${UUID.randomUUID()}"
            val source=AnalysisSource(Uri.fromFile(file),id,file.length(),id,DefaultDataSource.Factory(context),true)
            val request=DecodeRequest(AudioIntervalUs(0,30_000_000),AnalysisSourceIdentity(id,id))
            var corrected:DecodedWindow?=null
            for(mode in AnalysisPreprocessing.entries) {
                val events=mutableListOf<DecoderDiagnostic>()
                val result=AndroidWindowDecoder(mode).decode(source,request,
                    AnalysisControl(System.nanoTime()+25_000_000_000,decoderDiagnostics=DecoderDiagnosticSink {events+=it}))
                report.append("DECODE mode=$mode outcome=${if(result is DecodeResult.Decoded) "DECODED" else (result as DecodeResult.Failed).failure.code}\n")
                events.forEach {report.append(it.reportFields()).append('\n')}
                if(mode==AnalysisPreprocessing.BANDLIMITED)corrected=(result as? DecodeResult.Decoded)?.window
                reportFile.writeText(report.toString())
            }
            val w=corrected
            assertTrue(report.toString(),extractorChecks && w!=null && w.invalidReason()==null &&
                w.coverage.status(w.decodeInterval)==CoverageStatus.FULL && w.frameCount==661500 &&
                w.coverage.acceptedDecodedFrames==1_323_000L && w.preparation?.droppedOverlapFrames==0L &&
                w.preparation.gapCount==0 && w.repeatedOutputFrames==0L && w.preparation.synthesizedTimelineFrames==0L)
        }finally {
            reportFile.writeText(report.toString())
            android.util.Log.i("TiniumNativeDj",report.toString())
            file.delete()
        }
    }

    /** Local generated file only: compare the platform extractor's initial and zero-seek paths.
     * No raw samples, URI or arbitrary MediaFormat contents are printed. Work/memory are bounded. */
    private fun extract(file:File,seekZero:Boolean):String {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track=(0 until extractor.trackCount).first {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
            val format=extractor.getTrackFormat(track)
            val mime=format.getString(MediaFormat.KEY_MIME)?.takeIf {it.matches(Regex("audio/[A-Za-z0-9.+_-]{1,80}"))}
            val rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);val channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding=if(format.containsKey(MediaFormat.KEY_PCM_ENCODING))format.getInteger(MediaFormat.KEY_PCM_ENCODING) else null
            val pcmBytes=if(mime!="audio/raw")null else when(encoding) {C.ENCODING_PCM_16BIT->2;C.ENCODING_PCM_FLOAT->4;else->null}
            val duration=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION) else null
            extractor.selectTrack(track)
            if(seekZero)extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val data=ByteBuffer.allocateDirect(1024*1024)
            var packets=0;var bytes=0L;var frames=if(pcmBytes==null)null else 0L
            var firstPts:Long?=null;var lastPts:Long?=null;var lastSize:Int?=null
            var maxRawResidual=0.0
            val first=mutableListOf<String>();val tail=java.util.ArrayDeque<String>()
            val end=System.nanoTime()+10_000_000_000
            while(true) {
                check(System.nanoTime()<end && packets<1024 && bytes<=8*1024*1024){"EXTRACTOR_PROBE_LIMIT"}
                data.clear()
                val size=extractor.readSampleData(data,0)
                if(size<0)break
                check(size<=data.capacity())
                val pts=extractor.sampleTime
                firstPts=firstPts ?: pts
                frames?.let {maxRawResidual=maxOf(maxRawResidual,abs(pts-(firstPts+it*1_000_000.0/rate)))}
                frames=if(frames!=null && pcmBytes!=null && size%(channels*pcmBytes)==0)frames+size/(channels*pcmBytes) else null
                val row="($pts,$size)"
                if(first.size<3)first+=row
                if(tail.size==6)tail.removeFirst();tail.addLast(row)
                packets++;bytes+=size;lastPts=pts;lastSize=size
                if(!extractor.advance())break
            }
            return "EXTRACTOR seekZero=$seekZero mime=$mime rate=$rate channels=$channels encoding=$encoding durationUs=$duration packets=$packets bytes=$bytes rawFrames=$frames firstPtsUs=$firstPts lastPtsUs=$lastPts lastSize=$lastSize maxRawPtsResidualUs=${if(frames==null)null else maxRawResidual} first=$first tail=$tail"
        }finally {extractor.release()}
    }
}
