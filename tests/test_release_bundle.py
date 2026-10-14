"""Validate release bundles with real APKs and disposable test credentials only."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from test_release_management import FakeGitHub, Registry, COMMIT
from release_apks import prepare_bundle, validate_bundle
from release_tools import ReleaseError


@unittest.skipUnless(os.environ.get('TINIUM_SIGNING_TEST_APK'), 'Android integration fixture not enabled')
class ReleaseBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='tinium-release-bundle-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root=Path(cls.temp.name)
        cls.sdk=Path(os.environ['ANDROID_HOME'])
        versions=sorted((p for p in (cls.sdk/'build-tools').iterdir() if p.name.replace('.','').isdigit()),
                        key=lambda p:tuple(map(int,p.name.split('.'))))
        cls.tools=versions[-1]
        cls.env=dict(os.environ,ANDROID_KEYSTORE_PASSWORD=secrets.token_urlsafe(24),ANDROID_KEY_ALIAS='fixture')
        cls.key=cls.root/'disposable.keystore'
        cls.tool(['keytool','-genkeypair','-keystore',str(cls.key),'-alias','fixture',
                  '-storepass:env','ANDROID_KEYSTORE_PASSWORD','-keypass:env','ANDROID_KEYSTORE_PASSWORD',
                  '-keyalg','RSA','-keysize','2048','-validity','1','-dname','CN=Disposable Release Bundle Test'])
        cls.key.chmod(0o600)
        jar=next((cls.sdk/'platforms').glob('*/android.jar'))
        manifest=cls.root/'AndroidManifest.xml'
        manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yren411.tinium" android:versionCode="78" android:versionName="6.0.8"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37"/><application android:label="Tinium" android:debuggable="false"/></manifest>''')
        unsigned=cls.root/'unsigned.apk'
        cls.tool([str(cls.tools/'aapt2'),'link','-I',str(jar),'--manifest',str(manifest),'-o',str(unsigned)])
        cls.apk=cls.root/'signed.apk'
        cls.tool([str(cls.tools/'apksigner'),'sign','--ks',str(cls.key),'--ks-key-alias','fixture',
                  '--ks-pass','env:ANDROID_KEYSTORE_PASSWORD','--key-pass','env:ANDROID_KEYSTORE_PASSWORD',
                  '--out',str(cls.apk),str(unsigned)])

    @classmethod
    def tool(cls,command):
        result=subprocess.run(command,env=cls.env,capture_output=True)
        if result.returncode:raise AssertionError('Disposable release-bundle fixture setup failed.')

    def setUp(self):
        temporary=tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory=Path(temporary.name)/'bundle'
        self.registry=Registry(FakeGitHub())
        self.record=self.registry.reserve('stable','6.0.8',COMMIT)
        self.provenance={'artifactId':123,'buildRunId':456,'buildAttempt':1,'channel':'stable','commit':COMMIT}
        self.environ=patch.dict(os.environ,self.env)
        self.environ.start()
        self.addCleanup(self.environ.stop)
        prepare_bundle(self.record,self.provenance,self.key,self.apk,self.apk,self.directory,self.registry.github.repository)
        original_notes=Path('release-notes/6.0.8.md').read_bytes()
        self.notes_reader=patch('release_notes.committed_notes_bytes',return_value=original_notes)
        self.notes_reader.start()
        self.addCleanup(self.notes_reader.stop)

    def validate(self,provenance=None):
        return validate_bundle(self.directory,provenance or self.provenance,self.registry,self.key,self.registry.github.repository)

    def test_real_signed_bundle_identity_versions_checksums(self):
        record,files,notes=self.validate()
        self.assertEqual(record,self.record)
        self.assertEqual(len(files),4)
        self.assertIn('Tinium 6.0.8',notes)
        self.assertEqual(notes.count('# Tinium 6.0.8'),1)
        metadata=json.loads((self.directory/'release.json').read_text())
        self.assertEqual({a['variant'] for a in metadata['assets']},{'gms','foss'})
        self.assertTrue(all(a['minSdk']==26 and a['targetSdk']==37 for a in metadata['assets']))

    def test_changed_tested_apk_is_rejected(self):
        apk=next(self.directory.glob('*.apk'))
        apk.write_bytes(apk.read_bytes()+b'changed after testing')
        with self.assertRaisesRegex(ReleaseError,'bytes changed'):self.validate()

    def test_changed_notes_are_rejected(self):
        (self.directory/'release-notes.md').write_text('Changed after testing')
        with self.assertRaisesRegex(ReleaseError,'notes changed'):self.validate()

    def test_wrong_build_attempt_is_rejected(self):
        with self.assertRaisesRegex(ReleaseError,'approved build'):
            self.validate(dict(self.provenance,buildAttempt=2))

    def test_extra_file_or_symlink_is_rejected(self):
        (self.directory/'private.keystore').write_text('must never upload arbitrary files')
        with self.assertRaisesRegex(ReleaseError,'Unexpected files'):self.validate()

    def test_unknown_metadata_fields_cannot_be_published(self):
        metadata=json.loads((self.directory/'release.json').read_text())
        metadata['privateSigningMaterial']='PRIVATE-TEST-VALUE'
        (self.directory/'release.json').write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ReleaseError,'Unexpected fields') as error:self.validate()
        self.assertNotIn('PRIVATE-TEST-VALUE',str(error.exception))

    def test_wrong_variant_version_code_is_rejected(self):
        metadata=json.loads((self.directory/'release.json').read_text())
        metadata['release']['versionCode']=79
        (self.directory/'release.json').write_text(json.dumps(metadata))
        with self.assertRaises(ReleaseError):self.validate()

    def test_private_draft_and_fallback_bundles_cannot_be_published(self):
        from release_notes import CandidateNotes, DRAFT_BANNER, FALLBACK
        for mode, text, source in [('unreviewed','- A real testing change.','release-notes/unreleased.md'),
                                   ('internal-testing',FALLBACK,None)]:
            directory=self.directory.parent/mode
            candidate=CandidateNotes(text,source,'a'*64 if source else None,mode)
            with patch('release_apks.candidate_notes_for',return_value=candidate):
                manifest=prepare_bundle(self.record,self.provenance,self.key,self.apk,self.apk,directory,self.registry.github.repository)
            self.assertEqual(manifest['notes']['reviewStatus'],mode)
            self.assertEqual(len(list(directory.iterdir())),4)
            self.assertIn(DRAFT_BANNER if mode=='unreviewed' else FALLBACK,(directory/'release-notes.md').read_text())
            with self.assertRaisesRegex(ReleaseError,'unreviewed/internal-testing'):
                validate_bundle(directory,self.provenance,self.registry,self.key,self.registry.github.repository)

    def test_legacy_reviewed_artifact_matches_original_commit_notes(self):
        path=self.directory/'release.json'
        manifest=json.loads(path.read_text())
        manifest['notes']={key:manifest['notes'][key] for key in ('filename','sha256')}
        path.write_text(json.dumps(manifest))
        self.validate()
        with patch('release_notes.committed_notes_bytes',return_value=b'Different source notes'):
            with self.assertRaisesRegex(ReleaseError,'built commit'):self.validate()

    def test_reviewed_flag_and_rehashed_notes_cannot_replace_source_snapshot(self):
        import hashlib
        path=self.directory/'release.json';manifest=json.loads(path.read_text())
        replacement=b'# Tinium 6.0.8\n\nChanged after testing.\n'
        (self.directory/'release-notes.md').write_bytes(replacement)
        manifest['notes']['sha256']=hashlib.sha256(replacement).hexdigest()
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ReleaseError,'built commit'):self.validate()


if __name__=='__main__':unittest.main()
