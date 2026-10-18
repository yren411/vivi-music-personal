# Phase 3.3.1 decoder evidence

Baseline `d17b094f8b6ba74bc33579c9821bc0b32b3245bc` (merged Phase 3.3).
This directory contains generated-fixture and host validation evidence, not private music.

- `before-pair35-replay.json`: original failing source-frame replay and its provenance limits.
- `generated-fixtures.json`: deterministic AAC/Opus recipe metadata and encoded asset hashes.
- `validation.json`: post-repair executed test counts, PCM parity and packaging observations.

| Validation level | Actual result |
| --- | --- |
| Arm64 GMS complete JVM suite | 507 passed, zero failures/errors/skips |
| Universal FOSS complete JVM suite | 507 passed, zero failures/errors/skips |
| Actual host JNI/comparison | 17 per variant, included above |
| PCM parity | Eight 44.1/48kHz × mono/stereo × PCM16/Float32 cases; max absolute error 0.0 |
| GMS/FOSS app and instrumentation APKs | Built; 4m06s, 50 tasks executed / 347 up-to-date |
| Actual native packaging | Four ABIs; ELF/ZIP 16KB alignment, symbols and license assets passed |
| New Android decoder tests | Ten compiled and present in test DEX; **zero executed by Codex** |
| Native CTest/sanitizers | Not rerun; DSP/JNI/C++ unchanged |
| Original-song/HTTP/acoustic/device results | Not tested; owner follow-up required |

The AAC FIFO mechanism is reproduced; matching the exact original source's encoder-padding
metadata remains a device follow-up. Natural EOS and request-boundary EOS are deliberately
different. The old unexplained PCM timestamp signature and genuinely missing frames still
fail strict coverage; no threshold relaxation or sample fabrication is an accepted result.

Run the actual JVM suites with host JNI, and compile/build both Android variants:

```bash
bash gradlew :app:testArm64GmsDebugUnitTest --rerun :app:testUniversalFossDebugUnitTest --rerun \
  :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest \
  :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug \
  '-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so' \
  '-PautomixDecoderTests=true' --no-configuration-cache --no-daemon --max-workers=4 --console=plain
```

See [the reliability guide](../../AUTOMIX_REAL_WORLD_DECODER_RELIABILITY.md) for exact
10-test device and prior-49 regression commands. No new Android runtime test has been
executed by Codex; compilation is not device execution. No real-song quality, HTTP/CDN,
phone memory/battery, acoustic or audible-EQ measurement is supplied.
