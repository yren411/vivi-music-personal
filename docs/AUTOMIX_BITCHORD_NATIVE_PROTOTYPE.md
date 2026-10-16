# Phase 2.3A — standalone BitChord native DSP prototype

## Scope and source baseline

This phase implements and executes the real BitChord native rhythm algorithm in
an independently buildable AGPLv3-or-later experiment. It does not integrate it
into Android AutoMix. Tinium starting main is
`1747635132df52506169801a70d926d9c8d0c44d` (merged PR #18).
The source of truth is BitChord revision
[`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`](https://github.com/kushagrasinghx/BitChord/tree/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/native/analyzer).
There is no deviation from that pin.

The standalone project is [experiments/bitchord-native-analysis](../experiments/bitchord-native-analysis/README.md).
It builds with CMake and a C++17 compiler without Tinium, Gradle, Android, lyrics,
JNI, ONNX, third-party FFT libraries or model assets. The containing repository
is an aggregate here: no app build references, includes or links the experiment.
A source-only snapshot of that directory contains its build source, notices,
license and tests. It is not a legally cleared combined Android application.

The owner's Musixmatch development-permission assumption is accepted for this
task. Public redistribution permission for a future combined application remains
an external prerequisite. No lyrics code, settings or licenses were changed,
and the prior Musixmatch investigation was not reopened.

## Source inventory and algorithm fidelity

| File within experiment | Source/change |
| --- | --- |
| `vendor/bitchord/tempo_analysis.cpp` | Byte-identical pinned upstream tempo detector |
| `vendor/bitchord/resampler.cpp`, `resampler.h` | Byte-identical upstream band-limited converter |
| `vendor/bitchord/audio_analysis.h` | Original notice, exact `TempoResult` and `AnalyzeTempo` declarations only |
| `cmake/ControlledSources.cmake` | New, hash-anchored generation of controlled function names and loop checkpoints |
| `include/tinium/analyzer.h`, `include/tinium/control.h`, `src/controlled_dsp.h` | New bounded API, cancellation and declarations |
| `src/analyzer.cpp`, `src/cli.cpp` | New validation/status/timing wrapper and JSON CLI |
| `tests/`, `tools/` | New deterministic tests, generators, metrics, benchmark and verification tools |
| `COPYING`, `NOTICE.md`, `provenance.json` | Complete AGPLv3 text, attribution, exact revision and SHA-256 inventory |

No key, structure or spectrogram implementation is
imported. The source hashes and Orchard/BitChord notices are checked by CMake
and a provenance test. Modified generated files carry a dated adaptation notice.

Upstream `AnalyzeTempo` uses Hann-windowed 512-point FFTs with a 128-sample hop;
positive log-magnitude spectral flux; local-mean subtraction, peak normalization
and square-root expansion; energy-normalized autocorrelation with metrical
priors; half/double-lag comparison; fractional parabolic lag interpolation;
onset-comb phase scoring; two-pass phase/interval tracking; and bass-accent
selection of a four-beat downbeat offset. It adds its own FFT-center correction.
These are actual imported algorithms, not a new integer-lag imitation.

`ControlledSources.cmake` threads a per-call control argument through helpers
and inserts checks inside FFT processing, normalization, correlation, phase
search, beat tracking, resampler kernel construction and output loops. Every
replacement checks its expected count. It changes no detector arithmetic,
constants, thresholds, priors or confidence weights. Original functions are
compiled separately into the reference test/CLI only. Pure native parity tests
compare every raw result field and array; benchmark comparisons additionally
compare the complete public semantic result after the same validation wrapper.

## Interface, time coordinates and input policy

```mermaid
flowchart LR
  PCM[Bounded mono Float32 window] --> API[Request + per-call Control]
  API --> Convert[Optional original sinc conversion to 11025 Hz]
  Convert --> DSP[BitChord tempo DSP with checkpoints]
  DSP --> Validate[Finite, bounded, ordered result validation]
  Validate --> JSON[Result / JSON benchmark]
```

`analyze(Request, Control)` synchronously borrows a stable, finite mono Float32
vector, explicit 8,000–96,000 Hz rate, separate nonnegative `window_origin_us`
and optional explicit audible-start hint. Default audible start is zero; tests
do not supply ground-truth onset phase. Maximum input is 32 seconds / 3,072,000
frames. Origin overflow, duration, channels, nonfinite samples and malformed
results are rejected. A 10-second hard budget bounds processing even without a
caller deadline; an earlier caller deadline wins. No threads are spawned.

Both output arrays contain **seconds relative to sample zero of the supplied
PCM window**. Origin is metadata, never silently added. Future absolute beats
must add that origin exactly once. Input and DSP sample counts/durations/rates
are exposed separately; DSP duration is computed from the actual converted
sample count. The prototype accepts already valid continuous PCM: it cannot
infer missing source packets from a raw float array. Phase 2.3B must validate
`DecodedWindow` coverage before invoking it, not turn unknown/partial coverage
into complete evidence.

The wrapper validates geometry before trimming only known trailing events that
upstream places beyond the window by its inclusive endpoint and FFT-center
correction. It reports the omitted count. It never sorts malformed output,
phase-rotates beats, synthesizes missing beats or extends a requested interval.
Exact silence receives `NO_EVIDENCE`; its raw detector reading remains visible
for auditing. A technical failure is distinct from a valid but uncertain reading.

`native_confidence` is the original clamped score:
`0.35 * selected_combined_tempo_score + 0.35 * best_phase_score +
0.30 * max(0, candidate_separation)`. The tempo score itself includes correlation,
double-lag support and a prior. This is not a calibrated probability and has no
validated conversion to the legacy Kotlin beat-confidence scale. There is no
separate calibrated phase/downbeat probability. Downbeats are an assumed
four-beat bass-accent heuristic, not meter recognition, phrase detection or
Beat This! output. Half/double BPM values in JSON are **unscored metrical
hypotheses**, not independent measurements; benchmark octave labels use known
fixture truth and never rewrite the detector reading.

## Sample-rate decision

Two explicit paths are measured on the same input files:

- `resample11025`: use the pinned Blackman-windowed sinc converter to 11,025 Hz,
  then the pinned detector. Identity rate borrows the existing input.
- `direct`: run the exact detector at the supplied rate. Its sample-count FFT
  and hop remain fixed, changing frequency/time resolution with sample rate.

The internal converter has 32 sinc zero crossings, 512 table positions per input
sample, cutoff at the smaller input/output Nyquist, normalized taps, clamped edge
support and `floor(input_frames * output_rate / input_rate)` output frames.
It is symmetric/offline, not a causal filter with an added timeline delay.
Output sample `i` maps to `i/output_rate`; no learned or truth-fitted shift is
applied. Edge support is synthetic filter support, not source coverage. This is
an **additional native-internal conversion**, not a replacement or relabeling
of Phase 2.2's `sinc64-blackman-mono-22050-v2` decoder preprocessing.

Unit tests check DC gain at eight rates, identity conversion, impulse peak at the
same physical time, passband RMSE <0.001 for a 0.5-peak 1 kHz tone, and residual
RMS <0.0001 for an 8 kHz tone downsampled from 22.05/44.1/48/96 kHz. These are
passing engineering bounds, not Android audio-quality measurements. At 11,025 Hz
the detector's half-window is 23.22 ms and hop 11.61 ms; at 22,050 Hz they are
11.61 ms and 5.80 ms. The measured beat error includes its FFT-center treatment.

The initial recommendation is the **explicit 11,025 Hz internal path**. It was
more consistent across rates/patterns, though slower because of conversion.
Direct 22,050 Hz remains an experimental comparison rather than an equivalent
substitute. Neither path is activated in Tinium.

## Deterministic corpus and measurement method

`tools/fixtures.py` creates 45 mono Float32LE files from versioned parameters and
a deterministic seed-411 LCG. It includes seven BPM values with kick/backbeat/
offbeat patterns and distinct onset phases; hats; quiet/tonal beds; 0.5/2-second
leading silence; two gains; 120→126 BPM drift; eight input rates; six negative
controls; and 0.2/1/5-second windows. Reference onsets are quantized to actual
input frames. Hats/offbeat accents are not counted as quarter-note truth beats.
No copyrighted recording, internet audio or source URL is used.

`tools/metrics.py` performs monotonic one-to-one matching within 30 and 70 ms.
There is no best-phase rotation, octave normalization or beat-grid repair.
The explicitly excluded boundary is `min(1 second, duration/10)` on each side.
Median/p95 absolute error and first-to-last matched signed drift accompany
precision/recall/F1; matching error alone cannot describe missing beats. Negative
controls have no true beat reference: their F1 is unavailable, not a perfect
score. Every raw result and metric is in `results/benchmark.jsonl`.

The optional Java comparator loads the actual current Kotlin analyzer using
20 selected nonrestricted analyzer/model classes in a temporary JAR, in a
separate process. It does not load lyrics/Android classes or link with native
DSP. A Gradle current-output check succeeded before those classes were used
(all 121 tasks up-to-date); class/source fingerprints are committed. Both
analyzers receive byte-identical input PCM and the same supplied sample rate.
This isolates detector differences; it does **not** compare old versus corrected
Android decoding. Legacy windows under four seconds are explicitly unsupported.

### New measured 30-second kick fixtures

All rows below use 22,050 Hz PCM, onset phase 0.137 seconds, the same synthesized
material for Kotlin/native, and native `resample11025`. Confidence columns are
native heuristics only. Counts/F1 exclude the documented boundary margin.

| True BPM | Current Kotlin BPM | Native BPM | Absolute BPM error | Native score | Beats expected/detected | F1 30/70 ms | Median / p95 error ms | Native ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 90 | 90.909 | 90.045 | 0.045 | 0.915 | 42/42 | 1.000/1.000 | 6.98 / 7.62 | 168.8 |
| 107 | 107.143 | 107.094 | 0.094 | 0.913 | 50/50 | 1.000/1.000 | 6.90 / 8.02 | 156.4 |
| 120 | 120.000 | 120.035 | 0.035 | 1.000 | 56/56 | 1.000/1.000 | 6.39 / 7.34 | 173.6 |
| 128 | 63.830 | 128.094 | 0.094 | 0.920 | 60/60 | 1.000/1.000 | 6.60 / 7.60 | 176.4 |
| 140 | 69.767 | 139.981 | 0.019 | 0.821 | 65/65 | 1.000/1.000 | 5.76 / 6.15 | 161.5 |
| 150 | 150.000 | 149.926 | 0.074 | 0.645 | 70/70 | 1.000/1.000 | 5.70 / 5.99 | 154.1 |
| 174 | 176.471 | 173.903 | 0.097 | 0.707 | 81/81 | 1.000/1.000 | 5.81 / 6.06 | 211.2 |

These are new measurements, not the historical Phase 1 table. The old fixtures
produced different Kotlin and native values, including a native 174→87 BPM
reading. The new simple kick at 174 is correctly read by the 11,025 Hz path;
other patterns still expose that half-time ambiguity.

### Whole-corpus findings and inconvenient results

There are 36 non-short rhythmic fixtures per path, six negative controls and
three short windows (45/path, 90 cases). The 11,025 Hz path gives 34/36 rhythmic
BPM estimates within 2%, one half-time and one other error. Direct input rates
give 29/36 within 2%, two half-time and five other errors. Median beat F1 at
both tolerances is 1.0 for each path, but this median must not hide failures:

| Fixture/path | BPM | Native score | F1 within 70 ms | Finding |
| --- | --- | --- | --- | --- |
| 107-offbeat/direct | 71.324 | 0.819 | 0.405 | other |
| 128-offbeat/direct | 85.350 | 0.791 | 0.400 | other |
| 174-backbeat/resample11025 | 87.077 | 0.715 | 0.667 | half_time |
| 174-offbeat/resample11025 | 69.695 | 0.607 | 0.261 | other |
| 174-backbeat/direct | 86.967 | 0.849 | 0.667 | half_time |
| 174-offbeat/direct | 116.018 | 0.803 | 0.397 | other |
| 128-rate-44100/direct | 85.312 | 0.411 | 0.168 | other |
| 128-rate-96000/direct | 115.424 | 0.367 | 0.258 | other |
| 120-to-126-drift/resample11025 | 123.116 | 0.727 | 0.903 | correct_2pct |

Thus a high score can accompany a wrong metrical interpretation. This is a
functional DSP foundation, not a safe justification for passing those scores
through Tinium's existing 0.65 legacy threshold. No thresholds were lowered.

| Non-rhythmic control (11,025 path) | Raw BPM | Native score | Raw false beat events | Wrapper status |
| --- | --- | --- | --- | --- |
| negative-silence | 117.454 | 0.031 | 55 | NO_EVIDENCE |
| negative-dc | 117.454 | 0.037 | 55 | OK_UNCALIBRATED_EVIDENCE |
| negative-noise | 161.521 | 0.152 | 76 | OK_UNCALIBRATED_EVIDENCE |
| negative-chord | 89.099 | 0.459 | 42 | OK_UNCALIBRATED_EVIDENCE |
| negative-tone | 86.179 | 0.228 | 40 | OK_UNCALIBRATED_EVIDENCE |
| negative-sweep | 72.810 | 0.541 | 34 | OK_UNCALIBRATED_EVIDENCE |

The exact-zero guard is not a calibrated music/rhythm classifier. Stationary
chords/sweeps remain explicit false positives. Tests preserve these raw results
rather than tuning them away. Future confidence validation and real permitted
music are needed before production planning consumes native evidence.

## Reproducibility and validation

Two complete 45-fixture generations have identical manifests and all PCM hashes.
Two full 90-case benchmark runs give the same numerical fingerprint, excluding
runtime/backend name. All 90 cases in each run exactly match the original DSP
through the same output wrapper. This is host/compiler reproducibility, not an
assertion of cross-architecture bitwise identity. Native parity tests also compare
raw upstream/controlled values and resampled arrays before wrapper filtering.

Results are committed in the experiment's `results/`: `fixture-manifest.json`,
`benchmark.jsonl`, `summary.json`, `reproducibility.json`, `performance.json`,
`validation.json`, `android-abis.json` and `legacy-class-and-source-hashes.json`.
No PCM, JAR, native binary, credentials or private track identities are committed.

Host Release CTest: **10/10 passed**, containing eight native suites with
**365 assertions**, 13 Python fixture/metric/CLI tests, and provenance validation.
The same **10/10** passed under AddressSanitizer, UndefinedBehaviorSanitizer and
LeakSanitizer. No suppressed sanitizer findings. Native tests include malformed
input/output, deterministic parity, actual rhythms, uncertainty, resampling,
inside-DSP cancellation, deadline/exception mapping, stale-evidence clearing and
four concurrent shared/independent windows. Injected `bad_alloc` verifies error
mapping, not recovery from an actual operating-system OOM kill.

## Performance, memory and cancellation

| Source rate | Window seconds | 11,025 path median ms | Direct median ms |
| --- | --- | --- | --- |
| 44100 | 5 | 48.86 | 26.35 |
| 44100 | 16 | 172.13 | 74.31 |
| 44100 | 24 | 260.79 | 109.32 |
| 44100 | 30 | 330.74 | 131.07 |
| 44100 | 32 | 334.20 | 134.43 |
| 48000 | 5 | 59.33 | 23.03 |
| 48000 | 16 | 194.96 | 73.14 |
| 48000 | 24 | 331.82 | 121.68 |
| 48000 | 30 | 350.07 | 149.53 |
| 48000 | 32 | 395.49 | 159.19 |

Each cell is the median of three trials (60 calls total). Maximum analysis time
in this window matrix was 399.68 ms. Across the broader 90-case corpus the
11,025 path median/p95/maximum was 171.80/343.49/776.00 ms; the direct path was
67.13/142.49/326.94 ms. Those groups include different sample rates and short/
negative inputs, so they are descriptive, not a paired speedup claim.

First shared-library load in the host performance process took 1.31 ms (one
observation, affected by host caches). There is no separate global DSP
initialization. Asynchronous cancellation inside the 96 kHz tempo loop measured
109.68 microseconds from the cancel request to join in one host trial. This is
not an Android worst-case bound. The controlled resampler is also interrupted
inside its loops in deterministic checkpoint tests; no PCM is retained afterward.

The four-job process high-water RSS was **29,332 KiB (28.64 MiB)**. That Linux
`getrusage` measurement includes fixtures, test/runtime allocations, sequential
baselines, a shared-input scenario and four independently owned 22,050 Hz /
32-second windows. It is not native-only allocation usage or an incremental
per-worker measurement; it must not be multiplied as a per-worker figure.


Measurements are desktop Linux/GCC 14.2.0 Release, CMake 3.31.6, Python 3.12.14,
JDK 21 for the optional comparator. They are not S25 Ultra CPU/battery numbers.
The repeated-window timings exclude PCM file reading and process startup;
process wall time is separately retained. No per-call allocation instrumentation
or Android power measurement was available.

The API borrows input, creates no independent worker pool and has no mutable
global detector state. Eight/16-bit/Float32 decoding and channel conversion are
outside this mono-float API. Input PCM maximum is 12,288,000 bytes at 96 kHz/32s;
canonical 22,050 Hz input is 2,822,400 bytes and internal 11,025 Hz output is
1,411,200 bytes. At 22,050→11,025, the kernel table is about 262 KB; at 96,000→
11,025 it is about 1.14 MB. These are calculated array payloads, not total peak
RSS. Envelopes, FFTs and tracking vectors add bounded allocations. A four-worker
Android budget still needs measurement with decoder buffers and JVM overhead.
No claim is made that Kotlin worker limits automatically constrain a future
model's internal threads; this DSP itself starts no threads.

Cancellation is checked inside the expensive loops, not merely around the call.
RAII releases vectors on success, exceptions, cancellation and deadline. The
caller owns callback/context lifetime; concurrent calls must use appropriate
thread-safe tokens. The pristine reference implementation is benchmark-only and
cannot interrupt its own computational loops.

## Android readiness and limits

The unchanged app configuration is Java 21, Gradle 9.3.1, AGP 9.1.1, Kotlin
2.3.10, NDK 27.0.12077973, compile/target SDK 37 and minSdk 26. Standalone NDK
builds at API 26 succeeded for **arm64-v8a, armeabi-v7a, x86 and x86_64**, producing
static and shared libraries outside the checkout. NDK `llvm-readelf` verified
all shared ELF load-segment alignments at 16,384 bytes. The arm64 shared test
library is about 852 KiB with statically linked libc++; this is an unstripped
ELF file size, not a measured compressed APK increase.

No Android runtime/emulator/device, JNI, APK assembly or ZIP alignment test ran.
The four Phase 2.2 decoder instrumentation tests were not changed or rerun;
the owner previously reported all four passing on the S25 Ultra. No new Gradle
instrumentation command is claimed for a backend that has no JNI integration.
GMS/FOSS Android app native integration is not part of this standalone build.
No phone listening or real-song performance was tested.

## Build/test guide and exact commands used

Paths below are this cloud host's build directories; replace the CMake prefix
with an installed `cmake`/`ctest` elsewhere. The standalone README also includes
PowerShell host commands. No app release/signing command is needed.

```sh
/workspace/toolchains/native-host/cmake/data/bin/cmake -S experiments/bitchord-native-analysis -B /tmp/tinium-native-build -DCMAKE_BUILD_TYPE=Release
/workspace/toolchains/native-host/cmake/data/bin/cmake --build /tmp/tinium-native-build -j4
/workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-build --output-on-failure -V
/workspace/toolchains/native-host/cmake/data/bin/cmake -S experiments/bitchord-native-analysis -B /tmp/tinium-native-sanitized -DCMAKE_BUILD_TYPE=RelWithDebInfo -DTINIUM_SANITIZERS=ON
/workspace/toolchains/native-host/cmake/data/bin/cmake --build /tmp/tinium-native-sanitized -j4
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 /workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-sanitized --output-on-failure -V
python3 experiments/bitchord-native-analysis/tools/fixtures.py --output /tmp/tinium-native-corpus
python3 experiments/bitchord-native-analysis/tools/fixtures.py --output /tmp/tinium-native-corpus-repeat
python3 experiments/bitchord-native-analysis/tools/benchmark.py --build /tmp/tinium-native-build --corpus /tmp/tinium-native-corpus-repeat --output /tmp/tinium-native-repeat-results --legacy-command /tmp/tinium-native-legacy/command.json
python3 experiments/bitchord-native-analysis/tools/performance.py --build /tmp/tinium-native-build --output experiments/bitchord-native-analysis/results/performance.json
```

The optional comparator preparation followed this existing-output validation:

```sh
source /workspace/onboarding/activate.sh
bash gradlew :app:compileArm64GmsDebugKotlin "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon
python3 experiments/bitchord-native-analysis/tools/build_legacy_baseline.py --classes app/build/intermediates/built_in_kotlinc/arm64GmsDebug/compileArm64GmsDebugKotlin/classes --stdlib /workspace/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.10/8b80e2f80a3b7daa99c16017f91d4c72cd8b31a6/kotlin-stdlib-2.3.10.jar --java-home "$JAVA_HOME" --output /tmp/tinium-native-legacy --repo .
```

For each ABI `arm64-v8a armeabi-v7a x86 x86_64`, the executed cross-build used:

```sh
cmake -S experiments/bitchord-native-analysis -B /tmp/tinium-native-android/$abi -DCMAKE_TOOLCHAIN_FILE=/workspace/android-sdk/ndk/27.0.12077973/build/cmake/android.toolchain.cmake -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release -DTINIUM_BUILD_TOOLS=OFF
cmake --build /tmp/tinium-native-android/$abi -j4
python3 experiments/bitchord-native-analysis/tools/check_android_builds.py --build-root /tmp/tinium-native-android --readelf /workspace/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf --output experiments/bitchord-native-analysis/results/android-abis.json
```

`cmake` in that block was the same explicit host executable above. The checker
runs after all ABI builds, not once for every intermediate missing ABI.

## Phase 2.3B checkpoints — proposed, not implemented

1. Keep production legacy/nearest-time defaults. Add a minimal JNI adapter around
   this validated C++ API, explicit owned cancellation handles, exception/error
   mapping and bounded array copies. Compile/package all ABIs, verify 16 KB ELF
   **and** APK alignment, and test real JNI on Android before enabling experiments.
2. Add a replaceable `AnalysisEngine` adapter consuming only validated contiguous
   Phase 2.2 `DecodedWindow` mono Float32/22,050 PCM. Use actual sample origin and
   actual covered duration. Keep corrected preprocessing ID distinct from legacy;
   include native backend/revision, algorithm adapter version and the additional
   internal resampling path in its cache profile. Native results must never enter
   legacy cache entries or acquire the legacy confidence scale.
3. Return typed musical evidence with tempo/raw score and actual relative-to-
   absolute beat conversion; keep key/vocal/phrase/learned-downbeat evidence
   unavailable rather than fabricate it. Preserve uncertainty as uncertainty.
   Technical load/ABI/output failures may fall back once to the separately
   identified legacy pipeline, never relabel native or partial PCM as legacy/full.
4. Use the existing bounded 1/2/4-worker queue and cache; no second scheduler or
   model threads. Measure combined decoder/JVM/native memory and cancellation on
   the S25 Ultra, cache isolation, generation invalidation and source failures.
5. Repeat the shared corpus through real JNI and corrected Android decoding;
   compare rates, origins, EOS and coverage. Add permitted local real music with
   independently annotated beats and include negative controls. Establish confidence
   reliability before connecting it to a production planner threshold.
6. Only a later approved planner/execution phase should consume this evidence for
   active-to-active/long overlaps, cue planning and gain changes. Existing EQ,
   Poweramp sessions, Media3 decks, settings, marker, Stats and exports remain
   untouched in 2.3A. Roll back experimental selection on JNI crashes, stale cache
   identities, timing mismatch, resource overruns or misleading evidence.

## Completion boundary

Phase 2.3A delivers a real, reproducible, cancellable native prototype with
original-code parity and explicit weaknesses. It does not deliver better audible
Tinium transitions yet. No production analyzer/preprocessing selection, Android
build dependency, playback/lyrics/UI/EQ behavior or root application license was
changed. No APK, signed artifact, release version or release was created.
