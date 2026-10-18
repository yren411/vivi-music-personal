#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generated percussion only; reproduce AAC tail-padding/Opus decoder assets, no downloads."""
from pathlib import Path
import hashlib,json,subprocess,tempfile,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'experiments/bitchord-native-analysis/tools'))
from fixtures import write_fixture

def run(args): subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y']+args,check=True)
if __name__=='__main__':
 out=ROOT/'app/src/androidTest/assets/automix'
 with tempfile.TemporaryDirectory() as tmp:
  # 1536 AAC frames minus 14 source samples. A sample-rate movie timebase avoids
  # millisecond edit-list rounding. One negative-PTS AAC priming packet is retained.
  frames=1536*1024-14
  fixture=write_fixture(tmp,dict(id='phase331-aac',bpm=128,rate=44100,seconds=frames/44100,pattern='kick',phase=.137))
  raw=Path(tmp)/fixture['file']
  target=out/'boundary-padding14-44100-stereo.m4a'
  run(['-f','f32le','-ar','44100','-ac','1','-i',str(raw),'-ac','2','-c:a','aac','-b:a','128k',
       '-movie_timescale','44100','-movflags','+faststart',str(target)])
  fixture=write_fixture(tmp,dict(id='phase331-opus',bpm=128,rate=48000,seconds=40,pattern='kick',phase=.137))
  target2=out/'rhythm-128-48000-40s.webm'
  run(['-f','f32le','-ar','48000','-ac','1','-i',str(Path(tmp)/fixture['file']),'-c:a','libopus','-b:a','64k',str(target2)])
  for p in [target,target2]: print(p.name,p.stat().st_size,hashlib.sha256(p.read_bytes()).hexdigest())
