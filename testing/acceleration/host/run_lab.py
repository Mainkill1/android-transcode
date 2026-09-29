#!/usr/bin/env python3
"""Run the opt-in test APK over ADB and reject skipped, stale or incomplete evidence."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import sys
from typing import Any
import uuid

PACKAGE = "dev.forma.transcode"
MODES = ("JAVA", "NDK", "NDK_ASYNC", "DECODE_BUFFER", "SURFACE")


def instrumentation_passed(output: str, returncode: int) -> bool:
    return (returncode == 0 and re.search(r"(?m)^OK \(1 test\)\s*$", output) is not None
            and re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", output) is not None
            and not any(s in output for s in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")))


def report_command(adb: str, serial: str, run_id: str) -> list[str]:
    if not re.fullmatch(r"[a-f0-9]{32}", run_id):
        raise ValueError("A fresh hexadecimal run ID is required.")
    return [adb, "-s", serial, "exec-out", "run-as", PACKAGE, "cat",
            f"files/acceleration-lab/{run_id}/report.json"]


def validate_report(report: dict[str, Any], expected: dict[str, Any]) -> None:
    def demand(condition: bool, message: str) -> None:
        if not condition:
            raise ValueError(message)

    demand(isinstance(report, dict), "Report is not a JSON object.")
    for key, value in expected.items():
        demand(report.get(key) == value, f"Report identity/workload mismatch: {key}.")
    demand(report.get("schemaVersion") == 1 and report.get("status") == "passed", "The device run did not pass.")
    demand(report.get("deviceQualified") is False, "A smoke benchmark must not claim full device qualification.")
    native = report.get("nativeBuild", "")
    demand(isinstance(native, str) and bool(native.strip()) and native != "Not loaded", "Native build identity is missing.")
    for key in ("appApkSha256", "fixtureSha256"):
        demand(re.fullmatch(r"[a-f0-9]{64}", str(report.get(key, ""))) is not None, f"Missing {key}.")
    samples = report.get("samples")
    demand(isinstance(samples, list) and len(samples) == 4, "All four ABBA samples are required.")
    routes = [expected["baseline"], expected["mode"], expected["mode"], expected["baseline"]]
    for index, (row, route) in enumerate(zip(samples, routes)):
        demand(isinstance(row, dict), "Invalid sample object.")
        demand(row.get("index") == index and row.get("route") == route, "Sample order/route mismatch.")
        demand(row.get("passed") is True, f"Sample {index} did not pass.")
        demand(row.get("frames") == expected["fps"] * expected["seconds"], "Decoded frame count mismatch.")
        size, duration, elapsed = row.get("bytes"), row.get("durationMs"), row.get("encodeAndMuxMs")
        demand(isinstance(size, int) and not isinstance(size, bool) and size > 0, "Empty output.")
        demand(isinstance(duration, (int, float)) and math.isfinite(duration)
               and abs(duration - expected["seconds"] * 1000) <= 100, "Output duration mismatch.")
        demand(isinstance(elapsed, (int, float)) and not isinstance(elapsed, bool)
               and math.isfinite(elapsed) and elapsed > 0, "Invalid encode timing.")
        demand(re.fullmatch(r"[a-f0-9]{64}", str(row.get("outputSha256", ""))) is not None, "Output identity is missing.")
        if route != "SOFTWARE":
            demand(bool(row.get("encoderComponent")), "The bound hardware encoder was not recorded.")
        if route in ("DECODE_BUFFER", "SURFACE"):
            demand(bool(row.get("decoderComponent")), "The actual hardware decoder was not observed.")
        if route == "SURFACE":
            demand(row.get("surfaceObserved") is True, "No non-null decoder surface was observed.")
            demand(row.get("physicalZeroCopyVerified") is False, "Surface logs cannot prove driver-internal zero copy.")


def bounded_int(minimum: int, maximum: int):
    def parse(value: str) -> int:
        number = int(value)
        if not minimum <= number <= maximum:
            raise argparse.ArgumentTypeError(f"Expected {minimum}..{maximum}.")
        return number
    return parse


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact adb device serial; never select an arbitrary connected phone.")
    parser.add_argument("--app-commit", required=True, help="Declared source commit of the installed APK (also records its actual SHA-256).")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--mode", choices=MODES, default="NDK")
    parser.add_argument("--baseline", choices=("JAVA", "SOFTWARE"), default="JAVA")
    parser.add_argument("--format", choices=("H264", "HEVC", "VP8", "VP9", "AV1"), default="H264")
    parser.add_argument("--height", type=int, choices=(360, 720, 1080, 2160), default=720)
    parser.add_argument("--fps", type=int, choices=(24, 30, 60, 120), default=30)
    parser.add_argument("--seconds", type=bounded_int(2, 30), default=3)
    parser.add_argument("--video-kbps", type=bounded_int(100, 200000), default=4000)
    parser.add_argument("--operating-rate", type=bounded_int(0, 1000), default=0)
    parser.add_argument("--timeout", type=bounded_int(1, 3600), default=1500)
    parser.add_argument("--keep-media", action="store_true")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parents[1] / "results")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{40}", args.app_commit):
        parser.error("--app-commit must be a complete lowercase 40-character SHA.")
    if args.mode == "JAVA" and args.baseline == "JAVA":
        parser.error("JAVA needs --baseline SOFTWARE; otherwise the routes would be identical.")
    if args.format == "VP8" and args.baseline == "SOFTWARE":
        parser.error("VP8 currently requires the JAVA baseline.")
    if args.mode == "NDK_ASYNC" and args.format not in ("H264", "HEVC"):
        parser.error("NDK_ASYNC currently covers H264/HEVC extradata only.")
    run_id = uuid.uuid4().hex
    expected = dict(runId=run_id, appCommit=args.app_commit, mode=args.mode, baseline=args.baseline,
                    format=args.format, width={360: 640, 720: 1280, 1080: 1920, 2160: 3840}[args.height],
                    height=args.height, fps=args.fps, seconds=args.seconds,
                    videoKbps=args.video_kbps, operatingRate=args.operating_rate)
    directory = args.output_dir / run_id
    directory.mkdir(parents=True, exist_ok=False)
    command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
               "dev.forma.app.HardwareAccelerationLabTest#benchmark", "-e", "formaAccelerationLab", "true"]
    for key, value in expected.items():
        command += ["-e", key, str(value)]
    command += ["-e", "keepMedia", str(args.keep_media).lower(),
                PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
    # All remote values are fixed tokens, bounded numbers or hexadecimal identities. No shell=True.
    (directory / "request.json").write_text(json.dumps(expected, indent=2), encoding="utf-8")
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.timeout)
        transcript = result.stdout + "\n" + result.stderr
        (directory / "instrumentation.log").write_text(transcript, encoding="utf-8")
        fetched = subprocess.run(report_command(args.adb, args.serial, run_id), capture_output=True,
                                 text=True, encoding="utf-8", errors="replace", timeout=30)
        (directory / "report.json").write_text(fetched.stdout, encoding="utf-8")
        if not instrumentation_passed(transcript, result.returncode):
            raise ValueError("Instrumentation did not run exactly one passing test. Missing/disabled native test APKs are failures.")
        if fetched.returncode != 0:
            raise ValueError("Cannot retrieve the new run's report: " + fetched.stderr.strip())
        report = json.loads(fetched.stdout)
        validate_report(report, expected)
        for row in report["samples"]:
            print(f"{row['index']}: {row['route']:13} {row['encodeAndMuxMs']:.1f} ms, "
                  f"{row['frames']} decoded frames, {row['bytes']} bytes")
        print(f"Smoke comparison passed. Not full device/quality qualification. Evidence: {directory}")
        return 0
    except subprocess.TimeoutExpired as error:
        (directory / "host-error.txt").write_text(str(error), encoding="utf-8")
        print("FAILED: Host timeout. Device instrumentation may still be running; inspect it before another run. "
              "The runner does not force-stop another app session.", file=sys.stderr)
    except (OSError, ValueError, TypeError, KeyError) as error:
        (directory / "host-error.txt").write_text(str(error), encoding="utf-8")
        print(f"FAILED: {error}\nEvidence: {directory}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
