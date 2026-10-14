"""Private drafts and strict publication review; no live releases or signing secrets."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
import manage_releases
from release_notes import (CandidateNotes, DRAFT_BANNER, FALLBACK, candidate_notes_for,
                           meaningful, render_notes, validate_public_notes)
from release_tools import ReleaseError
from test_release_management import FakeGitHub, Registry, COMMIT


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'release-notes'
        self.root.mkdir()
        self.version='6.0.8-beta.3'
        self.record={'versionName':self.version,'commit':COMMIT}

    def write(self,name,text):
        (self.root/name).write_text(text,encoding='utf-8')

    def select(self):return candidate_notes_for(self.version,self.root)

    def metadata(self,candidate,snapshot):
        return {'filename':'release-notes.md','sha256':hashlib.sha256(snapshot).hexdigest(),
                'reviewStatus':candidate.review_status,'source':candidate.source,'sourceSha256':candidate.source_sha256}

    def test_missing_notes_allow_identified_internal_build(self):
        candidate=self.select()
        self.assertEqual(candidate.review_status,'internal-testing')
        self.assertEqual(candidate.text,FALLBACK)
        self.assertIsNone(candidate.source)
        self.assertIn(FALLBACK.encode(),render_notes(self.version,candidate))

    def test_meaningful_unreleased_notes_are_unreviewed(self):
        self.write('unreleased.md','# Unreleased\n\n- Fix playback recovery.\n')
        candidate=self.select()
        self.assertEqual(candidate.review_status,'unreviewed')
        self.assertEqual(candidate.source,'release-notes/unreleased.md')
        self.assertIn(DRAFT_BANNER.encode(),render_notes(self.version,candidate))
        self.assertEqual(candidate.source_sha256,hashlib.sha256((self.root/'unreleased.md').read_bytes()).hexdigest())

    def test_empty_heading_comment_or_placeholder_draft_uses_fallback(self):
        for text in ['', ' \n', '# Unreleased\n', '<!-- future notes -->', '# Unreleased\n\n- TBD\n',
                     '# Unreleased\n\nTODO: fill in later',
                     '# Unreleased\n\nThis is an unversioned draft for review. No release version or tag is assigned.']:
            with self.subTest(text=text):
                self.write('unreleased.md',text)
                self.assertEqual(self.select().review_status,'internal-testing')

    def test_reviewed_versioned_file_takes_priority_over_draft(self):
        self.write(self.version+'.md',f'# Tinium {self.version}\n\n- Fix reviewed behavior.\n')
        self.write('unreleased.md','Other draft changes')
        candidate=self.select()
        self.assertEqual(candidate.review_status,'reviewed')
        self.assertNotIn(DRAFT_BANNER,render_notes(self.version,candidate).decode())
        self.assertNotIn('Other draft',candidate.text)

    def test_invalid_present_versioned_notes_do_not_silently_become_reviewed(self):
        self.write('unreleased.md','Meaningful changes')
        for text in ['', '# Title only', FALLBACK, DRAFT_BANNER+'\nChanges']:
            self.write(self.version+'.md',text)
            with self.assertRaises(ReleaseError):self.select()

    def test_symlinks_oversized_marker_or_invalid_utf8_are_rejected_safely(self):
        draft=self.root/'unreleased.md'
        outside=Path(self.temp.name)/'outside';outside.write_text('private-test-data')
        draft.symlink_to(outside)
        with self.assertRaisesRegex(ReleaseError,'symlink'):self.select()
        draft.unlink()
        for data in [b'x'*50_001,b'\xff',b'<!-- tinium-release:{} -->\nChanges']:
            draft.write_bytes(data)
            with self.assertRaises(ReleaseError) as error:self.select()
            self.assertNotIn('private-test-data',str(error.exception))

    def test_draft_or_placeholder_publication_is_rejected(self):
        for candidate in [CandidateNotes('Real draft changes','release-notes/unreleased.md','a'*64,'unreviewed'),
                          CandidateNotes(FALLBACK,None,None,'internal-testing')]:
            snapshot=render_notes(self.version,candidate)
            with patch('release_notes.committed_notes_bytes') as reader:
                with self.assertRaisesRegex(ReleaseError,'blocked'):
                    validate_public_notes(self.record,self.metadata(candidate,snapshot),snapshot)
                reader.assert_not_called()

    def test_reviewed_snapshot_is_bound_to_built_commit(self):
        self.write(self.version+'.md','- Fix reviewed behavior.\n')
        candidate=self.select();snapshot=render_notes(self.version,candidate)
        with patch('release_notes.committed_notes_bytes',return_value=(self.root/(self.version+'.md')).read_bytes()) as reader:
            self.assertIn('Fix reviewed',validate_public_notes(self.record,self.metadata(candidate,snapshot),snapshot))
            reader.assert_called_once_with(COMMIT,f'release-notes/{self.version}.md')

    def test_snapshot_or_source_mutation_is_rejected(self):
        self.write(self.version+'.md','- Reviewed behavior.\n')
        candidate=self.select();snapshot=render_notes(self.version,candidate);metadata=self.metadata(candidate,snapshot)
        with self.assertRaisesRegex(ReleaseError,'notes changed'):
            validate_public_notes(self.record,metadata,snapshot+b'Changed')
        with patch('release_notes.committed_notes_bytes',return_value=b'- Changed source.\n'):
            with self.assertRaisesRegex(ReleaseError,'source hash'):
                validate_public_notes(self.record,metadata,snapshot)

    def test_forged_reviewed_status_still_rejects_draft_banner_or_absent_source(self):
        candidate=CandidateNotes('A draft change','release-notes/unreleased.md','a'*64,'unreviewed')
        snapshot=render_notes(self.version,candidate);metadata=self.metadata(candidate,snapshot)
        metadata.update(reviewStatus='reviewed',source=f'release-notes/{self.version}.md')
        with self.assertRaisesRegex(ReleaseError,'cannot be published'):
            validate_public_notes(self.record,metadata,snapshot)
        clean=render_notes(self.version,CandidateNotes('Invented review',metadata['source'],'a'*64,'reviewed'))
        metadata['sha256']=hashlib.sha256(clean).hexdigest()
        with patch('release_notes.committed_notes_bytes',side_effect=ReleaseError('Absent from built commit')):
            with self.assertRaisesRegex(ReleaseError,'built commit'):
                validate_public_notes(self.record,metadata,clean)

    def test_legacy_candidate_requires_original_versioned_source(self):
        raw=b'- Original reviewed notes.\n'
        snapshot=render_notes(self.version,CandidateNotes(raw.decode(),None,None,'reviewed'))
        legacy={'filename':'release-notes.md','sha256':hashlib.sha256(snapshot).hexdigest()}
        with patch('release_notes.committed_notes_bytes',return_value=raw):
            self.assertIn('Original',validate_public_notes(self.record,legacy,snapshot))
        with patch('release_notes.committed_notes_bytes',return_value=b'Other notes'):
            with self.assertRaisesRegex(ReleaseError,'built commit'):validate_public_notes(self.record,legacy,snapshot)

    def test_selection_and_rendering_do_not_write_source_files(self):
        self.write('unreleased.md','- Real feature.\n')
        before={p.name:p.read_bytes() for p in self.root.iterdir()}
        snapshot=render_notes(self.version,self.select())
        self.write('unreleased.md','- Later changes.\n')
        self.assertIn(b'Real feature',snapshot)
        self.assertNotIn(b'Later changes',snapshot)
        self.assertEqual(set(before),{p.name for p in self.root.iterdir()})
        self.assertFalse((self.root/(self.version+'.md')).exists())

    def test_beta_and_production_both_allow_missing_versioned_notes(self):
        for version in ['6.0.8-beta.3','6.0.9']:
            self.assertEqual(candidate_notes_for(version,self.root).review_status,'internal-testing')
        with self.assertRaises(ReleaseError):candidate_notes_for('../private',self.root)

    def test_allocate_cli_allows_draft_or_missing_notes_without_source_commits(self):
        fake=FakeGitHub();registry=Registry(fake)
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF':'refs/heads/main',
             'GITHUB_WORKFLOW_REF':f'{fake.repository}/.github/workflows/beta_release.yml@refs/heads/main'}
        candidate=self.select()
        args=['manage_releases.py','allocate','--channel','beta','--version',self.version,'--commit',COMMIT]
        with patch.dict(os.environ,env),patch.object(sys,'argv',args),patch('manage_releases.Registry',return_value=registry),patch('manage_releases.candidate_notes_for',return_value=candidate):
            manage_releases.main()
        self.assertEqual(registry.reservation(78)['versionName'],self.version)
        self.assertEqual(len(fake.references),1)

    def test_legacy_notes_helper_does_not_silently_claim_a_draft_is_reviewed(self):
        from release_apks import notes_for
        draft=CandidateNotes('Real draft changes','release-notes/unreleased.md','a'*64,'unreviewed')
        with patch('release_apks.candidate_notes_for',return_value=draft):
            with self.assertRaisesRegex(ReleaseError,'Reviewed version-specific'):notes_for(self.version)
        reviewed=CandidateNotes('Reviewed changes',f'release-notes/{self.version}.md','a'*64,'reviewed')
        with patch('release_apks.candidate_notes_for',return_value=reviewed):
            self.assertEqual(notes_for(self.version),'Reviewed changes')

    def test_publish_cli_review_approval_cannot_default_to_true(self):
        fake=FakeGitHub();registry=Registry(fake)
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF':'refs/heads/main',
             'GITHUB_WORKFLOW_REF':f'{fake.repository}/.github/workflows/publish_release.yml@refs/heads/main',
             'NOTES_REVIEWED':'false'}
        args=['manage_releases.py','publish','--run-id','1','--attempt','1','--directory','test','--expected-tag','tinium-v'+self.version]
        with patch.dict(os.environ,env),patch.object(sys,'argv',args),patch('manage_releases.Registry',return_value=registry):
            with self.assertRaisesRegex(ReleaseError,'reviewed'):manage_releases.main()
        self.assertEqual(fake.mutations,[])


if __name__=='__main__':unittest.main()
