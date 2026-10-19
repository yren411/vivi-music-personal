# Phase 3.3.3 AAC boundary evidence

See [engineering findings and device commands](../../AUTOMIX_AAC_BOUNDARY_COVERAGE.md).
Base is merged PR #25, `c102d080ab91ddf8d80cf1707c84c6c2bbc2cf68`.

| Artifact | Evidence type |
| --- | --- |
| `owner-summary.json` | Owner's supplied task summary; no raw/private audio or independently verified track identities |
| `generated-fixtures.json` | Genuine AAC/MP4 generation, bounded fixture metadata, ffprobe packet facts, FFmpeg decoded frame count and SHA-256 values |
| `host-boundary-replay.json` | Deterministic PCM/frame replay through the actual corrected capture and preroll policy; not Android codec execution |
| `validation.json` | Executed test/build counts, commands and explicit unavailable runtime work |
| `apk-packaging.json` | Actual APK ABI/ELF/ZIP/license inspection, separate from device loading/execution |

Reproduce fixtures without commercial recordings or changes to the earlier fixture bytes:

```bash
python scripts/generate_automix_aac_boundary_fixtures.py
# Independent regeneration outside the checkout:
python scripts/generate_automix_aac_boundary_fixtures.py \
  --output /tmp/phase333-aac-assets --report /tmp/phase333-aac-assets/manifest.json
```

This requires host FFmpeg/ffprobe. Nothing invokes Python/FFmpeg in Android Gradle or
ships those tools in the app. Three assets regenerated with identical SHA-256 values.
The positive-origin fixture remuxes unchanged generated AAC `mdat` bytes with a legitimate
empty edit, not a decoder timestamp patch. It must retain partial requested coverage.
The long PNS-disabled AAC decodes to 6,838,272 stereo frames, despite a 155.063-second
movie endpoint. The 48 kHz mono fixture has an exact 36-second presentation duration.
PNS is disabled only in the new controlled parity fixtures, never in the decoder/player.

The host replay independently distinguishes the old 2,759 µs seek loss from the trailing
144 µs shortfall. Preroll recovers 122 real leading frames; an actual unsupported EOS
remains partial. All four nonconstant 44.1/48 kHz mono/stereo frame-order comparisons
measured max absolute canonical PCM error **0.0**. These measurements do not assert
bit-exact Android AAC seek output; a real forward/seek codec test must run on the phone.

Local matrix (actual counts/results in `validation.json`):

**563 GMS + 563 FOSS = 1,126 passed**, zero failures/errors/skips, with 34 actual host-JNI
executions included. Focused selection: 39 passed. Both debug app and instrumentation
APK variants built. Actual four-ABI/16 KB/JNI/license inspection passed; current Kotlin
v8 code and the unchanged planner/executor version strings were verified in APK DEX.
No physical-device execution is claimed.

```bash
bash gradlew :app:testArm64GmsDebugUnitTest --rerun \
  :app:testUniversalFossDebugUnitTest --rerun \
  :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug \
  :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest \
  -PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so \
  -PautomixDecoderTests=true --no-configuration-cache --no-daemon --max-workers=4 --console=plain
```

Build the real host JNI library using the Phase 2.3B guide if it is not present. Host-JNI
executions are included in JVM totals, not counted again as additional tests. Native
DSP/JNI/CMake source is unchanged, so historical CTest/sanitizer evidence is not presented
as new execution. Current APKs are inspected with the unchanged packaging checker:

```bash
python scripts/check_automix_native_apks.py \
  --readelf "$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  --zipalign "$ANDROID_HOME/build-tools/37.0.0/zipalign" \
  --output /tmp/phase333-apk-packaging.json \
  app/build/outputs/apk/arm64Gms/debug/app-arm64-gms-debug.apk \
  app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
```

New Android class has 10 methods; prior acceptance inventory remains 69, expanded total
79. Compilation/packaging is not Android execution. Owner's earlier 69-test v7 pass is
historical and is not carried forward to v8. The optional startup diagnostic is excluded
from the acceptance count. Initial private-source cause and EOS metadata cannot be proven
from generated fixtures alone; those limits and genuine partial outcomes are preserved.
