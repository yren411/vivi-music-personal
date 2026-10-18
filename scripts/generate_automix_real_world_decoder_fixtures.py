#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generated percussion only; reproduce AAC tail-padding/Opus decoder assets, no downloads."""
from pathlib import Path
import hashlib,json,subprocess,tempfile,sys,struct
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'experiments/bitchord-native-analysis/tools'))
from fixtures import write_fixture

def run(args): subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y']+args,check=True)

def declare_aac_tail_padding(path):
 """Express the existing last AAC frame's 14 encoded padding samples in MP4.
 FFmpeg shortens its last stts duration instead. Make coded-frame durations full,
 and extend mdhd accordingly, retaining the original elst presentation duration.
 No encoded audio bytes, atom sizes or presentation samples are changed.
 """
 data=bytearray(path.read_bytes())
 def box(start,end,name):
  at=start
  while at+8<=end:
   size,kind=struct.unpack_from('>I4s',data,at)
   assert size>=8 and at+size<=end
   if kind==name:return at+8,at+size
   at+=size
  raise AssertionError(name)
 moov=box(0,len(data),b'moov');trak=box(*moov,b'trak');mdia=box(*trak,b'mdia')
 mdhd=box(*mdia,b'mdhd');assert data[mdhd[0]]==0
 rate,duration=struct.unpack_from('>II',data,mdhd[0]+12);assert rate==44100
 stbl=box(*box(*mdia,b'minf'),b'stbl');stts=box(*stbl,b'stts')
 assert struct.unpack_from('>I',data,stts[0]+4)[0]==2
 first_count,first_duration,last_count,last_duration=struct.unpack_from('>IIII',data,stts[0]+8)
 assert (first_count,first_duration,last_count,last_duration)==(1536,1024,1,1010)
 assert duration==1536*1024+1010
 struct.pack_into('>I',data,stts[0]+20,1024)
 struct.pack_into('>I',data,mdhd[0]+16,duration+14)
 path.write_bytes(data)
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
  declare_aac_tail_padding(target)
  fixture=write_fixture(tmp,dict(id='phase331-opus',bpm=128,rate=48000,seconds=40,pattern='kick',phase=.137))
  target2=out/'rhythm-128-48000-40s.webm'
  run(['-f','f32le','-ar','48000','-ac','1','-i',str(Path(tmp)/fixture['file']),'-c:a','libopus','-b:a','64k',str(target2)])
  for p in [target,target2]: print(p.name,p.stat().st_size,hashlib.sha256(p.read_bytes()).hexdigest())
