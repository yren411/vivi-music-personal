#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Reproduce generated Android-test AAC/FLAC assets. Requires FFmpeg; never downloads audio."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'experiments/bitchord-native-analysis/tools'))
from fixtures import write_fixture

if __name__=='__main__':
    output=ROOT/'app/src/androidTest/assets/automix'
    with tempfile.TemporaryDirectory() as directory:
        for rate,ext,channels,codec in [(48000,'m4a',2,'aac'),(44100,'flac',1,'flac')]:
            spec=dict(id=f'native-128-{rate}',bpm=128,rate=rate,seconds=30,pattern='kick',phase=.137)
            fixture=write_fixture(directory,spec)
            command=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','f32le','-ar',str(rate),'-ac','1',
                '-i',str(Path(directory)/fixture['file']),'-ac',str(channels),'-c:a',codec]
            if codec=='aac':command+=['-b:a','128k','-movflags','+faststart']
            subprocess.run(command+[str(output/f'rhythm-128-{rate}.{ext}')],check=True)
