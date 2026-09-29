#!/usr/bin/env python3
"""Check real FFmpeg payloads and 16 KB ELF/APK alignment, not device execution.

Usage: python tools/verify_android_native.py engine.aar --json native-report.json
       python tools/verify_android_native.py app.apk --require-abi arm64-v8a
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

MACHINES = {'arm64-v8a': 183, 'x86_64': 62}
REQUIRED = {'libffmpegkit.so', 'libavcodec.so', 'libavformat.so', 'libavutil.so'}
PAGE = 16384


def elf_segments(data: bytes, abi: str) -> int:
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('Expected a little-endian ELF64 native library')
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    if header[1] != 3 or header[2] != MACHINES[abi]:
        raise ValueError(f'ELF type/machine does not match {abi}')
    offset, entry_size, count = header[5], header[9], header[10]
    if entry_size != 56 or not 0 < count < 65535 or offset + entry_size * count > len(data):
        raise ValueError('Invalid ELF program header table')
    loads = 0
    for index in range(count):
        kind, _, file_offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            '<IIQQQQQQ', data, offset + index * entry_size)
        if file_offset + file_size > len(data) or (kind == 1 and file_size > memory_size):
            raise ValueError('Invalid ELF segment bounds')
        if kind == 1:  # PT_LOAD
            loads += 1
            if alignment < PAGE or alignment & (alignment - 1) or file_offset % alignment != address % alignment:
                raise ValueError('PT_LOAD is not 16 KB compatible')
        if kind == 0x6474e552 and (address + memory_size) % PAGE:
            raise ValueError('GNU_RELRO end is not 16 KB aligned; rebuild with suitable linker flags')
    if not loads:
        raise ValueError('Native library has no loadable segments')
    return loads


def verify(path: Path, required_abis: list[str]) -> dict:
    path = Path(path)
    if path.suffix.lower() not in {'.aar', '.apk'}:
        raise ValueError('Supply the actual .aar or .apk, not an API jar or Maven directory')
    if not required_abis or any(abi not in MACHINES for abi in required_abis):
        raise ValueError('Require arm64-v8a and/or x86_64')
    root = 'jni' if path.suffix.lower() == '.aar' else 'lib'
    report = {'artifact': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'runtimeQualified': False, 'libraries': []}
    found: dict[str, set[str]] = {}
    with zipfile.ZipFile(path) as archive, path.open('rb') as raw:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive paths are not accepted')
        for info in archive.infolist():
            parts = info.filename.split('/')
            if len(parts) != 3 or parts[0] != root or parts[1] not in MACHINES or not parts[2].endswith('.so'):
                continue
            abi, name = parts[1:]
            data = archive.read(info)
            try:
                count = elf_segments(data, abi)
                if root == 'lib' and info.compress_type == zipfile.ZIP_STORED:
                    raw.seek(info.header_offset)
                    local = raw.read(30)
                    if len(local) != 30 or local[:4] != b'PK\x03\x04':
                        raise ValueError('Invalid ZIP local header')
                    name_size, extra_size = struct.unpack_from('<HH', local, 26)
                    if (info.header_offset + 30 + name_size + extra_size) % PAGE:
                        raise ValueError('Uncompressed APK library is not 16 KB ZIP-aligned')
            except ValueError as error:
                raise ValueError(f'{info.filename}: {error}') from error
            found.setdefault(abi, set()).add(name)
            report['libraries'].append({'path': info.filename, 'sha256': hashlib.sha256(data).hexdigest(),
                                        'loadSegments': count, 'bytes': len(data)})
    for abi in required_abis:
        missing = REQUIRED - found.get(abi, set())
        if missing:
            raise ValueError(f'{abi}: missing real FFmpeg libraries: {", ".join(sorted(missing))}')
    # All 64-bit libraries in an archive are checked, not just requested ABI entries.
    report['requiredAbis'] = required_abis
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path)
    parser.add_argument('--require-abi', action='append', choices=sorted(MACHINES))
    parser.add_argument('--json', type=Path, help='Write provenance/alignment report')
    args = parser.parse_args()
    try:
        report = verify(args.artifact, args.require_abi or ['arm64-v8a'])
        encoded = json.dumps(report, indent=2)
        if args.json:
            args.json.parent.mkdir(parents=True, exist_ok=True)
            args.json.write_text(encoded + '\n', encoding='utf-8')
        print(encoded)
        print('Native payload/alignment checks passed. Runtime/device qualification is still required.')
        return 0
    except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        print(f'Native package verification failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
