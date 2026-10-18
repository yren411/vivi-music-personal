# Phase 3.3.1 decoder evidence

Baseline `d17b094f8b6ba74bc33579c9821bc0b32b3245bc` (merged Phase 3.3).
This directory contains generated-fixture and host validation evidence, not private music.

- `before-pair35-replay.json`: original failing source-frame replay and its provenance limits.
- `opus-boundary-followup.json`: owner-reported v6 Opus failure, exact numeric diagnostic,
  bounded v7 support policy and separately labeled host replay; its pending-runtime
  field preserves the original code-handoff checkpoint.
- `owner-device-regression.json`: subsequent owner-reported single Opus pass and
  combined 59-test pass on S25 Ultra / Android 17; no raw XML or verified installed SHA supplied.
- `generated-fixtures.json`: deterministic AAC/Opus recipe metadata and encoded asset hashes.
- `validation.json`: initial post-repair results plus separately recorded final passive-diagnostics/packet-loss follow-up; original measurements preserved.
- `apk-packaging.json` / `apk-packaging-passive-followup.json` / `apk-packaging-opus-followup.json`:
  separate actual archive inspections, including the v7 Opus build.

| Validation level | Actual result |
| --- | --- |
| Arm64 GMS complete JVM suite (v7 follow-up) | 514 passed, zero failures/errors/skips |
| Universal FOSS complete JVM suite (v7 follow-up) | 514 passed, zero failures/errors/skips |
| Actual host JNI/comparison | 17 per variant, included above |
| PCM parity | Eight 44.1/48kHz × mono/stereo × PCM16/Float32 cases; max absolute error 0.0 |
| GMS/FOSS app and instrumentation APKs | v7 follow-up built; 4m31s, 60 tasks executed / 337 up-to-date (earlier runs recorded separately) |
| Actual native packaging | Four ABIs; ELF/ZIP 16KB alignment, symbols and license assets passed |
| New Android decoder tests | Ten compiled and present in test DEX; **zero executed by Codex** |
| Native CTest/sanitizers | Not rerun; DSP/JNI/C++ unchanged |
| Owner v6 Android follow-up | One of ten new methods failed; supplied Opus single-method diagnostic retained separately; historical failure retained |
| Owner v7 Android follow-up | All 59 combined methods reported passed, zero failures/skips; not executed by Codex |
| Original-song/HTTP/acoustic results | Not tested; owner follow-up required |

The preceding v6 run (508 per variant, retained in `validation.json`) includes one actual MP4 fixture-contract test per variant: media
frame count minus priming minus presentation must equal 14, every coded AAC duration
must be 1024, and the encoded payload hash must remain unchanged. Original 507-per-
variant results and the initial fixture metadata defect remain separately recorded.

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
10-test, prior-49 and combined-59 regression commands. No new Android runtime test has been
executed by Codex; the separate owner report is physical-device evidence, while
compilation alone is not device execution. No real-song quality, HTTP/CDN,
phone memory/battery, acoustic or audible-EQ measurement is supplied.
