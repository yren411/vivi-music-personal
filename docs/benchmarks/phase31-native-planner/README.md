# Phase 3.1 measured native/legacy planning comparison

Generated PCM only; **desktop JVM**, real BitChord JNI. No songs, listening tests, Android runtime, or production activation. All source PCM is prepared independently through the actual legacy/corrected captures; musical analysis and planning timings are separate in the JSON.

Implementation source commit: `3c4ad9bec62fc6af1d4a16863b5c70d9105b0c42`. Reports live in the following documentation commit.

## Results

31 pairs: {'NATIVE_SUPPORTED_LEGACY_FALLBACK': 12, 'BOTH_PLANS': 2, 'BOTH_FALLBACK': 14, 'NATIVE_SUSPICIOUS_METRICAL_OR_PHASE': 2, 'INSUFFICIENT_COMPARISON_EVIDENCE': 1}. Categories describe proposals and evidence, not listening quality.

| Equal-tempo pair | Legacy BPM out / in | Native BPM out / in | Legacy tier | Proposed overlap (s) | P95 residual (ms) |
| --- | --- | --- | --- | --- | --- |
| same-90-offset | 90.909 / 90.909 | 90.045 / 90.049 | C | 21.324 | 1.521 |
| same-107-offset | 107.143 / 107.143 | 107.094 / 107.099 | C | 17.935 | 1.664 |
| same-120-offset | 120.000 / 120.000 | 120.035 / 120.039 | B | 15.991 | 1.614 |
| same-128-offset | 63.830 / 63.830 | 128.094 / 128.098 | C | 14.991 | 1.707 |
| same-140-offset | 69.767 / 69.767 | 139.981 / 139.942 | C | 27.432 | 1.422 |
| same-150-offset | 150.000 / 150.000 | 149.926 / 149.930 | B | 25.603 | 1.593 |
| same-174-offset | 176.471 / 176.471 | 173.903 / 173.903 | C | 22.072 | 8.436 |

These are fresh measurements on the versioned new corpus, not the historical Phase 1 table. All seven simple-pair whole-window native 30 ms beat F1 values are 1.0. Tempo accuracy alone does not establish a musically suitable transition. Legacy also produces rhythmic plans for 120 and 150 BPM; these cases are retained rather than advertised as legacy failures.

## Every pairing

| Pair | Legacy tier | Native result | Outgoing / incoming cue (s) | Overlap (s) | P95 / final drift (ms) |
| --- | --- | --- | --- | --- | --- |
| same-90-offset | C | ONE_TO_ONE | 150.139 / 0.337 | 21.324 | 1.521 / -1.407 |
| same-107-offset | C | ONE_TO_ONE | 150.139 / 0.337 | 17.935 | 1.664 / -1.526 |
| same-120-offset | B | ONE_TO_ONE | 150.139 / 0.337 | 15.991 | 1.614 / -1.263 |
| same-128-offset | C | ONE_TO_ONE | 150.139 / 0.337 | 14.991 | 1.707 / -1.439 |
| same-140-offset | C | ONE_TO_ONE | 150.128 / 0.325 | 27.432 | 1.422 / -1.373 |
| same-150-offset | B | ONE_TO_ONE | 150.128 / 0.325 | 25.603 | 1.593 / -1.254 |
| same-174-offset | C | ONE_TO_ONE | 150.128 / 0.661 | 22.072 | 8.436 / 7.369 |
| tempo-90-120 | C | INCOMPATIBLE_TEMPO | — | — | — |
| tempo-120-128 | C | INCOMPATIBLE_TEMPO | — | — | — |
| tempo-128-140 | C | INCOMPATIBLE_TEMPO | — | — | — |
| tempo-150-174 | C | INCOMPATIBLE_TEMPO | — | — | — |
| tempo-128-129 | C | ONE_TO_ONE | 150.139 / 0.139 | 3.741 | 28.480 / -28.480 |
| tempo-120-123 | C | EXCESSIVE_DRIFT | — | — | — |
| tempo-87-174 | C | INCOMING_DOUBLE_PULSE | 153.581 / 0.128 | 22.067 | 7.042 / 5.367 |
| tempo-174-87 | C | OUTGOING_DOUBLE_PULSE | 153.925 / 0.139 | 22.068 | 9.506 / -8.587 |
| 174-kick-to-backbeat | C | OUTGOING_DOUBLE_PULSE **suspicious meter** | 150.128 / 0.488 | 22.072 | 14.948 / -13.628 |
| 174-kick-to-hats | C | OUTGOING_DOUBLE_PULSE **suspicious meter** | 150.128 / 0.488 | 22.072 | 15.543 / -14.165 |
| negative-silence | none | MISSING_BEATS | — | — | — |
| negative-noise | C | INCOMPATIBLE_TEMPO | — | — | — |
| negative-chord | C | INCOMPATIBLE_TEMPO | — | — | — |
| negative-sweep | C | INCOMPATIBLE_TEMPO | — | — | — |
| same-negative-silence | none | MISSING_BEATS | — | — | — |
| same-negative-noise | C | SIGNAL_NOT_CORROBORATED | — | — | — |
| same-negative-chord | C | SIGNAL_NOT_CORROBORATED | — | — | — |
| same-negative-sweep | C | SIGNAL_NOT_CORROBORATED | — | — | — |
| leading-18s | C | ONE_TO_ONE | 150.139 / 18.135 | 7.490 | 3.406 / 3.476 |
| drift-3bpm | C | POOR_ALIGNMENT | — | — | — |
| extended-64-pulse-128 | C | ONE_TO_ONE | 148.139 / 0.337 | 29.992 | 1.701 / -1.241 |
| quiet-128-hats | C | ONE_TO_ONE | 150.139 / 0.139 | 14.994 | 0.000 / 0.000 |
| source-48000-44100 | C | ONE_TO_ONE | 150.139 / 0.139 | 14.993 | 0.348 / 0.130 |
| short-window | C | INSUFFICIENT_BEATS | — | — | — |

