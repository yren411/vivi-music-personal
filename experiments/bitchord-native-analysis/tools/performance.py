# SPDX-License-Identifier: AGPL-3.0-or-later
"""Desktop CLI timings, native cancellation and process RSS; never Android/battery claims."""
import argparse
import ctypes
import json
from pathlib import Path
import platform
import statistics
import subprocess
import tempfile
import time
from benchmark import invoke
from fixtures import write_fixture

def main():
    p=argparse.ArgumentParser();p.add_argument('--build',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();build=a.build.resolve();rows=[]
    suffix='.exe' if platform.system()=='Windows' else ''
    load_us=None
    if platform.system()=='Linux':
        start=time.perf_counter_ns(); library=ctypes.CDLL(str(build/'libtinium_native_shared.so'))
        load_us=(time.perf_counter_ns()-start)/1000
    with tempfile.TemporaryDirectory() as directory:
        for rate in (44100,48000):
            for seconds in (5,16,24,30,32):
                spec=dict(id=f'perf-{rate}-{seconds}',bpm=128,rate=rate,seconds=seconds,pattern='backbeat')
                fixture=write_fixture(directory,spec)
                for path in ('resample11025','direct'):
                    trials=[]
                    for repeat in range(3):
                        trial=invoke([str(build/('analyzer_cli'+suffix)),'--input',str(Path(directory)/fixture['file']),
                                      '--sample-rate',str(rate),'--path',path])
                        trials.append({k:trial[k] for k in ('elapsed_us','validation_us','resample_us','dsp_us','process_wall_us')})
                    rows.append(dict(rate=rate,seconds=seconds,path=path,trials=trials,
                                     median_us=statistics.median(v['elapsed_us'] for v in trials),maximum_us=max(v['elapsed_us'] for v in trials)))
                    print(spec['id'],path,round(rows[-1]['median_us']),flush=True)
    diagnostics={}
    for suite in ('cancellation','concurrency'):
        r=subprocess.run([str(build/('native_tests'+suffix)),suite],capture_output=True,text=True,check=True,timeout=60)
        diagnostics[suite]=[json.loads(line) for line in r.stdout.splitlines() if line.startswith('{')]
    result=dict(schema=1,environment='desktop host; CLI analysis excludes file read/process startup; RSS includes fixture/runtime allocations',
                platform=platform.platform(),first_host_dlopen_us=load_us,window_trials=rows,native_diagnostics=diagnostics)
    a.output.write_text(json.dumps(result,indent=2)+'\n')
if __name__=='__main__':main()
