# SPDX-License-Identifier: AGPL-3.0-or-later
import array
import hashlib
import json
import math
import platform
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from fixtures import generate, write_fixture
from metrics import beats, evaluate, fingerprint

class MetricsTests(unittest.TestCase):
    def test_one_reference_beat_cannot_match_two_predictions(self):
        m=beats([1.0],[.99,1.01],.03)
        self.assertEqual(1,m['matches']);self.assertEqual(.5,m['precision'])
    def test_no_octave_normalization(self):
        m=beats([0,.5,1,1.5],[0,1],.03)
        self.assertEqual(.5,m['recall']);self.assertAlmostEqual(2/3,m['f1'])
    def test_no_phase_rotation(self):
        self.assertEqual(0,beats([0,.5,1],[.25,.75,1.25],.07)['matches'])
    def test_unknown_reference_is_not_perfect_f1(self):
        self.assertIsNone(beats([],[],.07)['f1'])
        self.assertEqual(2,beats([],[.1,.2],.07)['false_beats'])
    def test_known_errors_and_drift(self):
        m=beats([1,2,3],[1.01,2.02,3.03],.07)
        self.assertAlmostEqual(20,m['median_error_ms']);self.assertAlmostEqual(29,m['p95_error_ms'])
        self.assertAlmostEqual(20,m['matched_drift_ms'])
    def test_half_time_is_reported_separately(self):
        spec=dict(seconds=30,bpm=174,expected_bpm=174,expected_beats_seconds=[1,2,3])
        m=evaluate(spec,dict(bpm=87,beats_seconds=[1,2,3]))
        self.assertEqual('half_time',m['metrical_interpretation']);self.assertEqual(87,m['absolute_bpm_error'])
    def test_generator_is_deterministic_and_gain_does_not_change_truth(self):
        spec=dict(id='test',bpm=128,rate=8000,seconds=5,pattern='backbeat',phase=.137)
        a,gt=generate(spec);b,gt2=generate(spec)
        self.assertEqual(a.tobytes(),b.tobytes());self.assertEqual(gt,gt2)
        c,gt3=generate(dict(spec,gain=.1));self.assertEqual(gt,gt3)
        self.assertLess(max(abs(v) for v in c),max(abs(v) for v in a))
    def test_fixture_sha_corresponds_to_exact_written_pcm(self):
        with tempfile.TemporaryDirectory() as d:
            r=write_fixture(d,dict(id='test',bpm=120,rate=11025,seconds=1))
            data=(Path(d)/r['file']).read_bytes()
            self.assertEqual(r['samples']*4,len(data));self.assertEqual(r['sha256'],hashlib.sha256(data).hexdigest())

class CliTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if len(sys.argv)<2:
            raise RuntimeError('Native build directory is required; do not silently skip CLI tests')
        cls.build=Path(sys.argv[1])
        cls.suffix='.exe' if platform.system()=='Windows' else ''
    def invoke(self,data,args=None):
        with tempfile.TemporaryDirectory() as directory:
            f=Path(directory)/'test.f32le';f.write_bytes(data)
            command=[str(self.build/('analyzer_cli'+self.suffix)),'--input',str(f),'--sample-rate','22050']+(args or [])
            r=subprocess.run(command,capture_output=True,text=True,timeout=20)
            return r.returncode,json.loads(r.stdout)
    def test_malformed_file_empty_and_partial_float(self):
        for data in (b'',b'123'):
            code,r=self.invoke(data);self.assertEqual(2,code);self.assertEqual('CLI_INPUT_ERROR',r['status'])
    def test_nonfinite_input_is_structured_failure(self):
        for number in (math.nan,math.inf):
            code,r=self.invoke(struct.pack('<f',number)*22050*4)
            self.assertEqual(2,code);self.assertEqual('INVALID_INPUT',r['status']);self.assertIsNone(r['rms'])
    def test_metadata_is_validated(self):
        for args in (['--sample-rate','22050oops'],['--sample-rate','0'],['--channels','2'],['--origin-us','-1'],['--path','bad']):
            code,r=self.invoke(b'\0'*22050*4*4,args)
            self.assertEqual(2,code);self.assertIn(r['status'],('CLI_INPUT_ERROR','INVALID_INPUT'))
    def test_repeated_calls_are_numerically_reproducible_and_match_original(self):
        pcm,_=generate(dict(id='test',bpm=128,rate=22050,seconds=5,pattern='kick'))
        code,a=self.invoke(pcm.tobytes());code2,b=self.invoke(pcm.tobytes())
        self.assertEqual(0,code);self.assertEqual(0,code2);self.assertEqual(fingerprint(a),fingerprint(b))
        with tempfile.TemporaryDirectory() as directory:
            f=Path(directory)/'test.f32le';f.write_bytes(pcm.tobytes())
            original=subprocess.run([str(self.build/('reference_cli'+self.suffix)),'--input',str(f),'--sample-rate','22050'],capture_output=True,text=True,check=True,timeout=20)
            self.assertEqual(fingerprint(a),fingerprint(json.loads(original.stdout)))
    def test_origin_is_metadata_not_grid_phase(self):
        pcm,_=generate(dict(id='test',bpm=120,rate=22050,seconds=5))
        _,a=self.invoke(pcm.tobytes());_,b=self.invoke(pcm.tobytes(),['--origin-us','180000000'])
        self.assertEqual(a['beats_seconds'],b['beats_seconds']);self.assertEqual(180000000,b['origin_us'])

if __name__=='__main__':
    # Preserve the build argument for setUpClass, but don't let unittest parse it.
    suite=unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
