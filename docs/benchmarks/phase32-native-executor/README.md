# Phase 3.2 execution evidence

All audio is generated. **Real host JNI / virtual players**, **offline PCM sums**, **Android compilation**, and **device playback** are separate evidence levels. No Android runtime, acoustic capture or real-song listening was performed here.

## Native-plan-to-virtual-executor results

Actual corrected timestamp-aware capture -> existing JNI -> actual BitChord DSP -> Phase 3.1 planner -> Phase 3.2 executor. These use declared 180-second fixture timelines and supplied 30/32-second PCM windows; no full three-minute recording was decoded. Each virtual clock has exactly controlled progression.

| Fixture | Native BPM out / in | Outgoing / incoming cue (s) | Planned overlap (s) | Simulated concurrent progression (s) | Result |
| --- | --- | --- | --- | --- | --- |
| native-90.0-30s-leading0.0 | 90.045 / 90.049 | 150.139320 / 0.336689 | 21.324106 | 21.330 | COMPLETED |
| native-107.0-30s-leading0.0 | 107.094 / 107.099 | 150.139320 / 0.336689 | 17.934601 | 17.940 | COMPLETED |
| native-120.0-30s-leading0.0 | 120.035 / 120.039 | 150.139320 / 0.336689 | 15.990951 | 16.000 | COMPLETED |
| native-128.0-30s-leading0.0 | 128.094 / 128.098 | 150.139320 / 0.336689 | 14.990768 | 15.000 | COMPLETED |
| native-140.0-30s-leading0.0 | 139.981 / 139.942 | 150.127710 / 0.325079 | 27.432115 | 27.440 | COMPLETED |
| native-150.0-30s-leading0.0 | 149.926 / 149.930 | 150.127710 / 0.325079 | 25.603335 | 25.610 | COMPLETED |
| native-174.0-30s-leading0.0 | 173.903 / 173.903 | 150.127710 / 0.661239 | 22.072378 | 22.080 | COMPLETED |
| native-128.0-32s-leading0.0 | 128.079 / 128.085 | 148.139320 / 0.336689 | 29.991569 | 30.000 | COMPLETED |
| native-128.0-30s-leading18.0 | 128.094 / 128.112 | 150.139320 / 18.333266 | 7.490017 | 7.500 | COMPLETED |

All nine plans completed once with zero simulated start offset and zero injected underruns. These zeros are properties of the virtual clock, **not measured Android timing**. The 10 ms virtual tick can complete less than 10 ms past the planned endpoint; actual progression is reported rather than rounded down to the proposal. Native metrical/confidence limitations remain inherited from Phase 3.1; none were retuned here.

## Offline signal measurements

36 cases: four curves x nine deterministic signal pairs, 16 seconds at 22,050 Hz. All weights include worst-case correlation guarding. No clipping/limiter is used. These tests measure arithmetic PCM sums, not post-EQ/AudioTrack/Bluetooth output.

| Default restrained DJ case | Combined peak | Combined RMS |
| --- | --- | --- |
| correlated | 0.899996 | 0.601338 |
| uncorrelated | 0.899981 | 0.482704 |
| quiet-tail | 0.899994 | 0.360800 |
| quiet-incoming | 0.799995 | 0.320713 |
| offset-kicks | 0.794049 | 0.084937 |
| aligned-kicks | 0.794049 | 0.104408 |
| leading-silence | 0.899996 | 0.581171 |
| silence | 0.000000 | 0.000000 |
| amplitude-step | 0.899996 | 0.531199 |

Across all 36 cases the largest combined peak was 0.899998; every tested per-sample gain step was <0.001. Full per-deck contribution/RMS and per-curve results remain in the JSON. Correlated and uncorrelated RMS differ: sum guarding is not a constant-perceived-loudness promise.

Quiet-tail regression: the legacy reference ratio 0.005 / 0.2 is approximately 0.025. New invalid/quiet/mismatched reference handling returns unity incoming content factor; the default midpoint incoming fade is 0.5, not 0.0125. Comparable active measurements are capped at 3 dB attenuation; silence never causes a boost. This is commanded gain math, not a measured acoustic comparison.

