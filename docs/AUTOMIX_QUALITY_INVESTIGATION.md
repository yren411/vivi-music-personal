# AutoMix timing and external effect-session investigation

Baseline: Tinium main `cd0d1e29` (AutoMix PR #11). Reference rechecked:
BitChord `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` (still current main).
No reference code/model is copied; the AGPL notices described in AUTOMIX.md remain relevant.

## Confirmed code causes

- Planner starts from end minus overlap, searches at most the last 12 seconds,
  caps incoming cue at 2 seconds, and computes overlap as end minus start. An
  instrumental outro 20–40 seconds earlier cannot be selected. Four-beat index
  modulo is only a weak heuristic, not downbeat or phrase evidence.
- Decoder jobs cover only 32 seconds per side. Merging requires every job to
  succeed and applies the minimum beat confidence across both segments; a failed
  window or tempo disagreement removes useful local evidence. No diagnostics
  establish how frequently this happens on the user's device.
- Controller deadlines/preparation are tied to that near-end policy. Plans cannot
  represent independent early starts and overlap completion before the song end.
- MusicService uses one `isAudioEffectSessionOpened` flag. OPEN/CLOSE use the
  current player's ID, not the ID actually opened. Newly created/fading players
  have no independent session listener. Promotion can suppress incoming OPEN,
  send CLOSE to an unopened incoming ID, and leave an outgoing session stale.
  Invalid/unset IDs are not validated. Pausing toggles this global state and can
  reopen the incoming ID. These are lifecycle defects consistent with the
  reported Poweramp symptom, not proof of its device-specific cause.
- LoudnessEnhancer is not associated explicitly with the session it was created
  for. An existing enhancer can survive primary replacement on the wrong ID.
- Crossfade publishes standby players before promotion, swaps before readiness,
  and removes outgoing service listeners. The effect lifecycle must remain
  attached independently of primary metadata listeners.

## Proposed supported architecture

First add a per-output lifecycle observer and a testable session registry with
valid-ID checks and reference counts. OPEN a prepared output once its AudioTrack
session exists, retain attachment during pause/promotion, close the correct ID
on invalidation/release, and open a replacement ID before closing the previous
one. Each deck retains its independently allocated Media3 session: do not force
concurrent players to share an ID. Keep transition outputs in software PCM, and
track hardware normalization by primary session. Use documented Android effect
control-session broadcasts; no pause/seek hacks or third-party intents.

Then add bounded coarse head/tail scanning, shortlist structurally promising
regions, and refine them in the existing 1/2/4 worker pool. Store segment-local
beat confidence and measured section boundaries; never relabel modulo beats as
phrases. Score outgoing/incoming pairs using structure, rhythm, vocal risk,
energy, harmonic compatibility, skipped content and readiness. Require strong
sustained outro/intro evidence for early cuts or later cues. Keep conservative
near-end fallback when that evidence is missing. Execute the selected overlap
independently of the original file ending, prepare in advance, and log missed
boundaries separately from poor/unsupported analysis.

## Evidence and limits

Media3 1.7.1 `DefaultAudioSink` initializes/changes sessions when AudioTracks are
created; `ExoPlayer.setAudioSessionId` is public but does not guarantee that two
independent outputs sharing an ID will be handled consistently by external EQs.
The safest incremental solution announces each real output separately. References:
[Media3 sink](https://github.com/androidx/media/blob/1.7.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java),
[Android effect session API](https://developer.android.com/reference/android/media/audiofx/AudioEffect#ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).

JVM tests can prove ID ownership/event order and synthesized DSP behavior, not
Poweramp attachment, OEM routing, actual decoder output or perceived musical
quality. A shared-output mixer could offer a single effect session but requires
substantial source/decoder/session changes and is outside this focused approach.
