# Preparing Tinium release notes

Release notes are reviewed Markdown files committed with the code. The beta and
production build workflows require `release-notes/<versionName>.md` before the
candidate can be built. Nothing here publishes automatically or changes the
shared beta/stable versionCode allocator, signing keys or manual publish approval.

## During a Codex task

The repository's [AGENTS.md](../AGENTS.md) asks Codex to inspect what was actually
implemented and prepare accurate user-facing notes in that task's PR:

- If you explicitly assign a fresh target version (or a reviewed plan assigns it),
  Codex writes `release-notes/<versionName>.md`.
- If no version is assigned, Codex maintains `release-notes/unreleased.md`. This
  drafts useful changes without inventing a release number or reserving a tag.
- Existing entries are preserved/deduplicated. Notes describe real behavior and
  distinguish device checks still needed from tests that actually passed.
- If a candidate is already reserved/published, its source and tested notes are
  immutable. New changes go into the unversioned draft until you choose a fresh
  version; editing old notes does not make the old candidate reusable.

Review the notes alongside the implementation. You can edit wording, remove
irrelevant details or add accurate context before merging. Small tasks do not
need their own version; several tasks can accumulate in one unversioned draft.

Example request: “Implement this fix and prepare release notes for the fresh
version I specify: …” Or just request the change; Codex will explain which
unversioned file it prepared. Examples/default Gradle versions and an existing
beta notes filename do not silently assign that version to new work.

## Before building a beta or stable candidate

1. Choose a fresh intended version according to [RELEASING.md](../docs/RELEASING.md).
   Stable names use `X.Y.Z`; beta names use `X.Y.Z-beta.N`. This is your decision,
   not an automatic version bump on every change. Do not reuse an existing
   reserved version for changed code/notes.
2. On GitHub, open **Add file → Create new file** in a development branch. Name it
   `release-notes/` followed by your exact chosen version and `.md`.
3. Copy the applicable reviewed changes from `release-notes/unreleased.md` and
   other relevant task notes. Set the first line to `# Tinium <your versionName>`
   using the actual version, not literal angle brackets. Remove draft-only review
   instructions and verify every claim against the implemented changes.
4. In the same PR, remove only those assigned entries from `unreleased.md`; keep
   anything intended for a later release. Review and merge the PR before building.
5. Run **Beta Release Build** or **Production Release Build** manually on `main`
   with that exact versionName. The file must exist in the checked-out commit.
   If a build already reserved an earlier failed candidate, choose a fresh version
   when the source commit changes; do not retarget reservations.
6. Download and test the Actions artifacts privately. Only use the existing
   separate **Publish Tested Tinium Release** action when you explicitly approve
   those exact tested APKs for public distribution.

This task does not assign a new beta/stable version. The existing
`6.0.8-beta.2.md` is preserved; its presence is not an assignment for new branding.

Files must be non-empty UTF-8 Markdown and no larger than 50 KB. Do not include
private keys, passwords or the publisher's reserved hidden provenance comment.
`unreleased.md` and this README are documentation/drafts, not candidate build
inputs. CI deliberately does not synthesize generic notes to work around missing
versioned files. A file's existence alone does not make its contents reviewed.

## Quick local checks

With Python and the project's existing scripts available, check a chosen notes
file without allocating any version or calling GitHub:

```powershell
$VersionName = Read-Host 'Enter your already-chosen fresh candidate versionName'
python -c "import sys; sys.path.insert(0, 'scripts'); from release_tools import validate_version; from release_apks import notes_for; v=sys.argv[1]; validate_version('beta' if '-beta.' in v else 'stable', v); notes_for(v); print('Release notes format and presence are valid; review content before building.')" $VersionName
```

This performs the same existing name/presence/size/content-format checks locally.
It does not create tags, reserve version codes, build APKs or publish anything.
