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

Twenty complete 16-second virtual transitions (about 1600 10ms ticks each): median 4.389 ms elapsed desktop wall time, maximum 4.878 ms. These exclude analysis, decoding, actual scheduler waits and Android playback. Shared-cloud contention/JIT affect the sample; these are not device CPU/battery measurements.

The executor retains no PCM. At most two players per explicit session and 128 compact event records; scratch work is bounded. PCM analysis and two-player buffering retain their existing budgets. No new analysis pool, native threads, persistent PCM cache or always-on task exists. Peak/live heap, allocation counts, Android RSS, thermals, real decoder preparation latency and acoustic jitter were **not measured**. The mathematical gain loops are not an audio renderer.

## Executed checks

- Full GMS: **418 passed**, zero failures/errors/skips. Full FOSS: **418 passed**, zero failures/errors/skips. Includes 16 real host-JNI test methods per variant; without the optional configured host library those methods explicitly skip, while pure execution tests run.
- Existing native CTest **10/10**, and ASan/UBSan/LSan CTest **10/10**. DSP sources were not changed.
- Debug GMS/FOSS app APKs and test APKs assembled. **14 generated playback tests plus one separately selected optional listening test compiled**; 14 actual runtime JUnit annotations verified in the GMS test APK. Earlier 24 instrumentation tests are unchanged.
- No emulator/system image/KVM/connected device was provisioned. New Android runtime execution: **zero**. The owner's earlier 24 passes remain historical, not evidence for this executor.
- AutoMix JNI libraries in both app APKs pass ELF LOAD and native ZIP offset/uncompressed packaging checks; whole-APK zipalign -P16, ABI/JNI symbol and license-asset checks also pass. All four ABIs are present. Native payload hashes match Phase 3.1 exactly.
- Debug APK archive bytes: app-arm64-gms-debug.apk: 72990981, app-universal-foss-debug.apk: 70156022. These are archive sizes, not a controlled per-feature size delta; ZIP alignment/compression affect comparisons. New executor classes were inspected in GMS DEX.
- No existing assertions/tests were weakened. Early development failures were new-test numerical precision, a clock fixture representing a discontinuity, compilation while contracts were being completed, and watchdog stage classification. They were resolved before the final successful run. Preparation timeout and ready-clock stalls remain distinct.

## Commands actually executed

Cloud tool paths are environment-specific. Activate JDK21 / the existing Android SDK first.

```bash
source /workspace/onboarding/activate.sh
export TINIUM_PHASE32_REPORT_DIR=/tmp/phase32-admission-final-report
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest "-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so" "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
# During development the handoff-continuation assertion was also separately compiled with:
bash gradlew :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
/workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-build --output-on-failure
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 /workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-sanitized --output-on-failure
python3 scripts/check_automix_native_apks.py --readelf /workspace/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf --zipalign /workspace/android-sdk/build-tools/36.0.0/zipalign --output docs/benchmarks/phase32-native-executor/apk-packaging.json app/build/outputs/apk/arm64Gms/debug/app-arm64-gms-debug.apk app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
```

The JVM test tasks actually executed and were not restored FROM-CACHE. Unchanged compile/native tasks could remain UP-TO-DATE. Native tests reused unchanged already-built source-compatible host binaries; packaging validates the actual current APK libraries. No fresh native algorithm/build change is claimed.

## Device and Phase 3.3 gates

See [AUTOMIX_NATIVE_DJ_EXECUTOR.md](../../AUTOMIX_NATIVE_DJ_EXECUTOR.md) for exact 14-test and previous-24 PowerShell commands, private local listening, safe logs, cleanup and optional acoustic measurements. Production remains legacy. Source/runtime reports never imply device success, post-EQ clipping safety or perceptual quality. Real service focus/routing/MediaSession handoff and real-song listening require deliberate integration/validation before activation. No public APK release or automatic merge is authorized.

A final malformed-plan regression failed before repair: changing a valid 32-pulse plan to a 0.5s overlap was wrongly admitted. Admission now verifies supported pulse counts, overlap/tempo consistency, the recomputed drift model and matching rank summary. The earlier full 405+405 suites passed after repair; the earlier failure is not hidden or counted as a passing test. Valid native/virtual numerical results remain identical across the saved completed runs, excluding elapsed host execution times.


## Owner-reported Android startup failures — device repair pending

The owner supplied three failures from the new execution suite: quiet-reference gain,
pause/resume and extended handoff all terminated in `EXCESSIVE_DRIFT` before their main
assertions. First/maximum player-clock offsets were 125004/125004, 119004/129004 and
139163/139163 microseconds respectively. Command lateness was only 3741, 7741 and
6474 microseconds. Exact tested commit/full XML totals were not supplied. These reports
are real device evidence and supersede any suggestion that cloud success proves execution
correctness. Production remains legacy; **hold merge pending a measured repair and device
regression passes**.

