"""Validate package identity/signature and describe the exact tested release bytes."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from release_tools import APP_ID, ReleaseError, require, validate_record


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def sdk_tool(name):
    root = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    versions = [p for p in (root/'build-tools').glob('*') if re.fullmatch(r'\d+\.\d+\.\d+', p.name) and (p/name).is_file()]
    require(bool(versions), 'Stable Android SDK Build-Tools are required.')
    return max(versions, key=lambda p: tuple(map(int, p.name.split('.'))))/name


def certificate_digest(keystore):
    result = subprocess.run(['keytool', '-exportcert', '-keystore', str(keystore),
        '-storepass:env', 'ANDROID_KEYSTORE_PASSWORD', '-alias', os.environ.get('ANDROID_KEY_ALIAS', '')], capture_output=True)
    require(result.returncode == 0 and bool(result.stdout), 'Cannot read configured release certificate.')
    return hashlib.sha256(result.stdout).hexdigest()


def badging(path):
    result = subprocess.run([str(sdk_tool('aapt2')), 'dump', 'badging', str(path)], text=True, capture_output=True)
    require(result.returncode == 0, 'APK package inspection failed.')
    return result.stdout


def apk_identity(report, record):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", report, re.MULTILINE)
    require(package is not None, 'APK has no readable package/version metadata.')
    require(package[1] == APP_ID and int(package[2]) == record['versionCode'] and package[3] == record['versionName'],
            'APK application ID, versionCode or versionName does not match its release reservation.')
    require(not re.search(r'^application-debuggable', report, re.MULTILINE), 'Debug APKs cannot be publicly released.')
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", report, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'(\d+)'", report, re.MULTILINE)
    abis = re.search(r'^native-code: (.+)$', report, re.MULTILINE)
    require(minimum is not None and target is not None, 'APK SDK metadata is missing.')
    return {'minSdk': int(minimum[1]), 'targetSdk': int(target[1]),
            'supportedAbis': re.findall(r"'([^']+)'", abis[1]) if abis else []}


def verify_signatures(keystore, apks):
    script = Path(__file__).resolve().parent/'verify-release-apks.sh'
    result = subprocess.run(['bash', str(script), str(keystore), *map(str, apks)])
    require(result.returncode == 0, 'Release APK certificate verification failed.')


def notes_for(version):
    path = Path('release-notes')/(version + '.md')
    require(path.is_file() and not path.is_symlink() and not path.parent.is_symlink(),
            f'Add reviewed release notes at release-notes/{version}.md before starting a build.')
    require(0 < path.stat().st_size <= 50_000, 'Release notes must be non-empty and at most 50KB.')
    notes = path.read_text(encoding='utf-8').strip()
    require(bool(notes) and '<!-- tinium-release:' not in notes, 'Release notes are empty or contain the reserved metadata marker.')
    return notes


def apk_filename(record, variant):
    return f"tinium-{record['channel']}-{record['versionName']}-universal-{variant}.apk"


def prepare_bundle(record, provenance, keystore, gms, foss, directory, repository):
    validate_record(record)
    require(provenance['commit'] == record['commit'] and provenance['channel'] == record['channel'], 'Build provenance does not match reservation.')
    sources = {'gms': Path(gms), 'foss': Path(foss)}
    require(all(p.is_file() and not p.is_symlink() for p in sources.values()), 'Both release APK variants are required.')
    verify_signatures(keystore, sources.values())
    identities = {variant: apk_identity(badging(path), record) for variant, path in sources.items()}
    certificate = certificate_digest(keystore)
    notes = notes_for(record['versionName'])
    directory = Path(directory)
    require(not directory.exists(), 'Candidate artifact directory already exists; refusing overwrite.')
    directory.mkdir(parents=True)
    assets = []
    for variant, source in sources.items():
        filename = apk_filename(record, variant)
        target = directory/filename
        shutil.copyfile(source, target)
        assets.append({'variant': variant, 'abi': 'universal', 'filename': filename,
            'sha256': sha256(target), 'size': target.stat().st_size, **identities[variant],
            'url': f"https://github.com/{repository}/releases/download/{record['tag']}/{filename}"})
    heading = f"# Tinium {record['versionName']}"
    body = notes + '\n' if notes.splitlines()[0] == heading else heading + '\n\n' + notes + '\n'
    (directory/'release-notes.md').write_text(body, encoding='utf-8')
    manifest = {'schemaVersion': 1, 'release': record, 'repository': repository,
        'build': {k: provenance[k] for k in ('buildRunId', 'buildAttempt', 'commit', 'channel')},
        'certificateSha256': certificate, 'assets': assets,
        'notes': {'filename': 'release-notes.md', 'sha256': sha256(directory/'release-notes.md')}}
    (directory/'release.json').write_text(json.dumps(manifest, indent=2, sort_keys=True)+'\n', encoding='utf-8')
    return manifest


def validate_bundle(directory, provenance, registry, keystore, repository):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'Candidate artifact directory is missing.')
    manifest_path = directory/'release.json'
    require(manifest_path.is_file() and not manifest_path.is_symlink() and manifest_path.stat().st_size <= 50_000, 'Candidate release metadata is missing or invalid.')
    try:
        manifest = json.loads(manifest_path.read_text())
        record = validate_record(manifest['release'])
    except (json.JSONDecodeError, KeyError, TypeError) as error:
        raise ReleaseError('Candidate metadata is malformed.') from error
    require(isinstance(manifest, dict) and set(manifest) == {'schemaVersion','release','repository','build','certificateSha256','assets','notes'}, 'Unexpected fields in candidate metadata; refusing arbitrary uploads.')
    require(type(manifest.get('schemaVersion')) is int and manifest.get('schemaVersion') == 1 and manifest.get('repository') == repository, 'Wrong candidate metadata schema/repository.')
    require(registry.reservation(record['versionCode']) == record, 'Candidate does not match its immutable version reservation.')
    build = {k: provenance[k] for k in ('buildRunId', 'buildAttempt', 'commit', 'channel')}
    require(manifest.get('build') == build and record['commit'] == provenance['commit'] and record['channel'] == provenance['channel'], 'Candidate is not from the approved build run/attempt.')
    require(manifest.get('certificateSha256') == certificate_digest(keystore), 'Candidate used another permanent signing certificate; do not replace keys.')
    assets = manifest.get('assets', [])
    require(isinstance(assets, list) and len(assets) == 2 and all(isinstance(a, dict) for a in assets) and {a.get('variant') for a in assets} == {'gms', 'foss'}, 'Expected exactly one GMS and one FOSS APK.')
    expected_names = {apk_filename(record, variant) for variant in ('gms', 'foss')} | {'release.json', 'release-notes.md'}
    require({p.name for p in directory.iterdir()} == expected_names and all(p.is_file() and not p.is_symlink() for p in directory.iterdir()), 'Unexpected files or unsafe paths in tested artifact.')
    apks = []
    for asset in assets:
        require(set(asset) == {'variant','abi','filename','sha256','size','minSdk','targetSdk','supportedAbis','url'}, 'Unexpected APK metadata fields.')
        filename = apk_filename(record, asset['variant'])
        require(asset.get('filename') == filename and asset.get('abi') == 'universal', 'Unexpected APK variant filename/ABI.')
        path = directory/filename
        require(asset.get('sha256') == sha256(path) and asset.get('size') == path.stat().st_size, 'Tested APK bytes changed; refusing publication.')
        identity = apk_identity(badging(path), record)
        require(all(asset.get(k) == v for k, v in identity.items()), 'APK SDK/ABI metadata mismatch.')
        require(asset.get('url') == f"https://github.com/{repository}/releases/download/{record['tag']}/{filename}", 'Unexpected APK download URL.')
        apks.append(path)
    notes = manifest.get('notes', {})
    require(isinstance(notes, dict) and set(notes) == {'filename','sha256'}, 'Unexpected release-note metadata.')
    require(notes.get('filename') == 'release-notes.md' and notes.get('sha256') == sha256(directory/'release-notes.md'), 'Tested release notes changed.')
    verify_signatures(keystore, apks)
    files = {path: sha256(path) for path in directory.iterdir()}
    return record, files, (directory/'release-notes.md').read_text(encoding='utf-8')
