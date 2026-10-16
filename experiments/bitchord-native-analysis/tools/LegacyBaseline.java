// SPDX-License-Identifier: AGPL-3.0-or-later
// Optional DATA-only benchmark process. Loads only selected nonrestricted Tinium analyzer classes.
import com.music.vivi.playback.automix.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class LegacyBaseline {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("PCM filename and sample rate required");
        int rate = Integer.parseInt(args[1]);
        long size = Files.size(Path.of(args[0]));
        if (rate < 8000 || rate > 96000 || size == 0 || size % 4 != 0 || size > 96000L*32*4)
            throw new IllegalArgumentException("Invalid bounded PCM");
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[buffer.remaining()/4];
        buffer.asFloatBuffer().get(samples);
        if (samples.length < rate*4) {
            System.out.println("{\"status\":\"LEGACY_REQUIRES_FOUR_SECONDS\"}"); return;
        }
        long start = System.nanoTime();
        TrackFeatures f = new TrackFeaturesAnalyzer().analyze(samples,rate,0,samples.length*1000L/rate,
            Long.MAX_VALUE,AnalysisDetail.SCAN,AnalysisCancellation.Companion.getNONE());
        double elapsed = (System.nanoTime()-start)/1000.0;
        StringJoiner beats = new StringJoiner(",", "[", "]");
        for (long beat : f.getBeatsMs()) beats.add(Double.toString(beat/1000.0));
        System.out.println("{\"status\":\"LEGACY_COMPLETED\",\"bpm\":"+f.getBpm()+
            ",\"legacy_confidence\":"+f.getBeatConfidence()+",\"beats_seconds\":"+beats+
            ",\"phase_confidence\":"+f.getPhaseConfidence()+",\"rms\":"+f.getRms()+
            ",\"peak\":"+f.getPeak()+",\"elapsed_us\":"+elapsed+"}");
    }
}
