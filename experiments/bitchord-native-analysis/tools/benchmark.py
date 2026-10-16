# SPDX-License-Identifier: AGPL-3.0-or-later
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import platform
import statistics
import subprocess
import time
from fixtures import corpus
from metrics import evaluate, fingerprint, percentile

def invoke(command):
    start=time.perf_counter_ns()
    process=subprocess.run(command,capture_output=True,text=True,timeout=40)
    # JVM launcher may report JAVA_TOOL_OPTIONS on stderr; never merge it into JSON.
    if process.returncode:
        raise RuntimeError('Analyzer process failed: '+str(command[0])+'; '+process.stdout[:1000]+' '+process.stderr[:1000])
    result=json.loads(process.stdout)
    result['process_wall_us']=(time.perf_counter_ns()-start)/1000
    return result

def run(build, directory, output, legacy=None, regenerate=False):
    manifest_file=directory/'manifest.json'
    manifest=corpus(directory) if regenerate or not manifest_file.exists() else json.loads(manifest_file.read_text())
    output.mkdir(parents=True,exist_ok=True)
    (output/'fixture-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    suffix='.exe' if platform.system()=='Windows' else ''
    rows=[]
    with (output/'benchmark.jsonl').open('w') as stream:
        for fixture in manifest['fixtures']:
            file=directory/fixture['file']
            if hashlib.sha256(file.read_bytes()).hexdigest()!=fixture['sha256']:
                raise RuntimeError('Fixture content changed: '+fixture['id'])
            baseline=invoke(legacy+[str(file),str(fixture['rate'])]) if legacy else None
            baseline_metrics=evaluate(fixture,baseline) if baseline and baseline['status']=='LEGACY_COMPLETED' else None
            for path in ('resample11025','direct'):
                args=['--input',str(file),'--sample-rate',str(fixture['rate']),'--path',path,'--origin-us','180000000']
                native=invoke([str(build/('analyzer_cli'+suffix))]+args)
                reference=invoke([str(build/('reference_cli'+suffix))]+args)
                if fingerprint(native)!=fingerprint(reference):
                    raise RuntimeError('Adapted/original semantic mismatch: '+fixture['id']+' '+path)
                row=dict(fixture=fixture['id'],path=path,pcm_sha256=fixture['sha256'],native=native,
                         metrics=evaluate(fixture,native),original_semantic_parity=True,
                         original_fingerprint=fingerprint(reference),original_elapsed_us=reference['elapsed_us'],
                         legacy=baseline,legacy_metrics=baseline_metrics)
                rows.append(row);stream.write(json.dumps(row,sort_keys=True,allow_nan=False)+'\n');stream.flush()
                print(fixture['id'],path,'bpm',round(native['bpm'],4),'score',round(native['native_confidence'],4),flush=True)
    summaries={}
    for path in ('resample11025','direct'):
        group=[r for r in rows if r['path']==path]
        rhythmic=[r for r in group if r['metrics']['expected_bpm'] is not None and not '-short-' in r['fixture']]
        summaries[path]=dict(cases=len(group),metrical_interpretations=dict(Counter(r['metrics']['metrical_interpretation'] for r in rhythmic)),
            median_f1_70ms=statistics.median(r['metrics']['at_70ms']['f1'] for r in rhythmic),
            median_f1_30ms=statistics.median(r['metrics']['at_30ms']['f1'] for r in rhythmic),
            analysis_us=dict(median=statistics.median(r['native']['elapsed_us'] for r in group),
                             p95=percentile([r['native']['elapsed_us'] for r in group],.95),
                             maximum=max(r['native']['elapsed_us'] for r in group)),
            negative_controls=[dict(fixture=r['fixture'],status=r['native']['status'],bpm=r['native']['bpm'],
                                    confidence=r['native']['native_confidence'],raw_false_beats=r['metrics']['at_70ms']['false_beats'])
                               for r in group if r['fixture'].startswith('negative-')])
    deterministic=[dict(fixture=r['fixture'],path=r['path'],pcm=r['pcm_sha256'],native=fingerprint(r['native'])) for r in rows]
    summary=dict(schema=1,generator=manifest['generator'],fixture_count=len(manifest['fixtures']),case_count=len(rows),
                 original_parity_cases=len(rows),platform=platform.platform(),python=platform.python_version(),
                 numerical_fingerprint=hashlib.sha256(json.dumps(deterministic,sort_keys=True).encode()).hexdigest(),paths=summaries)
    (output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    return summary

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--build',type=Path,required=True);p.add_argument('--corpus',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--legacy-command',type=Path);p.add_argument('--regenerate',action='store_true')
    a=p.parse_args();legacy=json.loads(a.legacy_command.read_text()) if a.legacy_command else None
    print(json.dumps(run(a.build.resolve(),a.corpus,a.output,legacy,a.regenerate),indent=2))
