# AutoMix quality and external equalizers

This update keeps Tinium's Media3 service, sources, queue, internal EQ and settings.
It changes musical planning and fixes the separate per-output effect lifecycle.
Read [the investigation](AUTOMIX_QUALITY_INVESTIGATION.md) for confirmed baseline
causes. No signed APK, version reservation or public release was created here.

## Musical planning

### Scan, shortlist, refine

For tracks at least 3 minutes long, coarse analysis covers the last **90 seconds**;
for tracks at least 90 seconds long it covers the last **60 seconds**; shorter
tracks retain at most 32 seconds. Incoming analysis covers at most the first 32
seconds. Windows are at most 30 seconds for wider tail scans, otherwise 16 seconds.
An ordinary long pair has five coarse jobs, followed by **two selected refinement
windows** of at most 32 seconds. The coarse chroma/voice pass runs every 500ms;
the refinement pass runs every 100ms. Both measure onsets at 20ms and use the same
22,050Hz analysis PCM. Audible audio is not reduced. All performance modes perform
identical work and differ only in concurrency. Merge/section analysis and candidate
scoring run in the same pool; the main looper only consumes finished decisions
and controls playback. No extra planner executor is created.

One service-owned executor still uses **1/2/4 workers** and a four-job waiting
queue. A pair-wide 40-second analysis deadline bounds work. Each job reads at most
3MiB from uncached/range-supported sources, or 12MiB from fully cached audio;
coarse plus refinement therefore bounds source reads to 21MiB remotely (usually
much less for compressed audio). Four 64KiB source blocks per job bound read
memory; no entire track is downloaded/retained. Unsupported ranges, encryption,
codec failures or exhausted budgets degrade to fallback/normal playback. Cache
writes remain disabled. Platform codec/network threads are outside this worker
count; cancellation may wait for a bounded native/network call to return.

Rendition/window/detail-version keys deduplicate jobs and cache up to 64 compact
results. Mutable local URIs and unknown renditions are not reused from cache.
Queue identity invalidates preparation separately from audio identity; reordering
does not discard a valid immutable rendition's cached analysis. Known format
changes are checked before decoding. Failed windows no longer erase successful
ones, and tempo confidence remains local to each analyzed segment.

### Rhythm and sections

Positive **spectral flux** detects attacks even when compressed music has little
RMS variation. Autocorrelation proposes tempo, then phase concentration and
repeated onset coverage gate its confidence. Stationary chords and low-confidence
grids cannot authorize stretching. A four-beat bar estimate requires repeated
measured low-frequency accent dominance across at least four observed bars.
Uniform beats produce no downbeat estimate. This is a limited accent-supported
4/4 estimate, not universal meter detection.

Section boundaries use sustained before/after energy and voice-risk changes.
The voice proxy now discounts spectrally flat broadband percussion. It still
cannot reliably distinguish voices from every instrument: **no source separation,
trained vocal recognition, chorus classification or semantic phrase detector is
claimed**. Reliable accent boundaries guide snapping; otherwise use measured
beats or conservative timing. The old arbitrary beat-index phrase bonus is gone.

### Candidate pairs and safeguards

Outgoing candidates include the safe end region and measured section changes
within the final 60 seconds and final third of the track. An earlier candidate
needs boundary confidence >=0.7, a substantial energy/vocal decline, sustained
low vocal risk through the *remaining analyzed outro*, no later energy buildup,
and reliable coverage through the ending. A prominent later vocal-risk return
also rejects the early cut, even when its duration is a small percentage of the outro. Repetitive beats alone do not justify
cutting a song. Early starts require compatible stable rhythms, harmonic policy
and low collision risk in **both** sections.

Incoming candidates include the first useful sound/beat and measured intro
changes. Cues are limited to 12 seconds and roughly the first tenth of a track
(with leading quiet/silence allowed within that cap). Later cues are rejected if
they skip prominent vocals or substantial energy; this preserves important
introductions. Incoming/outgoing candidates are evaluated together.

The ranking combines measured outro evidence, rhythm/phase confidence, local
vocal risk, local RMS continuity and skipped-intro penalties. In the code,
early candidates start with 0.3 + 0.4×boundary evidence, end candidates with 0.35;
both add 0.2×rhythm quality, 0.05×vocal clearance and 0.05×energy fit, subtracting
0.01 per skipped incoming second. Strong compatibility/readiness gates precede
ranking; score cannot bypass those gates. Rejection counters explain unsuitable
outros, skipped intros, uncertain rhythm, changing tempo and missing refinement.

