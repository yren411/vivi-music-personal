# Native DJ reliability and transition placement — Phase 3.3.2

Base: merged PR #24 / `862c92ba0a98cda22161e7ec9d20ffdd8854f923`.
Legacy AutoMix remains the default. Native DJ remains explicitly experimental.
This change improves route admission, cue placement and conservative clock evidence;
it does **not** establish a repair for every real-device startup failure or acoustic sync.
No DSP, JNI, PCM decoding, preprocessing, EQ, gain curve, playback rate, signing,
version allocation or release workflow changes are made.

## Current validation status

The owner reports **all 69 tests passed in the combined Android regression selection**
on the Samsung Galaxy S25 Ultra / Android 17. This includes the previously failing route
acceptance method; the owner also reported its single-method pass before the combined run.
These are owner-reported physical-device results, not Codex Android execution. No raw
runner XML or independently verified installed commit SHA was supplied; no failures or
skips were reported. The source checkpoint used for the supplied instructions is
`955000e2267807c75710fa19f0e1e600af96b04e`.

| Combined Android category | Tests reported passed |
| --- | ---: |
| Real-world AAC/Opus decoder | 10 |
| Corrected PCM decoder | 4 |
| BitChord native analyzer | 14 |
| Native-aware planner | 6 |
| Dual-deck executor | 14 |
| MusicService integration | 11 |
| Phase 3.3.2 reliability and placement | 10 |
| **Total** | **69** |

The separate observation-only startup probe is **excluded** from that count. Its latest
snapshot reached 2.002 seconds of common progress with 43.419 ms maximum guarded drift,
steady offsets −1.320..13.650 ms, both output timestamps available and zero promotions.
It intentionally ended before handoff. Earlier startup failures remain part of the evidence;
neither this healthy observation nor a passing combined run proves they are eliminated.

