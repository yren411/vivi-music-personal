#!/usr/bin/env python3
"""Commands used by manual candidate-build and tested-artifact publishing workflows."""
import argparse
import json
import os
from pathlib import Path
import sys

from release_apks import certificate_digest, prepare_bundle, validate_bundle
from release_notes import candidate_notes_for, render_notes
from release_tools import (BUILD_WORKFLOWS, GitHub, Registry, ReleaseError, artifact_name,
                           require, require_manual_workflow, validate_version)


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True)+'\n', encoding='utf-8')


def outputs(values):
    path = os.environ.get('GITHUB_OUTPUT')
    if path:
        with open(path, 'a', encoding='utf-8') as stream:
            for key, value in values.items():
                stream.write(f'{key}={value}\n')


def provenance(record):
    return {'buildRunId': int(os.environ['GITHUB_RUN_ID']), 'buildAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
            'commit': record['commit'], 'channel': record['channel']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default=os.environ.get('GITHUB_REPOSITORY', 'yren411/vivi-music-personal'))
    commands = parser.add_subparsers(dest='command', required=True)
    allocate = commands.add_parser('allocate')
    allocate.add_argument('--channel', choices=['beta', 'stable'], required=True)
    allocate.add_argument('--version', required=True)
    allocate.add_argument('--commit', required=True)
    plan = commands.add_parser('plan')
    plan.add_argument('--code', required=True, type=int)
    plan.add_argument('--version', required=True)
    plan.add_argument('--channel', required=True)
    plan.add_argument('--commit', required=True)
    plan.add_argument('--output', required=True)
    prepare = commands.add_parser('prepare')
    prepare.add_argument('--plan', required=True)
    prepare.add_argument('--gms', required=True)
    prepare.add_argument('--foss', required=True)
    prepare.add_argument('--directory', required=True)
    for command in ('inspect-build', 'publish'):
        item = commands.add_parser(command)
        item.add_argument('--run-id', required=True, type=int)
        item.add_argument('--attempt', required=True, type=int)
        if command == 'publish':
            item.add_argument('--directory', required=True)
            item.add_argument('--expected-tag', required=True)
    args = parser.parse_args()
    registry = Registry(GitHub(args.repository))
    if args.command == 'allocate':
        require_manual_workflow(args.repository, BUILD_WORKFLOWS)
        validate_version(args.channel, args.version)
        # Check safe note inputs before allocating; missing/empty draft notes are allowed.
        selected_notes = candidate_notes_for(args.version)
        render_notes(args.version, selected_notes)
        print(f'Candidate notes: {selected_notes.review_status}; source: {selected_notes.source or "internal-testing placeholder"}')
        record = registry.reserve(args.channel, args.version, args.commit)
        outputs({'code': record['versionCode'], 'tag': record['tag']})
        print(f"Reserved {record['tag']}: versionCode {record['versionCode']}; source {record['commit']}")
    elif args.command == 'plan':
        record = registry.reservation(args.code)
        require(record['channel'] == args.channel and record['versionName'] == args.version and record['commit'] == args.commit,
                'Build does not match the reserved release.')
        write_json(args.output, record)
    elif args.command == 'prepare':
        record = json.loads(Path(args.plan).read_text())
        require(registry.reservation(record['versionCode']) == record, 'Release plan does not match the registry.')
        manifest = prepare_bundle(record, provenance(record), os.environ['ANDROID_KEYSTORE_PATH'],
                                  args.gms, args.foss, args.directory, args.repository)
        name = artifact_name(record['channel'], int(os.environ['GITHUB_RUN_ID']), int(os.environ['GITHUB_RUN_ATTEMPT']))
        outputs({'artifact_name': name})
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a', encoding='utf-8') as stream:
                stream.write(f"## Tinium candidate ready for testing\n\nTag: `{record['tag']}`; versionCode: **{record['versionCode']}**.\n\n")
                status = manifest['notes']['reviewStatus']
                stream.write(f"Notes status: **{status.upper()}**; source: `{manifest['notes']['source'] or 'internal-testing placeholder'}`.\n\n")
                stream.write(f"Exact notes SHA-256: `{manifest['notes']['sha256']}`.\n\n")
                if status != 'reviewed':
                    stream.write("**NOT PUBLISHABLE:** this is an unpublished testing candidate. For public distribution, commit reviewed version-specific notes and build/test a fresh version.\n\n")
                stream.write(f"Publish only a reviewed candidate after testing. Select build run **{os.environ['GITHUB_RUN_ID']}**, attempt **{os.environ['GITHUB_RUN_ATTEMPT']}** "
                             f"and approval tag **{record['tag']}** in the separate Publish Tested Tinium Release workflow.\n\n")
                stream.write(f"Permanent certificate SHA-256: `{manifest['certificateSha256']}`.\n")
    elif args.command == 'inspect-build':
        descriptor = registry.build_artifact(args.run_id, args.attempt)
        outputs({'artifact_id': descriptor['artifactId'], 'source': descriptor['commit']})
    else:
        require_manual_workflow(args.repository, ['.github/workflows/publish_release.yml'])
        require(os.environ.get('NOTES_REVIEWED') == 'true', 'Confirm that you reviewed the exact candidate release-notes.md before public publication.')
        descriptor = registry.build_artifact(args.run_id, args.attempt)
        record, files, notes = validate_bundle(args.directory, descriptor, registry,
                                             os.environ['ANDROID_KEYSTORE_PATH'], args.repository)
        released = registry.publish(record, descriptor, files, notes, args.expected_tag, certificate_digest(os.environ['ANDROID_KEYSTORE_PATH']), notes_reviewed=True)
        print(f"Published/verified immutable Tinium release: {released['html_url']}")


if __name__ == '__main__':
    try:
        main()
    except (ReleaseError, KeyError, OSError, json.JSONDecodeError) as error:
        # Private credential contents and raw command diagnostics are never included.
        print(f'::error::{error}', file=sys.stderr)
        sys.exit(1)