- Equal noise/chord/sweep pairs can generate matching high-score native grids; PCM corroboration rejects them. Negative kick-to-noise pairs sometimes reject on tempo first; the identical-negative cases prevent that from disguising a missing signal gate.
- 174 BPM backbeat/hats often produce approximately 87 BPM. Their common pulses align, but quarter-note F1 remains about 0.66 and the benchmark explicitly marks them suspicious. No octave correction or best-phase normalization was used.
- The leading-silence case retains poor whole-window precision from extrapolated native beats in silence. The selected later interval is evaluated separately and supported by actual quiet-prefix/transient observations. Both metrics remain in the JSON.
- No generated case here demonstrates legacy intelligent success while native rejects. That absence is not proof the native planner dominates on real music. There is one insufficient-comparison short-window case; its missing legacy analysis is not counted as a native improvement.
- Whole-window and selected-interval precision/recall/F1 at 30/70 ms, timing errors, raw native scores, actual rates, cues, residuals, metrical hypotheses, uncertainties, rejections and runtime breakdowns are in [comparison.json](comparison.json). No actual audio overlap was executed.

## Planning-only performance

Across the comparison corpus: median 0.671 ms, maximum 1.957 ms. These include validation/anchor generation/evaluation but exclude decoding and DSP.

| Workers | Samples | Median / max call (ms) | Candidate attempts per call | Median cumulative Java allocation |
| --- | --- | --- | --- | --- |
| 1 | 8 | 1.161 / 1.210 | 512 | 887352 bytes |
| 2 | 16 | 1.734 / 2.012 | 512 | 887352 bytes |
| 4 | 32 | 1.866 / 5.502 | 512 | 887352 bytes |

The timed cancellation request at checkpoint 40 returned in 82.412 us after the request; this is one desktop observation, not an Android upper bound. A malformed 512-event beat vector was rejected in 68.501 us before candidate enumeration.

Performance inputs are explicit synthetic compact observations at 220 BPM / 32 seconds; they are not native accuracy measurements. No PCM or native DSP is included in the timed sections. Warm-up, shared cloud/JVM contention and GC affect these observations. Allocation counters are cumulative thread-local Java allocation, not peak/live memory. Native/decoder memory and Android thermals were not measured. See [performance.json](performance.json).

## Validation evidence

