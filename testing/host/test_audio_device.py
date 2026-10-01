import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('audio_device',Path(__file__).parents[1]/'audio_device.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class InstrumentationReportTest(unittest.TestCase):
    def test_adb_zero_with_test_failure_is_rejected(self):
        with self.assertRaises(RuntimeError): module.require_success(0,'FAILURES!!!\nTests run: 1, Failures: 1\nINSTRUMENTATION_CODE: -1')
    def test_crash_is_rejected(self):
        with self.assertRaises(RuntimeError): module.require_success(0,'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0')
    def test_missing_results_is_rejected(self):
        with self.assertRaises(RuntimeError): module.require_success(0,'INSTRUMENTATION_CODE: -1')
    def test_success_with_tests_passes(self): module.require_success(0,'OK (3 tests)\nINSTRUMENTATION_CODE: -1')
    def test_adb_connection_failure_is_rejected(self):
        with self.assertRaises(RuntimeError): module.require_success(1,'OK (1 test)')
if __name__=='__main__': unittest.main()
