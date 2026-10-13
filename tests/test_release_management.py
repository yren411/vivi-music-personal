"""Release coordination tests. All GitHub mutations use an in-memory fake."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
from release_tools import (GitHub, Registry, ReleaseError, artifact_name, require_manual_workflow, validate_record, validate_version)
from release_apks import apk_identity, sha256

COMMIT = 'a' * 40
OTHER_COMMIT = 'b' * 40


class FakeGitHub:
    repository = 'yren411/vivi-music-personal'

    def __init__(self):
        self.references, self.tags, self.published = {}, {}, []
        self.mutations = []
        self.runs, self.build_artifacts = {}, []
        self.fail_upload = False

    def refs(self, prefix):
        return [{'ref': 'refs/tags/'+name, 'object': {'type': 'tag', 'sha': sha}}
                for name, sha in self.references.items() if name.startswith(prefix)]

    def tag(self, sha):
        return self.tags[sha]

    def create_tag(self, name, record):
        sha = hashlib.sha1((name+json.dumps(record, sort_keys=True)).encode()).hexdigest()
        self.tags[sha] = {'tag': name, 'message': json.dumps(record),
                          'object': {'type': 'commit', 'sha': record['commit'], 'url': 'https://api.github.com/object'}}
        self.mutations.append(('tag', name))
        return sha

    def create_ref(self, name, sha):
        if name in self.references:
            raise ReleaseError('Reference exists; never overwrite.')
        self.references[name] = sha
        self.mutations.append(('ref', name))

    def releases(self):
        return copy.deepcopy(self.published)

    def create_release(self, data):
        if any(r['tag_name'] == data['tag_name'] for r in self.published):
            raise ReleaseError('Release exists.')
        value = dict(data, id=len(self.published)+1, assets=[], html_url='https://github.com/example/release')
        self.published.append(value)
        self.mutations.append(('release', copy.deepcopy(data)))
        return copy.deepcopy(value)

    def edit_release(self, release_id, data):
        value = next(r for r in self.published if r['id'] == release_id)
        value.update(data)
        self.mutations.append(('edit', copy.deepcopy(data)))
        return copy.deepcopy(value)

    def upload(self, release_id, path):
        if self.fail_upload:
            self.fail_upload = False
            raise ReleaseError('Simulated upload interruption.')
        value = {'name': path.name, 'digest': 'sha256:'+sha256(path), 'size': path.stat().st_size}
        next(r for r in self.published if r['id'] == release_id)['assets'].append(value)
        self.mutations.append(('upload', path.name))
        return value

    def run(self, run_id, attempt):
        return self.runs[(run_id, attempt)]

    def artifacts(self, run_id):
        return copy.deepcopy(self.build_artifacts)


class ReleaseManagementTest(unittest.TestCase):
    def setUp(self):
        self.github = FakeGitHub()
        self.registry = Registry(self.github)
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.files = {}
        for name in ['tinium-gms.apk', 'tinium-foss.apk', 'release.json', 'release-notes.md']:
            path = self.directory/name
            path.write_bytes(('Test bytes: '+name).encode())
            self.files[path] = sha256(path)

    def provenance(self, record):
        return {'artifactId': 123, 'buildRunId': 456, 'buildAttempt': 1,
                'channel': record['channel'], 'commit': record['commit']}

    def publish(self, record, **changes):
        provenance = dict(self.provenance(record), **changes)
        return self.registry.publish(record, provenance, self.files, '# Tinium release notes', record['tag'], 'ab'*32)

    def test_shared_beta_stable_sequence_starts_above_77(self):
        beta = self.registry.reserve('beta', '6.0.8-beta.1', COMMIT)
        stable = self.registry.reserve('stable', '6.0.8', COMMIT)
        next_beta = self.registry.reserve('beta', '6.0.9-beta.1', COMMIT)
        self.assertEqual([beta['versionCode'], stable['versionCode'], next_beta['versionCode']], [78,79,80])

    def test_build_retries_reuse_immutable_reservation(self):
        first = self.registry.reserve('beta', '6.0.8-beta.1', COMMIT)
        mutations = list(self.github.mutations)
        self.assertEqual(first, self.registry.reserve('beta', '6.0.8-beta.1', COMMIT))
        self.assertEqual(mutations, self.github.mutations)
        with self.assertRaisesRegex(ReleaseError, 'another source commit'):
            self.registry.reserve('beta', '6.0.8-beta.1', OTHER_COMMIT)

    def test_different_release_names_never_reuse_a_code(self):
        codes = [self.registry.reserve('beta', f'6.0.8-beta.{n}', COMMIT)['versionCode'] for n in range(1,5)]
        self.assertEqual(codes, [78,79,80,81])

    def test_version_names_are_human_readable_and_channel_specific(self):
        for channel, name in [('stable','6.0.8-beta.1'), ('beta','6.0.8'), ('beta','6.0.8-beta.0'),
                              ('stable','../6.0.8'), ('stable','6.0.8\ncommand'), ('stable','6.00.8')]:
            with self.subTest(name=name), self.assertRaises(ReleaseError):
                validate_version(channel, name)

    def test_version_code_limit_and_baseline(self):
        record = self.registry.reserve('stable','6.0.8',COMMIT)
        for code in [77,76,0,2_100_000_001,'78',True]:
            with self.subTest(code=code), self.assertRaises(ReleaseError):
                validate_record(dict(record, versionCode=code))

    def test_beta_prerelease_and_stable_latest_flags(self):
        beta = self.registry.reserve('beta','6.0.8-beta.1',COMMIT)
        result = self.publish(beta)
        self.assertTrue(result['prerelease'])
        self.assertFalse(result['draft'])
        self.assertEqual(result['make_latest'],'false')
        stable = self.registry.reserve('stable','6.0.8',COMMIT)
        result = self.publish(stable)
        self.assertFalse(result['prerelease'])
        self.assertEqual(result['make_latest'],'true')
        self.assertGreater(stable['versionCode'],beta['versionCode'])

    def test_newer_stable_blocks_older_beta_publication(self):
        beta = self.registry.reserve('beta','6.0.8-beta.1',COMMIT)
        stable = self.registry.reserve('stable','6.0.8',COMMIT)
        self.publish(stable)
        with self.assertRaisesRegex(ReleaseError,'never downgrade'):
            self.publish(beta)
        newer = self.registry.reserve('beta','6.0.9-beta.1',COMMIT)
        self.assertGreater(newer['versionCode'],stable['versionCode'])
        self.publish(newer)

    def test_publish_retries_are_read_only_after_success(self):
        record = self.registry.reserve('stable','6.0.8',COMMIT)
        self.publish(record)
        mutations = list(self.github.mutations)
        self.publish(record)
        self.assertEqual(mutations,self.github.mutations)
        with self.assertRaisesRegex(ReleaseError,'already approved'):
            self.registry.reserve('stable','6.0.8',COMMIT)

    def test_interrupted_upload_resumes_same_draft_without_overwrite(self):
        record = self.registry.reserve('stable','6.0.8',COMMIT)
        self.github.fail_upload = True
        with self.assertRaisesRegex(ReleaseError,'interruption'):
            self.publish(record)
        self.assertTrue(self.github.published[0]['draft'])
        self.assertFalse(any(m[0]=='edit' for m in self.github.mutations))
        self.publish(record)
        self.assertEqual(len([m for m in self.github.mutations if m[0]=='release']),1)
        self.assertEqual(len([m for m in self.github.mutations if m[0]=='upload']),4)

    def test_partial_upload_retry_preserves_existing_identical_assets(self):
        record=self.registry.reserve('stable','6.0.8',COMMIT)
        upload=self.github.upload
        calls=0
        def interrupted(release_id,path):
            nonlocal calls
            calls+=1
            if calls==2:raise ReleaseError('Second upload interrupted')
            return upload(release_id,path)
        with patch.object(self.github,'upload',side_effect=interrupted),self.assertRaises(ReleaseError):
            self.publish(record)
        self.assertEqual(len(self.github.published[0]['assets']),1)
        self.publish(record)
        names=[m[1] for m in self.github.mutations if m[0]=='upload']
        self.assertEqual(len(names),4)
        self.assertEqual(len(set(names)),4)

    def test_server_asset_hash_must_match_before_public_visibility(self):
        record=self.registry.reserve('stable','6.0.8',COMMIT)
        with patch.object(self.github,'upload',return_value={'digest':'sha256:'+'0'*64,'size':0}),self.assertRaisesRegex(ReleaseError,'digest/size'):
            self.publish(record)
        self.assertTrue(self.github.published[0]['draft'])
        self.assertFalse(any(m[0]=='edit' for m in self.github.mutations))

    def test_different_artifact_cannot_overwrite_existing_release(self):
        record = self.registry.reserve('stable','6.0.8',COMMIT)
        self.publish(record)
        with self.assertRaisesRegex(ReleaseError,'another artifact'):
            self.publish(record,artifactId=999)
        self.assertEqual(len(self.github.published),1)

    def test_changed_existing_asset_prevents_resume(self):
        record = self.registry.reserve('stable','6.0.8',COMMIT)
        self.publish(record)
        self.github.published[0]['assets'][0]['digest']='sha256:'+'0'*64
        with self.assertRaisesRegex(ReleaseError,'nothing will be overwritten'):
            self.publish(record)

    def test_deleted_release_still_has_durable_high_watermark(self):
        older = self.registry.reserve('beta','6.0.8-beta.1',COMMIT)
        newer = self.registry.reserve('stable','6.0.8',COMMIT)
        self.publish(newer)
        self.github.published.clear()  # Operator deleted release; tags remain.
        with self.assertRaisesRegex(ReleaseError,'never downgrade'):
            self.publish(older)
        self.assertEqual(self.registry.reserve('stable','6.0.9',COMMIT)['versionCode'],80)

    def test_unmanaged_release_fails_closed(self):
        self.github.published=[{'tag_name':'old-manual-release','body':'Unknown old APK code','draft':False,'prerelease':False}]
        with self.assertRaisesRegex(ReleaseError,'Unmanaged'):
            self.registry.reserve('stable','6.0.8',COMMIT)
        self.assertEqual(self.github.mutations,[])

    def test_missing_public_tag_never_resets_history(self):
        record=self.registry.reserve('stable','6.0.8',COMMIT)
        self.publish(record)
        del self.github.references[record['tag']]
        with self.assertRaisesRegex(ReleaseError,'durable'):
            self.registry.reserve('stable','6.0.9',COMMIT)

    def test_permanent_certificate_is_pinned_across_channels_and_deleted_releases(self):
        first=self.registry.reserve('stable','6.0.8',COMMIT)
        self.publish(first)
        self.github.published.clear()
        second=self.registry.reserve('beta','6.0.9-beta.1',COMMIT)
        with self.assertRaisesRegex(ReleaseError,'Permanent certificate changed'):
            self.registry.publish(second,self.provenance(second),self.files,'Notes',second['tag'],'cd'*32)
        self.assertEqual(self.github.published,[])

    def test_manual_approval_tag_must_match_candidate(self):
        record=self.registry.reserve('stable','6.0.8',COMMIT)
        with self.assertRaisesRegex(ReleaseError,'Typed approval'):
            self.registry.publish(record,self.provenance(record),self.files,'Notes','wrong-tag','ab'*32)
        self.assertEqual(self.github.published,[])

    def test_mutations_require_manual_main_workflow(self):
        expected = {'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF':'refs/heads/main',
                    'GITHUB_WORKFLOW_REF':'yren411/vivi-music-personal/.github/workflows/publish_release.yml@refs/heads/main'}
        with patch.dict('os.environ',expected,clear=True):
            require_manual_workflow(self.github.repository,['.github/workflows/publish_release.yml'])
        for field,value in [('GITHUB_EVENT_NAME','push'),('GITHUB_REF','refs/heads/other'),
                            ('GITHUB_WORKFLOW_REF','repo/.github/workflows/build.yml@refs/heads/main')]:
            with patch.dict('os.environ',dict(expected,**{field:value}),clear=True), self.assertRaises(ReleaseError):
                require_manual_workflow(self.github.repository,['.github/workflows/publish_release.yml'])

    def trusted_run(self):
        run={'path':'.github/workflows/beta_release.yml','event':'workflow_dispatch','head_branch':'main',
             'head_repository':{'full_name':self.github.repository},'status':'completed','conclusion':'success',
             'run_attempt':2,'head_sha':COMMIT}
        self.github.runs[(456,2)]=run
        self.github.build_artifacts=[{'name':artifact_name('beta',456,2),'id':123,'expired':False,
                                     'workflow_run':{'id':456,'head_sha':COMMIT,'head_branch':'main'}}]
        return run

    def test_publish_selects_exact_successful_attempt_and_artifact_id(self):
        self.trusted_run()
        descriptor=self.registry.build_artifact(456,2)
        self.assertEqual(descriptor['artifactId'],123)
        self.assertEqual(descriptor['buildAttempt'],2)

    def test_rejects_untrusted_failed_or_wrong_workflow_build(self):
        for field,value in [('event','pull_request'),('head_branch','other'),('conclusion','failure'),
                            ('path','.github/workflows/build-apk.yml'),('run_attempt',1),
                            ('head_repository',{'full_name':'attacker/fork'})]:
            self.trusted_run()[field]=value
            with self.subTest(field=field), self.assertRaises(ReleaseError):
                self.registry.build_artifact(456,2)

    def test_expired_ambiguous_and_wrong_commit_artifacts_rejected(self):
        for fault in ['expired','duplicate','source']:
            self.trusted_run()
            if fault=='expired':self.github.build_artifacts[0]['expired']=True
            if fault=='duplicate':self.github.build_artifacts*=2
            if fault=='source':self.github.build_artifacts[0]['workflow_run']['head_sha']=OTHER_COMMIT
            with self.subTest(fault=fault), self.assertRaises(ReleaseError):
                self.registry.build_artifact(456,2)

    def test_apk_identity_versions_debuggable_and_sdk(self):
        record=self.registry.reserve('stable','6.0.8',COMMIT)
        valid="package: name='com.yren411.tinium' versionCode='78' versionName='6.0.8'\nminSdkVersion:'26'\ntargetSdkVersion:'37'\nnative-code: 'arm64-v8a' 'x86_64'\n"
        self.assertEqual(apk_identity(valid,record)['supportedAbis'],['arm64-v8a','x86_64'])
        for bad in [valid.replace('com.yren411.tinium','other.app'),valid.replace("versionCode='78'","versionCode='77'"),
                    valid.replace("versionName='6.0.8'","versionName='6.0.9'"),valid+'application-debuggable\n']:
            with self.subTest(report=bad),self.assertRaises(ReleaseError):apk_identity(bad,record)

    def test_github_pagination_supports_existing_cli_without_slurp(self):
        result=type('Result',(),{'returncode':0,'stdout':'[1,2]\n[3]','stderr':''})()
        with patch('release_tools.subprocess.run',return_value=result) as run:
            self.assertEqual(GitHub(self.github.repository).pages('releases'),[1,2,3])
            self.assertNotIn('--slurp',run.call_args[0][0])
    def test_immutable_tag_annotations_are_cached_but_not_mutable_release_state(self):
        client=GitHub(self.github.repository)
        with patch.object(client,'request',return_value={'tag':'immutable'}) as request:
            client.tag(COMMIT)
            client.tag(COMMIT)
            self.assertEqual(request.call_count,1)



class WorkflowAuthorizationTest(unittest.TestCase):
    def test_only_manual_publisher_has_publication_command_and_no_build(self):
        root=Path(__file__).resolve().parents[1]
        publisher=(root/'.github/workflows/publish_release.yml').read_text()
        self.assertIn('workflow_dispatch:',publisher)
        self.assertNotRegex(publisher,r'(?m)^  (push|workflow_run|pull_request|schedule|release):')
        self.assertIn('github.ref == \'refs/heads/main\'',publisher)
        self.assertIn('artifact-ids:',publisher)
        self.assertIn('--expected-tag "$APPROVED_TAG"',publisher)
        self.assertNotIn('gradlew',publisher)
        for filename in ['beta_release.yml','release.yml']:
            builder=(root/'.github/workflows'/filename).read_text()
            self.assertNotIn('manage_releases.py publish',builder)
            self.assertIn('needs: allocate_version',builder)
            self.assertIn('group: tinium-release-registry',builder)
            self.assertIn('-PreleaseVersionCode="$RELEASE_CODE" -PreleaseVersionName="$RELEASE_VERSION"',builder)
            self.assertNotIn('github.run_number',builder)
        self.assertIn('group: tinium-release-registry',publisher)


if __name__=='__main__':unittest.main()
