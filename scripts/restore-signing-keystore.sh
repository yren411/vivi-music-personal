#!/usr/bin/env bash
# Secrets arrive only through the environment, never command-line interpolation.
set -euo pipefail
set +x
umask 077

fail() { printf '::error::%s\n' "$1" >&2; exit 1; }
mode=${1:-}
destination=${2:-}
[[ "$mode" == debug || "$mode" == release ]] || fail 'Expected debug or release signing mode.'
[[ -n "$destination" ]] || fail 'A keystore destination is required.'

if [[ "$mode" == debug ]]; then
  [[ -n "${DEBUG_KEYSTORE_BASE64:-}" ]] || fail 'Missing DEBUG_KEYSTORE_BASE64 repository secret.'
  encoded=$DEBUG_KEYSTORE_BASE64
  export SIGNING_STORE_PASSWORD=android SIGNING_KEY_PASSWORD=android
  alias_name=androiddebugkey
else
  for name in ANDROID_SIGNING_KEY ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
    [[ -n "${!name:-}" ]] || fail "Missing $name repository secret."
  done
  [[ "$ANDROID_KEY_ALIAS" != androiddebugkey ]] || fail 'The Android debug alias cannot be used for releases.'
  encoded=$ANDROID_SIGNING_KEY
  export SIGNING_STORE_PASSWORD=$ANDROID_KEYSTORE_PASSWORD SIGNING_KEY_PASSWORD=$ANDROID_KEY_PASSWORD
  alias_name=$ANDROID_KEY_ALIAS
fi

mkdir -p "$(dirname "$destination")"
temporary=$(mktemp "$(dirname "$destination")/.tinium-keystore.XXXXXX")
trap 'rm -f "$temporary"' EXIT
# Accept Base64 pasted as one line or wrapped lines, including Windows CRLF.
if ! printf '%s' "$encoded" | tr -d '\r\n' | base64 --decode > "$temporary" 2>/dev/null; then
  fail 'Signing secret is not valid Base64. Encode the entire binary keystore.'
fi
unset encoded DEBUG_KEYSTORE_BASE64 ANDROID_SIGNING_KEY
[[ -s "$temporary" && -r "$temporary" ]] || fail 'Decoded signing keystore is empty or unreadable.'

# Listing validates the store password and alias; certreq also verifies private-key recovery.
if ! metadata=$(keytool -J-Duser.language=en -J-Duser.country=US -list -v \
  -keystore "$temporary" -storepass:env SIGNING_STORE_PASSWORD -alias "$alias_name" 2>/dev/null); then
  fail 'Keystore validation failed: check the store password and expected alias.'
fi
[[ "$metadata" == *'Entry type: PrivateKeyEntry'* ]] || fail 'The signing alias must contain a private key, not just a certificate.'
if [[ "$mode" == release && "$metadata" == *'CN=Android Debug'* ]]; then
  fail 'An Android debug certificate cannot be used for releases.'
fi
if ! keytool -certreq -keystore "$temporary" -storepass:env SIGNING_STORE_PASSWORD \
  -keypass:env SIGNING_KEY_PASSWORD -alias "$alias_name" >/dev/null 2>&1; then
  fail 'Cannot access the signing private key: check the key password.'
fi
chmod 600 "$temporary"
mv -f "$temporary" "$destination"
# Certificate fingerprints are public and let the owner detect unintended key changes.
keytool -J-Duser.language=en -J-Duser.country=US -list -v -keystore "$destination" \
  -storepass:env SIGNING_STORE_PASSWORD -alias "$alias_name" 2>/dev/null | sed -n '/SHA256:/p'
printf 'Validated %s signing keystore.\n' "$mode"
