# Adaptive native analysis and stable evidence reuse — Phase 3.3.4

Base: merged PR #26 (`76eda458c3ac2dc0efd307d24116bf4c68cd31df`). Native DJ remains
explicit experimental opt-in; legacy remains default. BitChord, corrected **v8** PCM,
planner **v2** duration preservation and executor **v5** bracketed clocks are unchanged.
This is bounded **regional** analysis, not a new streaming BitChord API or a global beat grid.

## Evidence and confirmed limits

The owner reports all 79 prior Android acceptance methods passed on S25 Ultra / Android 17.
Their later real-song summary reports 13 retained attempts, six native attempts, two plans,
two observed mixes, one completed native mix and four native fallback attempts. These
are owner-supplied summaries, not independently inspected private audio or new cloud runs.
The successful later pairing remains evidence of an actual handoff, not universal reliability.

V8 recovered the outgoing AAC head: `123065759..155062856` became
`123063000..155062856` for a request `123063000..155063000`. The remaining 144 µs endpoint
loss and a separate incoming `137006..32000000` interval persisted without interior gaps
or synthesized PCM, despite complete encoded caches. The private edit/endpoint metadata
is still unverified. Earlier generated empty-edit and rounded-duration artifacts remain
unchanged; they establish plausible mechanisms, not the original files' exact metadata.

Two architectural limits were confirmed in source:

- Fixed requested edges could reject useful subsequent audio before native analysis.
- `invalidate` closed pair observers and canceled the entire native pool for seeks/routes.
  Completed immutable results already survived `cancelAll()`; they were **not** generally
  erased. In-flight work was canceled. Local files were also excluded from completed reuse
  despite the existing length/mtime/rendition checks. Non-material route observations already
  preserved schedules after Phase 3.3.2; this phase does not claim to repair that tracker again.

## Coverage-aware discovery, exact coordinates and strict revalidation

The separately versioned strategy is `native-regions-v1-verified-inset`. V8 decoder samples,
preroll, AAC tail ownership, Opus support packets, FLAC packet extraction, byte limits,
resampling and source-gap tolerances are unchanged.

The primary job first requests the existing intro/outro interval (at most 32 s). A successful
corrected decode with one measured contiguous source interval may guide **one** second
request. Unknown coverage, failed decoding, interior discontinuities, repeated/synthesized
samples, malformed windows and unsupported profiles cannot guide recovery.

```text
new start = ceil(max(effective start, verified source start) / 1000) * 1000
new end   = floor(min(effective end, verified source end) / 1000) * 1000
```

The new interval must be contained in genuine measured coverage, differ from the original
request and contain 8..32 seconds. Millisecond insets obey the existing Android request
contract; they do not move the source clock or increase a tolerance. The decoder then
independently decodes that new request through unchanged v8. The **entire selected request**
must pass full coverage, canonical format, identity, engine and result validation. A second
partial result fails; there is no third retry, gap fill, endpoint extension or hidden clamp.
The original partial diagnostic is preserved. Natural EOS clamping is explicitly retargeted
only when a qualifying verified interval exists; the old request is never relabeled FULL.

| Original intent, µs | Verified source, µs | Candidate new request, µs |
| --- | --- | --- |
| 0..32000000 | 137006..32000000 | 138000..32000000 |
| 123063000..155063000 | 123063000..155062856 | 123063000..155062000 |

These requests are derived, not song-specific constants. The first retains 31.862 s of
actual music; the second retains 31.999 s. Short or fragmented evidence still falls back.
A true interior gap is not repaired by taking a prefix from a failed decode. This conservative
version does not discover arbitrary islands beyond a broken decode.

The original intent and selected request live in `NativeRegionAudit`. Result metadata,
actual PCM origin and beat vectors describe the **selected** request. The existing native
adapter adds the actual materialized PCM origin exactly once. For the positive-origin
fixture it remains 138000 µs, not zero. Filter support and integer-frame endpoints remain
reported by v8. No edit-list rebasing is applied a second time.

