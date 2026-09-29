import copy
import importlib.util
from pathlib import Path
import json
import unittest

path = Path(__file__).resolve().parents[2] / 'tools' / 'collect-android-acceleration.py'
spec = importlib.util.spec_from_file_location('runtime_collector', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class RuntimeReportTests(unittest.TestCase):
    def setUp(self):
        self.run = '69431791-81c1-4464-a33b-ac87253a00e9'
        self.report = dict(schemaVersion=1, runId=self.run, requestedEncoder='H264_AUTO',
            success=True, nativeExecutionTested=True, deviceQualified=False,
            outputBytes=123456, sourceSha256='a'*64, outputSha256='b'*64,
            events=[dict(attempt=1, status='STARTED'), dict(attempt=1, status='VERIFIED')],
            selected=dict(backend='MEDIACODEC', encoder='h264_mediacodec', component='vendor', hardware='UNKNOWN'))
    def validate(self, report=None, encoder='H264_AUTO'):
        return module.validate_runtime_report(json.dumps(report or self.report), self.run, encoder)
    def test_runtime_report_is_not_a_qualification_claim(self):
        self.assertEqual(self.validate()['selected']['hardware'], 'UNKNOWN')
    def test_auto_can_report_cpu_success(self):
        self.report['selected'] = dict(backend='SOFTWARE', encoder='libx264', component=None, hardware='UNKNOWN')
        self.validate()
    def test_required_hardware_must_not_report_cpu_success(self):
        self.report['requestedEncoder'] = 'H264_HW'
        self.report['selected']['backend'] = 'SOFTWARE'
        with self.assertRaises(ValueError): self.validate(encoder='H264_HW')
    def test_reject_stale_run(self):
        self.report['runId'] = 'stale'
        with self.assertRaises(ValueError): self.validate()
    def test_reject_failed_or_missing_native(self):
        for key in ['success', 'nativeExecutionTested']:
            r = copy.deepcopy(self.report); r[key] = False
            with self.assertRaises(ValueError): self.validate(r)
    def test_reject_fake_qualification(self):
        self.report['deviceQualified'] = True
        with self.assertRaises(ValueError): self.validate()
    def test_reject_missing_or_invalid_size(self):
        for size in [0, -1, True, '123']:
            r = copy.deepcopy(self.report); r['outputBytes'] = size
            with self.assertRaises(ValueError): self.validate(r)
    def test_reject_missing_verified_event(self):
        self.report['events'] = [dict(attempt=1, status='STARTED')]
        with self.assertRaises(ValueError): self.validate()
    def test_reject_wrong_encoder(self):
        self.report['requestedEncoder'] = 'H265_AUTO'
        with self.assertRaises(ValueError): self.validate()
    def test_reject_bad_hash(self):
        self.report['outputSha256'] = 'bad'
        with self.assertRaises(ValueError): self.validate()
