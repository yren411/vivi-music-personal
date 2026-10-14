# Tinium branding assets

Both owner-supplied 1080 × 1080 PNGs remain unchanged:

- `Tinium Logo with Background.png`: full-color circular purple gradient with
  shaded headphones/stars and transparent corners. Packaged verbatim once as
  `drawable-nodpi/tinium_logo_background.png` (about 452 KiB).
- `Tinium Logo.png`: the original transparent mark, still packaged verbatim as
  `drawable-nodpi/tinium_logo.png` and used to derive compact/specialized assets.

The background version is used for the system startup splash, onboarding hero
and existing completion transition, 36dp main app-bar logo beside the existing
Tinium title, 40dp About tile, and 100dp/96dp Wrapped introduction/conclusion.
`TiniumBrandLogo` uses `Image`, `ContentScale.Fit` and no color filter, preserving
colors and aspect ratio in light/dark/dynamic themes. Existing accessibility
labels are retained; adjacent-title/decorative logos avoid duplicate narration.
No new splash activity, timer or startup delay is added. The existing onboarding
completion timing and navigation are unchanged.

The shared `Theme.vivimusic.Starting` already covers WelcomeActivity and
MainActivity. Its icon now uses `tinium_splash_logo.xml`: a transparent 288dp
canvas with the complete circular PNG centered at 180dp. Android's no-icon-
background splash has a 192dp safe circle; the artwork has almost 6dp radial
clearance. This layout-only adaptation preserves every source pixel/design detail
without cropping, stretching, tinting or creating a second background. AndroidX
uses the same drawable on pre-Android-12 devices. Light/dark splash surfaces remain
white/#121212 respectively; no splash-specific icon background is added.

Keep the original transparent assets in the 16dp lyrics/share watermark, compact
About menu item, player placeholders and share-image branding. The regular,
round, adaptive/static launcher and store-preview icons now use the full
background logo. Adaptive foregrounds fit the entire circular design inside the
66dp safe circle; the adaptive background layer stays transparent, adding no
second rounded-square backdrop. Legacy/store icons use the complete circle at
their required resolutions. Source artwork is unchanged; only technically
required launcher sizes/padding are derived.

Android status-bar small icons and themed launcher icons are alpha-only
silhouettes: a gradient cannot appear there, and using the opaque background's
alpha would produce a solid white disk. Keep the recognizable transparent
headphone/star silhouette for those platform-required slots. App-update
notifications use the full-color background logo as their large icon. Playback
bootstrap notifications and Media3 notifications without album artwork use the
same large-icon fallback. Actual album artwork, controls, notification IDs,
channels, permissions and preferences stay intact; asynchronous artwork updates
also preserve that policy. Android/OEM notification layouts decide whether the
large icon is visible. Existing TV banner, Wrapped cover art and official banner
remain unchanged.

`python scripts/generate_tinium_assets.py` derives launcher/store icons from the
background PNG, retains transparent specialized assets, and copies the UI PNG
verbatim. It requires Pillow/numpy.
`python scripts/validate_tinium_branding.py` checks source identity, splash safe
geometry, UI binding launcher gradient, retained compact/monochrome assets and notification bindings. Run an
Android debug build as well; static checks cannot establish actual OS/OEM rendering.

On a phone (including Galaxy S25 Ultra), cold-launch with light/dark mode, then
check the app-bar spacing, About tile, Wrapped screens, launcher shapes/themed
icons and normal navigation. Test onboarding on a fresh test installation or use
its existing `FORCE_SHOW` activity flag with ADB without clearing app data:

```bash
adb shell am start -n com.yren411.tinium/com.music.vivi.WelcomeActivity --ez FORCE_SHOW true
```

No extra secrets or permissions are needed. Debug APKs need the same debug key
as an existing debug installation; release-signed apps cannot be replaced by a
new debug-signed APK in place.