Analysis interval, materialized evidence support, planner cue region and execution overlap
remain distinct. The unchanged planner/executor require the entire overlap within each
selected local support interval. A middle-region BPM never certifies an unobserved outro,
and the incoming intro-preservation or outgoing-loss gates are not relaxed.

## Stable feature identities and consumer ownership

The service uses the same `AnalysisWorkers` pool (1/2/4 workers, waiting capacity 8,
completed LRU 64), sharing its existing concurrency budget with independent legacy workers.
No second scheduler, decoder service or native worker thread is created.

The native region job key contains the existing source/profile digest (recording/rendition,
backend/version, v8 PCM, detail), signal/gain observation versions, strategy version,
exact intended interval and immutability policy. A strict-window job has a separate strategy
key. A cached adaptive entry is an **intent lookup**, not a claim that the old requested
edges are supported: its audit records selection and result metadata retains the exact
actual request/origin. Different intents do not alias; every hit retains that provenance.
No title, artwork, URL or artist string is used as an audio identity.

`acquire` returns consumer-specific futures. Equivalent in-flight requests share one task;
canceling one observer wakes that consumer without canceling other observers. Immutable
native source work can finish after its last observer leaves on seek/route invalidation,
within its **original** deadline. A newer consumer can join it. The existing service tick
also expires retained jobs waiting for the shared concurrency budget; it uses no new timer
or polling thread, and publishes an analysis-timeout category before canceling the task. Planning tasks are always
canceled, and obsolete tickets cannot publish schedules. Optional/unverified mutable work
is canceled when no consumer remains. Queue/source changes, configuration changes and
shutdown still cancel pool work; completed successes remain reusable until LRU eviction.
Failed analyses never become successful cache entries.

The existing direct `submit` API and identity-preserving cache/cancellation protections
remain for legacy users. A direct consumer cannot be canceled by a shared subscriber.
Local files now qualify only under the existing current path/length/mtime identity checks;
remote immutable reuse retains the existing complete-rendition policy. Unverified mutable
sources do not acquire durable completed reuse merely because a title matches.

No PCM is cached. There is no persistent analysis disk cache. A compact catalog retains
at most eight source identities and six regions per source; the worker LRU remains 64.
Catalog entries and LRU entries normally reference the same evidence, but worst-case
retention may reach 112 distinct compact results after independent eviction. Each carries
bounded beat/downbeat/rise vectors and metadata, never decoded samples. Playback/download
cache contents are not cleared to free analysis memory.

## Cache-aware progressive enrichment

Fast initial analysis remains two relevant windows, with at most one verified-inset retry
per region and the original finite pair deadline (4..20 s, bounded by remaining cue time).
An uncached initial job can read through the existing authorized bounded source path;
`fullyCached=false` alone is not a measurement of network traffic.

Optional enrichment requires a selected native plan, playing audio, immutable sources and
ample preparation lead. Before **each** optional region MusicService rechecks complete
cache bytes or a local file's current identity. Remote enrichment receives a read-only
`CacheDataSource` with **no upstream** and no writer. Eviction during the read fails closed,
not by downloading the rest. Cache completeness certifies encoded byte availability, not
PCM continuity, immutable upstream content, correct duration or complete musical analysis.

Policy:

- One optional region job at a time; three attempts per source per catalog lifetime.
- At most six candidate intervals per source, each 8..32 s, and six retained regions.
- A region has a six-second deadline, further bounded by available playback time.
- Lead reserve is `max(10 s, 3 * observed pair-analysis time + 2 s)`; preparation retains
  its existing separate timing policy. Optional work is canceled when that reserve is
  reached, playback pauses, execution prepares/starts, the pair changes or mode shuts down.
