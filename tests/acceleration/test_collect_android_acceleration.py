"""Host-side regression tests; never part of the Android application."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[2] / 'tools' / 'collect-android-acceleration.py'
spec = importlib.util.spec_from_file_location('collector', MODULE_PATH)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
RUN_ID = 'cf798586-f9e6-4f70-b1ad-3321d4edcfd3'
SUCCESS = 'Time: 0.203\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n'


def report(**changes):
    result = dict(schemaVersion=1, runId=RUN_ID, inventoryComplete=True,
                  deviceQualified=False, nativeExecutionTested=False,
                  codecs=[dict(name='c2.test.avc.encoder', mime='video/avc', encoder=True)])
    result.update(changes)
    return result


class InstrumentationResultTests(unittest.TestCase):
    def test_accepts_normal_junit_completion_including_minus_one_code(self):
        collector.validate_instrumentation(SUCCESS)

    def test_rejects_zero_tests(self):
        with self.assertRaises(ValueError):
            collector.validate_instrumentation('OK (0 tests)\nINSTRUMENTATION_CODE: -1')

    def test_rejects_skipped_or_incomplete_output(self):
        for text in ['', 'INSTRUMENTATION_CODE: -1', 'Test ignored', 'OK (2 tests)']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                collector.validate_instrumentation(text)

    def test_rejects_failure_even_when_success_text_also_appears(self):
        for error in ['FAILURES!!!', 'INSTRUMENTATION_FAILED: runner missing',
                      'INSTRUMENTATION_RESULT: shortMsg=Process crashed.', 'INSTRUMENTATION_ABORTED: crash']:
            with self.subTest(error=error), self.assertRaises(ValueError):
                collector.validate_instrumentation(SUCCESS + error)


class ReportTests(unittest.TestCase):
    def test_valid_report_preserves_unknown_values(self):
        data = report(optional=None)
        self.assertEqual(collector.validate_report(json.dumps(data), RUN_ID), data)

    def test_rejects_invalid_json(self):
        for data in ['{', '[]', 'null']:
            with self.subTest(data=data), self.assertRaises(ValueError):
                collector.validate_report(data, RUN_ID)

    def test_rejects_stale_run(self):
        with self.assertRaises(ValueError):
            collector.validate_report(json.dumps(report(runId='old')), RUN_ID)

    def test_rejects_incomplete_or_false_qualification(self):
        for data in [report(inventoryComplete=False), report(deviceQualified=True),
                     report(nativeExecutionTested=True), report(schemaVersion=2),
                     report(schemaVersion=True), report(inventoryComplete=1),
                     report(codecs=[]), report(codecs='not-a-list'), report(codecs=[None]),
                     report(codecs=[dict(name='', mime='video/avc', encoder=True)])]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                collector.validate_report(json.dumps(data), RUN_ID)

    def test_rejects_missing_required_field(self):
        for key in ['runId', 'inventoryComplete', 'deviceQualified', 'nativeExecutionTested', 'codecs']:
            data = report()
            del data[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                collector.validate_report(json.dumps(data), RUN_ID)


class AdbTests(unittest.TestCase):
    @patch.object(collector.subprocess, 'run')
    def test_commands_are_argument_lists_and_select_exact_device(self, run):
        run.side_effect = [subprocess.CompletedProcess([], 0, SUCCESS.encode(), b''),
                           subprocess.CompletedProcess([], 0, json.dumps(report()).encode(), b'')]
        self.assertEqual(collector.collect('adb', 'serial-123', RUN_ID, 120), report())
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands[0][:4], ['adb', '-s', 'serial-123', 'shell'])
        self.assertIn('formaRunId', commands[0])
        self.assertIn(RUN_ID, commands[0])
        self.assertIn('dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner', commands[0])
        self.assertEqual(commands[1][3:6], ['exec-out', 'run-as', 'dev.forma.transcode'])
        for call in run.call_args_list:
            self.assertFalse(call.kwargs.get('shell', False))

    @patch.object(collector.subprocess, 'run')
    def test_adb_nonzero_exit_is_not_success(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, b'', b'device offline')
        with self.assertRaises(RuntimeError):
            collector.collect('adb', 'serial-123', RUN_ID, 120)
        self.assertEqual(run.call_count, 1)

    @patch.object(collector.subprocess, 'run')
    def test_report_not_read_after_failed_instrumentation(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, b'FAILURES!!!', b'')
        with self.assertRaises(ValueError):
            collector.collect('adb', 'serial-123', RUN_ID, 120)
        self.assertEqual(run.call_count, 1)

    @patch.object(collector.subprocess, 'run')
    def test_stale_report_fails_after_adb_success(self, run):
        run.side_effect = [subprocess.CompletedProcess([], 0, SUCCESS.encode(), b''),
                           subprocess.CompletedProcess([], 0, json.dumps(report(runId='stale')).encode(), b'')]
        with self.assertRaises(ValueError):
            collector.collect('adb', 'serial-123', RUN_ID, 120)

    @patch.object(collector.subprocess, 'run')
    def test_timeout_is_propagated_not_accepted_as_completion(self, run):
        run.side_effect = subprocess.TimeoutExpired('adb', 1)
        with self.assertRaises(subprocess.TimeoutExpired):
            collector.collect('adb', 'serial-123', RUN_ID, 1)

    def test_rejects_values_unsafe_for_adb_remote_shell(self):
        for serial, run_id in [('', RUN_ID), ('-d', RUN_ID), ('device;echo', RUN_ID),
                               ('device', 'bad;echo'), ('device', '../../x')]:
            with self.subTest(serial=serial, run_id=run_id), self.assertRaises(ValueError):
                collector.collect('adb', serial, run_id, 120)

    def test_output_never_overwrites_existing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'report.json'
            collector.write_report(target, report())
            initial = target.read_bytes()
            with self.assertRaises(FileExistsError):
                collector.write_report(target, report(runId='different'))
            self.assertEqual(target.read_bytes(), initial)


if __name__ == '__main__':
    unittest.main()
