import unittest
from unittest.mock import patch, MagicMock
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import forma_device as cli


class HostContractTests(unittest.TestCase):
    def report(self, command="capabilities"):
        return {"schema": 1, "runId": "a" * 32, "command": command, "status": "PASS",
                "apkSha256": "b" * 64, "capabilities": {"available": False}, "results": []}

    def test_single_authorized_device(self):
        self.assertEqual("abc", cli.select_device("List of devices attached\nabc\tdevice product:x\n", None))

    def test_ambiguous_devices_fail(self):
        with self.assertRaises(ValueError):
            cli.select_device("a device\nb device\n", None)

    def test_explicit_device_selection(self):
        self.assertEqual("b", cli.select_device("a device\nb device\n", "b"))

    def test_offline_and_unauthorized_are_not_selected(self):
        for status in ["unauthorized", "offline"]:
            with self.assertRaises(ValueError):
                cli.select_device(f"abc {status}\n", "abc")

    def test_success_requires_one_executed_test(self):
        cli.verify_instrumentation("Time: 0.4\n\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        for log in ["", "OK (0 tests)", "FAILURES!!!\nOK (1 test)", "INSTRUMENTATION_FAILED: process crashed"]:
            with self.assertRaises(ValueError):
                cli.verify_instrumentation(log)

    def test_capability_inspection_does_not_claim_native_export(self):
        cli.validate_report(self.report(), "a" * 32, "capabilities")

    def test_requested_native_without_native_cannot_pass(self):
        with self.assertRaises(ValueError):
            cli.validate_report(self.report("smoke"), "a" * 32, "smoke")

    def test_stale_and_wrong_command_reports_fail(self):
        for key, value in [("runId", "c" * 32), ("command", "smoke"), ("status", "FAIL"), ("schema", 2)]:
            report = self.report(); report[key] = value
            with self.assertRaises(ValueError):
                cli.validate_report(report, "a" * 32, "capabilities")

    def test_no_arbitrary_device_or_artifact_path(self):
        for name in ["../input.mp4", "input.mp4;id", "/tmp/input.mp4", "a\\b.mp4"]:
            with self.assertRaises(ValueError): cli.safe_artifact(name)
        self.assertEqual("crop-color.mp4", cli.safe_artifact("crop-color.mp4"))

    def test_incomplete_export_report_cannot_pass(self):
        report = self.report("export"); report["capabilities"]["available"] = True
        with self.assertRaises(ValueError): cli.validate_report(report, "a" * 32, "export")
        report["results"] = [{"name": "custom", "bytes": 20, "decoded": False, "sourceSha256": "f" * 64, "outputFile": "custom.mp4"}]
        with self.assertRaises(ValueError): cli.validate_report(report, "a" * 32, "export")
        report["results"][0]["decoded"] = True
        cli.validate_report(report, "a" * 32, "export")

    def test_wrong_smoke_case_inventory_fails(self):
        report = self.report("smoke"); report["capabilities"]["available"] = True
        report["results"] = [{"name": "custom", "bytes": 20, "decoded": True, "sourceSha256": "f" * 64, "outputFile": "custom.mp4"}]
        with self.assertRaises(ValueError): cli.validate_report(report, "a" * 32, "smoke")

    def test_cleanup_timeout_is_not_recorded_as_pass(self):
        Path("testing/results/contract").mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir="testing/results/contract") as temporary:
            report = self.report()
            device = MagicMock()
            def shell(args, **kwargs):
                if args[0] == "pm":
                    return f"instrumentation:{cli.RUNNER} (target={cli.PACKAGE})".encode()
                if "rm" in args:
                    raise subprocess.TimeoutExpired("adb", 30)
                if args[0] == "am":
                    return b"OK (1 test)\n"
                return b""
            device.shell.side_effect = shell
            device.read.return_value = json.dumps(report).encode()
            with patch.object(cli.uuid, "uuid4") as identifier, patch.object(cli, "source_identity", return_value={}), \
                    patch.object(cli.subprocess, "check_output", return_value=b"abc device\n"), \
                    patch.object(cli, "Device", return_value=device), contextlib.redirect_stderr(io.StringIO()):
                identifier.return_value.hex = "a" * 32
                self.assertEqual(2, cli.main(["capabilities", "--output", temporary]))
            saved = json.loads((Path(temporary) / ("a" * 32) / "host.json").read_text())
            self.assertEqual("FAIL", saved["status"])

    def test_movie_smoke_requires_complete_native_inventory(self):
        report=self.report("movie-smoke"); report["capabilities"]["available"]=True
        with self.assertRaises(ValueError): cli.validate_report(report,"a"*32,"movie-smoke")
        report["results"]=[{"name": name,"decoded": True,"bytes": 10,"sourceSha256": "f"*64,"outputFile":name+".mp4"} for name in cli.MOVIE_CASES]
        cli.validate_report(report,"a"*32,"movie-smoke")

    def test_remote_command_quoting(self):
        self.assertEqual("run-as dev.forma.transcode.lab sh -c 'cat > files/forma-tests/abc/input.media'",
            cli.remote_command(["run-as", cli.PACKAGE, "sh", "-c", "cat > files/forma-tests/abc/input.media"]))


if __name__ == "__main__": unittest.main()
