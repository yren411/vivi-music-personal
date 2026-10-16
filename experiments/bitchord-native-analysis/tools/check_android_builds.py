# SPDX-License-Identifier: AGPL-3.0-or-later
"""Inspect actual cross-built ELF files. Does NOT assert APK ZIP alignment/runtime execution."""
import argparse
import json
from pathlib import Path
import re
import subprocess

def main():
    p=argparse.ArgumentParser();p.add_argument('--build-root',type=Path,required=True)
    p.add_argument('--readelf',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();records=[]
    expected={'arm64-v8a':'AArch64','armeabi-v7a':'ARM','x86':'Intel 80386','x86_64':'Advanced Micro Devices X86-64'}
    for abi,machine in expected.items():
        f=a.build_root/abi/'libtinium_native_shared.so'
        raw=subprocess.check_output([str(a.readelf),'-h','-lW','-d',str(f)],text=True)
        actual=re.search(r'^\s*Machine:\s*(.+)$',raw,re.M).group(1).strip()
        if actual!=machine:raise RuntimeError('Wrong ABI: '+abi+' '+actual)
        loads=[int(line.split()[-1],16) for line in raw.splitlines() if line.strip().startswith('LOAD ')]
        if not loads or any(value<16384 for value in loads):raise RuntimeError('Insufficient ELF alignment: '+abi)
        needed=re.findall(r'Shared library: \[(.*?)\]',raw)
        records.append(dict(abi=abi,machine=actual,elf_bytes=f.stat().st_size,load_segment_alignments=loads,needed=needed))
    a.output.write_text(json.dumps(dict(schema=1,android_api=26,ndk='27.0.12077973',runtime_tested=False,apk_packaging_tested=False,abis=records),indent=2)+'\n')
    print('PASS: four actual Android ELF architectures and >=16 KB LOAD alignment; no APK/runtime claim')
if __name__=='__main__':main()
