#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Inspect real APK bytes/ELFs/notices. This is packaging validation, NOT Android execution."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

def inspect(apk, readelf, zipalign, root):
    subprocess.run([str(zipalign), '-c', '-P', '16', '-v', '4', str(apk)],
                   check=True, capture_output=True, text=True)
    expected={'arm64-v8a':'AArch64','armeabi-v7a':'ARM','x86':'Intel 80386','x86_64':'Advanced Micro Devices X86-64'}
    records=[]
    with zipfile.ZipFile(apk) as archive, apk.open('rb') as raw, tempfile.TemporaryDirectory() as temporary:
        names=archive.namelist()
        assert len(names)==len(set(names)), 'Duplicate ZIP entries'
        assert not any('libtinium_native_shared.so' in n or 'libupstream_reference' in n for n in names)
        native=[n for n in names if n.endswith('/libtinium_automix.so')]
        assert {n.split('/')[1] for n in native}==set(expected), native
        for name in sorted(native):
            entry=archive.getinfo(name);abi=name.split('/')[1]
            assert entry.compress_type==zipfile.ZIP_STORED, name
            raw.seek(entry.header_offset)
            header=struct.unpack('<IHHHHHIIIHH',raw.read(30))
            assert header[0]==0x04034b50
            offset=entry.header_offset+30+header[-2]+header[-1]
            assert offset%16384==0, (name,offset)
            data=archive.read(name);file=Path(temporary)/(abi+'.so');file.write_bytes(data)
            elf=subprocess.check_output([str(readelf),'-h','-lW','-d','--dyn-syms',str(file)],text=True)
            machine=re.search(r'^\s*Machine:\s*(.+)$',elf,re.M).group(1).strip()
            assert machine==expected[abi], machine
            alignments=[int(line.split()[-1],16) for line in elf.splitlines() if line.strip().startswith('LOAD ')]
            assert alignments and all(a>=16384 for a in alignments), alignments
            needed=re.findall(r'Shared library: \[(.*?)\]',elf)
            assert 'libc++_shared.so' not in needed, needed
            for method in ('analyze','implementationVersion'):
                assert 'Java_com_music_vivi_playback_automix_BitChordNativeBindings_'+method in elf
            records.append(dict(abi=abi,elf_bytes=len(data),zip_bytes=entry.compress_size,
                zip_data_offset=offset,elf_load_alignments=alignments,needed=needed,sha256=hashlib.sha256(data).hexdigest()))
        notices={}
        for name,file in {
            'COPYING':root/'experiments/bitchord-native-analysis/COPYING',
            'NOTICE.md':root/'experiments/bitchord-native-analysis/NOTICE.md',
            'provenance.json':root/'experiments/bitchord-native-analysis/provenance.json',
            'JNI-NOTICE.md':root/'app/src/main/cpp/NOTICE.md',
            'NDK-LIBCXX-NOTICE':root/'app/src/main/cpp/NDK-LIBCXX-NOTICE',
        }.items():
            entry='assets/licenses/bitchord-automix/'+name
            assert archive.read(entry)==file.read_bytes(), entry
            notices[name]=dict(bytes=len(archive.read(entry)),zip_bytes=archive.getinfo(entry).compress_size)
    return dict(apk=apk.name,apk_bytes=apk.stat().st_size,zipalign_16k=True,abis=records,notices=notices,
        added_native_and_notice_zip_payload=sum(r['zip_bytes'] for r in records)+sum(r['zip_bytes'] for r in notices.values()),
        size_caveat='Exact new-entry ZIP payload only; not a controlled total APK before/after delta (DEX/alignment/signing overhead excluded).')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--readelf',type=Path,required=True);p.add_argument('--zipalign',type=Path,required=True)
    p.add_argument('--root',type=Path,default=Path('.'));p.add_argument('--output',type=Path,required=True)
    p.add_argument('apks',type=Path,nargs='+');a=p.parse_args()
    results=dict(schema=1,android_runtime_tested=False,debug_test_apks_only=True,
        apks=[inspect(apk,a.readelf,a.zipalign,a.root) for apk in a.apks])
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(results,indent=2)+'\n')
    print('PASS: actual JNI ABIs, ELF/ZIP 16 KB alignment, JNI symbols and license assets')
