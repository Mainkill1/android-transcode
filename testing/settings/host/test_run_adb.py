import unittest
from run_adb import validate_report, revision_argument
import argparse

class ReportTests(unittest.TestCase):
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
