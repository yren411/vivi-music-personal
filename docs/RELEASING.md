# Build, test and publish Tinium releases

Tinium uses one permanent release certificate and application ID
`com.yren411.tinium` for both beta and stable APKs. This process keeps Android's
version codes coordinated and publishes only the exact APKs you tested.

**Building never makes a GitHub Release public.** Publishing is a separate manual
action, with the tested build run, attempt and tag entered explicitly. Do not
change or regenerate your existing signing keys. The five secrets documented in
[APK_SIGNING.md](APK_SIGNING.md) are reused; no extra personal access token is needed.

This repository is public. Actions artifacts can be downloaded by other logged-in
GitHub users before publication. They support pre-release testing but are not
private storage. Use a private repository/storage if confidential testing is needed.

Codex tasks now prepare release-note drafts for review under `release-notes/`.
See [the release-note preparation guide](../release-notes/README.md) and
[AGENTS.md](../AGENTS.md). Without an assigned fresh version, changes accumulate
in `release-notes/unreleased.md`; that file is not a candidate build input.

## How automatic version numbering works

- Development builds retain their existing baseline `versionCode = 77` and
  persistent debug signing. They never allocate release codes.
- Beta and stable share **one global sequence**, starting at **78**. Codes are
  allocated automatically; you choose a meaningful human version name.
- Stable names use `X.Y.Z`, such as `6.0.8` or `6.0.9`.
- Beta names use `X.Y.Z-beta.N`, such as `6.0.8-beta.1` or `6.0.8-beta.2`.
- Each candidate gets an immutable annotated Git tag such as
  `tinium-code/0000000078`. Its JSON records the code, channel, version name,
  application ID and full source commit. These internal tags are reservations,
  not public GitHub Releases.
- The shared Actions concurrency group serializes allocation and publication.
  A new candidate receives one more than the highest reserved/approved code,
  accounting for both channels and existing release metadata.
- Retrying the **same name and source commit** reuses the exact same code.
  Workflow run numbers and attempts do not determine version codes.
- A candidate cannot be retargeted to another commit. If you fix code after
  testing, choose a new name (for example the next beta number), commit its notes,
  and build again. Do not delete or edit a reservation to reclaim its code.
- Abandoned/failed candidates can leave gaps. Gaps are harmless; reusing an old
  code for different APK content is not. Only new release candidates consume codes,
  not development builds or ordinary build retries.
- An approved/public release name cannot be rebuilt or reused. Publishing retries
  use the already-tested artifact. Both GMS and FOSS APKs in a release use the same
  code, version name, source commit and permanent certificate.

The versionCode is reproducible for a given reserved candidate because the record
is permanent. A rebuild is not promised to be byte-for-byte identical: that is why
publication downloads a specific immutable artifact rather than rebuilding it.

**Never delete or retarget `tinium-code/*` or `tinium-v*` tags.** They are release
state, including the high-water mark when an old GitHub Release is deleted. Back
up the repository including tags, and consider tag rulesets preventing update and
deletion while allowing these workflows to create tags. The publisher never
forces, updates or deletes a tag. Unknown/manual releases or malformed history
cause a clear failure rather than guessing their version codes.

No previous Tinium GitHub Releases existed when this was implemented, and 77 is
the established baseline. If you import older manually distributed releases or
an unrelated release history, account for their actual codes before using this
allocator. Never bypass an unmanaged-history error to reset the sequence.

## One coordinated beta/stable sequence

Example (assuming each build was a new candidate):

| Action | Name | Code | Result |
| --- | --- | --- | --- |
| Build beta candidate | `6.0.8-beta.1` | 78 | Test artifact only; not public yet |
| Retry beta build on its original commit | `6.0.8-beta.1` | 78 | Same reserved identity; test the selected new artifact if rebuilding |
| Publish tested beta | `6.0.8-beta.1` | 78 | Installs over an older release signed by the same key |
| Build/publish stable | `6.0.8` | 79 | Users on beta 78 can move to stable without uninstalling |
| Build/publish next beta | `6.0.9-beta.1` | 80 | Users on stable 79 can opt into the newer beta |
| Try to install stable 79 over beta 80 | `6.0.8` | 79 | Normal Android downgrade: not supported |
| Build/publish newer stable | `6.0.9` | 81 | Users on beta 80 can return to stable |