A new JVM characterization reproduces all three offsets and requires safe rejection,
without changing the 120ms limit or the existing 14 Android tests. Both full JVM tasks
actually reran: **406+406 passed, zero failures/errors/skips**, including the host JNI tests.
Both instrumentation APK variants compiled the additional separate one-test startup probe;
this probe measures two seconds per fixture with incoming muted and does not approve a mix.
The subsequent owner speaker probe (below) shows the offset persists past one second; the measured-lead repair still needs device verification.
There is no claimed synchronization repair or new successful Android runtime result.
See the architecture document for the exact probe/report retrieval commands.

Latest commands (in addition to the earlier unchanged native/packaging checks above):

```bash
source /workspace/onboarding/activate.sh
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest "-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so" "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
```

The final diagnostic source was separately recompiled after simplifying its local nullability;
no production/native source changed, so unchanged native CTests and APK payload checks
were not redundantly rerun for this test-only addition.


## Measured-start correction — implementation f1e10472

The owner supplied two additional speaker probe summaries with 97 samples each. Standard
startup first advanced at 147883us and had 116004–130004us offsets during seconds 1–2;
extended startup first advanced at 137686us and had 111163–124163us tail offsets. Full
values and provenance are in `device-startup-observations.json`. These are player clocks,
not captured acoustic latency. Waiting for clock settling alone does not reliably satisfy
120ms. None of these device numbers is hardcoded into the implementation.

The experimental adapter can now receive a fresh, bounded observation of the outgoing
play command and initial position progress. The coordinator predicts an early incoming
command, still starts the source at its selected cue, keeps incoming gain zero until the
original fade anchor, and validates actual separation against both original anchors.
`STARTING` separates muted preparation from the planned overlap. The original 120ms
limit and 40ms command-lateness limit remain; measured drift never subtracts the prediction.
No observation defaults to zero lead. Source/generation, age, duration, progress, overflow
and prediction bounds reject stale/malformed input. No analysis cache or native code changed.

Twelve new deterministic JVM methods cover this policy. Selected verified virtual cases:

| Predicted command lead | Simulated incoming startup delay | Verified first offset | Result |
| --- | --- | --- | --- |
| 90ms | 90ms | 0 | COMPLETED, full 16s progression, one promotion |
| 120ms | 120ms | 0 | COMPLETED, full 16s progression, one promotion |
| 140ms | 140ms | 0 | COMPLETED, full 16s progression, one promotion |
| 120ms | 135ms | 15ms | COMPLETED within unchanged bounds |
| None | 130ms | 130ms | EXCESSIVE_DRIFT; outgoing continues |
| Incorrect early/late prediction | 0ms / 170ms | Absolute offset >120ms | EXCESSIVE_DRIFT, no promotion |

These are asserted virtual-clock results, not new Android performance measurements. An
initial regression failed before the correction because the old scheduler waited for the
original anchor even with a supplied observation. Focused testing also exposed interruption
of an unpresented early start: resume now continues outgoing and explicitly cancels with
STARTUP_INTERRUPTED, rather than fabricate retained alignment. Observed pre-anchor playback
can pause/resume with its measured original relationship intact.

Both **full suites actually reran: 418 GMS + 418 FOSS passed, zero failures/errors/skips**,
including 16 actual host-JNI methods per variant. Both app/test APK variants assembled;
14 runtime test annotations and the new stricter maximum-drift/dispatch assertions were
inspected in GMS test DEX. All four native ABIs, ELF/ZIP16K alignment, symbols and notices
pass on the newly assembled APKs; native payload hashes are identical to Phase 3.1.
Nine native-plan/virtual results and all 36 signal rows match the earlier run exactly,
excluding elapsed host runtime; updated raw reports are committed. Native CTests were
not redundantly rerun for this Kotlin-only correction. No device repair pass is claimed.

Latest full command:

```bash
source /workspace/onboarding/activate.sh
export TINIUM_PHASE32_REPORT_DIR=/tmp/phase32-measured-start-final-report
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest "-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so" "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
```

The original 14 execution tests retain all assertions; the extended test adds maximum
drift and prediction-relative command checks. The harness measures actual outgoing
startup each attempt, never copies virtual delays into real playback. Awaiting the
owner's full execution regression on the speaker route; the prior 24 device regressions
and acoustic/listening/output-route evaluations remain required before activation.
