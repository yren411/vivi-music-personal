package com.music.vivi.playback.automix

import org.junit.Test
import org.junit.Assert.*
import java.lang.management.ManagementFactory
import kotlin.math.*

/** Desktop JVM observations only. No timing assertion or production deadline relaxation. */
class PcmPreprocessingBenchmarkTest {
    @Test fun reportsRepresentativeWindowsWithoutWallClockPassFailThresholds() {
        val bean=ManagementFactory.getThreadMXBean()
        for(rate in listOf(44100,48000))for(seconds in listOf(5,16,24,30,32)) {
            val input=FloatArray(rate*seconds){(0.4*sin(2*PI*997*it/rate)).toFloat()}
            for(mode in AnalysisPreprocessing.entries) {
                val began=System.nanoTime();val cpu=if(bean.isCurrentThreadCpuTimeSupported)bean.currentThreadCpuTime else 0
                val result=PcmTestPreparation.prepare(input,rate,mode)
                val elapsed=(System.nanoTime()-began)/1e6
                val cpuMs=if(bean.isCurrentThreadCpuTimeSupported)(bean.currentThreadCpuTime-cpu)/1e6 else null
                println("PCM_BENCH desktop=true rate="+rate+" seconds="+seconds+" mode="+mode+" elapsedMs="+elapsed+
                    " cpuMs="+cpuMs+" inputFrames="+input.size+" outputFrames="+result.frameCount+
                    " sourceArrayBytes="+(input.size*4L)+" outputArrayBytes="+(result.samples.size*4L))
                assertEquals(seconds*22050,result.frameCount)
            }
        }
    }
}