- Candidates prioritize the opposite edge, then additional contiguous or representative
  middle regions. Already analyzed contained intervals are skipped. Results use the same
  keys/pool as primary jobs, so an enriched outro can satisfy a later primary outro request.
- Failed optional work consumes its finite attempt budget and never forces playback fallback
  or unlimited retries. No optional work is started during legacy fallback or active mixing.

A short cached source can have its entire verified playable region covered by overlapping
segments (e.g. 0..32 plus 8..40 s). A long recording is selectively sampled, not fully
scanned. Even for short songs, an early transition, failed plan, source boundary or finite
budget may prevent expansion. Playback never waits for whole-track completion.

Regional tempo comparison reports insufficient comparisons, regional agreement, or
conflict/octave uncertainty. It does **not** average BPMs, calibrate native confidence,
stitch grids or promote local evidence into a global stable tempo. Transient/level/quiet
observations retain their existing measured meanings. Vocals, phrases and harmony remain
unknown; optional enrichment does not introduce a detector for those features.

## Invalidation, UI and diagnostics

| Event | Source evidence | Plan / execution |
| --- | --- | --- |
| Ordinary progress | Reuse | Existing cue/guards apply |
| Same-source seek | Reuse completed immutable evidence; rejoin compatible in-flight work | Clear marker, cancel owned overlap/preparation, plan against current position and new generation |
| Seek beyond cue | Reuse | Later cue must independently qualify; otherwise fallback |
| Unchanged route/capture notification | Reuse | Existing route tracker retains safe state |
| Material route/AudioTrack replacement or unknown unsafe output | Reuse compatible source music | Cancel schedule/owned execution, invalidate startup calibration, require current output readiness |
| Incoming queue/source changes | Preserve completed compatible entries; cancel old jobs | Invalidate obsolete pair and ownership |
| Rendition/profile/strategy change | Different identity; no incompatible hit | New admission required |
| Pause/focus | Preserve evidence; stop optional work | Existing focus/pause rules remain |
| Disable/shutdown | Stop work; shutdown releases catalogs | Existing cleanup and legacy routing |

A seek currently replans rather than retaining an armed schedule even if the old cue was
still ahead. That small bounded planner cost favors safety; it does not require new DSP.
Source evidence is independent of the queue generation; plans and executors are not.
Unchanged screen-recorder notifications do not receive special exemptions: actual route
classification remains authoritative. Samsung's built-in recorder is not automated here.

Existing phases and slider marker publication remain. Cache hits can move directly into
REFINING, rather than replaying source work. Optional expansion keeps the confirmed plan's
marker and PLANNED status; it is reported separately, not called a completed whole-track
scan. Canceled schedules remove their marker; only real execution produces mixing/completion.

Bounded additive `analysisRegionSchema=1` / `analysisReuseSchema=1` records include strategy,
intent/selected interval, adaptation, actual decode count, decoded source-frame count,
host-job elapsed time, pool executions/hits/coalescing/cancellations/queue/active/cache counts,
region union duration/bounds, tempo comparison, optional progress and seek/route replans.
They contain no raw keys, source handles, titles, URLs, device IDs, tokens, paths or PCM.
The existing salted source hashes and bounded history/export remain. A pool execution is
not necessarily a decoder call (a boundary job can decode twice). Separate cumulative
`actualDecoderCalls` and `measuredDecodedSourceFrames` count actual work, including retries;
frames use codec output counts when measured, otherwise accepted source-frame counts.
Cached `NativeRegionAudit` decode/time values describe the originating job and must not be
summed again as new work on cache hits. Elapsed wall time is not measured CPU/battery cost. The JSON evidence/PR distinguishes those metrics.

## Verification and known limits

See [evidence inventory](benchmarks/phase334-adaptive-analysis/README.md) for exact commands,
counts, measured host JNI/reuse results, packaging and explicit unexecuted work.
The new Android class has **12** methods; the earlier **79** are retained, expanded total
**91**, excluding the separate observation-only startup diagnostic.

