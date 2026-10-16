# SPDX-License-Identifier: AGPL-3.0-or-later
import hashlib
import json
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'provenance.json').read_text())
for item in manifest['files']:
    file=root/item['vendored_path']
    assert hashlib.sha256(file.read_bytes()).hexdigest()==item['vendored_sha256'],file
    text=file.read_text()
    assert 'GNU Affero General Public' in text and 'SFG545' in text and 'Kushagra Singh' in text,file
    if item['change']=='byte-identical':
        assert item['vendored_sha256']==item['upstream_sha256'],file
copying=(root/'COPYING').read_text()
assert hashlib.sha256((root/'COPYING').read_bytes()).hexdigest()==manifest['license_text_sha256']
assert 'GNU AFFERO GENERAL PUBLIC LICENSE' in copying and 'Version 3, 19 November 2007' in copying
assert manifest['revision']=='fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f'
assert not (root/'build.gradle.kts').exists()
print('PASS: pinned source, subset provenance, Orchard/BitChord notices and AGPL text')
