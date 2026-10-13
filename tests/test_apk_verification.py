"""Regression checks for apksigner 36/37 output and real cryptographic verification.

Real APK tests require TINIUM_SIGNING_TEST_APK pointing to an existing built APK
and ANDROID_HOME/ANDROID_SDK_ROOT. They create disposable test keys in a protected
TemporaryDirectory, never use or change the owner's debug/release keys, and remove
all test files. Passwords reach keytool/apksigner through environment variables.
"""
import hashlib
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('apk_report', ROOT / 'scripts/check-apksigner-report.py')
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)
EXPECTED = 'a1' * 32
OTHER = 'b2' * 32


class ReportParsingTest(unittest.TestCase):
    def test_build_tools_36(self):
        self.assertEqual(REPORT.verify_report(
            f'Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: {EXPECTED}\n', EXPECTED), EXPECTED)

    def test_build_tools_37_schemes(self):
        for label in ['V1 Signer', 'V2 Signer', 'V3.0 Signer', 'V3.1 Signer', 'V3.1 Signer #1 (minSdkVersion=33)']:
            with self.subTest(label=label):
                self.assertEqual(REPORT.verify_report(
                    f'Number of signers: 1\n{label}: certificate SHA-256 digest: {EXPECTED}\n', EXPECTED), EXPECTED)

    def test_repeated_same_certificate_across_schemes_is_not_multiple_signers(self):
        report = f'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {EXPECTED}\nV3.0 Signer: certificate SHA-256 digest: {EXPECTED}\n'
        self.assertEqual(REPORT.verify_report(report, EXPECTED), EXPECTED)

    def test_different_scheme_certificate_is_rejected(self):
        with self.assertRaisesRegex(REPORT.VerificationError, 'mismatch'):
            REPORT.verify_report(f'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {EXPECTED}\nV3.0 Signer: certificate SHA-256 digest: {OTHER}\n', EXPECTED)

    def test_colons_uppercase_and_crlf(self):
        digest = ':'.join(EXPECTED.upper()[i:i+2] for i in range(0,64,2))
        self.assertEqual(REPORT.verify_report(f'Number of signers:   1\r\nV2 Signer: certificate SHA-256 digest: {digest}\r\n', digest), EXPECTED)

    def test_multiple_signers_including_ten(self):
        for count in [0, 2, 10]:
            with self.subTest(count=count), self.assertRaisesRegex(REPORT.VerificationError, 'exactly one'):
                REPORT.verify_report(f'Number of signers: {count}\nSigner #1 certificate SHA-256 digest: {EXPECTED}\n', EXPECTED)

    def test_numbered_additional_signer_is_rejected(self):
        with self.assertRaisesRegex(REPORT.VerificationError, 'additional'):
            REPORT.verify_report(f'Number of signers: 1\nV2 Signer #2: certificate SHA-256 digest: {EXPECTED}\n', EXPECTED)

    def test_missing_or_malformed_identity_is_rejected(self):
        for report in ['', f'V2 Signer: certificate SHA-256 digest: {EXPECTED}\n',
                       'Number of signers: 1\n', 'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: SECRET-CONTENTS\n']:
            with self.subTest(report=report), self.assertRaises(REPORT.VerificationError):
                REPORT.verify_report(report, EXPECTED)

    def test_unknown_certificate_output_is_rejected(self):
        with self.assertRaisesRegex(REPORT.VerificationError, 'Unsupported'):
            REPORT.verify_report(f'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {EXPECTED}\nUnknown Signer: certificate SHA-256 digest: {OTHER}\n', EXPECTED)

    def test_diagnostics_contain_only_validated_fingerprints(self):
        result = subprocess.run(['python3', str(ROOT/'scripts/check-apksigner-report.py'), EXPECTED],
            input='Number of signers: 1\nV2 Signer: certificate SHA-256 digest: PRIVATE-TEST-CONTENTS\n',
            text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('PRIVATE-TEST-CONTENTS', result.stdout + result.stderr)


@unittest.skipUnless(os.environ.get('TINIUM_SIGNING_TEST_APK'), 'Existing APK fixture not supplied')
class CryptographicVerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='tinium-apk-verification-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)  # TemporaryDirectory is mode 0700.
        cls.env = dict(os.environ, TINIUM_TEST_PASSWORD=secrets.token_urlsafe(24))
        cls.sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
        cls.tools = sorted((p for p in (cls.sdk/'build-tools').iterdir()
                            if p.name.replace('.', '').isdigit() and (p/'apksigner').is_file()),
                           key=lambda p: tuple(map(int, p.name.split('.'))))
        cls.apksigner = cls.tools[-1]/'apksigner'
        cls.keys = []
        for index in range(2):
            key = cls.directory/f'test-{index}.keystore'
            cls.run_tool(['keytool', '-genkeypair', '-keystore', str(key), '-alias', 'fixture',
                '-storepass:env', 'TINIUM_TEST_PASSWORD', '-keypass:env', 'TINIUM_TEST_PASSWORD',
                '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', 'CN=Disposable APK Verification Test'])
            key.chmod(0o600)
            cls.keys.append(key)
        cls.unsigned = cls.directory/'unsigned.apk'
        # A real compiled manifest permits apksigner to determine Android SDK levels.
        with zipfile.ZipFile(os.environ['TINIUM_SIGNING_TEST_APK']) as apk:
            manifest = apk.read('AndroidManifest.xml')
        with zipfile.ZipFile(cls.unsigned, 'w') as apk:
            apk.writestr('AndroidManifest.xml', manifest)
            apk.writestr('assets/fixture.txt', b'Original signature test content')
        cls.signed = cls.directory/'valid.apk'
        cls.sign(cls.signed, cls.keys[:1])
        cls.expected = subprocess.run(['keytool', '-exportcert', '-keystore', str(cls.keys[0]),
            '-storepass:env', 'TINIUM_TEST_PASSWORD', '-alias', 'fixture'],
            env=cls.env, check=True, capture_output=True).stdout
        cls.expected = hashlib.sha256(cls.expected).hexdigest()

    @classmethod
    def run_tool(cls, command):
        result = subprocess.run(command, env=cls.env, capture_output=True)
        if result.returncode:
            # Never echo command environment, passwords, or raw tool diagnostics.
            raise AssertionError('Disposable cryptographic test fixture setup failed.')
        return result

    @classmethod
    def sign(cls, output, keys, v3=False):
        command = [str(cls.apksigner), 'sign', '--v1-signing-enabled', 'false', '--v3-signing-enabled', 'true' if v3 else 'false',
                   '--v4-signing-enabled', 'false', '--out', str(output)]
        for index, key in enumerate(keys):
            if index:
                command.append('--next-signer')
            command += ['--ks', str(key), '--ks-key-alias', 'fixture',
                        '--ks-pass', 'env:TINIUM_TEST_PASSWORD', '--key-pass', 'env:TINIUM_TEST_PASSWORD']
        cls.run_tool(command + [str(cls.unsigned)])

    def verify(self, apk, key=None):
        return subprocess.run(['bash', str(ROOT/'scripts/verify-release-apks.sh'),
            str(key or self.keys[0]), str(apk)], env=dict(self.env,
                ANDROID_KEYSTORE_PASSWORD=self.env['TINIUM_TEST_PASSWORD'], ANDROID_KEY_ALIAS='fixture'),
            text=True, capture_output=True)

    def assert_safe_failure(self, result, message):
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stderr)
        self.assertNotIn(self.env['TINIUM_TEST_PASSWORD'], result.stdout + result.stderr)

    def test_real_valid_signature_and_matching_certificate(self):
        result = self.verify(self.signed)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(self.expected, result.stdout)
        self.assertNotIn(self.env['TINIUM_TEST_PASSWORD'], result.stdout + result.stderr)

    def test_real_v3_signature_and_matching_certificate(self):
        signed = self.directory/'valid-v3.apk'
        self.sign(signed, self.keys[:1], v3=True)
        result = self.verify(signed)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(self.expected, result.stdout)

    def test_real_signature_with_wrong_configured_certificate(self):
        self.assert_safe_failure(self.verify(self.signed, self.keys[1]), 'certificate mismatch')

    def test_unsigned_apk_is_rejected(self):
        self.assert_safe_failure(self.verify(self.unsigned), 'signature verification failed')

    def test_tampered_signature_is_rejected(self):
        tampered = self.directory/'tampered.apk'
        raw = self.signed.read_bytes()
        self.assertIn(b'Original signature test content', raw)
        tampered.write_bytes(raw.replace(b'Original signature test content', b'Tampered signature test content'))
        self.assert_safe_failure(self.verify(tampered), 'signature verification failed')

    def test_missing_apk_is_rejected(self):
        self.assert_safe_failure(self.verify(self.directory/'missing.apk'), 'APK is missing')

    def test_multiple_real_signers_are_rejected(self):
        multiple = self.directory/'multiple.apk'
        self.sign(multiple, self.keys)
        # Establish that rejection is due to identity policy, not a broken signature.
        report = self.run_tool([str(self.apksigner), 'verify', '--verbose', '--print-certs', str(multiple)])
        self.assertIn(b'Number of signers: 2', report.stdout)
        self.assert_safe_failure(self.verify(multiple), 'exactly one APK signer')

    def test_actual_build_tools_36_and_37_reports(self):
        for tool in self.tools:
            with self.subTest(build_tools=tool.name):
                report = self.run_tool([str(tool/'apksigner'), 'verify', '--verbose', '--print-certs', str(self.signed)])
                self.assertEqual(REPORT.verify_report(report.stdout.decode(), self.expected), self.expected)


if __name__ == '__main__':
    unittest.main()
