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
[[ -f "$keystore" && -r "$keystore" ]] || fail 'Configured release keystore is missing or unreadable.'
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
printf 'Using Android Build-Tools %s for cryptographic APK verification.\n' "$(basename "$(dirname "$apksigner")")"
if ! expected=$(keytool -exportcert -keystore "$keystore" -storepass:env ANDROID_KEYSTORE_PASSWORD \
  -alias "$ANDROID_KEY_ALIAS" 2>/dev/null | sha256sum | cut -d ' ' -f 1); then
  fail 'Cannot read the configured signing certificate; check the keystore password and alias.'
fi
for apk in "$@"; do
  [[ -f "$apk" ]] || fail 'An expected release APK is missing.'
  if ! report=$("$apksigner" verify --verbose --print-certs "$apk" 2>&1); then
    fail 'APK signature verification failed.'
  fi
  # Build-Tools 36 uses "Signer #1 ..."; 37 uses "V2 Signer: ..." or
  # "V3.0 Signer: ...". Parse the successful report without printing raw output.
  printf '%s\n' "$report" | python3 "$script_dir/check-apksigner-report.py" "$expected"
  printf 'Verified %s against the configured permanent release certificate.\n' "$apk"
done