A reliable instrumental pair can use 16/32 measured beats (6–24 seconds), depending
on evidence and available intro material. A weaker end-region beat mix uses about
eight beats. Vocal conflicts use about 1.5 seconds, known key conflicts about 2
seconds, uncertain rhythm about 3.5 seconds. Unusable analysis has its own 2.5s
safe fade or normal playback. Known key incompatibility disables tempo matching;
unknown keys do not establish a claimed harmonic match.

## Execution and sound

Plans have independent outgoing start, incoming cue and overlap length. An early
mix **can finish before the file ending**; only a confirmed sustained outro may
be omitted. The outgoing deck is stopped/released after that overlap, cannot
advance its queue, and never continues indefinitely at audible volume. The
incoming deck retains its position/full queue and is promoted once without restart.

Incoming preparation starts up to **20 seconds before** the selected boundary.
Readiness and actual cue position (within 100ms) are checked before start. A missed
intelligent boundary releases the prepared deck and schedules an honest later
AutoMix fallback if there is time; it does not start an off-beat early mix.

Local transition-region RMS replaces whole-window/body loudness matching. Outgoing
attenuation is introduced over two seconds rather than jumping immediately.
Smoothed low-frequency handoff opens incoming bass before removing outgoing bass,
avoiding simultaneous deep mid-blend cuts. Gentle treble filtering remains for
harmonic conflicts. Correlation headroom remains conservative and can make equal
level mixes quieter in the middle; it applies during overlap and recovers, not as
a permanent reduction to all music. External EQ boosting can still overload output.

Pitch-preserving Sonic matching stays within 4%, with at most 1% extra drift
correction. Corrections now have a 35ms deadband, run no faster than twice per
second, and change by at most 0.2 percentage points each update. Recovery starts
from the last applied rate and returns gradually over three seconds. Independent
AudioTracks are **not sample-locked**: JVM tests do not prove OEM/Bluetooth timing
or absence of audible artifacts. AutoMix settings, defaults and mutual exclusion
with Crossfade are unchanged.

## External EQ lifecycle fix

The old single opened flag suppressed new-player OPEN events and could CLOSE the
wrong primary ID after a swap. That code defect matches the reported pause/resume
symptom; Poweramp's actual device behavior has not been observed here.

Every local player now owns an independent observer of public Media3
`onAudioSessionIdChanged`, `onAudioTrackInitialized` and `onAudioTrackReleased`
callbacks. Readiness or an allocated ID alone does not announce a nonexistent
output. Prepared AudioTracks may be announced before they become audible so an
external effect can attach in time. Pauses and promotion retain the session.
Replacing a valid ID opens the new ID before closing the former ID; reference
counts prevent closing an ID still owned by another output. Delayed release of
an old AudioTrack cannot close an initialized replacement. Disposal prevents
late callbacks from reopening released sessions.

Crossfade and AutoMix keep independently allocated sessions; they do **not** force
concurrent players to share an ID. Documented Android OPEN/CLOSE effect-control
broadcasts contain the valid ID, package name and music content type. Incoming
transition decks use software PCM (float/direct playback parameters and offload
are excluded); normal single-player settings remain available. Internal EQ still
has exactly one existing processor per deck, with the saved profile applied to
new decks and registration removed on release. Hardware normalization follows
the actual primary session; stale asynchronous gain updates are rejected.

Crossfade's duration/curve are preserved. Standby publication is suppressed;
metadata/session promotion is explicit; outgoing buffering is distinguished from
user pause; outgoing automatic advancement is blocked. Error/seek/skip cleanup
releases obsolete decks, selected routes are applied to all decks, and destroyed
service collectors are canceled after synchronous queue saving. A failed MediaSession swap restores the outgoing
player rather than leaving controls on a released output.

Android audio effects and third-party EQ implementations vary. Poweramp may
support only one session for an app/output, or a Bluetooth/offload/remote route
may not support insertion effects. Announcing both sessions does not force another
app to process both. A guaranteed single-output effect path would require a
shared PCM mixer and a substantially larger decoder/session redesign. No root,
Poweramp-specific/hidden intent, settings reset or automatic pause/seek workaround
is used. Cast/Listen Together and incompatible AutoMix settings retain normal
playback.

## Galaxy S25 Ultra verification

Use a **fresh unpublished candidate** built from the reviewed source, with the
normal permanent key and unchanged version allocator. Choose a new candidate
name; never retarget a reserved version. No workflow is dispatched by this task.
Use the GMS/FOSS asset appropriate to your installation.