On repository checkpoint `955000e2`, both required workflows passed:
[CI & Debug Build](https://github.com/yren411/vivi-music-personal/actions/runs/39195269924)
and [Build PR](https://github.com/yren411/vivi-music-personal/actions/runs/39195269934).
The duplicate push workflow was conditionally skipped. Existing nonblocking lint policy
is unchanged; these results do not certify clean lint. The last complete local JVM matrix
on that code passed **550 GMS + 550 FOSS = 1,100 tests**, zero failures/errors/skips,
including 34 actual host-JNI executions. Both instrumentation APKs assembled. These are
the prior code-validation results, not new executions caused by this documentation update;
workflow results are attributed only to their actual commit.

Phase 3.3.2 has passing evidence for the tested generated-media Android scenarios and
remains an experimental opt-in implementation for review. Real-song cue quality, repeated
startup reliability, Samsung screen-recorder behavior, Bluetooth, acoustic synchronization
and audible Poweramp processing still require controlled evaluation. Legacy remains the
default. No merge, release, APK publication, Phase 3.4 implementation or broader activation
is authorized. Historical investigations below are retained in chronological order.

## Evidence and its limits

The owner describes an October 18, 2026 S25 Ultra / Android 17 session using Tinium
6.0.8 (77), Performance / four workers, corrected PCM v7, planner v1 and executor v4.
The supplied summaries report 22 retained native pairs, seven plans, five observed mixes,
two completed native mixes, six fallback pairs, three completed handoffs across engines
and eight cancellations. These are retained attempts, not distinct musical pairings.
The new task supplies descriptions of the logs/videos; it does not provide synchronized
acoustic measurements or distributable commercial recordings.

Two owner-reported completions used approximately 6.49-second overlaps, one promotion
each and maximum player offsets of 105.5 and 115 ms. Their outgoing cues were approximately
153.587 and 132.097 seconds. Other executions failed at about 121–127 ms against the
unchanged 120 ms limit, with zero startup lead. Small initial offsets of 10–15 ms do not
prove that later positions must stay aligned. The successful second handoff is technically
valid but leaves substantial outgoing music unheard:

| Quantity | Owner-reported regression |
| --- | ---: |
| Outgoing duration | 163,865,000 µs |
| Cue | 132,097,200 µs |
| Overlap | 6,490,457 µs |
| Expected outgoing endpoint | 138,587,657 µs |
| Remaining at promotion | **25,277,343 µs** |
| Timeline preserved | **84.5743%** |

Starting screen recording reportedly refreshed detection. That establishes correlation,
not a physical route replacement. The two completed real-song handoffs remain an important
owner-reported result; they are not rewritten as failures or generalized musical success.

## Clock investigation and conservative model

Confirmed in the old executor: outgoing and incoming positions were queried sequentially,
then associated with one later monotonic time. Query time/order was not exported. Thus the
summaries cannot distinguish a slow query, stale Media3 position, renderer handover,
actual startup delay, or sustained playback mismatch. No fixed Samsung offset is justified.

Media3 1.7.1's [`DefaultMediaClock.syncClocks`](https://github.com/androidx/media/blob/b732b2a0aa3c186edb75c5b3dadc810b849071e9/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultMediaClock.java#L164)
can advance the standalone clock, then stop it while waiting for a lagging renderer clock
to catch up. This is a source-supported *possible* small-first-offset/larger-later-offset
mechanism, not a diagnosis of the owner's device. Source was inspected, not copied.

`NativeDjClockObservation` brackets each snapshot with monotonic microseconds. With
raw cue-relative offset `r`, query midpoints `ta,tb`, and half-query-duration uncertainty `u`:

```text
point = r + tb - ta
lower = min(r, point) - u
upper = max(r, point) + u
guardedDrift = max(abs(lower), abs(upper))
```

The point estimates positions at a common instant under rate-1 progress. Keeping the raw
offset too is essential: a startup clock may hold instead of progressing. This is a
conservative **query-timing bound**, not a bound on hidden renderer/acoustic latency.
An unsafe raw offset can never become an approved completion through projection.
If the entire interval lies past ±120 ms, fallback is `EXCESSIVE_DRIFT`. If the bound
crosses the limit ambiguously, fallback is `CLOCK_OBSERVATION_UNCERTAIN`. Neither is success.
The independent progress, buffering, discontinuity, first-start and stall guards remain.
At a stopped outgoing EOS the clock is not projected. Slow query age also counts against
the unchanged 40 ms start-command guard; a wide query cannot authorize a late start.
The final command-time reading includes intervening prepared-format/capability checks,
so a slow check after snapshots cannot use a stale tick timestamp to pass admission.

Executor version is **`tinium-native-dj-executor-v5-bracketed-media-clocks`**. Diagnostics
separate first offset, raw peak offset, guarded peak drift, query span/brackets, steady
offset range after one second, immutable command time, first incoming progress, actual
fade-start position and promotion position. Sampling is at the existing executor cadence;
no extra polling loop, thread, warm-up player or audible calibration is introduced.

The existing startup observation remains same-source/generation and format compatible,
at most ten seconds old, with a maximum 250 ms command lead. Its absence, incompatibility
or expiry is now explicit. Expiry plausibly explains zero lead late in a song, but does not
establish the cause of 121–127 ms drift. This phase does **not** extend validity or reuse
old output-route calibration. No reliable fresh calibration can be inferred without
starting another output; that workaround is deliberately not installed. Zero-lead fallback
remains honest. Additional phone measurements may justify a separate startup correction.

Passive `AudioTrack.getTimestamp` samples, if available, expose frame position, monotonic
timestamp and sample rate for each deck in bounded execution reports. These output frames
have no established mapping to each selected media cue in this phase. They are diagnostics,
not beat timestamps or an acoustic correction. Two sequential output timestamp queries are
also not claimed to be simultaneous.

### Tempo drift is a separate quantity

147.95433486768678 versus 146.44518730720463 BPM gives a hypothetical incoming rate
1.0103052042080176. Actual playback stays 1.0. The existing planner compares real beat
vectors and rate-1 tempo-phase drift over each overlap (about 66.9 ms over 6.490457 s).
It rejects longer incompatible overlaps under its unchanged 70 ms gate. This is distinct
from cue-relative Media3 progression error, startup latency and acoustic delay. BPM is not
changed to compensate for playback clocks; no pitch shift or time stretching is added.

## Effective route classification

Confirmed old behavior: every `AudioDeviceCallback` addition/removal invalidated native
planning, even a capture-only/input device or repeated unchanged notification. The repair
delegates Media3's **unchanged DEFAULT AudioTrack provider**, then passively observes actual
`AudioTrack.routedDevice` and routing listeners. It does not replace renderers, processing,
preferred-device policy, attributes, sessions, focus or volume. One weak track/listener is
held per live processor; replacement, deck release and service shutdown remove it.

| Observation | Policy |
| --- | --- |
| Same effective outgoing route, repeated availability/capture/session notification | Retain plan, generation and completed evidence; no decode restart |
| Standby deck/session creation while primary output is unchanged | Retain; not a physical-route change |
| Actual primary device change or primary AudioTrack replacement | Invalidate once, clear startup evidence and stale plan/marker |
| Playing incoming output changes or differs from outgoing output | Invalidate conservatively |
| Active required output becomes unknown | Cancel once and block new native attempts until known |
| Same primary track paused, routed device temporarily unavailable | Report deferred UNKNOWN; block new attempts, retain comparison baseline; require fresh observation on resume |
| Output-device notification or track replacement while paused | Still invalidate conservatively |
| Expected atomic deck promotion | Reset comparison baseline to the new primary |

No hardware ID, Bluetooth name/address or raw device identifier is exported. In-process
device ID and track-instance tokens distinguish two devices of the same category; their
string representation is redacted. Reports contain route categories, notification source,
changed-deck/replacement flags, material/known/deferred flags, and session/focus notification
flags. Existing event monotonic time supplies chronology. A session/focus flag records the
notification kind; it does not assert that physical routing changed. Active incoming is
required once it has observable progress. Unknown routes block new admission; unchanged
repeated observations do not create a new generation. Repeated genuinely different
known routes still invalidate; device traces must establish whether additional stabilization
is needed for a physically flapping output. No arbitrary retry/debounce timer is installed.

This fixes the demonstrated notification-policy defect. Whether Samsung screen recording
changes actual routing, sessions or clock behavior still requires the manual procedure below.
No screen-recording detector, telemetry service or arbitrary debounce delay is added.

## Musical placement and bounded search

Confirmed old ranking preferred the earliest cue in the supplied outgoing region after
overlap/alignment classes. A scan beginning 32 seconds from the end could therefore discard
25 seconds even when a comparable later cue existed. This is independent of executor success.

Planner version is **`tinium-native-dj-planner-v2-duration-preservation`**. Requests and
plans default to `PRESERVE_OUTGOING`; MusicService explicitly requires it. For each candidate:

```text
endpoint = outgoingCue + overlap
remaining = outgoingDuration - endpoint
preservedFraction = endpoint / outgoingDuration
allowedLoss = min(outgoingDuration / 20, floor(8 * 60_000_000 / effectiveOutgoingBpm))
```

A native service plan must preserve at least 95% of the timeline and leave no more than
one smallest supported eight-pulse overlap unheard. This is an explicit conservative
listening policy, **not** verified phrasing or outro detection. It makes the early regression
inadmissible (budget about 3.244 s), but does not assert that a safe later cue exists in those
actual songs. Fallback is preferable when later beats, coverage or transient evidence fail.
The pulse period uses the selected hypothesis's effective BPM, which can fall below
60 BPM after halving measured native BPM. Measured evidence still requires 60–220 BPM;
the placement helper must not reject an already valid octave hypothesis on that basis.

Search reserves the latest feasible start for each supported overlap/stride, ending at
supplied real beats, then fills the same bounded anchor budget from late sampled anchors.
No extra PCM windows, whole-song decode or invented beats are used. Default 16 outgoing
anchors / 3,072 candidates and configured maxima remain bounded. Candidates still pass all
original coverage, intro preservation, tempo, 70 ms maximum drift/residual, 40 ms p95,
25 ms median and transient gates. A preserving endpoint additionally requires an observed
outgoing rise at its final beat using the existing 70 ms event-matching tolerance: an
average audit must not quietly carry a grid through a silent tail.

Ranking is transparent and lexicographic: corroboration, metrical ambiguity, 10 ms p95
quality bands, longer supported pulse overlap, endpoint distance in pulse bands, incoming
intro skip, exact remaining duration, finer alignment and deterministic tie breakers.
There are no opaque additive weights. Long valid overlaps are retained; a poor late beat
cannot bypass alignment. Quiet tails or missing late percussion can correctly fall back
with `EXCESSIVE_OUTGOING_LOSS` or the appropriate signal/timing reason.

`ANALYZED_REGION` is an explicit bounded-window mathematical/codec-test intent. Existing
tests analyzing only the first 24/32 seconds of longer fixtures use it to preserve their
original purpose and assertions. It is not selectable in settings or accepted by the
service. Executor admission recomputes strict placement and rejects forged/stale metadata.
Native analysis caches/profile/preprocessing remain v7; changed plan semantics carry v2,
and executor admission rejects v1. No old plan is silently reinterpreted.

## Status, diagnostics and ownership

The existing marker uses the confirmed native cue; cancellation/fallback clears it through
the existing generation boundary. Completion now explicitly clears the outgoing native
plan/identities while retaining a completed execution state until normal playback updates.
Actual start reports the original-cue fade start, separately from an early command lead.
Generating a plan is never displayed as successful execution. Reports include selected
rank, duration, endpoint, remaining/preserved fraction/budget, and aggregate rejection
counts for anchors later than the selected cue. These are evaluated candidates only;
bounded search cannot explain uninspected candidates. `placementSchema=1`, `clockSchema=2`
and `routeSchema=1` are additive to the bounded recorder export. The later startup diagnostic
follow-up extends clockSchema to **3**; historical schema-2 traces retain their meaning. No titles, URLs, paths,
PCM, device IDs or automatic uploads are introduced.

Native and legacy ownership, exactly-once promotion, fallback cleanup, reentrant controls,
user volume, focus and effect-session hooks stay in the existing service/executor. Passive
route observations run on the player application looper; no audio processing is performed
there. EQ lifecycle assertions still do not prove audible Poweramp processing on both decks.

## Validation and reproducibility

See [raw evidence and commands](benchmarks/phase332-native-reliability/README.md).
Host-generated beat vectors are controlled planner evidence, not re-analysis of the real
songs. The old ranking is replayed through explicit `ANALYZED_REGION` in the new planner,
not represented as execution of an old binary. Historical owner failures are retained
separately. Native BitChord source, JNI, corrected AAC/Opus/FLAC processing and licenses are
unchanged, so this repair does not rerun native CTest/sanitizers solely for Kotlin changes;
full JVM validation includes actual host-JNI execution. Android compilation is separate
from phone execution. The baseline's owner-reported 59-test pass is **not** a Phase 3.3.2 pass.

There is no emulator/device in this cloud environment. The new ten tests execute real
Media3 and several actual MusicService scenarios on the owner's phone when run. They cover
repeated transitions, fresh/no-prediction behavior, both query orders, preserving service
planning/promotion, unchanged capture notifications, standby sessions, focus, seek and mode
rollback. No-prediction explicitly accepts **either** guarded completion **or** controlled
drift fallback; it cannot classify fallback as successful synchronization. Notification
injection exercises actual live route observation, not Samsung's recorder or physical
Bluetooth replacement. Existing 59 tests/assertions remain; the combined selection is 69.

### Owner device follow-up: ten passes and one older test-trigger defect

The owner subsequently reported all **ten new tests passed** on the S25 Ultra / Android 17,
but one method in the previous 59-test selection failed:
`NativeAutoMixServiceInstrumentedTest.routeInvalidationReleasesIncomingWithoutMutingPrimary`.
No raw Android XML, separate skip count or independently verified installed commit SHA was
supplied. The attached diagnostic identifies planner v2, executor v5 and PCM v7. This is
owner-reported device evidence, not Codex execution. This initial checkpoint did not yet
establish a clean 69-test pass; the later combined pass is recorded above.

The old test called `setPreferredAudioDevice(null)` while the service preference was already
`null`, then expected the second deck to disappear within three seconds. That request is
intentionally unchanged under the new policy. The supplied log shows continued MIXING,
maximum guarded drift **16,643 µs**, maximum query span **285 µs**, zero promotions and
zero CANCELED records. SESSION, TRACK_CREATED and TRACK_ROUTING observations all retained
the same known SPEAKER output with `material=false`. The test timed out waiting for a
cancellation it had not actually requested; this was not excessive drift or failed cleanup.

The correction is **test-only**. It first verifies that default → default retains the same
plan, MIXING state and two outputs, then changes default → an explicit preference for the
currently routed output. That real preference change exercises the existing conservative
service cancellation path without requiring another physical device. The original three-second
release deadline, primary playback, volume and queue-item assertions remain. Additional
assertions require ROUTE_CHANGED history and zero native promotions. The previous preference
is restored in `finally`. No production route policy, timing guard or audio behavior changed.
This does not simulate a physical Bluetooth/speaker replacement. A normalized summary is in
`benchmarks/phase332-native-reliability/owner-route-test-followup.json`; the full private session
export is not committed. The owner then reran the corrected method, but startup failed
before its route assertions; see the separate investigation below. At that checkpoint a
clean combined 69-test pass remained unestablished. The owner later reported the route
method and then the full combined selection passed, as recorded in the current summary.

Route acceptance command, retained as an optional regression/troubleshooting tool
(one test, expected zero failures/skips; no claim that intermittent startup is repaired):

```powershell
git fetch origin
git switch codex/automix-native-reliability-placement
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest#routeInvalidationReleasesIncomingWithoutMutingPrimary" --no-configuration-cache --no-daemon --stacktrace
```

Initial implementation/test-trigger matrix: **546 Arm64 GMS + 546 Universal FOSS JVM tests = 1,092 passed**,
zero failures/errors/skips. Included actual host-JNI cases: 17 per variant / 34 executions.
Both app debug APKs and both instrumentation APKs assembled successfully. Actual APK
inspection verified all four native ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`),
16 KB ELF LOAD and uncompressed ZIP data alignment, JNI symbols and license assets.
Packaged native hashes match the archived Phase 3.3.1 artifacts. Native build dependencies
remain unchanged. These are local results for this implementation, not phone execution.

### Second owner rerun: startup fallback precedes route testing

The next supplied diagnostic is a **different failure**. `h.mixing()` timed out before
the corrected test issued either preferred-device assertion. Native analysis and planning
succeeded with full generated-source coverage and stable known SPEAKER routing, then
execution fell back with `EXCESSIVE_DRIFT`. The later visible `LOW_BEAT_CONFIDENCE` is
independent legacy analysis following that fallback, not its native cause.

| Measured quantity | Supplied diagnostic |
| --- | ---: |
| Planned outgoing cue | 9,976,065 µs |
| Planned incoming cue | 336,689 µs |
| Start command outgoing position | 9,750,000 µs |
| Selected startup lead | 229,556 µs |
| First progress, command-to-observation time | 11,124 µs |
| Outgoing at first progress | 9,760,000 µs |
| Incoming at first progress | 400,000 µs |
| Raw cue-relative offset | **−279,376 µs** |
| Common-time point | −279,336 µs |
| Query-duration uncertainty | 40 µs |
| Guarded drift | **279,416 µs** |
| Promotions | 0 |

The arithmetic is exact: `(9,760,000−9,976,065)−(400,000−336,689) = −279,376`.
Incoming reported +64 ms from the millisecond-floor seek while outgoing reported +10 ms,
only 11.124 ms after command dispatch. The sequential-query correction is only 40 µs and
cannot explain or repair this offset. Both conservative bounds exceed the negative 120 ms
limit. Rejection is correct under the existing media-clock contract. An earlier command
based on the outgoing observation does not establish the incoming player's actual startup.
Identical prepared formats alone do not prove matching clock or output startup behavior.
Renderer/standalone handover, seek reporting and cold/warm output differences remain
possible mechanisms, not established acoustic diagnoses. Do not transfer this outcome
to the earlier trace that reached MIXING with only 16.643 ms drift.

An exact virtual-clock regression now reproduces the supplied relative positions,
fractional seek floor and 229.556 ms prediction, requires excessive-drift fallback and
zero promotions, and verifies surviving playback. It does **not** simulate sound or
turn fallback into success. No clock is rebased, no cue changed, no new fixed offset
introduced and neither the 120 ms nor 40 ms guard is relaxed.

Two narrowly scoped diagnostic gaps are addressed, without changing startup selection:

- Numeric startup-reference command/observation positions/times and optional raw source
  AudioTrack timestamps are exported, without source/rendition identifiers.
- Failure output timestamps are sampled **before** incoming cleanup and retained. Previously
  querying only after release lost the incoming AudioTrack just when it was most useful.
  Missing/throwing diagnostic timestamp providers cannot prevent normal cleanup. Success
  also retains its final pre-promotion pair. No additional continuous production polling
  is added: source reference capture adds at most two queries and terminal capture one pair.

These are observability changes, not a claimed synchronization repair. PCM v7, backend,
planner v2, executor v5 control semantics, prediction validity, confidence/gain policies and
default legacy mode are unchanged. Musical/acoustic output timing remains unknown.
See `benchmarks/phase332-native-reliability/owner-startup-failure-followup.json` for the
normalized measurement summary; the raw session export is not published.

The startup-evidence follow-up passed **548 GMS + 548 FOSS = 1,096 JVM tests**, with
zero failures/errors/skips and 34 actual host-JNI executions included. The focused startup/
clock selection passed 24 tests. Both app and instrumentation APK variants assembled;
all four JNI ABIs, ELF/ZIP 16 KB alignment and license assets passed inspection again.
See `startup-followup-apk-packaging.json` and the separate follow-up block in `validation.json`.
Those local results do not execute Android, repair startup or change the owner's earlier
reported test outcomes. Original benchmark/packaging artifacts remain separate.

### Bounded service-startup diagnostic (separate from acceptance)

`NativeServiceStartupDiagnosticInstrumentedTest` has **one** explicitly selected diagnostic
method using the real MusicService, JNI, planner and executor. It observes a native startup
outcome for at most 25 seconds, sampling reports at most 10 Hz, and then cleans up through
the existing harness. There is no substitute player, extra warm-up, calibration sound,
automatic retry or relaxed guard. It writes at most 96,000 characters to one app-private file.
A passing diagnostic can report FALLBACK/FAILED/CANCELED; it validates bounded observation
and playback safety, **not successful synchronization**. The 69 acceptance methods remain
separately selected and unchanged. If startup fails again, use this optional probe to collect
evidence rather than repeatedly retrying acceptance until green. Pause other music and use comfortable volume;
generated audio plays. Keep the existing isolated app/key; no uninstall or cache wipe.

```powershell
git fetch origin
git switch codex/automix-native-reliability-placement
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeServiceStartupDiagnosticInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase332-service-startup.txt
```

Expect one diagnostic executed, zero runner failures/skips, with an **explicit outcome**
in the file. Supply that redacted file even if Gradle succeeds. The next synchronization
decision requires comparing the initial reference, first incoming clock and available
pre-release output frames; no optimistic correction is inferred from a passing diagnostic task.

### Owner probe follow-up: zero lead and a diagnostic export race

The owner supplied the observation-only probe's file from the S25 Ultra / Android 17.
Its header reports **FALLBACK / EXCESSIVE_DRIFT**. It is not a completed mix, nor evidence
that the 69 acceptance tests passed. No raw runner XML or independent installed SHA was
supplied. Both required GitHub workflows passed on the preceding source head
`cbea1ccab503ba7407a6d7d3fd65929b520d2a4b`:
[CI & Debug Build](https://github.com/yren411/vivi-music-personal/actions/runs/39177536320)
(attempt 2 after an external dependency-mirror HTTP 504) and
[Build PR](https://github.com/yren411/vivi-music-personal/actions/runs/39177536314).
Those successes are not attributed to subsequent changes.

This run differs from the earlier 229.556 ms lead failure:

| Supplied value | Observation |
| --- | ---: |
| Outgoing start command monotonic time | 386,220,606,224 µs |
| Outgoing first progress monotonic time | 386,220,936,746 µs |
| First reported outgoing progress | 6,000 µs |
| Calculated reference delay | **324,522 µs** |
| Applied incoming startup lead | **0 µs** |
| Incoming start command, outgoing position | 9,983,000 µs |
| Last history event | Unchanged SPEAKER routing, `material=false` |
| Peak drift / final paired output timestamps | **Absent from supplied file** |

The reference delay is `(386220936746−386220606224)−6000 = 324522` µs,
outside the existing 250,000 µs admissible prediction range. Zero lead is therefore
consistent with the unchanged validity policy, not proof that a usable prediction
expired. A preceding `MIXING` history record has `firstOffsetUs=null`, zero concurrent
progress and `lastClock=null`: that state alone does not establish simultaneous advancing
playback. The initial source AudioTrack timestamp is frame zero at 386220922319434 ns;
it is not an incoming timestamp or a measurement of acoustic delay. Query-order,
startup-output and sustained-drift causes cannot be resolved from this file.

The probe had an export race: it stored the observed terminal executor report but wrote
only that report's status plus the independently sampled service history. The service's
next recorder pump had not yet appended the terminal measurements. Pre-cleanup capture
in the executor remained available in the observed report, but the file omitted it.
This was a diagnostic defect, not evidence that fallback or route cleanup failed.

The test-only repair writes `diagnosticSchema=2` and `LATEST_EXECUTOR_SNAPSHOT` **before**
the possibly lagging history. The snapshot includes the observed measurements, paired
output timestamps and startup reference directly; it retains the final 16 player events.
The total file remains bounded to 96,000 characters, and history cannot evict the snapshot.
A deterministic regression exports a genuine 130 ms guarded failure before any service
history update and verifies the final clock/output evidence survives. A second regression
checks the size bound and explicit missing-outcome representation. No additional waiting,
production polling, startup policy, playback code or safety limit changes are introduced.

The same one-method PowerShell command above optionally reruns the repaired probe. Confirm that
the file contains both `diagnosticSchema=2` and `LATEST_EXECUTOR_SNAPSHOT`; supply that file
even if the method passes with fallback. The test-only repair passed **550 GMS + 550 FOSS = 1,100 JVM tests**, zero failures/errors/
skips, including 34 actual host-JNI executions. Four focused tests passed and both
instrumentation APKs assembled. No Android execution by Codex, new production APK
packaging result or synchronization repair is claimed. At this historical checkpoint the
repaired probe and combined run were outstanding. The owner subsequently supplied the
healthy schema-2 snapshot, reported the route-method pass, and then reported all 69 tests
passed. The normalized historical failure summary is
`benchmarks/phase332-native-reliability/owner-zero-lead-probe-followup.json`.

### Later schema-2 observation and combined acceptance

The healthy schema-2 snapshot selected a **146,384 µs** reference lead: 177,384 µs elapsed
minus 31,000 µs observed progress. First incoming progress was observed 116,552 µs after
command. First projected offset was −43,333 µs, maximum guarded drift 43,419 µs, and final
raw/projected offsets 5,624 / 5,649 µs with 24 µs query uncertainty. Common progress reached
2,002,311 µs; steady offsets after one second were −1,320..13,650 µs. Both AudioTrack
timestamp fields were available. No acoustic output or cross-deck frame-origin calibration
was performed, and no handoff was expected at this two-second diagnostic checkpoint.

The only intervening change was the test export, not playback behavior. This healthy
execution therefore cannot be attributed to a synchronization repair. The owner next
reported the original route method passed and then all **69 combined acceptance tests**
passed. This supersedes the earlier pending Android acceptance status, while preserving
the earlier failures and their unresolved intermittent startup implications. See
`benchmarks/phase332-native-reliability/owner-device-validation.json` for normalized
provenance and counts. No raw private session report is committed.

### Resource observations

The archived final host run measured 100,000 clock-bound evaluations in 13.209 ms
(about 132 ns/evaluation) and 100,000 identical route notifications in 14.083 ms
(about 141 ns/observation), with zero material cancellations. Generated planner cases
complete within the individual host nanosecond timings in the JSON; these microbenchmarks
exclude Android player calls, scheduling, AudioTrack queries, capture and audio effects.
They are not Android CPU/battery estimates. Existing executor callbacks remain 10 ms
(250 ms while paused); the service route poll uses its existing at-most-100 ms native
tick, plus platform routing callbacks. No additional polling driver is introduced.
Each active execution tick adds bounded small observation/evidence objects and four
monotonic reads, not PCM allocations; ART allocation/GC cost still requires measurement.
Raw output timestamps are sampled only when execution reports are produced, not at
audio-frame frequency. Default candidate/anchor budgets and analysis-worker counts,
queue capacity and cache sizes are unchanged. No new background player or thread exists.

The inspected debug APK sizes are 76,816,015 bytes (Arm64 GMS) and 73,622,528 bytes
(Universal FOSS). Both happen to match archived Phase 3.3.1 total sizes: ZIP alignment
padding can absorb changed compressed DEX size. This is not a controlled before/after
build-size experiment. The actual new v2/v5 version strings and route class were verified
inside each APK's DEX; unchanged total size does not imply an old APK was tested.
No phone memory, thermal, battery or acoustic performance is inferred from these sizes.

## Galaxy S25 Ultra commands

Use the Android Studio **PowerShell** terminal at the repository root. Keep the existing
working debug key and isolated `com.yren411.tinium.decodercheck` configuration. These commands
do not uninstall/update the normal Tinium app, wipe caches or delete its data. Generated
audio plays: pause ordinary music, use comfortable volume, run only one test command at a time.
These are optional future regression/troubleshooting commands. The owner-reported combined
pass is already recorded; no phone rerun is needed solely for this documentation update.

```powershell
git fetch origin
git switch codex/automix-native-reliability-placement
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeDjReliabilityInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expect **10 executed, zero failures/skips**; a zero-test task is not validation. Retrieve the
bounded latest-test report and retain the Android HTML/XML report if a test fails:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase332-reliability-last.txt
```

Reports are under `app/build/reports/androidTests/connected/` and
`app/build/outputs/androidTest-results/connected/`. The optional existing
`phase33-service-progress.txt`, `phase33-service-failure.txt`, `phase33-service-threads.txt`
files support harness troubleshooting; missing files alone are not a failure.
Re-run a method by appending `#methodName` to the actual class selector above.

Previous **59 tests**, expected zero failures/skips (append the new class to select **69**):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Exact combined **69-test** selection used in the owner's reported final regression
(the separate observation-only probe is deliberately excluded):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest,com.music.vivi.playback.automix.NativeDjReliabilityInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

For ordinary listening, tests do not update the normal app. Build/install only the supported
isolated debug app with the owner's existing local debug signing setup (no distribution signing):

```powershell
.\gradlew.bat :app:assembleArm64GmsDebug "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
.\gradlew.bat :app:installArm64GmsDebug "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
```

Open the isolated test Tinium installation, not the unchanged normal app. Existing test-app
settings/data are separate. If signing conflicts, stop and restore the same debug-key setup;
do not uninstall either app or change the global application ID. In the isolated app enable
AutoMix, then **Settings → Player and audio → Player → AutoMix → AutoMix engine → Native DJ
AutoMix — Experimental**. Legacy remains the saved default for existing installations.
Select **Legacy AutoMix — Default** to roll back. Three technical failures may suspend native
attempts for the service session without erasing the saved opt-in preference.

## Controlled real-song and screen-recording protocol

After device regression, use authorized sources through the normal queue in the isolated
app. Test **Oye Como Va — Banda M1 → Eva Maria — Banda Maguey → El Viejo Joven — Joan Sebastian**.
Start any desired recording before initial playback for the first run; do not seek or change
outputs. Let transitions occur naturally. Hold the existing Stats for Nerds overlay to export
its redacted report using the existing local export path. Do not post private media/URLs.
Compare cue, expected/actual endpoint, remaining duration, raw/guarded drift, query spans,
first/steady offsets, prediction state and native completion versus justified fallback.

A separate recording regression should let a plan appear **before** starting Samsung's
screen recorder, export after starting, stop recording, then export again. Compare ROUTE
events' categories, material/known/deferred, track replacement, focus/session notification
flags and generation/plan retention. Repeat on speaker, then Bluetooth if available.
Physically disconnect the active output in a separate controlled run: a real route change
must invalidate/recover safely, not merely preserve the marker. The test suite does not
automate the recorder, Bluetooth or acoustic output measurement.

Record negative feedback too: early cut, wrong phase/BPM, vocal clash, poor intro cue,
quiet incoming, excessive combined level, abrupt rollback, buffering/stutter, handoff
continuity. Compare the same authorized pair with legacy under similar conditions; keep
subjective ratings separate from clock measurements. No audio/history upload is automatic.

## Limits and Phase 3.4 handoff

Real startup mismatch remains unresolved until fresh device traces distinguish renderer
handover, stale position reporting and real output delay. The conservative estimator can
cause additional honest fallback; it is not a promise of more successful native mixes.
Strict preservation can also reduce native acceptance where endings lack evidence. Neither
is repaired by loosening gates. Native BPM can be wrong; octave ambiguity, uncalibrated
confidence, unknown vocals, phrases and harmony remain. No perfect cue is invented for the
owner's recordings. Streaming/cache variants, Bluetooth, audible Poweramp EQ, battery/thermal
cost and long-term reliability still require listening/device validation. No acoustic or
sample-accuracy claim follows from player-clock agreement.

Phase 3.4 should wait for new Android regressions and the controlled recorder/music runs.
Future per-deck frequency processing can live in each deck's existing PCM processor before
the unchanged shared output/effect lifecycle, with independent gain/ownership tests. This
phase adds no filters, bass swaps, models, pitch shift or global-EQ manipulation.
BitChord/Orchard notices and AGPL source assets remain preserved. The prior Musixmatch
development-permission assumption is unchanged and is not public redistribution clearance.
No merge, APK publication, release or production-default activation is authorized here.