One historical service fallback test changes only its generated fixture: the old 137 ms
leading gap is now deliberately recoverable, so a legitimate 30 s empty edit leaves <8 s
within the initial 32 s request. Its original fallback/mode/ownership assertions are retained.
The original strict 137 ms engine rejection and all genuine-gap/14-frame/Opus assertions
remain. The new small-gap service case verifies native evidence reaches planning; a musical
planner rejection is still valid. No historical benchmark JSON is rewritten.

Private AAC edit lists/endpoints are not independently verified. Retrying inside verified
edges cannot recover genuinely missing samples, certify beyond a failed discontinuity or
establish sample-identical coordinates across different renditions. Mutable/expired sources
may still need new decoding. In-flight reuse inherits the earlier deadline; it does not
extend an expired job to guarantee success. Caches are service-memory only, not durable
across service/process destruction. Long-song whole-track analysis and explicit arbitrary
region cue search remain future work. Optional enrichment requires an already usable plan;
it does not rescue an entirely non-rhythmic initial pair or add an intro/vocal classifier.

Intermittent real-song `EXCESSIVE_DRIFT` is still separate. The 120 ms drift and 40 ms command
guards, route calibration rules, gain curves, focus, sessions, EQ and exactly-once promotion
are unchanged. Host timing is not phone timing or acoustic alignment. Bluetooth, audible
Poweramp processing, vocals/phrases/harmony, thermal/battery load and broad musical quality
still need real listening. AGPL notices and BitChord provenance remain; the development
permission assumption is not public-distribution legal clearance. No release is authorized.

## Executed cloud validation

Final local matrix: **599 Arm64 GMS + 599 Universal FOSS = 1,198 passed**, zero
failures/errors/skips. This includes **19 actual host-JNI cases per variant / 38 executions**;
these are not added again to the JVM total. Both debug app APKs and both instrumentation
APKs assembled (final matrix 3m 40s). Actual APK inspection passed all four JNI ABIs,
ELF/ZIP 16 KB alignment, native exports and license assets. The new empty-edit fixture
regenerated to the same SHA-256. Native DSP/JNI/CMake are unchanged, so CTest/sanitizers
were not newly run. There is no installed emulator image/KVM/exposed USB device here:
**zero Android methods were executed by Codex**. New-device and expanded 91-test results
remain pending, separate from the owner's earlier 79-test v8 baseline.

| Host measurement | GMS | FOSS |
| --- | ---: | ---: |
| Boundary discovery + verified inset capture/resampling/JNI | 358.009 ms | 358.234 ms |
| Actual native BPM | 128.083205 | 128.083205 |
| Raw uncalibrated confidence | 0.924256 | 0.924256 |
| 100 completed-evidence lookups after schedule invalidation | 0.502 ms | 0.668 ms |
| Additional decodes during those lookups | 0 | 0 |
| First region in separate 96 s stable-tempo model | 299.194 ms | 261.893 ms |
| Two additional independent regions | 479.282 ms | 490.179 ms |

These are actual host captures/resampling/native calls with generated PCM and simulated
source transport, not Android codec/cache/network measurements or CPU/battery benchmarks.
The 96 s model retained three separate grids, measured 128.079/128.087/128.074 BPM, and
reported regional agreement without creating a global grid. No PCM window exceeded
32 s. The virtual service enrichment test observed eight source jobs total (two primary,
six optional) under its finite budget; it is not measured physical-device decoding.

A canonical 32 s mono Float32 buffer is **2,822,400 bytes**, versus **15,876,000 bytes** for
180 s or **158,760,000 bytes** for 30 min. Source capture, JNI copies, native resampling,
FFT and runtime allocations add to that input size; it is not total heap/RSS. A boundary
retry can temporarily retain the first decoded window while producing the second. Feature
retention is count-bounded, with object overhead estimated at several MB, not measured as
an Android byte bound. No full-file native benchmark is invented: the backend's 32 s cap
requires independent segments. Separate queue latency, phone CPU, battery and thermals
were not measured. See the raw evidence files for limits and initial unfavorable checkpoints.

