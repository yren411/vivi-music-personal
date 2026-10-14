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

## Fixes

- Correct the About screen's build label for beta releases, while retaining nightly
  labels and identifying ordinary debug builds as development. Display the installed
  application's version name and build number.

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
