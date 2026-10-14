"""Immutable candidate note snapshots; only reviewed versioned notes can be public."""
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import subprocess

from release_tools import ReleaseError, require, validate_version

MAX_BYTES = 50_000
DRAFT_BANNER = '> **UNREVIEWED DRAFT — for internal testing only.**'
FALLBACK = 'Internal testing build — release notes not finalized.'


@dataclass(frozen=True)
class CandidateNotes:
    text: str
    source: str | None
    source_sha256: str | None
    review_status: str


def digest(data):
    return hashlib.sha256(data).hexdigest()


def decode_notes(data):
    require(len(data) <= MAX_BYTES, 'Release notes must be at most 50KB.')
    try:
        text = data.decode('utf-8').strip()
    except UnicodeDecodeError as error:
        raise ReleaseError('Release notes must be valid UTF-8.') from error
    require('<!-- tinium-release:' not in text, 'Release notes contain the reserved metadata marker.')
    return text


def meaningful(text):
    # Exclude title-only drafts, comments and common placeholders, not actual prose/lists.
    text = re.sub(r'<!--[\s\S]*?(?:-->|$)', '', text)
    boilerplate = {'todo', 'tbd', 'none', 'no changes', 'no changes yet', 'coming soon',
                   'release notes pending', 'release notes not finalized', 'pending', 'draft',
                   'unreviewed draft', 'unreleased', "what's new", 'notes', 'changelog', 'changes',
                   'this is an unversioned draft for review. no release version or tag is assigned'}
    for line in text.splitlines():
        value = line.strip()
        if not value or value.startswith('#') or re.fullmatch(r'[-*_\s]+', value):
            continue
        value = re.sub(r'^(?:[-*+]|\d+[.)])\s+', '', value).strip().strip('*_').rstrip('.').casefold()
        if value not in boilerplate and not re.fullmatch(r'(todo|tbd)(?::.*)?', value):
            return True
    return False


def read_file(root, name):
    require(not root.is_symlink(), 'Release notes directory must not be a symlink.')
    path = root/name
    require(not path.is_symlink(), 'Release notes must not be a symlink.')
    if not path.exists():
        return None
    require(path.is_file(), 'Release notes must be a regular file.')
    require(path.stat().st_size <= MAX_BYTES, 'Release notes must be at most 50KB.')
    data = path.read_bytes()
    return data, decode_notes(data)


def candidate_notes_for(version, root=Path('release-notes')):
    validate_version('beta' if '-beta.' in version else 'stable', version)
    root = Path(root)
    versioned = read_file(root, version+'.md')
    if versioned is not None:
        data, text = versioned
        require(meaningful(text), 'Version-specific release notes are empty or contain only placeholders.')
        require(DRAFT_BANNER not in text and FALLBACK.casefold() not in text.casefold(),
                'Version-specific notes still contain an internal-testing marker; review them before building.')
        return CandidateNotes(text, f'release-notes/{version}.md', digest(data), 'reviewed')
    draft = read_file(root, 'unreleased.md')
    if draft is not None and meaningful(draft[1]):
        return CandidateNotes(draft[1], 'release-notes/unreleased.md', digest(draft[0]), 'unreviewed')
    return CandidateNotes(FALLBACK, None, None, 'internal-testing')


def render_notes(version, candidate):
    heading = f'# Tinium {version}'
    text = candidate.text
    body = text if text.splitlines()[0] == heading else heading+'\n\n'+text
    if candidate.review_status == 'unreviewed':
        body = heading+'\n\n'+DRAFT_BANNER+'\n\n'+text
    encoded = (body.rstrip()+'\n').encode('utf-8')
    require(len(encoded) <= MAX_BYTES, 'Rendered candidate release notes exceed 50KB.')
    return encoded


def committed_notes_bytes(commit, path):
    # Publisher checks out full main history and verifies this commit is its ancestor.
    # Read the built commit, never today's working-tree draft/notes.
    result = subprocess.run(['git', 'show', f'{commit}:{path}'], capture_output=True)
    require(result.returncode == 0, 'Reviewed version-specific notes are absent from the built commit; build a fresh reviewed candidate.')
    return result.stdout


def validate_public_notes(record, metadata, snapshot):
    require(isinstance(metadata, dict), 'Invalid release-note metadata.')
    legacy = set(metadata) == {'filename', 'sha256'}
    if not legacy:
        require(set(metadata) == {'filename','sha256','reviewStatus','source','sourceSha256'}, 'Unexpected release-note metadata.')
        require(metadata.get('reviewStatus') == 'reviewed',
                'Candidate notes are unreviewed/internal-testing. Public publication is blocked; create reviewed version-specific notes and build/test a fresh version.')
        require(metadata.get('source') == f"release-notes/{record['versionName']}.md", 'Reviewed notes must come from the candidate version-specific source.')
    require(metadata.get('filename') == 'release-notes.md' and metadata.get('sha256') == digest(snapshot), 'Tested release notes changed.')
    text = decode_notes(snapshot)
    require(meaningful(text) and DRAFT_BANNER not in text and FALLBACK.casefold() not in text.casefold(), 'Unreviewed or placeholder release notes cannot be published.')
    source_path = f"release-notes/{record['versionName']}.md"
    raw = committed_notes_bytes(record['commit'], source_path)
    source_text = decode_notes(raw)
    require(meaningful(source_text), 'Reviewed source notes are empty or contain only placeholders.')
    if not legacy:
        require(metadata.get('sourceSha256') == digest(raw), 'Reviewed notes source hash does not match the built commit.')
    expected = render_notes(record['versionName'], CandidateNotes(source_text, source_path, digest(raw), 'reviewed'))
    require(snapshot == expected, 'Tested notes do not match the reviewed version-specific file in the built commit.')
    return text