## Windows / S25 Ultra instructions

Use the repository root, your existing debug key and the isolated test app. No uninstall,
normal Tinium data deletion, cache wipe or global signing/app-ID change. Pause normal music,
use comfortable media volume, and run only one test command at a time.

```powershell
git fetch origin
git switch codex/automix-adaptive-analysis
git pull --ff-only
.\gradlew.bat :app:assembleArm64GmsDebug :app:assembleArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AdaptiveNativeAnalysisInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expect **12 executed, zero failures/skips**. The connected task installs/updates only the
isolated package `com.yren411.tinium.decodercheck` and its runner. Intentional source-gap
rejections are passing test assertions, not runner failures. A zero-test invocation is not
validation. If signing conflicts, retain the working key; do not uninstall normal Tinium.

Previous **79-test** selection:

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AacBoundaryCoverageInstrumentedTest,com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest,com.music.vivi.playback.automix.NativeDjReliabilityInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expanded **91-test** selection (includes those 79; running both duplicates coverage):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AdaptiveNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.AacBoundaryCoverageInstrumentedTest,com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest,com.music.vivi.playback.automix.NativeDjReliabilityInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Retrieve failures from `app/build/reports/androidTests/connected/` and
`app/build/outputs/androidTest-results/connected/`. Optional last-case diagnostics:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase334-decoder-last.txt
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase33-service-last.txt
```

Paste the assertion and redacted diagnostic; omit private URLs/paths. These files show
the latest case, not every case. Actual Samsung recorder and physical Bluetooth replacement
are manual tests, not established by injected route/configuration test events.

## Authorized real-world acceptance

After Android regression, the isolated app is available for normal controlled listening.
If necessary update it with `:app:installArm64GmsDebug -PautomixDecoderTests=true` and the same
key. Instrumentation does not update the normal Tinium installation. Use existing AutoMix
settings to select Native DJ Experimental; switch back to Legacy there at any time.

1. Repeat **Oye Como Va → Eva Maria → El Viejo Joven** with authorized sources. Confirm v8,
   strategy v1, planner v2/executor v5 in the export. Start recording before the first run
   if desired, then avoid seeking/output changes until the two natural transitions finish.
2. Compare original intent, first partial diagnostic, actual selected request and second
   FULL diagnostic. Expected conceptual offsets are 138000 and 155062000 µs only if the
   verified bounds match the earlier report; they are not hardcoded song requirements.
3. Check native EVIDENCE separately from PLAN, execution preparing/armed, MIXING, completion
   and exactly one promotion. Valid evidence can receive a legitimate musical rejection.
   Genuine gaps and real drift must still fall back safely. Preserve the successful later
   pair's outgoing-song preservation, continuity and comfortable incoming audibility.
4. Seek earlier, forward-before-cue, then beyond-cue on an already analyzed song. Compare
   pool cache hits/coalescing and actual decode records; distinguish optional new regions
   from primary redecoding. The marker must refresh/remove; stale execution must not survive.
5. On stable speaker output, wait for a plan, start/stop Samsung's screen recorder, then
   export. Compare effective route materiality, plan generation and reuse counters. Test
   Bluetooth separately if available, including a real disconnect. Do not suppress a true
   route/track timing change to retain a marker.
6. Repeat several natural transitions. Record full/partial analyses, musical rejections,
   execution fallbacks, native completions, outgoing preservation and subjective timing,
   vocal collisions, level balance, cue quality and stutter separately. No automatic upload.

Phase 3.4 per-deck EQ/filter work is not implemented. It still requires a dependable
analysis/execution foundation and independent audio-chain/effects testing without changing
the listener's global EQ or Poweramp settings.
