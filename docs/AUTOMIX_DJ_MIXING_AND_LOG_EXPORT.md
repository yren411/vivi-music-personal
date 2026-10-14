# Active AutoMix, recovery tiers and diagnostic export

This follows PR #13 on `main`. AutoMix remains opt-in at Player and audio → Player.
Appearance settings, slider styles, Crossfade preferences, source authentication,
signing/release/updater infrastructure and PR #12's effect-session ownership are
preserved. No dependency, model, native library or database schema is added.

## Confirmed restrictions and changes

The original early path required an energy/vocal drop and a quiet, non-building
remainder. It ignored an equally loud, stable instrumental groove. Incoming cues
were capped at 12 seconds, plans at 24 seconds, and one detailed 32-second window
had to contain the entire overlap. Failed refinement always selected a safe fade.

`DjCandidateSelector` adds an independent active-material evaluator alongside the
protected-outro scorer. It does **not** remove the latter's conservative rules.
The active evaluator uses actual spectral/transient measurements: percussion,
bass fraction, spectral occupancy, local BPM/phase/stability, measured repeated
bass accents, RMS variation, key compatibility when reliable and vocal-risk proxy.
A strong groove need not become quiet. It must have stable measurable rhythm,
usable active audio on both sides, acceptable joint spectral density and energy,
no protected incoming material skipped, and no later vocal/buildup return cut off.

The new analyzer measures bass energy rather than classifying a bass transient's
mid-band leakage as vocals. Grid confidence is measured over a sustained train of
three detected onsets, so a quiet audible introduction without drums does not
invalidate the later drum grid. Missing beats inside that region still reduce
stability. These remain DSP estimates: **not stem separation, human-voice detection,
semantic chorus detection or automatic understanding of distinctive intros**.

## Candidate selection and tiers

Candidates are paired and ranked, not selected in publication/time/filename order:

- Outgoing: last 72 seconds/final third, observed downbeats, sampled beat-grid
  positions and measured boundaries. Candidate count is bounded to 40 exits.
- Incoming: up to 32 seconds and at most one fifth of actual incoming duration.
  Sixteen candidates maximum, from first sound, structural changes, section starts
  and measured accents. Active cues must lie within 150ms of a measured beat.
- Skipping is refused when prior coverage is missing, prominent vocal risk exists,
  or the skipped passage's RMS exceeds 45% of entry energy (with an absolute silence
  floor). Later cues are meaningful improvements, not a reason to skip half a song.
- Pair checks: tempo ratio within ±4%, confidence/phase/stability gates, harmonic
  compatibility when estimated reliably, sustained active RMS >=0.02, measured
  percussion >=0.2, joint occupancy <=1.5, no rising tail or unstable energy.
  Known incompatible keys reject the active path; unknown keys are not labeled a
  harmonic match. Source/decoder availability is required before final execution.
- Score: rhythm, energy fit, accent evidence and percussion, minus content discarded
  and intro skipped. A bounded duration bonus applies only to well-supported full
  mixes. Long duration cannot make an incompatible pair acceptable.

**Tier A — DJ mix:** strong rhythm, stable repeated bar accents and valid coverage.
Full eligibility requires confidence >=0.7, phase >=0.65, stability >=0.9, repeated
accent confidence >=0.4 and percussion >=0.3 on both sides. Cue/start must be measured
bar accents. It can use 16/32/64 beats when coverage and track lengths support them.

**Tier B — Moderate:** useful rhythmic evidence but incomplete accent/refinement
confidence. A recovered coarse plan is explicitly marked `coarse-analysis-recovery`
and limited to 16 seconds, with no full-tier label. Protected-outro rhythmic plans
remain available. Eight-beat short rhythmic plans still exist in the legacy end path.

**Tier C — Safe:** basic energy/frequency fades, the 2.5-second fallback or ordinary
playback when no safe mix exists. Low-confidence fades are never labeled Tier A/B.

