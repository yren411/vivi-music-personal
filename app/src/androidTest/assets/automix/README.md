# Generated decoder fixtures

Phase 2.3B additionally includes `rhythm-128-48000.m4a` (stereo AAC) and
`rhythm-128-44100.flac` (mono FLAC), 30-second generated 128 BPM kick patterns
with 0.137-second onset phase and a quiet tonal bed. Recipe: Phase 2.3A corpus v1.
Regenerate with `python scripts/generate_automix_native_codec_fixtures.py`
(FFmpeg 7.1.5 was used). No recording or internet audio is used. These files
exist only in instrumentation APKs, not normal app assets.

- AAC SHA-256: `baeaa0d5a747c7648430c836dd8cac84a8791097adb9940e93fd19ab18f78b12`.
- FLAC SHA-256: `83ed050f64f01cb7a57ac21a547aa8846a6b28a70b9ca8f130f03d809892a23d`.

The four earlier decoder tests and their following tone fixtures are unchanged.

These files contain generated 997 Hz sine tones (7 seconds), not music recordings.
They were generated with FFmpeg 7.1.5; no third-party audio or model is included.

```sh
ffmpeg -f lavfi -i 'sine=frequency=997:sample_rate=48000:duration=7' -ac 2 -c:a aac -b:a 96k -movflags +faststart tone-48000-stereo.m4a
ffmpeg -f lavfi -i 'sine=frequency=997:sample_rate=44100:duration=7' -ac 1 -c:a flac tone-44100-mono.flac
```

Encoded bytes may vary with FFmpeg version; tests use measured codec output, not
bit-exact compressed encodings. Tests copy assets into app-private temporary files
and delete them afterward. No network, account, storage permission, or playback
player is needed. These assets are included only in the instrumentation test APK.

## Phase 3.3.1 generated AAC boundary and Opus fixtures

Regenerate with `python scripts/generate_automix_real_world_decoder_fixtures.py`.
`boundary-padding14-44100-stereo.m4a` uses 1,572,850 source frames (1536 × 1024 − 14),
44.1kHz stereo AAC and a 44.1kHz movie timebase. FFprobe reports an initial −1024-sample
priming packet and a final 1010-sample presentation duration. The Android regression
also explicitly configures 14 padding frames in an independent codec probe, so it does
not assume every extractor derives identical padding metadata.
`rhythm-128-48000-40s.webm` contains 40 seconds of generated 128 BPM rhythm encoded as
mono Opus. Hashes and exact encoded byte counts are recorded in
`docs/benchmarks/phase331-native-decoder/generated-fixtures.json`. Both exist only in
instrumentation APKs; no original fixture was changed or copyrighted recording used.
