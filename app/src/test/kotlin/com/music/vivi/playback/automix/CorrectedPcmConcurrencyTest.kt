package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test

/** JVM stress of the actual maximum-rate/window conversion, not an Android heap benchmark. */
class CorrectedPcmConcurrencyTest {
    @Test fun fourWorkersCanConvertBoundedMaximumRateWindowsWithoutCachingSourcePcm() {
        val entered=CountDownLatch(4)
        val release=CountDownLatch(1)
        val threads=ConcurrentHashMap.newKeySet<String>()
        val sourceRate=192000
        val seconds=32
        AnalysisWorkers<DecodedWindow>(AutoMixPerformance.PERFORMANCE).use { workers ->
            val jobs=(0 until 4).map { worker -> workers.submit("maximum-window-"+worker,cacheable=false) {
                threads.add(Thread.currentThread().name)
                val interval=AudioIntervalUs(0,seconds*1_000_000L)
                val request=DecodeRequest(interval,AnalysisSourceIdentity("generated",worker.toString()))
                val capture=CorrectedPcmWindowCapture(request,interval,AudioDuration(interval.endUs,DurationBasis.FIXTURE))
                val format=SourcePcmFormat(PcmFormat(sourceRate,1,PcmEncoding.FLOAT32),SourceFormatBasis.DECODER_OUTPUT)
                val block=ByteBuffer.allocate(4096*4).order(ByteOrder.nativeOrder())
                repeat(4096){block.putFloat(0.125f)}
                var offset=0
                while(offset<sourceRate*seconds) {
                    val frames=minOf(4096,sourceRate*seconds-offset)
                    block.position(0);block.limit(frames*4)
                    capture.accept(block,offset*1_000_000L/sourceRate,format,AnalysisControl())
                    offset+=frames
                }
                entered.countDown()
                check(release.await(30,TimeUnit.SECONDS))
                capture.finish(false,AnalysisControl())
            } }
            try {
                assertTrue(entered.await(30,TimeUnit.SECONDS))
                assertEquals(4,threads.size)
                assertTrue(threads.all { it.startsWith("Tinium-AutoMix-") })
            } finally { release.countDown() }
            jobs.forEachIndexed { index,job ->
                val window=job.get(30,TimeUnit.SECONDS)
                assertNull(window.invalidReason())
                assertEquals(705600,window.frameCount)
                assertEquals(CoverageStatus.FULL,window.coverageStatus)
                assertEquals(6144000L,window.coverage.acceptedDecodedFrames)
                assertTrue(window.samples.all { kotlin.math.abs(it-0.125f)<0.000002 })
                assertFalse(workers.hasCached("maximum-window-"+index))
            }
        }
    }
}
