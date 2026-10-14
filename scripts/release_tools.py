"""Coordinated release reservations and idempotent GitHub publication.

No private signing material belongs here. API mutations are invoked only by the
manually dispatched workflows; tests use an in-memory GitHub implementation.
"""
import json
import os
import re
import subprocess
from urllib.parse import quote

APP_ID = 'com.yren411.tinium'
BASELINE = 77
MAX_CODE = 2_100_000_000
RESERVATION_PREFIX = 'tinium-code/'
PUBLIC_PREFIX = 'tinium-v'
BUILD_WORKFLOWS = {'.github/workflows/beta_release.yml': 'beta', '.github/workflows/release.yml': 'stable'}
VERSION = re.compile(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-beta\.([1-9]\d*))?')
MARKER = re.compile(r'<!-- tinium-release:(\{[^\n]+\}) -->')


class ReleaseError(ValueError):
    pass


class NotFound(ReleaseError):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def validate_version(channel, name):
    require(isinstance(name, str), 'A version name string is required.')
    match = VERSION.fullmatch(name)
    require(channel in ('beta', 'stable') and match is not None, 'Use X.Y.Z for stable or X.Y.Z-beta.N for beta.')
    require(bool(match[4]) == (channel == 'beta'), 'Version name does not match the release channel.')
    return name


def validate_record(record):
    require(isinstance(record, dict) and type(record.get('schemaVersion')) is int and record.get('schemaVersion') == 1, 'Unsupported release reservation schema.')
    validate_version(record.get('channel'), record.get('versionName', ''))
    code = record.get('versionCode')
    require(type(code) is int and BASELINE < code <= MAX_CODE, 'Release versionCode must be greater than 77 and within Android limits.')
    require(record.get('applicationId') == APP_ID, 'Unexpected release application ID.')
    require(isinstance(record.get('commit'), str) and re.fullmatch(r'[0-9a-f]{40}', record['commit']) is not None, 'A full source commit SHA is required.')
    require(record.get('tag') == PUBLIC_PREFIX + record['versionName'], 'Unexpected public release tag.')
    require(set(record) == {'schemaVersion', 'channel', 'versionName', 'versionCode', 'commit', 'applicationId', 'tag'}, 'Unexpected reservation fields.')
    return record


def artifact_name(channel, run_id, attempt):
    return f'tinium-candidate-{channel}-{int(run_id)}-{int(attempt)}'


class GitHub:
    def __init__(self, repository):
        require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) is not None, 'Invalid GitHub repository.')
        self.repository = repository
        self.base = f'repos/{repository}/'
        self.immutable_tags = {}

    def request(self, path, method='GET', data=None, paginated=False, upload=None):
        command = ['gh', 'api', path if path.startswith('https://') else self.base + path, '--method', method]
        if paginated:
            command += ['--paginate']
        if upload is not None:
            command += ['-H', 'Content-Type: application/octet-stream', '--input', str(upload)]
        elif data is not None:
            command += ['--input', '-']
        result = subprocess.run(command, input=json.dumps(data) if data is not None else None,
                                text=True, capture_output=True)
        if result.returncode:
            if '(HTTP 404)' in result.stderr:
                raise NotFound('GitHub resource does not exist.')
            # Never dump a token, redirect URL, full environment, or GitHub diagnostics.
            raise ReleaseError(f'GitHub {method} request failed; check permissions and network access.')
        if not paginated:
            return json.loads(result.stdout)
        pages, remaining = [], result.stdout.lstrip()
        decoder = json.JSONDecoder()
        while remaining:
            page, end = decoder.raw_decode(remaining)
            pages.append(page)
            remaining = remaining[end:].lstrip()
        return pages

    def pages(self, path, key=None):
        pages = self.request(path, paginated=True)
        return [item for page in pages for item in (page[key] if key else page)]

    def refs(self, prefix):
        return self.pages('git/matching-refs/tags/' + prefix)

    def tag(self, sha):
        # Git objects are immutable by SHA. Keep refs/releases fresh, but avoid
        # rereading every historical annotation during staged publication.
        if sha not in self.immutable_tags:
            self.immutable_tags[sha] = self.request('git/tags/' + sha)
        return self.immutable_tags[sha]

    def create_tag(self, name, record):
        return self.request('git/tags', 'POST', {'tag': name, 'message': json.dumps(record, sort_keys=True),
                                              'object': record['commit'], 'type': 'commit'})['sha']

    def create_ref(self, name, sha):
        return self.request('git/refs', 'POST', {'ref': 'refs/tags/' + name, 'sha': sha})

    def releases(self):
        return self.pages('releases?per_page=100')

    def create_release(self, data):
        return self.request('releases', 'POST', data)

    def edit_release(self, release_id, data):
        return self.request(f'releases/{release_id}', 'PATCH', data)

    def upload(self, release_id, path):
        url = f'https://uploads.github.com/repos/{self.repository}/releases/{release_id}/assets?name={quote(path.name)}'
        return self.request(url, 'POST', upload=path)

    def run(self, run_id, attempt):
        return self.request(f'actions/runs/{run_id}/attempts/{attempt}')

    def artifacts(self, run_id):
        return self.pages(f'actions/runs/{run_id}/artifacts?per_page=100', 'artifacts')


