# Experimental BitChord native rhythm analyzer — Phase 2.3A

Working standalone C++17 DSP, based on BitChord revision
`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`. **AGPLv3-or-later**;
read [NOTICE.md](NOTICE.md), [COPYING](COPYING) and
[provenance.json](provenance.json). This directory can be built separately.
Phase 2.3B reuses this authoritative source tree through an experimental Android
JNI target; normal AutoMix still does not load or select it. See
[`AUTOMIX_BITCHORD_ANDROID_INTEGRATION.md`](../../docs/AUTOMIX_BITCHORD_ANDROID_INTEGRATION.md).

## Build and test

Requirements: CMake >=3.20, C++17 compiler, a build tool and Python 3.9+ for
host tests/benchmarks (standard library only). No Gradle, JDK, Android SDK,
Python packages, models or external DSP libraries are required for the native
prototype. Tests were executed on Linux/GCC 14; Windows/macOS runtime behavior
has not been tested.

From this directory:

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel 4
ctest --test-dir build --output-on-failure -V
python3 tools/fixtures.py --output corpus
python3 tools/benchmark.py --build build --corpus corpus --output results-local
python3 tools/performance.py --build build --output results-local/performance.json
```

On Windows, use a C++ developer PowerShell with CMake/Ninja/Python available:

```powershell
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel 4
ctest --test-dir build --output-on-failure -V
python tools/fixtures.py --output corpus
python tools/benchmark.py --build build --corpus corpus --output results-local
```

These commands are for the standalone host prototype, not the four existing
Android decoder instrumentation tests. No phone installation is needed here.
Prefer an output directory outside a clean source snapshot when sharing it.

GCC/Clang sanitizer run:

```sh
cmake -S . -B build-asan -DCMAKE_BUILD_TYPE=RelWithDebInfo -DTINIUM_SANITIZERS=ON
cmake --build build-asan --parallel 4
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 ctest --test-dir build-asan --output-on-failure -V
```

## CLI and native API

```sh
build/analyzer_cli --input corpus/128-kick.f32le --sample-rate 22050 --path resample11025 --origin-us 180000000 --deadline-ms 2000
```

Input is **raw little-endian mono IEEE Float32**, not a WAV/MP3/container.
CLI output is one JSON object, schema 1. Exit 0 means a completed analysis
(including `NO_EVIDENCE`); exit 2 means a controlled technical/input failure.
`OK_UNCALIBRATED_EVIDENCE` means geometrically valid output, not reliable music.
`reference_cli` accepts the same arguments and calls the pristine upstream DSP;
it is a benchmark tool and lacks cooperative cancellation inside those original
functions. The regular CLI and API use the controlled implementation.

The C++ API is `tinium::native_analysis::analyze(Request, Control)` in
`include/tinium/analyzer.h`. PCM is borrowed for the synchronous call and never
mutated or retained. Rates 8,000–96,000 Hz, one channel, <=32 seconds and
<=3,072,000 samples are accepted. Inputs must be finite. The API enforces a
10-second hard processing budget or an earlier caller deadline. `Control`
borrows a cancellation callback/context, polled inside conversion/FFT/correlation/
tracking loops. It creates no threads. Canceled results clear musical evidence.
Callers must keep input/context alive and stable until return; concurrently
mutating them is unsupported.

- `resample11025` (default): the actual upstream Blackman-windowed sinc resampler
  converts input to 11,025 Hz, then the actual upstream tempo detector runs.
  At identity rate input is borrowed without a conversion copy.
- `direct`: the same detector receives the supplied rate. Its fixed 512-sample
  FFT/128-sample hop change their time/frequency resolution with rate; this is a
  measured comparison path, not a claim of equivalent accuracy.
- Beat/downbeat arrays are ordered **seconds relative to the supplied PCM
  window**, never absolute track times. `origin_us` is separate metadata.
  A later JNI adapter must add it exactly once. Resampling is symmetric/offline:
  no causal latency is added; sample `i` maps to `i/output_rate`. No phase shift
  is fitted to benchmark truth. The original FFT-center correction is retained.
  This API assumes valid continuous PCM. A future decoder adapter must check
  actual `DecodedWindow` coverage before calling it; raw floats cannot reveal
  missing source packets or establish source coverage.
- Downbeats are bass-accent heuristics assuming four-beat grouping, not learned
  downbeats, meter recognition or phrase detection. Half/double-time hypotheses
  in JSON are unscored alternatives, not measured ambiguity probabilities.
- Upstream confidence is the unchanged clamped combination of tempo score,
  onset-phase strength and competing-lag separation. It is neither a calibrated
  probability nor interchangeable with Tinium's legacy beat confidence.
- Exact-zero PCM receives `NO_EVIDENCE` while retaining raw DSP results for
  auditing. Noise, chords and sweeps can produce false grids and substantial
  scores; they are deliberately reported rather than hidden by tuned gates.

The wrapper validates finite results, bounds, ordering and downbeat membership.
It removes only upstream trailing grid events shifted beyond the window by the
FFT-center correction and reports their count. It does not sort, repair phase
or fill missing musical features. Allocation failures, cancellation, deadlines
and unexpected exceptions become explicit statuses; crashes/OS termination are
not recoverable C++ errors.

## Benchmarks and limitations

`results/` contains machine-readable measurements, not audio recordings:

- `fixture-manifest.json`: 45 deterministic PCM identities, exact generation
  parameters and reference beat positions.
- `benchmark.jsonl`: every fixture/path, native output, one-to-one 30/70 ms beat
  metrics, error, ambiguity and optional same-PCM legacy measurement.
- `summary.json`: aggregate results and numerical fingerprint.
- `reproducibility.json`, `performance.json`, `validation.json`,
  `android-abis.json`: repeated-run, resource, tests and ELF evidence.
- `legacy-class-and-source-hashes.json`: optional comparator provenance.

The 36 non-short rhythmic fixtures include seven tempi, three patterns/phases,
leading silence, amplitudes, drift and eight rates. Six non-rhythmic controls
and three short windows expose limitations. Beat matching is one-to-one with
no phase rotation/octave normalization; boundary exclusion is explicitly
`min(1 second, duration/10)`. Reported drift is between first/last matched beat,
not a substitute for precision/recall when many beats are missing.

The 11,025 Hz path produced correct-within-2% BPM on 34/36 rhythmic fixtures;
one was half-time and one was another metrical error. This does not establish
real-song accuracy. All 90 native cases exactly matched original DSP semantics
after the same boundary-validation wrapper. The native score still produces
false positives on stationary material. No listening, Android runtime, playback,
transition quality or external-EQ validation was performed.

Optional comparison with the **actual current Kotlin analyzer** requires its
existing compiler outputs and JDK 21. `tools/build_legacy_baseline.py --help`
documents paths; it creates a temporary JAR containing selected pure musical-
analyzer classes, never lyrics classes. Pass its generated `command.json` to
`benchmark.py --legacy-command ...`. Native and Kotlin receive identical PCM;
this is not a comparison of Tinium's old preprocessing against the corrected
decoder. Legacy windows shorter than four seconds are reported unsupported.
Historical Phase 1 measurements used different fixtures and are not new results.

## Android cross-compilation only

With NDK 27.0.12077973, repeat for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`:

```sh
cmake -S . -B build-arm64 -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/27.0.12077973/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DTINIUM_BUILD_TOOLS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build-arm64 --parallel 4
```

All four static/shared builds passed. ELF `PT_LOAD` alignment was checked at
16,384 bytes using NDK `llvm-readelf`. This does not verify APK ZIP alignment,
Android execution, JNI or device compatibility. No JNI or APK is supplied.
Future Android integration must preserve backend/preprocessing cache isolation,
coverage validation, bounded workers and the current legacy production default.
