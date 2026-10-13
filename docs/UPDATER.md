# Tinium updates from GitHub Releases

Tinium checks only public releases at
`https://api.github.com/repos/yren411/vivi-music-personal/releases`.
No GitHub account, token, new signing key, secret, or additional app permission is
needed. Creating a build, reserving a tag, or uploading an Actions artifact does
**not** make it an available update. Use the existing separate build/test/publish
process in [RELEASING.md](RELEASING.md). This change does not allocate a release
version, publish a release, or alter the permanent signing configuration.

## What users see

Open **Settings → Updates → System update**. Opening this screen performs a fresh
check, including when **Auto Update Check** is disabled. **Check for update** or
**Retry** performs another fresh check. An in-progress check is shared between
startup, settings, and the screen; tapping repeatedly does not start duplicate
requests. Requests have connection/read/call timeouts and a 60-second overall check limit and are cancelled when the
calling screen/activity leaves the composition.

- **Checking:** a progress indicator; no claim that the app is up to date.
- **Update available:** the published Tinium version, the correct APK's actual
  GitHub asset size, and the current GitHub release description.
- **Up to date:** “You are already on the latest version.” The installed version
  name and build code come from Android's package information for the running
  app, not from a GitHub tag or a fixed version constant.
- **No published releases:** “No published Tinium updates are available yet.”
  This is expected before the first public release. There is no upstream fallback.
- **Error:** a recoverable network, rate-limit, HTTP, or metadata error and a retry
  button. The installed version and last successful check remain visible.

The successful-check time is saved as epoch milliseconds in app-private settings,
then displayed in the device's current locale/timezone. Only a completely
successful check writes it, including a genuine empty release list. Network,
HTTP, rate-limit, invalid JSON, missing metadata/assets, and partial pagination
failures do not change it. The old updater stored a display-formatted local string;
that ambiguous timestamp is not treated as a successful Tinium check.

## Versions, channels, and variants

Each candidate must have schema-version-1 `release.json` in the format already
produced by the release workflows. The checker validates the repository,
application ID, tag/version/channel agreement, numeric versionCode, source/build
metadata, certificate/checksum format, and both universal APK entries. It matches
the APK filenames, sizes and URLs against actual assets of that published release.
Conflicting version codes and malformed eligible release metadata cause an error,
so the app does not guess that an unverified release is an update. Repair the
public release rather than supplying alternate URLs or fallback tags.

Published releases are paginated, up to 1,000 entries. A history exceeding this
bound produces an error rather than a false up-to-date result. Drafts, unpublished
releases, reservation tags and foreign tag formats are ignored. The checker scans
numeric codes; publication dates and version-name sorting do not select updates.

- **Beta Updates off:** published stable releases only.
- **Beta Updates on:** published stable and beta releases, choosing the highest
  compatible versionCode. A newer stable beats an older beta.
- Only a versionCode **strictly greater** than the installed code is offered.
  Equal codes with different names do not become updates. A newer installed beta
  cannot be replaced by an older stable through this checker. Wait for a stable
  with a higher code; Android normally refuses lower-code APK updates.
- GMS builds select GMS; FOSS builds select FOSS, using the compiled flavor's
  existing `CAST_AVAILABLE` flag. There is no manual variant override.
- The selected universal APK must support the device's SDK and at least one of
  its supported ABIs (an empty ABI list means no native-code restriction).
  A valid but incompatible release is excluded from selection.

Changing **Beta Updates** immediately invalidates the old status/indicator and
checks the new channel policy. An in-flight response for a changed policy is not
committed. Existing update and music-notification preferences remain intact.

## Download and install

**Download APK** validates the exact release tag, filename, installed flavor,
versionCode and HTTPS repository asset URL again, then opens that asset's actual
`browser_download_url` with an external `ACTION_VIEW` browser intent. The browser
handles GitHub's normal redirects and downloads the APK. Tinium does not fetch APK
bytes, request installer permissions, extract ZIPs, back up automatically before
installation, or launch the package installer. Returning from the browser has no
internal download/install side effect. If no browser can open the link, the app
shows an error. **Later** closes the update screen.

