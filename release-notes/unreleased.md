# Unreleased

This is an unversioned draft for review. No release version or tag is assigned.

## Branding

- Use the updated Tinium logo with its purple gradient background during startup,
  in the onboarding introduction and completion transition, and beside the
  Tinium title in the main app bar.
- Use the same full-color artwork in the About tile and Wrapped introduction
  and conclusion, with proportional scaling and consistent colors across themes.
- Use the background logo for regular launcher icons and full-color app-update
  notifications, with the same playback notification fallback when album artwork
  is unavailable. Actual album artwork and playback controls remain unchanged.
- Keep the transparent Tinium mark for compact UI, player placeholders, sharing
  and Android's required monochrome status-bar/themed icons.
- Reduce only the system startup logo's display size, adding transparent margins
  around the unchanged gradient artwork. Launcher icons and in-app branding retain
  their existing appearance; Samsung's launcher animation remains OS-controlled.

## Fixes

- Correct the About screen's build label for beta releases, while retaining nightly
  labels and identifying ordinary debug builds as development. Display the installed
  application's version name and build number.

## AutoMix

- Add opt-in, analysis-driven AutoMix above Crossfade in Player and audio settings.
  It analyzes bounded audio windows for rhythm, key, energy and transition level
  balancing, then executes actual dual-decoder overlaps with beat-aligned timing,
  conservative tempo matching and selective bass/filter processing.
- Add persistent Efficient, Balanced and Performance analysis modes with 1, 2 and
  4 bounded workers. Preserve the previous Crossfade choice while the modes are
  mutually exclusive, and explain software-audio conflicts and safe fallbacks.
- Credit BitChord's reference architecture. Tinium's implementation is independent;
  no BitChord/Orchard code, native libraries or ONNX model assets are copied.
  Vocal and phrase estimates are approximate DSP heuristics; perceived audio
  quality and Android decoder/output behavior still require phone testing.

## Release preparation

- Add project instructions for Codex to prepare accurate release-note drafts
  alongside meaningful changes, using a chosen release version when assigned or
  this unversioned draft otherwise.
- Let unpublished beta and production test builds use the accumulating draft
  automatically when version-specific notes are missing, or clearly labeled
  internal-testing notes when no meaningful draft exists.
- Preserve exact candidate notes, source hashes and review status in immutable
  artifacts. Block unreviewed drafts/placeholders from public publication, and
  require reviewed version-specific source notes plus explicit review confirmation.
  Coordinated version codes, permanent signing and manual publishing remain intact.

## Checks before assigning a release

- Review wording and copy only changes intended for the chosen fresh version.
- Verify cold startup, top-bar spacing, onboarding and About/Wrapped branding on
  a phone in light/dark mode. Device appearance/navigation checks remain manual.
- These notes do not claim a newly published release or a tested release-signed APK.

## AutoMix quality and external equalizers

- AutoMix can identify sustained instrumental outros beyond the old near-end
  window, pair them with suitable intro cues, and finish a planned overlap before
  the outgoing file ends when evidence supports it.
- Added wider coarse scanning, targeted refinement, spectral rhythm confidence,
  measured accent/section boundaries and candidate scoring. Vocal/structure
  estimates remain approximate; unsafe early cuts and intro skipping are refused.
- Smoothed bass handoffs, transition-region level matching and tempo corrections;
  missed intelligent boundaries use a later safe fallback when possible.
- Corrected external effect-session ownership across prepared, promoted and
  fading players for both Crossfade and AutoMix. Real AudioTrack callbacks prevent
  stale/incorrect IDs and delayed old releases from closing a replacement session.
- Improved Crossfade metadata, seek/error cleanup and promotion recovery while
  preserving its duration/curve. Poweramp Equalizer compatibility and listening
  quality still require device validation; no third-party settings are reset.