- Full app JVM suites: **353 GMS + 353 FOSS passed, zero failures/errors/skips** with host JNI configured. Fifteen test methods per variant invoke genuine native DSP (14 dedicated JNI tests plus the real comparison).
- Without the optional host library, those 15 test methods explicitly skip; mathematical, scheduling and legacy tests still execute. Android tests fail if the packaged JNI library is missing. A configured-but-unloadable host library fails the comparison self-check.
- Native CTest **10/10 passed**, and **10/10 passed** under ASan/UBSan/LSan. Native source/provenance are unchanged.
- A separate cross-batch identity regression proves old tickets cannot read/invalidate a new pair.
- Four concurrency/cache methods repeated 50 times: **200 invocations passed**, zero failures/skips. This includes the original unchanged `assertSame` regression plus completed-evidence reuse, shared-pair invalidation and incoming replacement. The repetition uses JUnit method requests on the current Gradle test runtime classpath, without sleeps in the cache assertion.
- GMS/FOSS debug app and instrumentation APKs assembled. Six new planner tests are packaged; 14 native and four decoder test methods are unchanged. **Android runtime execution was unavailable here**. The previous owner-run 18 passes are historical, not execution of these six tests.
- Four native ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) verified in both APKs. ELF LOAD >=16 KB, native ZIP offsets/uncompressed entries, whole-APK `zipalign -P16`, JNI symbols and notice assets pass. See [apk-packaging.json](apk-packaging.json).
- Private harness smoke: one generated local WAV pair, offline ffmpeg conversion and real JNI comparison passed. Existing-output protection and in-checkout output rejection were exercised; generated private filenames/paths are absent from its JSON. No real commercial recording was used.

See [validation.json](validation.json), [concurrency-repetition.json](concurrency-repetition.json) and [reproducibility.json](reproducibility.json).

Numerical fingerprint: `0d55201085714c0798c6bc0081d447f6dc2431c7d259df293afa952e3fa3ab4a`. It matches an independent earlier complete host run after excluding measured timings and session-salted report IDs; the excluded keys/canonicalization are recorded. No timestamp or octave rotation was applied to beat metrics.

## Reproduce

Activate your JDK 21 / Android SDK as usual. Cloud commands actually executed (workspace-specific tool paths are not prerequisites on your own machine):

```bash
source /workspace/onboarding/activate.sh
export TINIUM_PHASE31_REPORT_DIR=/tmp/phase31-report
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest "-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so" "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
bash gradlew :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --max-workers=4 --console=plain
/workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-build --output-on-failure
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 /workspace/toolchains/native-host/cmake/data/bin/ctest --test-dir /tmp/tinium-native-sanitized --output-on-failure
```

The final combined validation used these same test/assembly tasks in one invocation. They executed both test tasks; no unit test task was restored FROM-CACHE. Some unchanged compile/native tasks correctly remained UP-TO-DATE. Host JNI build instructions and the packaging checker invocation are in [the integration guide](../../AUTOMIX_BITCHORD_ANDROID_INTEGRATION.md). Reports can be regenerated by selecting `NativePlannerComparisonTest` and `NativePlannerPerformanceTest` with the same host-library property; the optional report directory is never used by normal playback.

## Permitted private/local music

Use recordings you may lawfully analyze. Do not put source audio, raw PCM or a private manifest in the checkout. Optional desktop tooling requires ffmpeg/ffprobe; no app dependency was added. Prepare one pair:

```bash
python3 scripts/prepare_native_planner_local.py --outgoing /private/outgoing.flac --incoming /private/incoming.flac --outgoing-start-seconds 150 --window-seconds 30 --output /tmp/tinium-private-pair
export TINIUM_PHASE31_CORPUS_DIR=/tmp/tinium-private-pair
export TINIUM_PHASE31_REPORT_DIR=/tmp/tinium-private-report
bash gradlew :app:testArm64GmsDebugUnitTest --tests "com.music.vivi.playback.automix.NativePlannerComparisonTest" "-PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so" "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
```

The script refuses output inside the checkout or an existing output directory, preserves source sample rate, writes only bounded mono Float32 windows, disallows network protocols, and omits original names from its manifest. It does not prove Android MediaCodec behavior. Supply an actual analyzed outgoing section near its intended transition; the harness does not discover whole-track structure. Incoming analysis starts at zero to preserve intro evidence. Windows/other host JNI builds are not verified here; Windows phone instrumentation commands are in the architecture document.

A manually prepared `pairs.json` may contain up to 64 pairs, streamed one at a time. Fields per side: `pcm` (relative little-endian mono Float32 file inside that directory), `sampleRate` (8000–96000), `originUs` (nonnegative millisecond aligned), `trackDurationUs`. Frames must fit a <=32-second window and the declared track timeline. Source names are not exported; local ground truth/F1 remains unknown unless independently annotated. Share only the sanitized report, not inputs or unrestricted Gradle/logcat output.

For the S25 Ultra, see the exact six-test and combined 24-test PowerShell commands in [AUTOMIX_NATIVE_DJ_PLANNER.md](../../AUTOMIX_NATIVE_DJ_PLANNER.md). No release, signing arrangement, production planner selection or public setting is changed.
