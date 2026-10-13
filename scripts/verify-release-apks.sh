#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
fail() { printf '::error::%s\n' "$1" >&2; exit 1; }
keystore=${1:?Expected keystore path}
shift
(( $# > 0 )) || fail 'No release APKs supplied for verification.'
: "${ANDROID_KEYSTORE_PASSWORD:?Missing ANDROID_KEYSTORE_PASSWORD}"
: "${ANDROID_KEY_ALIAS:?Missing ANDROID_KEY_ALIAS}"
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$sdk" ]] || fail 'Android SDK path is missing.'
# Use the newest installed stable build-tools, with their matching apksigner.
apksigner=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name apksigner | sed -nE '\|/[0-9]+\.[0-9]+\.[0-9]+/apksigner$|p' | sort -V | tail -n 1)
[[ -x "$apksigner" ]] || fail 'Android build-tools/apksigner is missing.'
expected=$(keytool -exportcert -keystore "$keystore" -storepass:env ANDROID_KEYSTORE_PASSWORD \
  -alias "$ANDROID_KEY_ALIAS" 2>/dev/null | sha256sum | cut -d ' ' -f 1)
for apk in "$@"; do
  [[ -f "$apk" ]] || fail 'An expected release APK is missing.'
  if ! report=$("$apksigner" verify --verbose --print-certs "$apk" 2>&1); then
    fail 'APK signature verification failed.'
  fi
  actual=$(printf '%s\n' "$report" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
  [[ "$report" == *'Number of signers: 1'* && "$actual" == "$expected" ]] || fail 'APK does not use the configured permanent release certificate.'
  printf 'Verified %s; release certificate SHA-256: %s\n' "$apk" "$actual"
done
