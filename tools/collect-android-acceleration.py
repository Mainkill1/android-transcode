#!/usr/bin/env python3
"""Opt-in, fail-closed ADB inventory collector. No benchmark or qualification claim.

Requires matching debug and androidTest APKs already installed. Never installs APKs,
changes phone settings, or publishes data to a remote service. With --encoder it
runs a short, real native export using the separate instrumentation APK.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid
from typing import Any

PACKAGE = 'dev.forma.transcode'
TEST_CLASS = 'dev.forma.app.HardwareAccelerationInventoryTest'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
RUNTIME_CLASS = 'dev.forma.app.RuntimeAccelerationTest'
ENCODERS = ('H264_AUTO', 'H265_AUTO', 'H264_HW', 'H265_HW', 'VP9_HW', 'AV1_HW')


def validate_instrumentation(text: str) -> None:
    """ADB's exit status alone does not describe AndroidJUnitRunner's result."""
    failure_tokens = ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED',
                      'INSTRUMENTATION_RESULT: shortMsg=', 'Process crashed')
    if any(token in text for token in failure_tokens):
        raise ValueError('Android instrumentation reported a failure:\n' + text[-12000:])
    if not re.search(r'^\s*OK \(1 tests?\)\s*$', text, re.MULTILINE):
        raise ValueError('Expected exactly one completed inventory test:\n' + text[-12000:])


def validate_report(text: str, run_id: str) -> dict[str, Any]:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError('The device report is not valid JSON.') from error
    if not isinstance(data, dict):
        raise ValueError('The device report must be a JSON object.')
    if type(data.get('schemaVersion')) is not int or data['schemaVersion'] != 1:
        raise ValueError('Unknown device report schema.')
    if data.get('runId') != run_id:
        raise ValueError('Stale or mismatched device report; refusing to publish it.')
    if data.get('inventoryComplete') is not True:
        raise ValueError('Device inventory did not complete.')
    if data.get('deviceQualified') is not False or data.get('nativeExecutionTested') is not False:
        raise ValueError('Inventory must not claim native execution or device qualification.')
    codecs = data.get('codecs')
    if not isinstance(codecs, list) or not codecs:
        raise ValueError('Expected a nonempty list of video components.')
    for entry in codecs:
        if not isinstance(entry, dict) or not isinstance(entry.get('name'), str) or not entry['name']:
            raise ValueError('Invalid codec inventory entry.')
    return data


def validate_runtime_report(text: str, run_id: str, encoder: str) -> dict[str, Any]:
    data = json.loads(text)
    if not isinstance(data, dict) or type(data.get('schemaVersion')) is not int or data['schemaVersion'] != 1:
        raise ValueError('Unknown runtime report schema.')
    if data.get('runId') != run_id or data.get('requestedEncoder') != encoder:
        raise ValueError('Stale run or mismatched encoder request.')
    if data.get('success') is not True or data.get('nativeExecutionTested') is not True or data.get('deviceQualified') is not False:
        raise ValueError('Expected an actual successful native test, not a full qualification claim.')
    if type(data.get('outputBytes')) is not int or data['outputBytes'] <= 0:
        raise ValueError('Missing nonempty output evidence.')
    for key in ('sourceSha256', 'outputSha256'):
        if not isinstance(data.get(key), str) or not re.fullmatch(r'[0-9a-f]{64}', data[key]):
            raise ValueError('Missing media identity: ' + key)
    selected = data.get('selected')
    if not isinstance(selected, dict) or selected.get('backend') not in ('SOFTWARE', 'MEDIACODEC'):
        raise ValueError('Missing selected backend.')
    expected = {
        'H264_AUTO': ('libx264', 'h264_mediacodec'), 'H264_HW': (None, 'h264_mediacodec'),
        'H265_AUTO': ('libx265', 'hevc_mediacodec'), 'H265_HW': (None, 'hevc_mediacodec'),
        'VP9_HW': (None, 'vp9_mediacodec'), 'AV1_HW': (None, 'av1_mediacodec')
    }
    if encoder not in expected or selected.get('encoder') != expected[encoder][selected['backend'] == 'MEDIACODEC']:
        raise ValueError('The actual encoder does not match the requested format/backend.')
    if encoder.endswith('_HW') and selected['backend'] != 'MEDIACODEC':
        raise ValueError('Required-device test silently fell back to software.')
    if selected['backend'] == 'MEDIACODEC' and (not selected.get('component') or selected.get('hardware') not in ('YES', 'UNKNOWN')):
        raise ValueError('Missing actual device component/identity.')
    events = data.get('events')
    if not isinstance(events, list) or not events or not isinstance(events[-1], dict) or events[-1].get('status') != 'VERIFIED':
        raise ValueError('The last attempt was not verified.')
    return data


def _run(arguments: list[str], timeout: int) -> bytes:
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, check=False)
    if result.returncode != 0:
        diagnostic = (result.stdout + b'\n' + result.stderr).decode('utf-8', errors='replace')
        raise RuntimeError(f'ADB exited {result.returncode}:\n{diagnostic[-12000:]}')
    return result.stdout


def collect(adb: str, serial: str, run_id: str, timeout: int, encoder: str | None = None) -> dict[str, Any]:
    # ADB may serialize remote shell arguments itself; do not rely only on shell=False.
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:\[\]-]*', serial):
        raise ValueError('Specify one safe adb device serial (including an optional TCP port).')
    try:
        if str(uuid.UUID(run_id)) != run_id:
            raise ValueError('Run ID must be a canonical UUID.')
    except (ValueError, AttributeError) as error:
        raise ValueError('Run ID must be a canonical UUID.') from error
    if timeout <= 0:
        raise ValueError('Timeout must be positive.')
    if encoder is not None and encoder not in ENCODERS:
        raise ValueError('Unsupported encoder test.')
    prefix = [adb, '-s', serial]
    if encoder is not None:
        output = _run(prefix + ['shell', 'am', 'instrument', '-w', '-r',
                               '-e', 'class', RUNTIME_CLASS, '-e', 'formaNative', 'true',
                               '-e', 'formaEncoder', encoder, '-e', 'formaRunId', run_id, RUNNER], timeout)
        validate_instrumentation(output.decode('utf-8', errors='replace'))
        raw = _run(prefix + ['exec-out', 'run-as', PACKAGE, 'cat', 'files/acceleration/runtime.json'], timeout)
        return validate_runtime_report(raw.decode('utf-8'), run_id, encoder)
    output = _run(prefix + ['shell', 'am', 'instrument', '-w', '-r',
                           '-e', 'class', TEST_CLASS,
                           '-e', 'formaAccelerationInventory', 'true',
                           '-e', 'formaRunId', run_id, RUNNER], timeout)
    validate_instrumentation(output.decode('utf-8', errors='replace'))
    raw = _run(prefix + ['exec-out', 'run-as', PACKAGE, 'cat',
                         'files/acceleration/inventory.json'], timeout)
    return validate_report(raw.decode('utf-8'), run_id)


def write_report(path: Path, data: dict[str, Any]) -> None:
    """Exclusive creation protects earlier evidence, including against an existence race."""
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(data, stream, indent=2, ensure_ascii=False, allow_nan=False)
        stream.write('\n')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Exact serial from adb devices; no implicit device selection.')
    parser.add_argument('--output', type=Path, required=True, help='New local JSON file. Existing files are never replaced.')
    parser.add_argument('--encoder', choices=ENCODERS, help='Run a real native export rather than inventory-only.')
    parser.add_argument('--adb', default='adb', help='Path to the adb executable.')
    parser.add_argument('--timeout', type=int, default=120, help='Per-command timeout in seconds.')
    arguments = parser.parse_args()
    if arguments.timeout <= 0:
        parser.error('--timeout must be positive.')
    if arguments.output.exists():
        parser.error('--output already exists; choose a new evidence filename.')
    run_id = str(uuid.uuid4())
    try:
        data = collect(arguments.adb, arguments.serial, run_id, arguments.timeout, arguments.encoder)
        write_report(arguments.output, data)
    except subprocess.TimeoutExpired:
        print('ADB timed out. This does not prove the Android test stopped. No valid report was collected.', file=sys.stderr)
        return 1
    except (OSError, ValueError, RuntimeError) as error:
        print(f'Inventory failed: {error}', file=sys.stderr)
        return 1
    scope = 'Real short native export; not full device qualification.' if arguments.encoder else 'Advertised inventory only, not an encode benchmark.'
    print(f'Wrote {arguments.output}; run {run_id}. {scope}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
