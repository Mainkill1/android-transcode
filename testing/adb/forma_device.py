#!/usr/bin/env python3
"""Drive Forma's separate lab instrumentation APK. Python 3.10+, adb; no root/server."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
from typing import Any, BinaryIO, Sequence
import uuid

PACKAGE = "dev.forma.transcode.lab"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.forma.app.EditorCommandTest"
MOVIE_CASES = {"movie-cut", "movie-crossfade", "movie-speed", "movie-audio", "movie-budget", "movie-preview", "movie-video-delay"}
SMOKE_CASES = {"neutral", "speed", "crop-color", "fades", "audio-only"}


def remote_command(args: Sequence[str]) -> str:
    # adb shell still uses a remote shell even though the host does not use shell=True.
    return shlex.join(args)


def select_device(text: str, requested: str | None) -> str:
    rows = [line.split() for line in text.splitlines()]
    devices = {row[0]: row[1] for row in rows if len(row) >= 2 and row[1] in {"device", "offline", "unauthorized"}}
    if requested:
        if devices.get(requested) != "device":
            raise ValueError("Requested device is missing, offline or unauthorized. Unlock it and accept USB debugging.")
        return requested
    ready = [name for name, state in devices.items() if state == "device"]
    if len(ready) != 1:
        raise ValueError("Expected one authorized device. Connect/unlock a device or supply --serial explicitly.")
    return ready[0]


def verify_instrumentation(log: str) -> None:
    if (not re.search(r"(?m)^OK\s*\(1 test\)\s*$", log)
            or any(word in log for word in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))):
        raise ValueError("Instrumentation did not report exactly one successful command test. See instrumentation.txt.")


def safe_artifact(name: str) -> str:
    if not isinstance(name, str) or not re.fullmatch(r"[a-z0-9-]+\.(mp4|m4a|mkv|webm)", name):
        raise ValueError("Invalid test artifact name.")
    return name


def validate_report(report: dict[str, Any], run_id: str, command: str) -> None:
    if not isinstance(report, dict) or (report.get("schema"), report.get("runId"), report.get("command")) != (1, run_id, command):
        raise ValueError("Missing, stale or incompatible device report.")
    if report.get("status") != "PASS":
        raise ValueError("Device test failed: " + str(report.get("error", "No error details")))
    if not re.fullmatch(r"[a-f0-9]{64}", str(report.get("apkSha256", ""))):
        raise ValueError("Device report lacks the actual APK identity.")
    if command != "capabilities":
        if report.get("capabilities", {}).get("available") is not True:
            raise ValueError("Requested native test has no native engine.")
        results = report.get("results", [])
        names = [item.get("name") for item in results]
        expected = MOVIE_CASES if command == "movie-smoke" else SMOKE_CASES if command == "smoke" else {"custom"}
        if len(names) != len(expected) or set(names) != expected:
            raise ValueError("The report did not execute the requested case inventory.")
        for item in results:
            if item.get("decoded") is not True or not isinstance(item.get("bytes"), int) or item["bytes"] <= 0:
                raise ValueError("An export is empty or lacks successful decode evidence.")
            if not re.fullmatch(r"[a-f0-9]{64}", str(item.get("sourceSha256", ""))):
                raise ValueError("An export lacks its source identity.")
            safe_artifact(item.get("outputFile", ""))


class Device:
    def __init__(self, adb: str, serial: str):
        self.prefix = [adb, "-s", serial]

    def call(self, *args: str, timeout: int = 30, stdin: BinaryIO | None = None) -> bytes:
        result = subprocess.run([*self.prefix, *args], stdin=stdin, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace") or result.stdout.decode("utf-8", "replace"))
        return result.stdout

    def shell(self, args: Sequence[str], **kwargs: Any) -> bytes:
        return self.call("shell", "-T", remote_command(args), **kwargs)

    def read(self, relative_path: str) -> bytes:
        return self.call("exec-out", "run-as", PACKAGE, "cat", relative_path)

    def write(self, relative_path: str, source: Path, timeout: int) -> None:
        with source.open("rb") as stream:
            self.shell(["run-as", PACKAGE, "sh", "-c", "cat > " + shlex.quote(relative_path)], stdin=stream, timeout=timeout)


def source_identity() -> dict[str, Any]:
    """A checkout claim, not proof that an installed APK was built from this checkout."""
    root = Path(__file__).resolve().parents[2]
    try:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, stderr=subprocess.DEVNULL, timeout=10).decode().strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, timeout=10).strip())
        return {"checkoutRevision": revision, "checkoutDirty": dirty, "installedSourceRevisionVerified": False}
    except (OSError, subprocess.SubprocessError):
        return {"checkoutRevision": "unknown", "installedSourceRevisionVerified": False}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["capabilities", "smoke", "export", "movie-smoke"])
    parser.add_argument("--serial")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--recipe", type=Path)
    parser.add_argument("--output", type=Path, default=Path("testing/results"))
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--pull-media", action="store_true")
    parser.add_argument("--keep-device-files", action="store_true")
    args = parser.parse_args(argv)
    if not 30 <= args.timeout <= 3600:
        parser.error("--timeout must be 30–3600 seconds")
    if args.command == "export":
        if args.input is None or args.recipe is None or not args.input.is_file() or not args.recipe.is_file():
            parser.error("export needs existing --input and --recipe files")
        if args.input.stat().st_size <= 0 or not 0 < args.recipe.stat().st_size <= 65_536:
            parser.error("Input must be nonempty and recipe must be 1–65536 bytes")
        try:
            if not isinstance(json.loads(args.recipe.read_text(encoding="utf-8")), dict):
                raise ValueError("Recipe must be an object")
        except (ValueError, OSError) as error:
            parser.error(str(error))
    elif args.input is not None or args.recipe is not None:
        parser.error("--input and --recipe are only used by export")

    run_id = uuid.uuid4().hex
    output = args.output / run_id
    output.mkdir(parents=True, exist_ok=False)
    relative = "files/forma-tests/" + run_id
    host = {"runId": run_id, "command": args.command, "status": "FAIL", **source_identity()}
    try:
        listing = subprocess.check_output([args.adb, "devices", "-l"], timeout=30).decode("utf-8", "replace")
        serial = select_device(listing, args.serial)
        host["serial"] = serial
        device = Device(args.adb, serial)
        instruments = device.shell(["pm", "list", "instrumentation"]).decode("utf-8", "replace")
        if f"instrumentation:{RUNNER} (target={PACKAGE})" not in instruments:
            raise ValueError("Install the lab app and its matching test APK first; see testing/README.md.")
        device.shell(["run-as", PACKAGE, "mkdir", "-p", relative])
        if args.command == "export":
            device.write(relative + "/input.media", args.input, args.timeout)
            device.write(relative + "/recipe.json", args.recipe, 30)
        log = device.shell(["am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                            "-e", "formaCommand", args.command, "-e", "formaRunId", run_id,
                            "-e", "formaTimeoutMs", str((args.timeout - 10) * 1000), RUNNER], timeout=args.timeout)
        (output / "instrumentation.txt").write_bytes(log)
        # Retrieve even a FAIL report before interpreting instrumentation output.
        raw = device.read(relative + "/result.json")
        (output / "device.json").write_bytes(raw)
        report = json.loads(raw)
        verify_instrumentation(log.decode("utf-8", "replace"))
        validate_report(report, run_id, args.command)
        if args.pull_media:
            for item in report.get("results", []):
                name = safe_artifact(item["outputFile"])
                # Stream media to disk rather than loading a potentially large export into host RAM.
                with (output / name).open("wb") as target:
                    subprocess.run([*device.prefix, "exec-out", "run-as", PACKAGE, "cat", relative + "/" + name],
                                   stdout=target, check=True, timeout=args.timeout)
        if not args.keep_device_files:
            device.shell(["run-as", PACKAGE, "rm", "-r", relative])
        host["status"] = "PASS"
        print(f"PASS {args.command}: {output}")
        return 0
    except subprocess.TimeoutExpired as error:
        host["status"] = "FAIL"
        host["error"] = "ADB timeout. Device work may still be cleaning up; do not delete its input or start another run yet."
        if error.output:
            (output / "instrumentation.txt").write_bytes(error.output if isinstance(error.output, bytes) else error.output.encode())
        print(host["error"], file=sys.stderr)
        return 2
    except (OSError, subprocess.SubprocessError, ValueError, RuntimeError, KeyError, TypeError, AttributeError) as error:
        host["status"] = "FAIL"
        host["error"] = str(error)
        print(f"FAIL: {error}\nReports: {output}\nDevice files retained: {relative}", file=sys.stderr)
        return 1
    finally:
        (output / "host.json").write_text(json.dumps(host, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
