#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generated AAC only. FFmpeg muxes real edit lists; no production MP4 parser added.
Run from any directory; requires FFmpeg/ffprobe on the host, never in Android builds.
"""
import hashlib
import argparse
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'experiments/bitchord-native-analysis/tools'))
from fixtures import write_fixture


def ffmpeg(args):
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y'] + args, check=True)


def boxes(data, start, end):
    while start < end:
        assert start + 8 <= end
        size, name = struct.unpack_from('>I4s', data, start)
        assert 8 <= size <= end - start
        yield name, start + 8, start + size
        start += size


def metadata(path):
    data = path.read_bytes()
    assert len(data) < 4 * 1024 * 1024
    def box(parent, name):
        return next((a, b) for n, a, b in boxes(data, *parent) if n == name)
    moov = box((0, len(data)), b'moov')
    trak = box(moov, b'trak')
    mvhd = box(moov, b'mvhd')[0]
    mdia = box(trak, b'mdia')
    mdhd = box(mdia, b'mdhd')[0]
    elst = box(box(trak, b'edts'), b'elst')[0]
    stts = box(box(box(mdia, b'minf'), b'stbl'), b'stts')[0]
    assert data[mvhd] == data[mdhd] == data[elst] == 0
    edits = [struct.unpack_from('>IiHH', data, elst + 8 + i * 12)
             for i in range(struct.unpack_from('>I', data, elst + 4)[0])]
    packets = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_packets',
        '-show_entries', 'packet=pts,duration,size:stream=sample_rate,channels,duration',
        '-of', 'json', str(path)]))
    mdat = box((0, len(data)), b'mdat')
    return dict(asset=path.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
        mdat_sha256=hashlib.sha256(data[mdat[0]:mdat[1]]).hexdigest(),
        movie_timescale=struct.unpack_from('>I', data, mvhd + 12)[0],
        media_timescale=struct.unpack_from('>I', data, mdhd + 12)[0],
        media_duration_frames=struct.unpack_from('>I', data, mdhd + 16)[0],
        raw_stts=[list(struct.unpack_from('>II',data,stts+8+i*8))
            for i in range(struct.unpack_from('>I',data,stts+4)[0])],
        edits=[dict(duration=e[0], media_time=e[1], rate_integer=e[2], rate_fraction=e[3]) for e in edits],
        first_packets=packets['packets'][:2], last_packets=packets['packets'][-2:],
        packet_count=len(packets['packets']), stream=packets['streams'][0])


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,default=ROOT/'app/src/androidTest/assets/automix')
    parser.add_argument('--report',type=Path,default=ROOT/'docs/benchmarks/phase333-aac-boundary/generated-fixtures.json')
    args=parser.parse_args()
    out=args.output;out.mkdir(parents=True,exist_ok=True)
    original = ROOT / 'app/src/androidTest/assets/automix/boundary-padding14-44100-stereo.m4a'
    positive = out / 'boundary-empty-edit6042-44100-stereo.m4a'
    # The original first packet is -1024/44100. Move that actual packet to +6042/44100
    # with a legitimate empty edit, not PCM insertion or a decoder timestamp patch.
    ffmpeg(['-copyts', '-i', str(original), '-c:a', 'copy', '-output_ts_offset',
        '0.1602267573696145', '-use_editlist', '1', '-movie_timescale', '44100',
        '-movflags', '+faststart', str(positive)])
    long = out / 'boundary-rounded-end-44100-stereo.m4a'
    with tempfile.TemporaryDirectory() as temp:
        frames = 6678 * 1024  # actual PCM ends at 155.062857142... s, not 155.063 s
        fixture = write_fixture(temp, dict(id='phase333-long', bpm=128, rate=44100,
            seconds=frames / 44100, pattern='kick', phase=.137))
        assert fixture['samples'] == frames
        ffmpeg(['-f', 'f32le', '-ar', '44100', '-ac', '1', '-i', str(Path(temp) / fixture['file']),
            '-ac', '2', '-c:a', 'aac', '-aac_pns', '0', '-b:a', '64k', '-movie_timescale', '1000',
            '-movflags', '+faststart', str(long)])
        decoded = Path(temp) / 'reference.f32le'
        ffmpeg(['-i', str(long), '-f', 'f32le', str(decoded)])
        assert decoded.stat().st_size // 8 == frames
        mono=out / 'boundary-exact-48000-mono.m4a'
        short=write_fixture(temp,dict(id='phase333-mono',bpm=128,rate=48000,seconds=36,pattern='kick',phase=.137))
        ffmpeg(['-f','f32le','-ar','48000','-ac','1','-i',str(Path(temp)/short['file']),
            '-c:a','aac','-aac_pns','0','-b:a','64k','-movie_timescale','48000','-movflags','+faststart',str(mono)])
    records = [metadata(p) for p in (positive, long, mono)]
    original_meta = metadata(original)
    assert records[0]['mdat_sha256'] == original_meta['mdat_sha256']
    assert records[0]['edits'][0]['duration'] == records[0]['first_packets'][0]['pts'] == 6042
    report = dict(schema=1, generator=Path(__file__).relative_to(ROOT).as_posix(),
        ffmpeg_version=subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
        fixtures=records, long_reference_decoded_frames=frames,
        reference_codec='FFmpeg AAC; host only, not Android MediaCodec',
        commercial_audio=False, android_execution='not executed by generator')
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    for r in records:
        print(r['asset'], r['bytes'], r['sha256'])