1. Begin with downloaded songs, internal EQ disabled, and your existing Poweramp
   setup enabled. Confirm ordinary playback is visibly listed as processing in
   Poweramp where its UI exposes this. Briefly compare external EQ on/off at safe
   volume using the same preset to learn the audible difference; do not reset it.
2. Disable AutoMix, enable Crossfade with your existing duration/curve. Test
   automatic end transitions, next/previous buttons and queue taps. Run at least
   five consecutive transitions without pause/resume. Check the **incoming** song
   retains the audible preset and Poweramp processing indication.
3. Enable AutoMix/Balanced. Choose songs with an extended instrumental outro and
   a quiet/strong drum intro, then vocal-heavy and incompatible-tempo pairs.
   Confirm transition/cue timing, both audible songs, one metadata change, and no
   outgoing audio after overlap. Compare Efficient/Performance separately.
4. Test pause/resume, seek during overlap, rapid skips, queue edits, repeat/shuffle,
   screen off, notification/lock-screen controls and interruptions/calls. Resume
   should not be needed to restore EQ. Playback must never leave two decks alive.
5. Repeat with speakers, wired output if available, Bluetooth and route changes.
   Compare external EQ disabled; test Tinium internal EQ alone, then both enabled
   if desired. Check presets survive songs/restart and listen for doubled EQ,
   clipping, bass holes, gain jumps, timing drift and stretching artifacts.
6. Restart playback/service/app and repeat normal, Crossfade and AutoMix checks.
   Verify queue/settings persist. Compare Poweramp's incoming-song processing
   status and audible on/off difference, rather than assuming music playing proves
   effects remain attached.

Install Android Platform Tools on Windows if you want diagnostics. Capture only
our tags from PowerShell (never share unfiltered logs containing media/auth data):

```powershell
adb logcat -v threadtime TiniumAudioSession:I TiniumAutoMix:I '*:S'
```

Expected pattern: OPEN outgoing ID, OPEN incoming ID, PROMOTE incoming, CLOSE only
outgoing after it stops/releases; incoming stays open across promotion/pause.
After a route/Track replacement, the new live ID must not be closed by an older
release callback. `deck-N` is a local counter, not a song/account ID. Route logs
show device types, not names, addresses or personal details.

AutoMix logs selected technique/start/cue/duration/score/phase/reason, preparation
lead, actual ready cue, start, missed boundary and handoff completion. Detailed
scan/refinement rejection and failure-category logs are debug-only; normal builds
keep concise per-transition events. No audio URL, token, credentials, headers or
exception messages are added to these diagnostics.

## Licensing and validation limits

BitChord main remains `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`. Candidate/section,
phrase-switch and tempo/bass designs were revisited as references. This is an
independent extension of Tinium's existing code, with no imported GPL/AGPL source,
ONNX models, native assets or new dependency. Existing credits/license restrictions
remain intact. See [AUTOMIX.md](AUTOMIX.md) for the component/license review.

Generated PCM tests cover compressed onsets, measured accents, rejection of fake
bar evidence, and actual numerical overlap. Feature fixtures test early outros,
later cues, vocals, tempo/key conflicts, candidate scores and partial windows.
Controller tests execute an early overlap and stop outgoing playback before its
file end; missed early boundaries use a later fallback. Worker tests also cover completion/cache races, queued-work cancellation ordering,
and planning jobs sharing the analysis cap. Session tests assert exact
ID ownership/event order, replacement/release races, promotion/rollback, errors,
pauses, cancellation and destruction. These do **not** prove Android decoder,
AudioTrack output, perceived DJ quality or Poweramp attachment. Those require the
phone checks above. Full-project Android lint and real-device listening are not
claimed as passed.

Final cloud validation: **140 unit tests pass per variant** (ARM64 GMS debug and
universal FOSS debug), including 72 AutoMix/worker cases and 12 output/session
cases per variant. Universal GMS/FOSS release source compilation passes without
packaging/signing APKs. Merged manifests retain `com.yren411.tinium`; unchanged
development/beta/stable/publish workflow syntax and `git diff --check` pass.

Post-PR #12 analysis lifecycle and player diagnostics are documented in
[AUTOMIX_PIPELINE_AND_DIAGNOSTICS.md](AUTOMIX_PIPELINE_AND_DIAGNOSTICS.md).
The audio-effect session registry/output lifecycle from this change is preserved.