Durations come from measured BPM and beats, capped at 48 seconds. A 64-beat blend
at 120 BPM lasts 32s. At slower BPM it must fit the cap, available evidence and
remaining source length or use fewer beats. Bars are estimated from repeated
measured accents, not a claim of semantic phrase recognition or guaranteed 4/4.

## Analysis, execution and audible processing

Coarse coverage is <=90s outgoing and <=64s incoming. The incoming pass separates
its early and later intro windows (0–16, 16–40, 40–64s on longer tracks). Active
shortlists refine up to 52 seconds around the incoming cue and a bounded outgoing
look-behind region, so an initially moderate preview can become a longer confirmed
plan. Refinement is split into <=32s windows; contiguous detailed coverage is merged.
The preview is not the final timeline marker.

This look-behind was verified necessary: the first controller test refined only
its shorter preview, which excluded the earlier 64-beat region and selected 16s.
With bounded wider refinement it confirms and executes the full 32s plan.

Existing dual Media3 decks actually overlap. Preparation seeks to the final cue,
keeps the deck paused/silent until ready, and promotes it once without restarting.
The selected duration controls outgoing release independently of file ending.
Real start/ready/completion states and the marker remain synchronized.

`djAutomation` feeds the actual deck volume and PCM processor, and the numerical
fixture tests. Full mixes use a smooth nonlinear gain progression, an incoming
bass reduction, incoming treble opening, bass transfer and a gentle outgoing treble
reduction. The incoming low end opens before the outgoing bass is removed to avoid
an extended bass hole. Strong simultaneous bass selects this handoff; sparse pairs
can use a gentler beat blend. No extreme filtering or all-effects-on-every-song rule.
Gain matching is bounded (0.65–1.0), rather than forcing active music down to a
nearly silent track's level. Every envelope retains correlation peak headroom;
no permanent music-volume decrease is introduced.

Existing pitch-preserving Sonic tempo adjustment, phase-error correction and smooth
rate recovery remain. Independent Android AudioTracks are not sample-locked;
Bluetooth/device latency and musical quality require listening/device testing.
Worst-case correlation protection can still reduce middle-of-mix RMS. There is no
shared-output limiter or promise of perfectly constant perceived loudness. External
EQ can boost/clip after app processing. Session handling from PR #12 is unchanged.

## Recovery and failure reporting

Successful windows/cache results are retained. Eligible long-window failures get
**one** 16-second retry: insufficient/truncated data, byte budget, decoder failure
or source I/O, provided time remains. Authorization denial, non-seekability,
unsupported formats, cancellation and timeout are not repeatedly retried.
Refinement can replan over covered regions or use trustworthy coarse rhythm as
Tier B; failed source/preparation or missed starts still use Tier C/normal playback.
No audio/key/tempo value is invented to avoid a fallback.

The full suite exposed a real worker-cancellation crash: Kotlin `queue.toList()`
could observe one entry and then iterate an emptied concurrent queue. Cancellation
now uses the queue's atomic array snapshot. A 500-cycle draining/cancellation stress
regression covers this. Rejected feature/planner submissions return explicit
`WORKER_QUEUE_FULL`, rather than masquerading as canceled analysis.

All decoding/DSP/planning jobs still share **1/2/4 analysis workers**, queue capacity
**8**, feature cache **64**. The former four waiting slots could not admit all six
new scan jobs in Efficient mode. Eight is a fixed bound, not an unbounded queue or
extra executor. Coarse budget remains 90s; fresh refinement budget <=40s is constrained
by remaining lead, and executing windows have <=25s deadline budgets. Decoder reads
retain per-attempt 3MiB remote/12MiB full-cache limits. A permitted retry is an
additional bounded attempt; no whole-song/prefix-discard workaround is introduced.
Codec, Media3 and existing I/O infrastructure may use internal threads beyond these
analysis-task counts. No audio analysis occurs in Compose or while AutoMix is off.