Publication must advance the global code even across channels. If a higher-code
candidate is approved/published first, a lower-code candidate cannot subsequently
be published. Build and test a **new** higher-code name instead. Publish multiple
pending candidates in increasing code order, or discard stale ones. A failed
publication still preserves its approved tag/high-water mark; resume it with the
same artifact or move forward with a new candidate.

Android does not normally permit lower-versionCode APK updates. Selecting the
stable channel cannot immediately downgrade a newer beta. Wait for a stable
release with a sufficiently high code; this system does not bypass Android's
protections. Debug and release have different certificates, so switching between
debug and release still normally requires uninstalling and backing up app data.

## Before your first build after merging

1. Keep the existing working signing secrets unchanged.
2. Review/merge this PR; all release workflows run only from `main`.
3. Confirm the repository/organization allows the requested Actions permissions.
   Allocation needs `contents: write` to create reservation tags. Actual APK build
   jobs are `contents: read`. The separate publisher needs `contents: write` and
   `actions: read`. Each job requests only its required permissions.
4. There are ready-to-use notes for initial `6.0.8-beta.1` and `6.0.8` candidates,
   based on the existing 6.0.8 changelog.
5. Old build artifacts from before this pipeline have no reservation/manifest and
   are not eligible for the new publisher. Start a new candidate build.

For future versions, add `release-notes/<versionName>.md` to `main` through a reviewed
PR before building. GitHub's **Add file → Create new file** is sufficient. Write the
actual changes users should know, under Tinium branding. There is no need to edit
versionCode or place credentials in the repository. Missing notes fail before
allocating a code.

## Build and test a beta

1. Open **Actions → Beta Release Build → Run workflow**.
2. Select `main` and enter the name of the reviewed notes file without `.md`, such
   as `6.0.8-beta.1`. Click **Run workflow**.
3. The allocation job creates/reuses the reservation; the build job compiles the
   universal GMS and FOSS release APKs with that same reserved version.
4. The build verifies cryptographic signatures against your configured permanent
   keystore and checks package ID, versionCode, versionName and non-debuggable APKs.
5. Open the build's **Summary**. Record its tag, version code, **build run ID** and
   **attempt**. Attempt 1 is the initial run; reruns use a new attempt.
6. Download the artifact named `tinium-candidate-beta-<run ID>-<attempt>`.
7. Extract the ZIP and install the appropriate APK on your phone. Check playback,
   settings and app data retention when updating from a prior release. GMS includes
   Google integration; FOSS omits it. Both variants replace the same installed app.

A successful build only produces a test artifact; it does not create a public
release. A run that failed/cancelled later in the build is not eligible to publish.
If you must rerun, prefer GitHub's **Re-run failed jobs** on the original commit.
Before publication, a fresh build on unchanged `main` with the same name also
reuses its code. If `main` has changed, select a new candidate name and notes file.

## Build and test stable

Use **Actions → Production Release Build → Run workflow** on `main`, entering a
stable name such as `6.0.8`. Download and test
`tinium-candidate-stable-<run ID>-<attempt>` using the same steps above.

Stable gets the next coordinated code, including all earlier beta reservations.
Do not assume a stable name automatically outranks a beta code; the actual code
controls Android update compatibility. Check the Summary before installation.

## Publish the exact tested APKs

Only do this once you have installed and tested the downloaded artifact.

1. Open **Actions → Publish Tested Tinium Release → Run workflow**.
2. Select `main`.
3. Enter the **build run ID** from the tested beta/production Summary.
4. Enter the exact **build attempt** you tested (usually `1`). If you tested a rerun,
   use its attempt, not the original run's artifact.
5. Enter the exact approval tag from the Summary, e.g. `tinium-v6.0.8-beta.1` or
   `tinium-v6.0.8`. This is the explicit approval to publish that candidate.
6. Click **Run workflow**. Do not start this action merely because a build succeeded.

The publisher requires a successful manually dispatched `main` build from this
repository and the matching immutable artifact ID. It verifies that the built
commit belongs to `main`, downloads that artifact, and rechecks file checksums,
package identity, versions, metadata and signatures with your same release
keystore. It **never compiles, rebuilds or re-signs an APK**.

