# Tinium candidate and public release notes

You can build unpublished beta and production APKs without creating a Markdown
file for every candidate. Use the existing manual build workflows and choose a
fresh versionName; the coordinated code allocator and permanent signing stay the
same. No new secrets are needed. “Private/testing” means not a public GitHub
Release: this repository is public, so Actions artifacts are still downloadable
by other logged-in GitHub users and are not confidential storage.

## How builds choose notes

The checked-out source commit is the source of truth, in this order:

| Available source | Candidate status | Public publication |
| --- | --- | --- |
| Valid `release-notes/<versionName>.md` | `reviewed` source, exact snapshot | Eligible after testing and explicit review confirmation |
| Meaningful `release-notes/unreleased.md` | `unreviewed`, visible draft banner | Blocked |
| Draft missing, empty, title/comment-only or common placeholder-only | `internal-testing` | Blocked |

The fallback says **“Internal testing build — release notes not finalized.”**
A present but invalid version-specific file fails clearly; it cannot silently
become “reviewed.” Oversized files, invalid UTF-8, symlinks and reserved provenance
comments also fail before allocation instead of being treated as safe drafts.
Real paragraphs/bullets count as draft content; headings, comments, the default
unversioned boilerplate and `TODO`/`TBD` alone do not. This is a format/content
heuristic, not an automatic human review or proof of a change's accuracy.

Nothing commits generated notes to `main`, creates a version-specific source
file, or edits the accumulating draft. Existing allocation still reserves an
immutable version tag. The artifacts include the exact rendered
`release-notes.md` and `release.json` with its SHA-256, source path/source SHA-256
and `reviewStatus`. The Actions Summary shows the status/hash and a prominent
**NOT PUBLISHABLE** warning for draft/fallback candidates. The four-file artifact
structure is unchanged: two APKs, `release.json`, `release-notes.md`.

## Frequent private beta/production tests

1. Merge the workflow changes and open **Actions → Beta Release Build** or
   **Production Release Build → Run workflow**.
2. Select `main` and enter a fresh intended versionName. Beta names use
   `X.Y.Z-beta.N`; production names use `X.Y.Z`. You do not need a matching notes
   file for testing. If present, `unreleased.md` supplies the draft automatically.
3. Wait for the signed GMS/FOSS artifacts; check the build Summary's actual
   versionCode, tag, notes status, run ID and attempt. Download/install the right
   APK and test normally. A successful build never creates a public release.
4. Updating `unreleased.md` later does not change an already-tested artifact.
   Rerun the original source/run to reuse a reservation. If `main` has changed,
   use a fresh versionName; do not retarget a reserved candidate.

`6.0.8-beta.3` was not reserved when this fix was prepared: the old missing-notes
check ran before reservation. After merging, it can be entered on `main` without
creating `6.0.8-beta.3.md`. If another run reserves it before you build, normal
source-pinning rules still apply. No build was dispatched or version reserved as
part of this implementation.

## Preparing a public release

1. Choose a fresh public candidate version. Review the actual changes and create
   `release-notes/<versionName>.md` in a development PR, headed
   `# Tinium <versionName>` using the actual chosen name. Copy applicable entries
   from `unreleased.md`, remove draft-only guidance and verify each claim.
2. Remove only those assigned entries from the accumulating draft in the same PR.
   Review and merge the PR. Never edit an old candidate's artifact/notes or reuse
   its reserved name with a changed source commit.
3. Manually build this **fresh** candidate and test its exact Actions artifact.
   The Summary should say `REVIEWED`; the version-specific file is treated as the
   reviewed source by convention, but its presence does not replace human review.
4. Open **Publish Tested Tinium Release**. Enter the exact tested run ID, attempt
   and approval tag, then explicitly check **notes_reviewed** after reading the
   artifact's actual `release-notes.md`. This checkbox defaults to false.
5. The publisher verifies source ancestry, version reservation, package/versions,
   certificates/APK hashes and notes. Reviewed notes must match the version-specific
   file at the **built commit**, not today's working tree. Draft/fallback artifacts
   stay blocked even if you check `notes_reviewed`. The publisher never rebuilds,
   re-signs, edits tested notes or silently promotes a draft.

Tradeoff: a private draft candidate cannot later be promoted in place. Creating
reviewed source notes changes the commit, so you need a fresh version/build/test
for public distribution. This keeps the existing immutable-candidate rule and
ensures publication uses exactly the files that were tested. There is no notes
replacement/approval override for unreviewed artifacts. Old reviewed artifacts
without the new status fields remain eligible only when their exact notes match
the original version-specific source at their built commit, with explicit review.
Placeholders cannot be published through that compatibility path either.

## Codex and local checks

[AGENTS.md](../AGENTS.md) asks Codex to maintain accurate notes with meaningful
changes. Without an assigned public target, it updates `unreleased.md` rather than
inventing/reserving a version. Review wording in the implementation PR. Notes are
not authorization for a build, key change or publication.

From the repository root with Python available, preview the selected status
without writing files, contacting GitHub or allocating a code:

```powershell
$VersionName = Read-Host 'Enter your intended candidate versionName'
python -c "import sys; sys.path.insert(0, 'scripts'); from release_notes import candidate_notes_for; n=candidate_notes_for(sys.argv[1]); print('Status:', n.review_status); print('Source:', n.source or 'Internal-testing placeholder')" $VersionName
```

See [RELEASING.md](../docs/RELEASING.md) for full signing/version/build/publication
instructions. No signing keys or credentials belong in any notes file.
