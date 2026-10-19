# Phase 3.3.2 reliability evidence

Base `862c92ba0a98cda22161e7ec9d20ffdd8854f923` (merged PR #24). See the
[engineering report](../../AUTOMIX_NATIVE_DJ_RELIABILITY_AND_PLACEMENT.md) for algorithms,
limits, physical-device commands and recording/listening protocol.

## Evidence boundaries

| Artifact | Meaning |
| --- | --- |
| `owner-baseline-summary.json` | Original task's owner-reported real-device summary and separately labeled calculations; no new phone measurements |
| `owner-route-test-followup.json` | Subsequent owner-reported ten-test pass and normalized older service-test failure; no raw session export or clean 69-test claim |
| `owner-startup-failure-followup.json` | Next owner rerun: excessive negative startup offset before route assertions; distinct from the no-op test-trigger defect |
| `owner-zero-lead-probe-followup.json` | Subsequent owner probe reports drift fallback with zero lead; its original export omitted terminal measurements because history lagged |
| `placement-and-observation-host.json` | Generated mathematical beat fixtures through the real Kotlin planner, plus host JVM clock/route microbenchmarks; no acoustic or Android output |
| `validation.json` | Actual local test counts/builds and explicit runtime omissions |
| `apk-packaging.json` | Actual ELF, ZIP native-library alignment, ABI, JNI symbol and license-asset inspection |
| `startup-followup-apk-packaging.json` | Repeated actual packaging check after observation-only startup evidence additions; original artifact retained |

`ANALYZED_REGION` replays the original v1 ordering through the current v2 implementation;
it is **not** a run of the original binary. `PRESERVE_OUTGOING` uses strict service policy.
Synthetic BPM/phase grids reproduce the owner's duration/tempo geometry, not the real
recordings' unknown beat vectors. Per-case host time is measured, environment-dependent
and not phone battery/thermal cost. Rejected cases and raw historical owner offsets remain.

### Controlled cue-ranking comparison

| Synthetic case | Earlier-ranking cue / overlap | Preserving cue / overlap | Unheard at handoff, before → after |
| --- | --- | --- | --- |
| 163.865 s, reported tempo pair, generated grids | 132.097200 / 3.244244 s | 160.484337 / 3.244244 s | 28.523556 → 0.136419 s |
| 180 s, 120 BPM rhythmic ending | 148.232200 / 16 s | 163.732200 / 16 s | 15.767800 → 0.267800 s |
| 8 s short ending | 0.232200 / 4 s | 3.732200 / 4 s | 3.767800 → 0.267800 s |
| 1,200 s, 120 BPM ending | 1168.232200 / 16 s | 1183.732200 / 16 s | 15.767800 → 0.267800 s |
| 120 vs 140 BPM | Incompatible tempo | Incompatible tempo | No fabricated transition |

The first generated case supports eight pulses, **not** the owner's actual 6.490457 s
overlap. Its exact synthetic beat vectors differ; the owner's historical 25.277343 s
loss is preserved separately in `owner-baseline-summary.json`. No later real-song cue
is claimed. New selected p95 residual is 33,433 µs in that generated case and zero for
the matched-tempo examples, all under unchanged gates. Separate regression tests require
fallback for unsupported late percussion/quiet tails and retain ≥29-second actual-host-JNI
service overlap coverage. This table is musical placement geometry, not listening ratings.

## Reproduce host validation

Completed locally: **546 GMS + 546 FOSS = 1,092 JVM tests passed**, zero failures/errors/
skips; includes 17 actual host-JNI cases per variant. Both app and instrumentation APK
variants built. All four JNI ABIs, ELF/ZIP 16 KB alignment and license assets passed
inspection. No Android runtime tests executed here. The final host microbenchmark is
about 132 ns per clock calculation and 141 ns per unchanged route decision; these do not
include Android position/timestamp calls or characterize phone audio latency.

Subsequent startup-evidence follow-up: **548 tests per variant / 1,096 total**, zero
failures/errors/skips, with the same 34 actual host-JNI executions included; 24 focused
startup/clock tests also passed. Both debug app/test APKs assembled and four-ABI/16 KB/
license inspection passed again. The added exact-offset regression tests correct rejection
and pre-release evidence retention; it is not an acoustic or startup-repair success case.
No Codex Android device execution is claimed. The owner subsequently supplied a probe
report with excessive-drift fallback and zero lead, but the original probe exported its
status plus stale service history, omitting the observed terminal clock/output snapshot.
The test-only schema-2 repair exports that snapshot first; its phone rerun is pending.
Neither a passed observation probe nor the original MIXING record with zero concurrent
progress is evidence of successful synchronization. Historical counts above remain intact.

The schema-2 **test-only export follow-up** passed **550 GMS + 550 FOSS = 1,100 JVM tests**,
zero failures/errors/skips, including 34 actual host-JNI executions. Four focused tests
passed, and both instrumentation APKs assembled (2m 49s complete local matrix). App
code/native packaging is unchanged; no new app-packaging or native CTest run is claimed.
The two new tests exercise export before the recorder pump and bounded snapshot retention.
See the separate `probeExportFollowup` block in `validation.json` for exact commands.

Environment: JDK 21, Gradle 9.3.1, AGP 9.1.1, Kotlin 2.3.10, SDK 37, NDK 27.0.12077973.
The cloud activation script is local setup, not an application dependency. Build the
existing host JNI library as documented in `AUTOMIX_BITCHORD_ANDROID_INTEGRATION.md`.
From the repository root, with the SDK/JDK and existing local debug signing configured:

```bash
export TINIUM_PHASE332_REPORT_DIR=/tmp/phase332-evidence
bash gradlew :app:testArm64GmsDebugUnitTest --rerun \
  :app:testUniversalFossDebugUnitTest --rerun \
  :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest \
  :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug \
  -PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so \
  -PautomixDecoderTests=true --no-configuration-cache --no-daemon --max-workers=4 --console=plain
```

The report directory is optional and outside the checkout; both variants run the same
deterministic corpus and overwrite the latest host evidence file. Test counts are read
separately from each variant's actual JUnit XML, including skips/errors. Omit no failures.
Existing host-JNI tests run with the actual compiled library, not a mock. Native source,
JNI adapter and decoder remain unchanged; native CTest/sanitizers are not rerun solely
for these Kotlin changes. Native packaging is independently verified:

```bash
python scripts/check_automix_native_apks.py \
  --readelf "$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  --zipalign "$ANDROID_HOME/build-tools/37.0.0/zipalign" \
  --output /tmp/phase332-apk-packaging.json \
  app/build/outputs/apk/arm64Gms/debug/app-arm64-gms-debug.apk \
  app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
```

## Test inventory and regressions

- Clock tests cover both query orders, tick gaps, uncertainty, a 10 ms first offset
  followed by a real 127 ms error, unchanged 120/40 ms guards and cleanup/no promotion.
  A raw -29 ms offset can hide a projected +121 ms offset when queries are 150 ms apart;
  the new guard rejects that old-model false approval. A projected zero never erases
  an original unsafe raw observation.
- Route tests cover same-category replacement, capture-only/repeated notifications,
  standby/session creation, active-deck mismatch, unknown readiness, paused unknown,
  promotion rebasing and privacy. 100,000 identical observations must cancel zero times.
- Placement tests cover the 163.865-second regression, short/long/abrupt/rhythmic endings,
  missing percussion/quiet tails/fades, poor late alignment, incompatible tempo,
  intro/coverage protection, strict defaults, explicit bounded-window intent and
  forged-placement rejection. Original long-overlap assertions remain.
- Earlier startup, reentrancy, queue generation, cancellation, source pin, host JNI,
  worker cache identity, effects and legacy-default suites remain enabled.
- New Android class has **10 methods**. Existing decoder/native/planner/executor/service/
  real-world-decoder selection retains **59 methods**; combined **69**. These cloud
  results compile APKs only. Subsequently the owner reported all ten new methods passed,
  with one older service method failing because it used unchanged default output preference
  as its cancellation trigger. The corrected test retains the original assertions/deadline
  and additionally checks no-op retention and explicit preference-change cancellation.
  No production code changed in that test-trigger repair. The next owner rerun failed at
  startup before route assertions: 229,556 µs lead, −279,376 µs raw offset and 279,416 µs
  guarded drift. This is not a new route cleanup failure. The follow-up preserves numeric
  reference/pre-release output evidence; it does not change the prediction/120 ms guard.
  The exact host replay still requires fallback, zero promotions and surviving playback.
  Corrected-method/full-69 and screen-recorder/Bluetooth validation remain incomplete.
  A separate one-method `NativeServiceStartupDiagnosticInstrumentedTest` is an explicitly
  bounded observation tool, not a 70th synchronization acceptance test. A passed probe can
  honestly report FALLBACK; its file outcome and latest snapshot are required to interpret it.
  The first owner probe reported zero-lead drift fallback but lost terminal measurements
  at export; schema 2 writes the captured snapshot independently of the recorder pump.
  See the engineering
  guide for its exact PowerShell command. No device XML or Codex Android runtime is claimed.

The initial focused run exposed an overlap-ranking regression: prioritizing endpoint
distance before supported overlap shortened valid long mixes. Ranking was corrected
and both the new long-ending assertion and existing ≥29-second host-JNI service assertion
were retained. This unfavorable intermediate result is not a device failure.

Final review also reproduced one new placement-helper defect before repair: measured
119.5 vs 60 BPM can form the existing 59.75 vs 60 effective-pulse hypothesis, but using
the measured 60 BPM lower bound for placement wrongly returned `INVALID_EVIDENCE`.
The regression failed before the helper accepted the existing effective 30–220 range;
native measured-BPM validation and all alignment gates remain unchanged. That octave
remains explicitly unverified, not forcibly treated as the song's true tempo.

No full-recording commercial audio or private diagnostics are committed. No telemetry is
uploaded. Speaker/Bluetooth/acoustic/EQ listening and extended resource observations are
separate from mathematical tests. Absence of Android evidence is not recorded as a pass.
