# SPDX-License-Identifier: AGPL-3.0-or-later
"""Optional comparison only; no Android/lyrics classes are copied or loaded.
Requires current Kotlin compiler outputs, NOT an APK. Native build never invokes this.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--classes',type=Path,required=True)
    p.add_argument('--stdlib',type=Path,required=True)
    p.add_argument('--java-home',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--repo',type=Path,required=True)
    a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=True)
    names=['TrackFeaturesAnalyzer','TrackFeatures','MusicalKey','AnalysisDetail','RhythmSection',
           'SectionBoundary','EnergyPoint','SectionAnalysisKt','AutoMixModelsKt','MixingPolicy',
           'AnalysisUnavailable','AnalysisCancellation']
    prefix=Path('com/music/vivi/playback/automix')
    selected=[]
    for name in names:
        matches=list((a.classes/prefix).glob(name+'.class'))+list((a.classes/prefix).glob(name+'$*.class'))
        if not matches:
            raise SystemExit('Missing actual analyzer class: '+name)
        selected.extend(matches)
    jar=a.output/'legacy-analyzer-only.jar'
    with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_DEFLATED) as output:
        for file in sorted(set(selected)):
            output.write(file,str(file.relative_to(a.classes)))
    javac=a.java_home/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    java=a.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')
    cp=os.pathsep.join((str(jar.resolve()),str(a.stdlib.resolve())))
    subprocess.run([str(javac),'--release','21','-cp',cp,'-d',str(a.output),
                    str(Path(__file__).with_name('LegacyBaseline.java'))],check=True)
    command=[str(java),'-cp',cp+os.pathsep+str(a.output.resolve()),'LegacyBaseline']
    (a.output/'command.json').write_text(json.dumps(command,indent=2)+'\n')
    # Reproducibility evidence; no claim that stale compiler outputs match source without a fresh compile check.
    fingerprints={str(f.relative_to(a.classes)):hashlib.sha256(f.read_bytes()).hexdigest() for f in selected}
    for source in ['TrackFeaturesAnalyzer.kt','AutoMixModels.kt','SectionAnalysis.kt','AnalysisContracts.kt']:
        f=a.repo/'app/src/main/kotlin/com/music/vivi/playback/automix'/source
        fingerprints['source/'+source]=hashlib.sha256(f.read_bytes()).hexdigest()
    (a.output/'class-and-source-hashes.json').write_text(json.dumps(fingerprints,indent=2)+'\n')
    print('Prepared data-only legacy runner with',len(set(selected)),'nonrestricted classes; never linked to native library.')
if __name__=='__main__': main()
