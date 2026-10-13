#!/usr/bin/env python3
"""Check certificate identity after apksigner has cryptographically verified an APK."""
import re
import sys

COUNT = re.compile(r'^Number of signers:\s*(\d+)\s*$', re.MULTILINE)
CERTIFICATE = re.compile(
    r'^(?P<label>Signer #\d+|V\d+(?:\.\d+)? Signer(?: #\d+)?(?: \([^\n]*\))?):?'
    r' certificate SHA-256 digest:\s*(?P<digest>[^\n]+)$', re.MULTILINE,
)


class VerificationError(ValueError):
    pass


def fingerprint(value):
    normalized = value.strip().replace(':', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', normalized):
        raise VerificationError('Missing or malformed SHA-256 certificate fingerprint.')
    return normalized


def verify_report(report, expected):
    expected = fingerprint(expected)
    counts = COUNT.findall(report)
    if not counts:
        raise VerificationError('Cannot determine APK signer count from apksigner output.')
    if any(int(count) != 1 for count in counts):
        raise VerificationError('Expected exactly one APK signer; multiple or missing signers are not allowed.')
    certificates = list(CERTIFICATE.finditer(report))
    if not certificates:
        raise VerificationError('No supported APK signer certificate fingerprint in apksigner output.')
    # Some versions report a certificate per signing scheme. Check every scheme,
    # without treating repeated V2/V3 certificate reports as additional signers.
    for cert in certificates:
        signer_number = re.search(r'#(\d+)', cert['label'])
        if signer_number and int(signer_number[1]) != 1:
            raise VerificationError('Unexpected additional APK signer in certificate output.')
    for line in report.splitlines():
        if 'certificate SHA-256 digest:' in line and not CERTIFICATE.fullmatch(line):
            # A source-stamp certificate is not an APK signing identity.
            if not re.match(r'^Source Stamp Signer:? certificate SHA-256 digest:', line):
                raise VerificationError('Unsupported APK certificate output; refusing ambiguous signing identity.')
    actual = {fingerprint(cert['digest']) for cert in certificates}
    if actual != {expected}:
        raise VerificationError(
            f'APK signing certificate mismatch. Expected SHA-256: {expected}; '
            f'actual SHA-256: {", ".join(sorted(actual))}.'
        )
    return expected


def main():
    if len(sys.argv) != 2:
        print('::error::Expected the configured certificate SHA-256 fingerprint.', file=sys.stderr)
        return 1
    try:
        verified = verify_report(sys.stdin.read(), sys.argv[1])
    except VerificationError as error:
        # All error text is fixed or contains validated public fingerprints only.
        print(f'::error::{error}', file=sys.stderr)
        return 1
    print(f'Verified one APK signer; certificate SHA-256: {verified}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
