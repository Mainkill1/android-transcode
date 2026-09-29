import unittest
import run_adb
from run_adb import validate_report, revision_argument
import argparse

class ReportTests(unittest.TestCase):
    def test_instrumentation_requires_nonempty_success_and_rejects_failure(self):
        for code,output in [(0,''),(0,'OK (0 tests)'),(0,'OK (1 test)\nINSTRUMENTATION_ABORTED'),(0,'FAILURES!!!'),(1,'OK (1 test)')]:
            with self.subTest(output=output), self.assertRaises(ValueError):
                run_adb.require_instrumentation_success(code,output)
        run_adb.require_instrumentation_success(0,'OK (1 test)\nINSTRUMENTATION_CODE: -1')

    def test_default_evidence_stays_in_workspace(self):
        output=run_adb.default_output('run-id')
        self.assertEqual(run_adb.Path(__file__).resolve().parents[2]/'results'/'settings'/'run-id',output)
    def report(self):
        return {"schema": 1, "runId": "current", "case": "battery_low", "result": "PASS", "checks": ["boundary"], "nativeExecution": False, "powerInputs": "synthetic"}

    def test_revision_rejects_remote_shell_text(self):
        for text in ("main", "abc;id", "$(id)", "abc def", "-e other bad", "a" * 41):
            with self.subTest(text=text), self.assertRaises(argparse.ArgumentTypeError):
                revision_argument(text)
        self.assertEqual("a" * 40, revision_argument("a" * 40))
        self.assertEqual("unrecorded", revision_argument("unrecorded"))

    def test_valid_current_report(self):
        validate_report(self.report(), "current", "battery_low")

    def test_stale_report_is_not_success(self):
        with self.assertRaises(ValueError):
            validate_report(self.report(), "new", "battery_low")

    def test_failed_empty_or_wrong_case_is_not_success(self):
        for field, value in [("result", "FAIL"), ("checks", []), ("case", "catalog"), ("schema", 99)]:
            report = self.report()
            report[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_report(report, "current", "battery_low")

    def test_policy_report_cannot_masquerade_as_native_execution(self):
        report = self.report()
        report["nativeExecution"] = True
        with self.assertRaises(ValueError):
            validate_report(report, "current", "battery_low")

if __name__ == "__main__":
    unittest.main()
