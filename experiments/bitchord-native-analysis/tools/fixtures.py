# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generated PCM only. Versioned LCG; no songs, downloads, NumPy or audio dependencies."""
import array
import hashlib
import json
import math
from pathlib import Path
import sys

GENERATOR = 'tinium-native-corpus-v1'

class Noise:
    def __init__(self, seed=411):
        self.state = seed
    def next(self):
        self.state = (1664525 * self.state + 1013904223) & 0xffffffff
        return self.state / 4294967296.0 * 2 - 1

def generate(spec):
    rate = spec['rate']
    duration = spec['seconds']
    count = round(rate * duration)
    pcm = array.array('f', [0.0]) * count
    kind = spec.get('kind', 'rhythm')
    gain = spec.get('gain', 1.0)
    leading = spec.get('leading', 0.0)
    rng = Noise(spec.get('seed', 411))
    if kind == 'noise':
        for i in range(count):
            pcm[i] = gain * .08 * rng.next()
        return pcm, []
    if kind == 'silence':
        return pcm, []
    if kind == 'dc':
        return array.array('f', [.1 * gain]) * count, []
    for i in range(round(leading * rate), count):
        t = i / rate - leading
        if kind == 'tone':
            value = .08 * math.sin(2 * math.pi * 440 * t)
        elif kind == 'sweep':
            slope = (4000 - 80) / duration
            value = .08 * math.sin(2 * math.pi * (80 * t + .5 * slope * t * t))
        else:
            amplitude = .045 if kind == 'chord' else .008
            value = amplitude * sum(math.sin(2 * math.pi * f * t) for f in (261.625565, 329.627557, 391.995436))
        pcm[i] = gain * value
    if kind != 'rhythm':
        return pcm, []
    bpm = spec['bpm']
    phase = leading + spec.get('phase', .137)
    drift = spec.get('drift_bpm', 0.0)
    slope = drift / duration
    beats = []
    n = 0
    while True:
        # Integral of a linear BPM ramp, inverted analytically. No best-phase adjustment.
        offset = (math.sqrt(bpm*bpm + 120*slope*n) - bpm) / slope if slope else n * 60 / bpm
        time = phase + offset
        if time >= duration:
            break
        beats.append(round(time * rate) / rate)
        n += 1
    pattern = spec.get('pattern', 'kick')
    for beat, time in enumerate(beats):
        start = round(time * rate)
        accent = 1.0 if beat % 4 == 0 else .8
        if pattern == 'backbeat':
            accent = 1.0 if beat % 2 == 0 else .45
        for j in range(round(.18 * rate)):
            if start+j >= count:
                break
            t = j / rate
            attack = min(1.0, t / .002)
            pcm[start+j] += gain * .6 * accent * attack * math.exp(-t*36) * math.sin(2*math.pi*70*t)
        if pattern == 'backbeat' and beat % 2:
            for j in range(round(.11 * rate)):
                if start+j >= count:
                    break
                t = j / rate
                pcm[start+j] += gain * .16 * math.exp(-t*45) * rng.next()
        if pattern in ('backbeat', 'offbeat'):
            # Offbeat/hats are extra onsets, NOT extra ground-truth quarter-note beats.
            next_time = beats[beat+1] if beat+1 < len(beats) else time + 60/bpm
            for sub in (0, .5):
                hat = round((time + sub*(next_time-time)) * rate)
                for j in range(round(.028 * rate)):
                    if hat+j >= count:
                        break
                    t = j / rate
                    pcm[hat+j] += gain * .035 * math.exp(-t*100) * rng.next()
        if pattern == 'offbeat':
            off = start + round(.5 * 60/bpm * rate)
            for j in range(round(.08*rate)):
                if off+j >= count:
                    break
                t = j / rate
                pcm[off+j] += gain * .12 * math.exp(-t*50) * math.sin(2*math.pi*110*t)
    if max(abs(v) for v in pcm) >= 1:
        raise ValueError('Fixture clips; do not normalize away a generation defect')
    return pcm, beats

def specifications():
    out = []
    for bpm in (90, 107, 120, 128, 140, 150, 174):
        for pattern, phase in (('kick', .137), ('backbeat', .023), ('offbeat', .311)):
            out.append(dict(id=f'{bpm}-{pattern}', bpm=bpm, rate=22050, seconds=30, pattern=pattern, phase=phase))
    for bpm in (107, 128):
        for leading in (.5, 2.0):
            out.append(dict(id=f'{bpm}-leading-{leading}', bpm=bpm, rate=22050, seconds=30, pattern='backbeat', leading=leading))
    for gain in (.08, .5):
        out.append(dict(id=f'128-gain-{gain}', bpm=128, rate=22050, seconds=30, pattern='backbeat', gain=gain))
    out.append(dict(id='120-to-126-drift', bpm=120, drift_bpm=6, rate=22050, seconds=32, pattern='kick'))
    for rate in (8000, 11025, 16000, 22050, 32000, 44100, 48000, 96000):
        out.append(dict(id=f'128-rate-{rate}', bpm=128, rate=rate, seconds=32, pattern='kick'))
    for kind in ('silence', 'dc', 'noise', 'chord', 'tone', 'sweep'):
        out.append(dict(id=f'negative-{kind}', kind=kind, rate=22050, seconds=30))
    for seconds in (.2, 1, 5):
        out.append(dict(id=f'128-short-{seconds}', bpm=128, rate=22050, seconds=seconds, pattern='kick'))
    return out

def write_fixture(directory, spec):
    pcm, beats = generate(spec)
    if sys.byteorder != 'little':
        pcm.byteswap()
    data = pcm.tobytes()
    path = Path(directory) / (spec['id']+'.f32le')
    path.write_bytes(data)
    return dict(spec, generator=GENERATOR, file=path.name, sha256=hashlib.sha256(data).hexdigest(),
                samples=len(pcm), expected_beats_seconds=beats, expected_bpm=spec.get('bpm'), seed=spec.get('seed', 411))

def corpus(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    records = []
    for spec in specifications():
        print('Generating '+spec['id'], file=sys.stderr, flush=True)
        records.append(write_fixture(directory, spec))
    manifest = dict(generator=GENERATOR, format='mono IEEE Float32 little-endian', fixtures=records)
    (directory/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    return manifest

if __name__ == '__main__':
    import argparse
    p=argparse.ArgumentParser(); p.add_argument('--output', type=Path, required=True)
    args=p.parse_args(); corpus(args.output)