After the browser finishes, open the APK yourself and follow Android's installer.
If prompted, allow installation for **your browser/file manager**, not Tinium.
Android checks the package, signing certificate and version restrictions. The
release publisher already cryptographically verifies the permanent certificate
and APK checksums. The updater validates public metadata; it cannot hash or
cryptographically inspect an APK it deliberately does not download.

A development APK signed with the debug key cannot replace a release-signed APK
in place, or vice versa. Use the permanent release-signed candidates for testing
release-to-release updates. Back up data before any uninstall or manual channel
change requiring a downgrade. Normal backups, playlists, playback, music downloads,
sharing/FileProvider support and playback notifications are unchanged.

## Release notes and automatic checks

The displayed notes come from GitHub's release **body**, revalidated on each
successful fresh check. Editing that description on GitHub updates the displayed
notes without rebuilding the app. Keep the publisher's hidden `tinium-release`
comment intact; the UI strips all HTML comments. It supports headings, paragraphs,
bold/italic, bullet/numbered lists, HTTPS links, line breaks, fenced and inline
code. Raw HTML, images and embedded media are not rendered or fetched. Empty notes
have a neutral fallback. The release-history page links to Tinium's own GitHub
Releases; the share-app action and commit-history source also use Tinium.

Automatic checks respect **Auto Update Check** and run on the existing startup/
settings entry points, without a new background service or permission. Successful
automatic checks are spaced at least 12 hours, persisted across restarts; other
non-forced checks have a 15-minute attempt interval. HTTP caching revalidates JSON
rather than serving a stale body. Manual checks bypass the automatic interval.
GitHub 403/429 responses impose a persisted server retry/reset deadline (at least
one minute); even manual retries respect that limit. Errors preserve the last
successful time and do not pretend success.

Automatic update notifications respect the existing global/app update toggles and
Android notification permission. A successfully posted or viewed version is
remembered to avoid repeat notifications across restarts. A notification opens
Tinium's update screen, not an APK URL. Successful checks with no update and newly
installed versions clear stale indicators/notifications. The old beta nightly
9-PM/run-number logic and internal download notifications have been retired.
Persisted legacy `update_download` work is cancelled on updater initialization.
The media playback notification provider and shared FileProvider are retained.
The old update-only download-notification, clear-downloaded-APK and automatic
backup-before-update controls are removed; ordinary backups/music-release notices
remain available.

## Test before the first public release

1. Review and merge the updater PR; run the existing development APK workflow or
   build locally. Install the correct flavor. A debug-key change may require an
   uninstall; it cannot update a release-signed installation.
2. With no public Tinium releases, open the update screen. It should show the
   no-published-releases state, your actual installed version/code, and a genuine
   successful-check time. Reopen it after restarting the app to verify persistence.
3. Disable automatic checks and use **Check for update**. Manual checks still work.
   Disconnect the device from the network and check again: an error/retry should
   appear without changing the successful-check time. Reconnect and retry.
4. Toggle beta updates and theme/dark mode. Test notification preferences, playback,
   playlists, backups, and normal music downloads as normal device smoke checks.
5. Run the fixture tests below to exercise update-available states, channels,
   flavor assets, malformed data, rate limiting, Markdown and browser URL dispatch
   without creating public releases or permitting a test endpoint in production.
6. For a private release candidate, use the existing beta/stable build workflows
   and install their Actions artifacts manually. They intentionally do not appear
   in the checker. Confirm installed versionName/code in the update screen.
7. After you intentionally publish your first tested release through the separate
   publisher, use an older release-signed Tinium installation with a lower code
   to test the real update-available screen, size, fresh notes and browser download.
   Test both GMS and FOSS. The installer must accept the permanent certificate.
   A same-code install correctly shows no update. No release needs to be published
   merely to run the automated tests.

```bash
./gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest \
  :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug \
  --no-configuration-cache --max-workers=4
```

Fixtures in `app/src/test/resources/updater/` mirror the current publisher schema
and realistic GitHub release responses, including both flavors and hidden body
comments. They contain public test checksums, not private signing keys. Kotlin
unit tests use injected transports/stores/clocks/browser callbacks and no live
GitHub dependency. The existing GMS/FOSS PR CI jobs now run these tests too.
Actual Android browser/navigation/installer interaction and visual scrolling need
a device/emulator smoke check; JVM tests do not claim to replace those checks.
