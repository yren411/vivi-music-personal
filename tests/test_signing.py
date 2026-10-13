"""Signing integration checks; never generate private keys.

Run: python3 -m unittest discover -s tests -v
Optional: TINIUM_DEBUG_TEST_KEYSTORE=/path/to/existing/debug.keystore enables
real keytool restoration checks using the existing standard Android debug key.
"""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'restore-signing-keystore.sh'
SECRET_NAMES = (
    'DEBUG_KEYSTORE_BASE64', 'ANDROID_SIGNING_KEY', 'ANDROID_KEYSTORE_PASSWORD',
    'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD',
)


class SigningRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.destination = Path(self.temp.name) / 'keys' / 'test.keystore'
        self.env = {k: v for k, v in os.environ.items() if k not in SECRET_NAMES}

    def run_restore(self, mode, **secrets):
        return subprocess.run(
            ['bash', str(SCRIPT), mode, str(self.destination)],
            env={**self.env, **secrets}, text=True, capture_output=True,
        )

    def assert_rejected(self, result, message):
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stderr)
        self.assertFalse(self.destination.exists())
        self.assertEqual(list(Path(self.temp.name).rglob('.tinium-keystore.*')), [])

    def test_missing_debug_secret(self):
        self.assert_rejected(self.run_restore('debug'), 'Missing DEBUG_KEYSTORE_BASE64')

    def test_each_missing_release_secret(self):
        values = dict(ANDROID_SIGNING_KEY='not-a-key', ANDROID_KEYSTORE_PASSWORD='test',
                      ANDROID_KEY_ALIAS='tiniumrelease', ANDROID_KEY_PASSWORD='test')
        for name in values:
            with self.subTest(name=name):
                self.assert_rejected(self.run_restore('release', **{k: v for k, v in values.items() if k != name}), f'Missing {name}')

    def test_invalid_base64_leaves_no_key_and_does_not_log_input(self):
        secret = 'not-base64-PRIVATE-TEST-VALUE!'
        result = self.run_restore('debug', DEBUG_KEYSTORE_BASE64=secret)
        self.assert_rejected(result, 'not valid Base64')
        self.assertNotIn(secret, result.stdout + result.stderr)

    def test_decoded_non_keystore_is_rejected(self):
        secret = base64.b64encode(b'this is not a keystore').decode()
        result = self.run_restore('debug', DEBUG_KEYSTORE_BASE64=secret)
        self.assert_rejected(result, 'Keystore validation failed')
        self.assertNotIn(secret, result.stdout + result.stderr)

    def test_release_refuses_debug_alias(self):
        self.assert_rejected(self.run_restore(
            'release', ANDROID_SIGNING_KEY='unused', ANDROID_KEYSTORE_PASSWORD='android',
            ANDROID_KEY_ALIAS='androiddebugkey', ANDROID_KEY_PASSWORD='android',
        ), 'debug alias cannot be used for releases')

    def test_invalid_secret_preserves_existing_destination(self):
        self.destination.parent.mkdir()
        self.destination.write_bytes(b'existing user file')
        result = self.run_restore('debug', DEBUG_KEYSTORE_BASE64='broken!')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.destination.read_bytes(), b'existing user file')
        self.assertEqual(list(self.destination.parent.glob('.tinium-keystore.*')), [])

    @unittest.skipUnless(os.environ.get('TINIUM_DEBUG_TEST_KEYSTORE'), 'Existing debug fixture not supplied')
    def test_existing_debug_key_roundtrip_and_permissions(self):
        original = Path(os.environ['TINIUM_DEBUG_TEST_KEYSTORE']).read_bytes()
        encoded = base64.encodebytes(original).decode().replace('\n', '\r\n')
        result = self.run_restore('debug', DEBUG_KEYSTORE_BASE64=encoded)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.destination.read_bytes(), original)
        self.assertEqual(self.destination.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(encoded, result.stdout + result.stderr)

    @unittest.skipUnless(os.environ.get('TINIUM_DEBUG_TEST_KEYSTORE'), 'Existing debug fixture not supplied')
    def test_existing_key_rejects_wrong_store_password_and_alias(self):
        encoded = base64.b64encode(Path(os.environ['TINIUM_DEBUG_TEST_KEYSTORE']).read_bytes()).decode()
        for password, alias in [('wrong-password', 'tiniumrelease'), ('android', 'missing-alias')]:
            with self.subTest(password=password, alias=alias):
                result = self.run_restore('release', ANDROID_SIGNING_KEY=encoded,
                    ANDROID_KEYSTORE_PASSWORD=password, ANDROID_KEY_ALIAS=alias,
                    ANDROID_KEY_PASSWORD='android')
                self.assert_rejected(result, 'Keystore validation failed')
                self.assertNotIn(encoded, result.stdout + result.stderr)
                self.assertNotIn(password, result.stdout + result.stderr)

    @unittest.skipUnless(os.environ.get('TINIUM_DEBUG_TEST_KEYSTORE'), 'Existing debug fixture not supplied')
    def test_release_refuses_debug_certificate_even_with_renamed_alias(self):
        # Rename an alias in a copy of the existing key; never generate a key.
        copy = Path(self.temp.name) / 'renamed.keystore'
        copy.write_bytes(Path(os.environ['TINIUM_DEBUG_TEST_KEYSTORE']).read_bytes())
        subprocess.run(['keytool', '-changealias', '-keystore', str(copy),
            '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-destalias', 'tiniumrelease'],
            env=self.env, check=True, capture_output=True)
        encoded = base64.b64encode(copy.read_bytes()).decode()
        self.assert_rejected(self.run_restore('release', ANDROID_SIGNING_KEY=encoded,
            ANDROID_KEYSTORE_PASSWORD='android', ANDROID_KEY_ALIAS='tiniumrelease',
            ANDROID_KEY_PASSWORD='android'), 'debug certificate cannot be used for releases')

    @unittest.skipUnless(os.environ.get('TINIUM_DEBUG_TEST_KEYSTORE'), 'Existing debug fixture not supplied')
    def test_certificate_only_entry_is_rejected(self):
        # A public-certificate truststore contains no new or copied private key.
        certificate = Path(self.temp.name) / 'public.der'
        exported = subprocess.run(['keytool', '-exportcert', '-keystore',
            os.environ['TINIUM_DEBUG_TEST_KEYSTORE'], '-storepass', 'android',
            '-alias', 'androiddebugkey'], env=self.env, check=True, capture_output=True)
        certificate.write_bytes(exported.stdout)
        truststore = Path(self.temp.name) / 'public-only.keystore'
        subprocess.run(['keytool', '-importcert', '-noprompt', '-storetype', 'JKS',
            '-keystore', str(truststore), '-storepass', 'android',
            '-alias', 'androiddebugkey', '-file', str(certificate)],
            env=self.env, check=True, capture_output=True)
        encoded = base64.b64encode(truststore.read_bytes()).decode()
        self.assert_rejected(self.run_restore('debug', DEBUG_KEYSTORE_BASE64=encoded),
            'must contain a private key')


if __name__ == '__main__':
    unittest.main()