It rejects stale codes, duplicate names, mismatching artifacts and unexpected
assets. Uploads are staged in a draft GitHub Release. Server-reported asset hashes
must match the tested files before the draft becomes public. Existing assets are
never overwritten. A failed upload can leave a draft and approved tag; rerun the
publisher with the **same** run/attempt/tag to upload only missing identical files.
An already successful publishing retry is a read-only no-op when everything still
matches. Do not edit assets or notes in a draft to force the checks to pass.

Artifacts expire after 30 days. Publish a tested candidate within that window. If
an unpublished candidate's artifact expired, rebuild/retest its original source
commit and select the new artifact, provided publication has not already been
approved. Otherwise prepare a new higher-code candidate. A previously published
release cannot be rebuilt under its old name.

By default, the separate manual dispatch plus typed tag is the approval boundary.
You can additionally configure a protected GitHub Environment with required
reviewers on the publishing job if you want a second-person approval; it is not
required by this implementation.

## Public tags and assets

Beta releases have `prerelease: true`; stable releases have `prerelease: false` and
are explicitly marked latest. Beta publication never replaces GitHub's latest
stable release.

Example beta release:

```text
tag: tinium-v6.0.8-beta.1
name: Tinium 6.0.8-beta.1 (beta)
tinium-beta-6.0.8-beta.1-universal-gms.apk
tinium-beta-6.0.8-beta.1-universal-foss.apk
release.json
release-notes.md
```

Stable uses `tinium-v6.0.8` and
`tinium-stable-6.0.8-universal-{gms,foss}.apk`. Release descriptions and notes use
Tinium branding. Internal `tinium-code/*` reservations are never release downloads.

## GitHub Releases updater contract

Every official release contains a versioned `release.json` asset:

```json
{
  "schemaVersion": 1,
  "repository": "yren411/vivi-music-personal",
  "release": {
    "schemaVersion": 1,
    "channel": "beta",
    "versionName": "6.0.8-beta.1",
    "versionCode": 78,
    "applicationId": "com.yren411.tinium",
    "commit": "<full source SHA>",
    "tag": "tinium-v6.0.8-beta.1"
  },
  "build": {"buildRunId": 123, "buildAttempt": 1, "commit": "<full source SHA>", "channel": "beta"},
  "certificateSha256": "<public permanent certificate digest>",
  "assets": [
    {"variant": "gms", "abi": "universal", "filename": "tinium-beta-6.0.8-beta.1-universal-gms.apk",
     "sha256": "<APK checksum>", "size": 123456, "minSdk": 26, "targetSdk": 37,
     "supportedAbis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
     "url": "https://github.com/yren411/vivi-music-personal/releases/download/tinium-v6.0.8-beta.1/tinium-beta-6.0.8-beta.1-universal-gms.apk"}
  ],
  "notes": {"filename": "release-notes.md", "sha256": "<notes checksum>"}
}
```

Actual manifests always contain both GMS and FOSS entries with values measured
from the APKs, not this example's placeholders. Tinium's updater reads the public
release list and this metadata, selects the highest eligible numeric versionCode,
and matches the installed GMS/FOSS flavor to an actual published APK asset.
Downloads open externally in the browser. The visible notes come from the fresh
GitHub release body. See [UPDATER.md](UPDATER.md) for validation, channel policy,
manual testing, Android installer boundaries and automatic-check behavior.

The release body includes a hidden machine metadata comment so the publisher can
check all prior codes without downloading every APK. Keep that comment intact.
Public approval tags also pin the permanent certificate fingerprint. Publication
rejects a different certificate even if someone accidentally changed the signing
secrets, and the pin survives deletion of a GitHub Release.

The permanent certificate and Android package installer remain part of the trust
chain; a JSON checksum alone is not an alternative to signature verification.



## Checks and limitations

The automated suite tests shared sequences, cross-channel updates, deterministic
retry behavior, stale-code rejection, provenance, unchanged asset requirements,
draft resumption, channel/latest flags, and actual APK metadata/signatures using
disposable test keys. No owner signing key is modified by tests.

No public Release or reservation tag was created during implementation. Owner-key
builds and an actual public publication must be performed by you after reviewing
and merging. The implementation is ready for that end-to-end test; a successful
local test is not proof that GitHub permissions or publication have already run.
