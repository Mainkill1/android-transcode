import copy
import unittest
from run_lab import instrumentation_passed, validate_report, report_command


class ReportChecks(unittest.TestCase):
    def setUp(self):
        self.expected = dict(runId="a" * 32, appCommit="b" * 40, mode="NDK", baseline="JAVA",
                             format="H264", width=1280, height=720, fps=30, seconds=3,
                             videoKbps=4000, operatingRate=0)
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
        command = report_command("adb", "device", "a" * 32)
        self.assertIn("run-as", command)
        self.assertEqual(command[-1], "files/acceleration-lab/" + "a" * 32 + "/report.json")
        with self.assertRaises(ValueError): report_command("adb", "device", "../report; touch /bad")


if __name__ == "__main__": unittest.main()
