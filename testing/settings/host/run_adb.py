#!/usr/bin/env python3
"""Run the isolated settings instrumentation cases; stale/failed reports never pass."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import subprocess
import sys
import uuid

CASES = ("catalog", "overrides", "battery_low", "charging_exception", "thermal_wait", "storage_roundtrip")
COMPONENT = "dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.forma.app.settings.SettingsScenarioTest"


def default_output(run_id: str) -> Path:
    return Path(__file__).resolve().parents[2] / "results" / "settings" / run_id


def require_instrumentation_success(code: int, output: str) -> None:
    match = re.search(r"OK \((\d+) tests?\)", output)
    if code or not match or int(match[1]) < 1 or any(marker in output for marker in
            ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "INSTRUMENTATION_ABORTED")):
        raise ValueError("Instrumentation failed or ran no tests; see the captured log")


def validate_report(report: dict, run_id: str, case: str) -> None:
    """Require a current, nonempty, explicitly synthetic-policy success report."""
    if not isinstance(report, dict):
        raise ValueError("The test did not return a JSON object")
    if report.get("schema") != 1 or report.get("runId") != run_id or report.get("case") != case:
        raise ValueError("Unsupported, stale, or mismatched test report")
    if report.get("result") != "PASS":
        raise ValueError(f"Instrumentation did not pass: {report.get('reason', 'no reason supplied')}")
    checks = report.get("checks")
    if not isinstance(checks, list) or not checks or not all(isinstance(item, str) and item for item in checks):
        raise ValueError("The report contains no discovered checks")
    if report.get("nativeExecution") is not False or report.get("powerInputs") != "synthetic":
        raise ValueError("This runner proves policy behavior, not native/physical power execution")


def revision_argument(value: str) -> str:
    # ADB joins shell arguments remotely; keep user metadata to a strict safe token.
    if value == "unrecorded" or re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", value):
        return value
    raise argparse.ArgumentTypeError("Use an exact lowercase Git commit ID or 'unrecorded'")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", choices=CASES)
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--adb", default="adb", help="ADB executable")
    parser.add_argument("--app-revision", type=revision_argument, default="unrecorded", help="Exact revision used to build the installed APK")
    parser.add_argument("--output", type=Path, help="Evidence directory; defaults to testing/results/settings/<run-id>")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not 5 <= args.timeout <= 900:
        parser.error("--timeout must be 5–900 seconds")
    run_id = str(uuid.uuid4())
    output = args.output or default_output(run_id)
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    try:
        output.mkdir(parents=True, exist_ok=True)
        command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                         "-e", "formaSettingsCase", args.case, "-e", "formaSettingsRunId", run_id,
                         "-e", "formaAppRevision", args.app_revision, COMPONENT]
        result = subprocess.run(command, capture_output=True, text=True, timeout=args.timeout, check=False)
        (output / f"{run_id}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
        require_instrumentation_success(result.returncode, result.stdout + result.stderr)
        captured = subprocess.run(adb + ["exec-out", "run-as", "dev.forma.transcode", "cat",
                                        "files/settings-tests/last-result.json"],
                                  capture_output=True, text=True, timeout=30, check=True)
        report = json.loads(captured.stdout)
        validate_report(report, run_id, args.case)
        destination = output / f"{run_id}.json"
        destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"PASS {args.case}: {len(report['checks'])} policy checks. Report: {destination}")
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"FAIL {args.case}: {error}. Evidence directory: {output}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
