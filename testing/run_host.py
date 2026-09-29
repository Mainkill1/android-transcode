#!/usr/bin/env python3
"""Run isolated host checks; --exports additionally exercises real desktop FFmpeg, not Android."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]


def run(argv: list[str], log: Path, timeout: int = 180) -> None:
    with log.open("wb") as stream:
        result = subprocess.run(argv, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                check=False, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {log}\n{log.read_text(errors='replace')[-6000:]}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def probe(path: Path) -> dict:
    return json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_streams", "-show_format",
                                             "-of", "json", str(path)], timeout=30))


def exports(jar: Path, directory: Path) -> list[dict]:
    results = []
    for delayed in (False, True):
        work = directory / ("delayed" if delayed else "normal")
        work.mkdir()
        source = work / "input.media"
        fixture = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-n",
                   "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=6"]
        if delayed:
            fixture += ["-itsoffset", "0.4"]
        fixture += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6",
                    "-t", "6", "-c:v", "libx264", "-threads:v", "2", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-f", "mp4", str(source)]
        run(fixture, work / "fixture.log")
        before = sha256(source)
        source_audio = next(s for s in probe(source)["streams"] if s["codec_type"] == "audio")
        offset = float(source_audio.get("start_time", 0))
        run(["java", "-cp", str(jar), "dev.forma.testing.ExportCasesKt", str(source), str(work)], work / "plan.log")
        for line in (work / "cases.tsv").read_text().splitlines():
            name, filename, milliseconds, width, height = line.split("\t")
            if delayed and not name.startswith("offset-"):
                continue
            output = work / filename
            tokens = (work / f"{name}.argv").read_text().split("\0")
            # Host resource limits only; every media/edit argument comes from production Planner.
            tokens[-1:-1] = ["-threads:v", "2", "-filter_threads", "2"]
            run(["ffmpeg", *tokens], work / f"{name}.encode.log")
            facts = probe(output)
            streams = facts["streams"]
            videos = [s for s in streams if s["codec_type"] == "video"]
            audios = [s for s in streams if s["codec_type"] == "audio"]
            expected_video = 0 if int(width) == 0 else 1
            if len(videos) != expected_video or len(audios) != 1:
                raise AssertionError(f"{name}: wrong track layout")
            duration = float(facts["format"]["duration"])
            if abs(duration * 1000 - int(milliseconds)) > 300:
                raise AssertionError(f"{name}: wrong edited duration {duration}")
            if videos and (videos[0]["width"], videos[0]["height"]) != (int(width), int(height)):
                raise AssertionError(f"{name}: wrong transformed dimensions")
            actual_offset = float(audios[0].get("start_time", 0))
            if delayed:
                expected_offset = offset / (2 if name == "offset-speed" else 1)
                if abs(actual_offset - expected_offset) > 0.04:
                    raise AssertionError(f"{name}: A/V offset {actual_offset}, expected {expected_offset}")
            run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-i", str(output),
                 "-map", "0:v?", "-map", "0:a?", "-f", "null", "-"], work / f"{name}.decode.log")
            if output.stat().st_size <= 0 or sha256(source) != before:
                raise AssertionError(f"{name}: empty output or source modified")
            result = {"fixture": work.name, "case": name, "status": "PASS", "durationMs": duration * 1000,
                      "audioStartSeconds": actual_offset, "bytes": output.stat().st_size,
                      "sourceSha256": before, "outputSha256": sha256(output), "decoded": True}
            results.append(result)
            print(f"PASS desktop/{work.name}/{name}")
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exports", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "testing/results/host")
    args = parser.parse_args()
    required = ["kotlinc", "java"] + (["ffmpeg", "ffprobe"] if args.exports else [])
    missing = [tool for tool in required if shutil.which(tool) is None]
    if missing:
        parser.error("Missing tools: " + ", ".join(missing))
    directory = (args.output / uuid.uuid4().hex).resolve()
    directory.mkdir(parents=True)
    report = {"kind": "host-only", "status": "FAIL", "androidExecuted": False}
    try:
        unit = ROOT / "testing/core/unit/dev/forma/core"
        sources = sorted((ROOT / "core/src/main/kotlin/dev/forma/core").glob("*.kt"))
        sources += [unit / f"{name}.kt" for name in ("CoreChecks", "EditorChecks", "TimelineChecks", "SequenceChecks", "ProjectChecks")]
        sources += [ROOT / "testing/host/EditorMain.kt", ROOT / "testing/host/ExportCases.kt",
                    ROOT / "testing/shared/dev/forma/testing/EditorCases.kt"]
        sources += [ROOT / "testing/host/MovieMain.kt"]
        sources += [ROOT / "engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg" / (name + ".kt") for name in
                    ("FfmpegBridge", "ManagedFfmpegBridge", "FfmpegRenderSession", "MovieOutputValidation", "FfmpegListing")]
        kotlin_home = Path(shutil.which("kotlinc")).resolve().parents[1]
        coroutines = kotlin_home / "lib/kotlinx-coroutines-core-jvm.jar"
        if not coroutines.is_file():
            raise RuntimeError("Kotlin CLI needs its bundled kotlinx-coroutines-core-jvm.jar for production session checks")
        jar = directory / "checks.jar"
        run(["kotlinc", *map(str, sources), "-cp", str(coroutines), "-jvm-target", "17", "-include-runtime", "-d", str(jar)], directory / "compile.log")
        run(["java", "-cp", str(jar), "dev.forma.core.CoreChecksKt"], directory / "core.log")
        run(["java", "-cp", str(jar), "EditorMainKt"], directory / "editor.log")
        run([sys.executable, "-m", "unittest", "discover", "-s", "testing/adb", "-p", "test_*.py", "-v"], directory / "adb-contract.log")
        print((directory / "core.log").read_text().splitlines()[-1])
        print((directory / "editor.log").read_text().splitlines()[-1])
        report["desktopExports"] = exports(jar, directory) if args.exports else []
        if args.exports:
            movies = directory / "movies"
            run(["java", "-cp", str(jar) + ":" + str(coroutines), "dev.forma.testing.MovieMainKt", str(movies)], directory / "movies.log")
            report["movieExports"] = []
            for line in (movies / "movies.tsv").read_text().splitlines():
                name, artifact, duration, frames, size, attempts, first_hash, second_hash = line.split("\t")
                measured = {"case": name, "output": artifact, "durationMs": int(duration),
                    "decodedFrames": int(frames), "bytes": int(size), "attempts": int(attempts),
                    "sourceSha256": [first_hash, second_hash], "status": "PASS"}
                if name in ("movie-cut", "movie-speed", "movie-audio"):
                    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(movies / artifact),
                        "-map", "0:a:0", "-ar", "48000", "-ac", "1", "-f", "f32le", "pipe:1"], timeout=30)
                    samples = [sample[0] for sample in struct.iter_unpack("<f", raw)]
                    def rms(start, end):
                        window = samples[int(start * 48000):int(end * 48000)]
                        return math.sqrt(sum(value * value for value in window) / len(window))
                    leading = rms(0, .1 if name == "movie-speed" else .2)
                    tone = rms(.4 if name == "movie-speed" else .5, .8)
                    silent = rms(1.2 if name == "movie-speed" else 2.2, 2.8 if name == "movie-speed" else 3.8)
                    if leading > 1e-4 or tone < .01 or silent > 1e-4:
                        raise AssertionError(f"{name}: leading A/V offset or silent segment changed: {leading}, {tone}, {silent}")
                    measured["independentRms"] = {"leading": leading, "tone": tone, "silentSegment": silent}
                if name in ("movie-cut", "movie-crossfade"):
                    position = "2.5" if name == "movie-cut" else "1.75"
                    pixel = subprocess.check_output(["ffmpeg", "-v", "error", "-ss", position, "-i", str(movies / artifact),
                        "-frames:v", "1", "-vf", "scale=1:1", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"], timeout=30)
                    if len(pixel) != 3 or (name == "movie-cut" and pixel[2] <= pixel[0]) or (name == "movie-crossfade" and min(pixel[0],pixel[2]) < 20):
                        raise AssertionError(f"{name}: ordered pictures or dissolve failed: {list(pixel)}")
                    measured["independentRgb"] = list(pixel)
                if name == "movie-video-delay":
                    colors=[]
                    for position in ("0.1", "0.5"):
                        pixel = subprocess.check_output(["ffmpeg", "-v", "error", "-ss", position, "-i", str(movies / artifact),
                            "-frames:v", "1", "-vf", "scale=1:1", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"], timeout=30)
                        colors.append(list(pixel))
                    if max(colors[0]) > 3 or colors[1][0] < 150:
                        raise AssertionError(f"Delayed video advanced into its leading gap: {colors}")
                    measured["leadingGapRgb"] = colors
                report["movieExports"].append(measured)
            print((directory / "movies.log").read_text().strip())
        report["status"] = "PASS"
        print(f"PASS host checks. Android build/device execution NOT performed. Reports: {directory}")
        return 0
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, StopIteration, RuntimeError, AssertionError) as error:
        report["error"] = str(error)
        print(f"FAIL: {error}\nReports: {directory}", file=sys.stderr)
        return 1
    finally:
        (directory / "host.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