## Host execution performance / resources

Twenty complete 16-second virtual transitions (about 1600 10ms ticks each): median 4.397 ms elapsed desktop wall time, maximum 4.830 ms. These exclude analysis, decoding, actual scheduler waits and Android playback. Shared-cloud contention/JIT affect the sample; these are not device CPU/battery measurements.

The executor retains no PCM. At most two players per explicit session and 128 compact event records; scratch work is bounded. PCM analysis and two-player buffering retain their existing budgets. No new analysis pool, native threads, persistent PCM cache or always-on task exists. Peak/live heap, allocation counts, Android RSS, thermals, real decoder preparation latency and acoustic jitter were **not measured**. The mathematical gain loops are not an audio renderer.

## Executed checks

- Full GMS: **405 passed**, zero failures/errors/skips. Full FOSS: **405 passed**, zero failures/errors/skips. Includes 16 real host-JNI test methods per variant; without the optional configured host library those methods explicitly skip, while pure execution tests run.
- Existing native CTest **10/10**, and ASan/UBSan/LSan CTest **10/10**. DSP sources were not changed.
- Debug GMS/FOSS app APKs and test APKs assembled. **14 generated playback tests plus one separately selected optional listening test compiled**; 14 actual runtime JUnit annotations verified in the GMS test APK. Earlier 24 instrumentation tests are unchanged.
- No emulator/system image/KVM/connected device was provisioned. New Android runtime execution: **zero**. The owner's earlier 24 passes remain historical, not evidence for this executor.
- AutoMix JNI libraries in both app APKs pass ELF LOAD and native ZIP offset/uncompressed packaging checks; whole-APK zipalign -P16, ABI/JNI symbol and license-asset checks also pass. All four ABIs are present. Native payload hashes match Phase 3.1 exactly.
- Debug APK archive bytes: app-arm64-gms-debug.apk: 72990981, app-universal-foss-debug.apk: 70152934. These are archive sizes, not a controlled per-feature size delta; ZIP alignment/compression affect comparisons. New executor classes were inspected in GMS DEX.
- No existing assertions/tests were weakened. Early development failures were new-test numerical precision, a clock fixture representing a discontinuity, compilation while contracts were being completed, and watchdog stage classification. They were resolved before the final successful run. Preparation timeout and ready-clock stalls remain distinct.

## Commands actually executed

Cloud tool paths are environment-specific. Activate JDK21 / the existing Android SDK first.

```bash
source /workspace/onboarding/activate.sh
export TINIUM_PHASE32_REPORT_DIR=/tmp/phase32-complete-report
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest "-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so" "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
# A later test-only handoff-continuation assertion/harness change was compiled with:
bash gradlew :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
/workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-build --output-on-failure
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 /workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-sanitized --output-on-failure
python3 scripts/check_automix_native_apks.py --readelf /workspace/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf --zipalign /workspace/android-sdk/build-tools/36.0.0/zipalign --output docs/benchmarks/phase32-native-executor/apk-packaging.json app/build/outputs/apk/arm64Gms/debug/app-arm64-gms-debug.apk app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
```

The JVM test tasks actually executed and were not restored FROM-CACHE. Unchanged compile/native tasks could remain UP-TO-DATE. Native tests reused unchanged already-built source-compatible host binaries; packaging validates the actual current APK libraries. No fresh native algorithm/build change is claimed.

## Device and Phase 3.3 gates

See [AUTOMIX_NATIVE_DJ_EXECUTOR.md](../../AUTOMIX_NATIVE_DJ_EXECUTOR.md) for exact 14-test and previous-24 PowerShell commands, private local listening, safe logs, cleanup and optional acoustic measurements. Production remains legacy. Source/runtime reports never imply device success, post-EQ clipping safety or perceptual quality. Real service focus/routing/MediaSession handoff and real-song listening require deliberate integration/validation before activation. No public APK release or automatic merge is authorized.