Stable diagnostic codes include unresolved/non-seekable/denied/I/O source, unavailable
or failed decoder, unsupported/encrypted format, timeout/cancellation/byte budget,
insufficient data, low beat confidence, incompatible tempo/key, vocal/spectral conflict,
protected intro, unstable energy, refinement/preparation failure, missed start,
queue/source changes and duration mismatch/unknown. Unknown duration and unavailable
Android codecs can still legitimately prevent analysis. Exceptions export controlled
class categories/codes, never their original messages or HTTP headers.

## Stats and log export

The existing compact overlay adds Tier A/B/C and actual start when known. Confirmed,
preparing, ready, mixing, fallback, recovering and completed remain distinct. The
marker uses the real final pair/timeline, including recovered rhythmic plans;
basic fades and invalid plans do not get intelligent markers.

**Press and hold Stats for Nerds** to export. Normal tap does nothing. An in-flight
atomic gate prevents duplicate exports; TalkBack's custom action also provides
“Export AutoMix diagnostic log.” This works with AutoMix disabled because history
belongs to the playback service, not the current analysis operation.

- Android 10+: scoped `MediaStore.Downloads`, `text/plain`, pending insert → write/
  close → publish. Failure/cancellation removes the partial owned URI and never
  claims success. Filename: `tinium-automix-log-YYYY-MM-DD-HH-mm-ss-<unique>.txt`.
- Android 8/9 (minimum SDK 26): system CreateDocument picker; select Downloads.
  Only the chosen document is granted. Confirmation says the selected document
  was saved. No broad storage/MANAGE_EXTERNAL_STORAGE permission or file:// URI.
- Serialization/file writing run off the main/audio threads. No continuous public
  file writing. No APK/download/release action is triggered.

The recorder keeps at most **30 pair attempts ×128 events** in memory. A fresh
service has a new labeled session and independent private hash salt. Reports contain
actual installed version/code, Android/model, enabled/mode, timestamps/schema,
retained-history counters, salted track hashes, successful regions and estimates,
shortlisted candidates/scores/rejections, plans, preparation/start/completion,
retry/recovery/failure categories, worker/queue information and sampled automation.
Counters count actual starts/completions, not merely analysis or plans. Automation
records are commands to the decks/filters, not measured post-EQ/Bluetooth output.
Unknown/uncollected fields are omitted. No titles, signed media URLs, tokens, cookies,
request headers, private paths, raw PCM or unrestricted logcat is included.

## Verified and unverified

Generated audio tests exercise actual analyzer/merger/planner/controller and PCM
filters with a constant audible bass/drum groove, plus a quiet audible intro:
**216s outgoing; start 182.060s; incoming cue 18.040s; 64 beats/32s; bass handoff**.
All three modes execute that plan. Exactly 32s of PCM is rendered, both contributions
are present, bass and treble processing alter samples, peak stays below the tested
bound, the outgoing deck ends before the file and the incoming track is not restarted.
Other coverage verifies 16/32-beat selection, protected intros/vocal gates, bounded
retry, refinement recovery, cancellation/queue bounds, report sanitization/history,
filename uniqueness and transactional export failure handling.

These are JVM decoded-PCM/simulated-deck/storage tests. They do **not** run Android
MediaExtractor/MediaCodec, touch gestures, MediaStore/document providers, physical
outputs, external EQ or audible listening. No emulator/device/instrumentation runtime
is available. Full legacy Android Lint is not repeated; source/resource compilation,
XML/whitespace checks and focused tests are used. No signed release APK is generated.

## Galaxy S25 Ultra checks

1. After review/merge, manually build a fresh private candidate via existing Actions.
   Do not retarget a reserved version. Enable AutoMix; start with Balanced.
2. Enable the existing transition marker and Stats for Nerds in Appearance. Queue
   steady instrumental/percussive music and a compatible introduction well before
   the outro. Tier A, bass/beat technique, long overlap and Ready should appear.