class Registry:
    def __init__(self, github):
        self.github = github
        self.public_certificates = {}

    def records(self, prefix):
        records = []
        for ref in self.github.refs(prefix):
            name = ref['ref'].removeprefix('refs/tags/')
            require(ref['object']['type'] == 'tag', 'Release registry tags must be annotated; do not replace them.')
            tag = self.github.tag(ref['object']['sha'])
            try:
                value = json.loads(tag['message'])
                certificate = value.pop('certificateSha256', None) if prefix == PUBLIC_PREFIX else None
                record = validate_record(value)
                if prefix == PUBLIC_PREFIX:
                    require(isinstance(certificate, str) and re.fullmatch(r'[0-9a-f]{64}', certificate) is not None,
                            'Public tag is missing its permanent certificate fingerprint.')
                    self.public_certificates[record['versionCode']] = certificate
            except (json.JSONDecodeError, KeyError, TypeError) as error:
                raise ReleaseError('Malformed release registry; refusing to reset version codes.') from error
            require(tag['object'].get('type') == 'commit' and tag['object'].get('sha') == record['commit'], 'Release registry source commit mismatch.')
            expected = RESERVATION_PREFIX + f"{record['versionCode']:010d}" if prefix == RESERVATION_PREFIX else record['tag']
            require(name == expected and tag['tag'] == name, 'Release registry name/code mismatch.')
            records.append(record)
        return records

    def state(self):
        reservations = self.records(RESERVATION_PREFIX)
        self.public_certificates = {}
        public_marks = self.records(PUBLIC_PREFIX)
        require(len(set(self.public_certificates.values())) <= 1, 'Release history contains different signing certificates; refusing identity changes.')
        known = {}
        for record in reservations + public_marks:
            code = record['versionCode']
            require(code not in known or known[code] == record, 'Duplicate versionCode assigned to different releases.')
            known[code] = record
        require(len({r['versionName'] for r in reservations}) == len(reservations), 'Duplicate candidate version name in registry.')
        releases = self.github.releases()
        for release in releases:
            matches = MARKER.findall(release.get('body') or '')
            require(len(matches) == 1, 'Unmanaged GitHub Release detected. Account for its versionCode before using this publisher.')
            try:
                metadata = json.loads(matches[0])
                record = validate_record(metadata['release'])
            except (json.JSONDecodeError, KeyError, TypeError) as error:
                raise ReleaseError('Malformed published release metadata.') from error
            require(metadata.get('certificateSha256') == self.public_certificates.get(record['versionCode']), 'Published certificate metadata does not match the durable public tag.')
            require(release['tag_name'] == record['tag'], 'Published tag does not match version metadata.')
            require(release['prerelease'] == (record['channel'] == 'beta'), 'Published release channel mismatch.')
            require(known.get(record['versionCode']) == record and record in public_marks, 'Published release is missing its durable version reservation/public tag.')
        return reservations, public_marks, releases

    def reserve(self, channel, name, commit):
        validate_version(channel, name)
        require(re.fullmatch(r'[0-9a-f]{40}', commit) is not None, 'A full source commit SHA is required.')
        reservations, marks, releases = self.state()
        require(not any(r['tag_name'] == PUBLIC_PREFIX + name for r in releases) and not any(r['versionName'] == name for r in marks), 'This version was already approved/published; do not rebuild it.')
        for record in reservations:
            if record['versionName'] == name:
                require(record['channel'] == channel and record['commit'] == commit,
                        'This candidate is pinned to another source commit. Retry its original run or choose a new version name.')
                return record
        code = max([BASELINE] + [r['versionCode'] for r in reservations + marks]) + 1
        record = validate_record({'schemaVersion': 1, 'channel': channel, 'versionName': name,
                                  'versionCode': code, 'commit': commit, 'applicationId': APP_ID,
                                  'tag': PUBLIC_PREFIX + name})
        ref = RESERVATION_PREFIX + f'{code:010d}'
        # The shared Actions concurrency group serializes allocations/publication.
        # Creating a ref is atomic and never uses force/update/delete.
        self.github.create_ref(ref, self.github.create_tag(ref, record))
        return record

    def reservation(self, code):
        reservations, _, _ = self.state()
        matches = [r for r in reservations if r['versionCode'] == code]
        require(len(matches) == 1, 'Candidate version reservation not found.')
        return matches[0]

    def build_artifact(self, run_id, attempt):
        require(run_id > 0 and attempt > 0, 'Build run ID and attempt must be positive integers.')
        run = self.github.run(run_id, attempt)
        channel = BUILD_WORKFLOWS.get(run.get('path', '').split('@')[0])
        require(channel is not None and run.get('event') == 'workflow_dispatch' and run.get('head_branch') == 'main',
                'Only a manually dispatched beta/production main build can be published.')
        require(run.get('head_repository', {}).get('full_name') == self.github.repository, 'Fork artifacts cannot be published.')
        require(run.get('status') == 'completed' and run.get('conclusion') == 'success' and run.get('run_attempt') == attempt,
                'The selected build attempt must have completed successfully.')
        require(re.fullmatch(r'[0-9a-f]{40}', run.get('head_sha', '')) is not None, 'Invalid build source commit.')
        name = artifact_name(channel, run_id, attempt)
        artifacts = [a for a in self.github.artifacts(run_id) if a['name'] == name]
        require(len(artifacts) == 1 and not artifacts[0]['expired'], 'Tested artifact is missing, expired or ambiguous; build/test a new candidate.')
        artifact = artifacts[0]
        origin = artifact.get('workflow_run', {})
        require(origin.get('id') == run_id and origin.get('head_sha') == run['head_sha'] and origin.get('head_branch') == 'main',
                'Artifact does not belong to the selected trusted build.')
        return {'artifactId': artifact['id'], 'buildRunId': run_id, 'buildAttempt': attempt,
                'channel': channel, 'commit': run['head_sha']}

    def publish(self, record, provenance, files, notes, expected_tag, certificate, notes_reviewed=False):
        validate_record(record)
        require(notes_reviewed is True, 'Explicit release-notes review approval is required before publication.')
        require('UNREVIEWED DRAFT' not in notes and 'release notes not finalized' not in notes.casefold(), 'Unreviewed/placeholder release notes cannot be published.')
        require(isinstance(certificate, str) and re.fullmatch(r'[0-9a-f]{64}', certificate) is not None, 'A validated permanent certificate fingerprint is required.')
        require(expected_tag == record['tag'], 'Typed approval tag does not match the tested candidate.')
        require(provenance['channel'] == record['channel'] and provenance['commit'] == record['commit'], 'Build provenance mismatch.')
        metadata = {'release': record, 'certificateSha256': certificate, 'provenance': provenance,
                    'files': {path.name: digest for path, digest in files.items()}}
        require('<!-- tinium-release:' not in notes, 'Release notes must not contain the reserved metadata marker.')
        body = notes.rstrip() + '\n\n<!-- tinium-release:' + json.dumps(metadata, sort_keys=True) + ' -->\n'
        reservations, marks, releases = self.state()
        require(record in reservations, 'Candidate does not match its immutable reservation.')
        require(all(value == certificate for value in self.public_certificates.values()), 'Permanent certificate changed from previously approved releases. Do not replace signing keys.')
        existing = next((r for r in releases if r['tag_name'] == record['tag']), None)
        if existing:
            require(existing['body'] == body, 'Existing release belongs to another artifact or has changed. Nothing will be overwritten.')
            if not existing['draft']:
                self.check_assets(existing, files, complete=True)
                return existing  # Successful publishing retries are read-only no-ops.
        other_codes = [r['versionCode'] for r in marks if r['tag'] != record['tag']]
        require(record['versionCode'] > max([BASELINE] + other_codes),
                'A newer candidate was already published/approved. Build and test a new higher-code version; never downgrade.')
        if record not in marks:
            self.github.create_ref(record['tag'], self.github.create_tag(record['tag'], dict(record, certificateSha256=certificate)))
        if existing is None:
            existing = self.github.create_release({'tag_name': record['tag'], 'target_commitish': record['commit'],
                'name': f"Tinium {record['versionName']} ({record['channel']})", 'body': body,
                'draft': True, 'prerelease': record['channel'] == 'beta', 'make_latest': 'false'})
        # Stage as draft; upload only missing assets. Never clobber an existing asset.
        self.check_assets(existing, files, complete=False)
        present = {a['name'] for a in existing['assets']}
        for path, digest in files.items():
            if path.name not in present:
                uploaded = self.github.upload(existing['id'], path)
                require(uploaded.get('digest') == 'sha256:' + digest and uploaded.get('size') == path.stat().st_size,
                        'GitHub asset digest/size does not match the tested file. Draft remains unpublished.')
        # Re-fetch state immediately before public visibility. Serialized workflows
        # and durable public tags prevent stale-channel publication, even after a
        # release is deleted. A partially failed publish is retried with the same artifact.
        _, latest_marks, latest_releases = self.state()
        require(record['versionCode'] > max([BASELINE] + [r['versionCode'] for r in latest_marks if r['tag'] != record['tag']]),
                'A newer release was approved during staging; refusing stale publication.')
        staged = next(r for r in latest_releases if r['id'] == existing['id'])
        require(staged['draft'] and staged['body'] == body, 'Draft state changed; nothing will be overwritten.')
        self.check_assets(staged, files, complete=True)
        return self.github.edit_release(staged['id'], {'draft': False,
            'prerelease': record['channel'] == 'beta', 'make_latest': 'false' if record['channel'] == 'beta' else 'true'})

    @staticmethod
    def check_assets(release, files, complete):
        expected = {path.name: (digest, path.stat().st_size) for path, digest in files.items()}
        assets = release['assets']
        names = [a['name'] for a in assets]
        require(len(names) == len(set(names)) and set(names) <= set(expected), 'Unexpected or duplicate release assets; refusing overwrite.')
        if complete:
            require(set(names) == set(expected), 'Release assets are incomplete.')
        for asset in assets:
            digest, size = expected[asset['name']]
            require(asset.get('digest') == 'sha256:' + digest and asset.get('size') == size,
                    'Existing asset differs from the tested bytes; nothing will be overwritten.')


def require_manual_workflow(repository, paths):
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Release mutations require an explicit manual main workflow dispatch.')
    require(os.environ.get('GITHUB_WORKFLOW_REF') in {f'{repository}/{path}@refs/heads/main' for path in paths},
            'This workflow is not authorized to mutate the release registry or publish releases.')
