#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A real 30-second empty edit leaves <8s in the initial 32s request.
Preserves Phase 3.3.3's service fallback assertion for genuinely unsupported evidence;
the separate 137ms fixture now exercises adaptive recovery. No commercial material.
"""
import json
from pathlib import Path
from generate_automix_aac_boundary_fixtures import ROOT,ffmpeg,metadata
source=ROOT/'app/src/androidTest/assets/automix/boundary-padding14-44100-stereo.m4a'
target=ROOT/'app/src/androidTest/assets/automix/adaptive-empty-edit30s-44100-stereo.m4a'
ffmpeg(['-copyts','-i',str(source),'-c:a','copy','-output_ts_offset','30.023219954648526',
    '-use_editlist','1','-movie_timescale','44100','-movflags','+faststart',str(target)])
record=metadata(target)
assert record['mdat_sha256']==metadata(source)['mdat_sha256']
assert record['first_packets'][0]['pts']==30*44100
out=ROOT/'docs/benchmarks/phase334-adaptive-analysis'
out.mkdir(parents=True,exist_ok=True)
(out/'generated-fixture.json').write_text(json.dumps(record,indent=2)+'\n')
print(record['asset'],record['sha256'])
