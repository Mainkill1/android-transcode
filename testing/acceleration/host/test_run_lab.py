import copy
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from run_lab import instrumentation_passed, validate_report, report_command, main


class ReportChecks(unittest.TestCase):
    def setUp(self):
        self.expected = dict(runId="a" * 32, appCommit="b" * 40, mode="NDK", baseline="JAVA",
                             format="H264", width=1280, height=720, fps=30, seconds=3,
                             videoKbps=4000, operatingRate=0,
                             targetPackage="dev.forma.transcode.lab")
        self.report = dict(self.expected, schemaVersion=1, status="passed", deviceQualified=False,
                           nativeBuild="FFmpeg test build", appApkSha256="c" * 64,
                           fixtureSha256="d" * 64, samples=[
                               dict(route=route, index=i, passed=True, frames=90, bytes=12345,
                                    durationMs=3000, encodeAndMuxMs=100.0, outputSha256="e" * 64,
                                    encoderComponent="vendor.encoder")
                               for i, route in enumerate(["JAVA", "NDK", "NDK", "JAVA"])])

    def test_complete_matching_result(self):
        validate_report(self.report, self.expected)

    def test_rejects_stale_run(self):
        self.report["runId"] = "0" * 32
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_other_commit(self):
        self.report["appCommit"] = "0" * 40
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_report_from_other_package(self):
        self.report["targetPackage"] = "dev.forma.transcode"
        with self.assertRaisesRegex(ValueError, "targetPackage"):
            validate_report(self.report, self.expected)

    def test_rejects_missing_samples(self):
        self.report["samples"] = []
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_failed_or_skipped_sample(self):
        self.report["samples"][1]["passed"] = False
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_frame_loss(self):
        self.report["samples"][1]["frames"] = 89
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_non_finite_measurement(self):
        self.report["samples"][1]["encodeAndMuxMs"] = float("nan")
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_wrong_route_or_reordered_results(self):
        self.report["samples"][1]["route"] = "JAVA"
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_missing_native_identity(self):
        self.report["nativeBuild"] = "Not loaded"
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_failed_status(self):
        self.report["status"] = "failed"
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_rejects_unobserved_hardware_decoder(self):
        expected = dict(self.expected, mode="DECODE_BUFFER")
        report = copy.deepcopy(self.report)
        report["mode"] = "DECODE_BUFFER"
        for i in (1, 2): report["samples"][i]["route"] = "DECODE_BUFFER"
        with self.assertRaises(ValueError): validate_report(report, expected)
        for i in (1, 2): report["samples"][i]["decoderComponent"] = "vendor.decoder"
        validate_report(report, expected)

    def test_rejects_invented_qualification(self):
        self.report["deviceQualified"] = True
        with self.assertRaises(ValueError): validate_report(self.report, self.expected)

    def test_instrumentation_must_run_exactly_one_real_test(self):
        self.assertTrue(instrumentation_passed("OK (1 test)\nINSTRUMENTATION_CODE: -1", 0))
        for output in ["OK (0 tests)", "OK (1 test)\nFAILURES!!!", "INSTRUMENTATION_FAILED: class missing", ""]:
            self.assertFalse(instrumentation_passed(output, 0))
        self.assertFalse(instrumentation_passed("OK (1 test)\nINSTRUMENTATION_CODE: -1", 1))

    def test_report_path_cannot_be_shell_input(self):
        command = report_command("adb", "device", "dev.forma.transcode.lab", "a" * 32)
        self.assertIn("run-as", command)
        self.assertEqual(command[5], "dev.forma.transcode.lab")
        self.assertEqual(command[-1], "files/acceleration-lab/" + "a" * 32 + "/report.json")
        with self.assertRaises(ValueError): report_command("adb", "device", "dev.forma.transcode.lab", "../report; touch /bad")
        with self.assertRaises(ValueError): report_command("adb", "device", "invalid.package", "a" * 32)

    def run_with_installed_packages(self, installed, package=None, report_package=None):
        commands = []
        with tempfile.TemporaryDirectory() as root:
            args = ["run_lab.py", "--serial", "selected-phone", "--app-commit", "b" * 40,
                    "--output-dir", root]
            if package:
                args += ["--package", package]

            def adb(command, **_kwargs):
                commands.append(command)
                if command[3:6] == ["shell", "am", "instrument"]:
                    target = command[-1].split(".test/")[0]
                    output = "OK (1 test)\nINSTRUMENTATION_CODE: -1" if target in installed else "INSTRUMENTATION_FAILED: package missing"
                    return subprocess.CompletedProcess(command, 0, output, "")
                target = command[5]
                if target not in installed:
                    return subprocess.CompletedProcess(command, 1, "", "run-as: package missing")
                report = copy.deepcopy(self.report)
                report["targetPackage"] = report_package or target
                return subprocess.CompletedProcess(command, 0, json.dumps(report), "")

            with patch.object(sys, "argv", args), patch("run_lab.subprocess.run", side_effect=adb), \
                    patch("run_lab.uuid.uuid4", return_value=SimpleNamespace(hex="a" * 32)), \
                    redirect_stdout(StringIO()), redirect_stderr(StringIO()):
                result = main()
            evidence = Path(root) / ("a" * 32)
            saved = json.loads((evidence / "request.json").read_text())
            failure = evidence / "host-error.txt"
            error = failure.read_text() if failure.exists() else ""
        return result, commands, saved, error

    def test_lab_only_install_runs_and_reads_lab_package(self):
        result, commands, request, _ = self.run_with_installed_packages({"dev.forma.transcode.lab"})
        self.assertEqual(result, 0)
        self.assertEqual(request["targetPackage"], "dev.forma.transcode.lab")
        self.assertEqual(commands[0][-1], "dev.forma.transcode.lab.test/androidx.test.runner.AndroidJUnitRunner")
        self.assertEqual(commands[1][5], "dev.forma.transcode.lab")

    def test_both_installed_still_selects_lab_by_default(self):
        result, commands, request, _ = self.run_with_installed_packages(
            {"dev.forma.transcode", "dev.forma.transcode.lab"})
        self.assertEqual(result, 0)
        self.assertEqual(request["targetPackage"], "dev.forma.transcode.lab")
        self.assertEqual(commands[0][-1], "dev.forma.transcode.lab.test/androidx.test.runner.AndroidJUnitRunner")
        self.assertEqual(commands[1][5], "dev.forma.transcode.lab")

    def test_selected_package_must_match_instrumentation_and_report(self):
        result, commands, request, _ = self.run_with_installed_packages(
            {"dev.forma.transcode", "dev.forma.transcode.lab"}, package="dev.forma.transcode")
        self.assertEqual(result, 0)
        self.assertEqual(request["targetPackage"], "dev.forma.transcode")
        self.assertEqual(commands[0][-1], "dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner")
        self.assertEqual(commands[1][5], "dev.forma.transcode")

    def test_other_package_report_fails_even_when_both_installed(self):
        result, _, _, error = self.run_with_installed_packages(
            {"dev.forma.transcode", "dev.forma.transcode.lab"}, report_package="dev.forma.transcode")
        self.assertEqual(result, 1)
        self.assertIn("targetPackage", error)

    def test_uninstalled_selected_package_fails_without_fallback(self):
        result, commands, _, error = self.run_with_installed_packages(
            {"dev.forma.transcode.lab"}, package="dev.forma.transcode")
        self.assertEqual(result, 1)
        self.assertEqual(commands[0][-1], "dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner")
        self.assertEqual(commands[1][5], "dev.forma.transcode")
        self.assertIn("Instrumentation did not run", error)


if __name__ == "__main__": unittest.main()