3. Compare Starts with Actual/Mixing. Both songs should be clearly audible during
   the blend; incoming Cue should be nonzero where justified, without restarting
   after promotion. A Tier B recovery or Tier C safe fade is a different outcome.
4. Try 1/2/4 modes, consecutive mixes, voice-heavy/incompatible tracks, downloaded/
   cached/remote sources, missing next song and network loss. Do not expect Tier A
   for every song; inspect the reason instead of treating all fades as success.
5. Normal-tap Stats: no file. Hold once: one saved confirmation. Open Samsung
   **My Files → Downloads**, find `tinium-automix-log-…txt`, read and share it using
   Android's normal file share action. Try again after turning AutoMix off: retained
   history should still export. Check write failure handling if storage is unavailable.
6. Test every player/slider style, light/dark/pure-black, large font, pause/resume,
   seek/skip, queue/shuffle/repeat, screen-off, lock-screen, calls/focus, speaker,
   Bluetooth/wired, internal EQ and Poweramp separately. Confirm effects continue
   on incoming tracks and no extra deck remains active. Nothing resets EQ settings.

A report is safe by design, but review it before sharing. Optional filtered logcat:
`adb logcat -v threadtime TiniumAutoMix:I TiniumAudioSession:I '*:S'`.

## Reference/license

BitChord remains at inspected revision `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`.
TransitionPolicy/Planner candidate/vocal/entry preparation concepts were re-read.
Their Orchard headers are AGPLv3+ despite the repository's GPLv3 root. No source,
model, binary, asset or unrelated engine was copied. These are independent Tinium
implementations; existing BitChord/Orchard/VIVI attribution and licenses remain.
No added dependency/ABI/model packaging or measured APK-size claim.

## Final cloud checks and exact changed paths

Passed **167 JVM tests per flavor** (Arm64 GMS Debug and Universal FOSS Debug),
zero failures/errors/skips, including all existing external session lifecycle,
Sonic/DSP, playback/settings/updater tests and the new DJ/controller/export cases.
Both Universal GMS Release and Universal FOSS Release source-compilation targets
passed; merged manifests retain `com.yren411.tinium`. Strings XML parsing and
`git diff --check` passed. APK assembly/signing, Android instrumentation/provider/
gesture tests, rendered device UI and audible/Poweramp/Bluetooth verification were
not run. No workflow/signing/version reservation or release publication was changed.

Command:

```bash
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest \
  :app:compileUniversalGmsReleaseSources :app:compileUniversalFossReleaseSources \
  --no-configuration-cache --max-workers=4 --console=plain
```

- `app/src/main/kotlin/com/music/vivi/playback/MusicService.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AnalysisWorkers.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixController.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixFailures.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixLogExport.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixModels.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixPlanner.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixRecorder.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixStatus.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/DjAutomation.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/DjCandidateSelector.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/TrackFeaturesAnalyzer.kt`
- `app/src/main/kotlin/com/music/vivi/ui/component/PlayerDiagnostics.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/com/music/vivi/playback/automix/AnalysisWorkersTest.kt`
- `app/src/test/kotlin/com/music/vivi/playback/automix/AutoMixAnalysisTest.kt`
- `app/src/test/kotlin/com/music/vivi/playback/automix/AutoMixLogExportTest.kt`
- `app/src/test/kotlin/com/music/vivi/playback/automix/AutoMixPipelineTest.kt`
- `app/src/test/kotlin/com/music/vivi/playback/automix/DjMixAnalysisTest.kt`
- `app/src/test/kotlin/com/music/vivi/playback/automix/DjMixControllerTest.kt`
- `docs/AUTOMIX.md`
- `docs/AUTOMIX_DJ_MIXING_AND_LOG_EXPORT.md`
- `docs/AUTOMIX_PIPELINE_AND_DIAGNOSTICS.md`
- `docs/AUTOMIX_QUALITY_AND_EXTERNAL_EQ.md`
- `release-notes/unreleased.md`
