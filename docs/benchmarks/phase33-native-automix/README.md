# Phase 3.3 validation evidence

## Current reconciled validation

Reviewed implementation: **`84a97cba901e1ab98fddfcf95e8fdf941241b4db`**.
PR #23 was open and draft when this documentation reconciliation began. This is ready
for review as an **opt-in experimental integration**, with legacy AutoMix still default.

| Evidence level | Latest result | Provenance / limits |
| --- | --- | --- |
| Complete local JVM suites | **497 GMS + 497 FOSS = 994 passed**, zero failures/errors/skips | Completion-boundary repair `3d667e36`; only documentation/evidence changed through `84a97cba` |
| Host JNI | **17 per variant**, included above | Actual Linux JNI/DSP execution, not Android |
| [CI & Debug Build](https://github.com/yren411/vivi-music-personal/actions/runs/39079630570) | Passed on `84a97cba`, 9m42s | Universal GMS; duplicate push-only job conditionally skipped |
| [Build PR](https://github.com/yren411/vivi-music-personal/actions/runs/39079630450) | Passed on `84a97cba`, 13m07s | Universal FOSS; nonblocking lint, not clean lint |
| Combined Android instrumentation | **Owner reports 49 executed, zero failures/skips** | S25 Ultra / Android 17; decoder 4 + native 14 + planner 6 + executor 14 + service 11, all in one selection |

The final owner report followed the completion-boundary correction. Codex did not run
Android instrumentation; raw Android XML and independently verified installed SHA were
not supplied. No new tests were executed for this documentation-only reconciliation.
The linked CI runs are for the reviewed implementation, not subsequent documentation
commits; check the PR for those commits' own statuses. Compilation, historical CTest,
host JNI and actual owner-run Android execution remain distinct evidence levels.

The historical debugging outcomes below and in JSON are preserved unchanged. Their
then-pending phone/CI statuses are superseded by the current results above for the tested
scenarios. Real-song quality, acoustic sync, Bluetooth, audible EQ and extended phone
resource/reliability validation remain outstanding. See [the activation guide](../../AUTOMIX_NATIVE_DJ_ACTIVATION.md)
for the exact combined 49-test command and private listening workflow.

## Historical initial implementation checkpoint

Application implementation commit: `d7d9c40f` (see full hash in `validation.json`).
Baseline: `f3c96b1adb7607dbf71bb4c41933010fecb47fc0`, merged PR #22.
Environment: Linux x86_64, Java 21, Gradle 9.3.1, AGP 9.1.1, SDK 37,
NDK 27.0.12077973. No Android runtime or permitted real-song corpus was available.
This is engineering evidence for an **opt-in experiment**, not production readiness.

## Historical executed and unexecuted checks

| Evidence level | Result | Limits |
|---|---|---|
| Complete Arm64 GMS Debug JVM suite | 456 passed; 0 failed/errors/skipped | Actual executed task, not a cached zero-test run |
| Complete Universal FOSS Debug JVM suite | 456 passed; 0 failed/errors/skipped | Actual executed task; includes existing updater, effects and AutoMix tests |
| Real host-JNI tests | 17 per variant, included above | Linux native library; not Android JNI/device playback |
| New JVM service/contracts/source tests | 35 per variant, included above | Deterministic virtual-player boundary, generated PCM, shared worker budget |
| Universal FOSS Debug lint task | Completed successfully in 3m57s | Existing nonblocking findings remain; details below |
| Native CTest | 10/10 passed, 21.61s | Existing unchanged DSP and provenance tests rerun |
| ASan/UBSan/LSan native CTest | 10/10 passed, 75.81s | Host memory-safety checks; no Android memory measurement |
| Arm64 GMS + Universal FOSS debug app and test APKs | Built successfully | Incremental intermediates reused; not installed/executed |
| Arm64 GMS + Universal FOSS release Kotlin | Compiled successfully | No release APK assembled, signed or published |
| New service Android test methods | 11 present in actual test-APK DEX; compiled for both variants | **0 executed in this environment**; failures/skips unknown |
| Previous 38 Android methods | Source files unchanged | Earlier owner passes are historical, not this candidate's runtime result |
| Native ABI and APK inspection | Four actual packaged JNI ABIs; ELF and ZIP 16KB checks passed | Packaging does not establish runtime compatibility |
| Real songs / acoustic sync / audible EQ / phone resources | Not tested | No invented ratings, battery results or phone timings |

The full validation command completed in **3m34s**, with **52 tasks executed and
470 up-to-date**. JVM test tasks executed. Intermediate compilation/package reuse is
explicit in `validation.json`; it does not imply tests ran on Android.

`lintUniversalFossDebug` completed under the repository's unchanged `abortOnError=false`
policy. Its report contains 79 error-severity findings, 777 warnings and 5 hints; this is not
a clean lint report. Every primary error source line is present in the starting baseline
(including the existing resource lookup in PlayerDiagnostics); no baseline lint rerun is
claimed. No suppression or lint/workflow configuration was changed to obtain success. This local lint
run predates the isolated source-pin follow-up; final-commit CI lint is reported on the PR.
Command: `bash gradlew :app:lintUniversalFossDebug "-PautomixDecoderTests=true"
--no-configuration-cache --no-daemon --max-workers=4 --console=plain`.

## Reproduction

After configuring Java 21/Android SDK/NDK and building the existing real host JNI library
as described in [Phase 2.3B](../../AUTOMIX_BITCHORD_ANDROID_INTEGRATION.md):

```bash
export TINIUM_PHASE33_REPORT_DIR=/tmp/phase33-final-report
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest \
  :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug \
  :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest \
  :app:compileArm64GmsReleaseKotlin :app:compileUniversalFossReleaseKotlin \
  "-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so" \
  "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
```

No host-JNI test is counted as an Android pass. Without that property, the optional host-JNI
suites report skips rather than inventing native results. Prior native commands rerun here:

```bash
/workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-build --output-on-failure
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  /workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-sanitized --output-on-failure
```

See the earlier prototype document for reproducible CMake configuration/build commands.
Native C++/JNI sources did not change in Phase 3.3; their historical BPM characterization
is not rewritten or presented as new real-song evidence.

## Controlled service-coordinator execution

[`service-coordinator-host-jni.json`](service-coordinator-host-jni.json) is generated by
`NativeServiceJniHostTest`, using corrected PCM capture, **real native DSP**, real native
planning and the new service coordinator. Its playback decks are virtual:

| Measurement | Actual result |
|---|---:|
| Outgoing / incoming estimated BPM | 128.079251 / 128.084614 |
| Outgoing cue | 148,139,320us |
| Incoming cue | 336,689us |
| Planned overlap | 29,991,569us |
| Observed virtual common progress | 30,000,000us |
| Queue promotions | 1 |
| Decoded windows | 2 |
| Content gains, outgoing / incoming | 0.968973 / 1.0 |
| Host elapsed for this test's pipeline | 180,527,518ns (~181ms) |

This single host elapsed value is not a stable performance benchmark, MediaCodec runtime,
Android player start time or phone CPU/battery measurement. It contains no network source.

Other deterministic coordinator tests exercise rejection, genuine legacy fallback, source
failure, queue replacement, blocked-analysis cancellation, mode rollback, pause/resume,
volume forwarding, preparation failure, active player failure, missing actual duration,
three-failure session suspension, marker invalidation and bounded private diagnostics.
Worker tests enforce maximum active work of 1/2/4 across typed pools and interrupt waiting
obsolete jobs. Source tests reject old publication epochs and same-ID source replacements. Source-pin tests additionally preserve transition-time
rendition identity and release it for normal URL refresh after promotion/disposal.
No arbitrary sleeps, relaxed alignment thresholds or removed cache-identity assertions were used.

## Resource bounds and packaging

The following are **array-size calculations**, not peak process RSS or Android measurements:

- A 32s canonical mono Float32 window is `32 × 22050 × 4 = 2,822,400` bytes.
- Two canonical windows total 5,644,800 bytes before object overhead.
- Source-rate mono Float32 capture at 48kHz/96kHz can require 6,144,000/12,288,000 bytes per
  32s window. JNI can additionally copy canonical PCM and allocate internal 11,025Hz data
  (~1,411,200 bytes), plus existing DSP working arrays.
- Four simultaneous workers can multiply transient decode/native memory; no measured Android
  peak is available. Pair analysis submits only two windows, but canceled work may unwind.
  The shared service budget limits actual concurrent work to the selected 1/2/4, never higher.
- Typed feature caches remain 64 entries each (up to 128 compact feature entries after both
  paths have been used), with no raw PCM retention. Each worker queue remains bounded at 8.
  Authorized source handles are bounded at 8. Diagnostics retain 30 attempts × 128 events,
  with at most one player observation per recorded native event.
- Existing 32s decode-window, 3MiB remote / 12MiB fully cached read, duration and retry limits
  are unchanged. The service requests only the immediate next track. No whole-song download
  or additional analysis executor is introduced.

[`apk-packaging.json`](apk-packaging.json) inspects actual archive bytes, not flavor names.
Both debug variants package arm64-v8a, armeabi-v7a, x86 and x86_64 JNI binaries. All four JNI
SHA-256 values exactly match Phase 3.2. License assets are byte-checked. The final archives
are 76,816,015 bytes (Arm64 GMS) and 73,622,528 bytes (Universal FOSS). This is **not a controlled
before/after total-size delta**: DEX, dependency, signing and alignment differences are not
isolated. No native payload growth was introduced.

```bash
python3 scripts/check_automix_native_apks.py \
  --readelf /workspace/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf \
  --zipalign /workspace/android-sdk/build-tools/36.0.0/zipalign \
  --output docs/benchmarks/phase33-native-automix/apk-packaging.json \
  app/build/outputs/apk/arm64Gms/debug/app-arm64-gms-debug.apk \
  app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
```

## Device regression and remaining listening evidence

Use the exact **49-test combined** or separate **11-test** and **38-test** commands in
[the activation guide](../../AUTOMIX_NATIVE_DJ_ACTIVATION.md).
The new tests bind actual MusicService and real configured Media3 players; one requires a
substantial real overlap, promotion and active audio-session ownership. Focus and unexpected
EOS callbacks are injected to validate forwarding, not OS arbitration or truncated codecs.
Keep all failures, including format-specific startup drift and legitimate plan rejection.

The v4 executor retains the v3 ten-second startup-observation expiry, which means long
songs commonly reach their cue with **zero prediction lead**. Independent outputs may exceed the 120ms guard and fall
back. This limitation must be evaluated, not hidden by a wider tolerance or stale device delay.

At the original evidence commit, owner feedback had not yet been supplied; see the
subsequent hang report below. Real-song categories, route/EQ comparisons,
background/controls testing, subjective negative feedback and safe Stats export are described
in the activation guide. No acoustic, Poweramp audibility, streaming success, ordinary-listening
CPU, memory or battery claim is made. A passing generated-media test alone is insufficient for
broader activation or public redistribution clearance.

## Historical owner failures and targeted repairs

These chronological checkpoints retain what was known at the time. None of their
pending device instructions is a current blocker after the final owner-reported pass;
individual commands remain optional troubleshooting tools. Failed measurements and
original JSON statuses are not rewritten.

### Owner-reported hang and test-harness follow-up

After the initial evidence above, the owner reported no completed service tests after roughly
seven minutes on one attempt and three minutes on another, with audio/crackles and no returned
filtered logcat messages. The method, main-thread state and acoustic cause remain unknown.
These are unfavorable owner observations, not measured runtime/quality results or passes.

The test-only follow-up removes unbounded main-thread/global-idle waits, bounds preference
operations, preserves original failures during cleanup, and emits private last-stage/filtered
thread diagnostics. It does not alter MusicService, DSP, timing gates, gains or functional
playback assertions. See `harness-hang-followup.json` for the follow-up's actual host checks,
and the activation guide's optional single-test/ADB troubleshooting instructions.

### Subsequent device evidence: stale fixture bytes

The owner reported the single legacy-default test completed on its first attempt with the
bounded driver. The codec case then returned a sanitized assertion instead of a silent hang.
It showed old 40s/22,050Hz mono WAV content behind the 30s codec assets' reused cache keys;
no native plan or mix started. `fixture-cache-followup.json` records the numerical evidence,
independent ffprobe asset checks, fixture-key repair and actual host validation. Neither this
legacy smoke pass nor the rejected codec attempt is counted as a successful native service mix.

### Subsequent device evidence: admission before output readiness

The codec rerun after cache isolation showed the actual 30s AAC/FLAC source formats,
but native admission rejected at 391ms as `UNSUPPORTED_PLAYER_CONFIGURATION`. There
was no native analysis, plan or mix. The generic reason cannot identify the individual
false capability condition. The initial-output admission race was reproduced on the
JVM and repaired without changing the legacy gate or allowing unsupported processors.
See `output-readiness-followup.json` and the activation guide. A device rerun was
required then; this rejected attempt itself is not counted as a successful transition.

### Subsequent device evidence: FLAC sub-frame PTS rejection

The owner's next codec test reached native JNI for outgoing AAC (128.0922879 BPM) after
the output-readiness fix, but incoming FLAC conversion failed with SOURCE_GAP. Its frame
counts and codec end timestamp reproduce a ~5.8us negative PTS offset being converted into
an artificial whole-frame drop. `flac-frameclock-followup.json` records the failing pre-fix
PCM replay, the v4 frame mapping repair, version/cache safeguards and actual validation.
No native plan, mix or successful service end-to-end execution is counted from that report.

### Subsequent unfavorable device evidence: v4 full-window EOS

The owner's next v4 attempt still failed. Outgoing AAC reached native analysis, while
FLAC output ended 5,112 source frames short of the original file, followed by one capture
overlap drop. No native plan/mix started. The earlier one-anomaly PCM replay was useful
but did not verify the complete device PTS stream or EOS behavior. See
`flac-eos-diagnostic-followup.json` for exact counts, independent ffprobe frame inspection,
new input/output counters and the then-pending silent physical-device diagnostic. No claimed
full-window fix, acoustic improvement or service Android pass is made from host tests.


### Owner EOS probe and v5 corrected FLAC packet route

The owner's `probe=v1` finally identifies `audio/flac`, `c2.sec.flac.decoder`, 267
arbitrarily chunked packets and trailing zero PTS. All 835,064 compressed bytes reach
the codec, but only 1,317,888 PCM frames emerge. The source has 288 FLAC frames and
1,323,000 samples. This excludes a missing compressed byte range in that attempt;
the exact vendor codec truncation mechanism is not proven.

The v5 repair uses the already-available Media3 frame parser plus platform software
FLAC decoding for corrected compressed FLAC only. Genuine host parser tests establish
all 288 frames, complete payload hash/order identity, the short final frame, bounded
random seeking, cancellation and metadata/frame allocation guards. Exact v4/v5 cache
object identity/isolation and historical admission rejections are tested too. See
`flac-packetization-followup.json` for test/build results and limits. The upgraded
silent Android probe still demands complete actual decoded PCM; no threshold/window
is weakened and no output is fabricated. It now reports `probe=v2` and preprocessing
v5. Its phone run and subsequent service execution were pending at this checkpoint;
the owner subsequently reported both passing. Legacy is unchanged; host parser results
are not substituted for Samsung MediaCodec execution.

V5 source: `9a4e5bf9b806a308fb52695c047f2351d81a0177`. Recovery validation explicitly executed
both complete JVM suites with rebuilt real host JNI: 487 tests per variant, zero
failures/errors/skips. App/test APK outputs and lint were confirmed up-to-date;
packaging checks executed again on both APKs. No cloud Android runtime tests ran.
See the JSON's `recovered_environment_verification` for exact commands and distinctions.


### Callback reentrancy, retained histories and successive completion

The later full service class exposed six failures after isolated codec success.
`service-suite-callback-followup.json` preserves that checkpoint: a retained service
recorder could satisfy another test's completion wait, and pause/resume callbacks could
reenter playback commands. Test-only session/attempt scoping and disabled-routing/queue
barriers isolate generated fixtures without clearing the production recorder. Executor
v4 publishes control state before commands, guards reentry and accepts the primary's
already-applied play request only during explicit resume. Two regressions failed before
repair. That source checkpoint executed **496 tests per JVM variant**, including real
host JNI; it is not the final 497-per-variant count.

The next owner run left only the successive-transition one-output assertion failing.
`successive-completion-followup.json` retains the exact failure and repair: legacy
promotion occurs at fade start while two decks are legitimately owned, whereas native
promotion occurs at overlap completion. The test now waits for actual transition
ownership to become idle, then independently retains the original exactly-one-output,
queue, playback and volume assertions. It does not wait for the output count to become
one, extend the deadline or change production release behavior. A new real legacy
controller regression brings the complete suites to **497 + 497 = 994**.

The owner then reported the targeted successive test passing and finally **all 49**
combined Android regressions passing with zero failures/skips. Both required workflows
also passed on `84a97cba`. These later results supersede historical pending statuses,
without converting host measurements into phone data or erasing the unfavorable cases.
No real-song ratings, acoustic alignment or audible EQ result is implied.
