import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

MODULE = Path(__file__).resolve().parents[1] / 'verify_android_native.py'


def elf(align=16384, machine=183, relro_end=16384):
    data = bytearray(16384)
    ident = b'\x7fELF\x02\x01\x01' + bytes(9)
    struct.pack_into('<16sHHIQQQIHHHHHH', data, 0, ident, 3, machine, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, len(data), len(data), align)
    struct.pack_into('<IIQQQQQQ', data, 120, 0x6474e552, 4, 0, 0, 0, 0, relro_end, 1)
    return bytes(data)


class NativePackageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not MODULE.is_file():
            raise AssertionError('Native package verifier is missing')
        spec = importlib.util.spec_from_file_location('verify_android_native', MODULE)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def archive(self, libs=True, blob=None, apk=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / ('app.apk' if apk else 'engine.aar')
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('classes.jar', b'API is not a native library')
            if libs:
                for name in ('libffmpegkit.so', 'libavcodec.so', 'libavformat.so', 'libavutil.so'):
                    archive.writestr(('lib/' if apk else 'jni/') + 'arm64-v8a/' + name, blob or elf())
        return path

    def test_api_only_aar_rejected(self):
        with self.assertRaises(ValueError): self.module.verify(self.archive(libs=False), ['arm64-v8a'])

    def test_complete_16k_aar_passes(self):
        result = self.module.verify(self.archive(), ['arm64-v8a'])
        self.assertEqual(len(result['libraries']), 4)
        self.assertFalse(result['runtimeQualified'])

    def test_4k_elf_rejected(self):
        with self.assertRaises(ValueError): self.module.verify(self.archive(blob=elf(4096)), ['arm64-v8a'])

    def test_wrong_elf_machine_rejected(self):
        with self.assertRaises(ValueError): self.module.verify(self.archive(blob=elf(machine=62)), ['arm64-v8a'])

    def test_relro_file_size_can_exceed_memory_size(self):
        blob = bytearray(elf())
        struct.pack_into('<IIQQQQQQ', blob, 120, 0x6474e552, 4, 0, 15872, 0, 2048, 512, 1)
        result = self.module.verify(self.archive(blob=bytes(blob)), ['arm64-v8a'])
        self.assertEqual(len(result['libraries']), 4)

    def test_bad_relro_rejected(self):
        with self.assertRaises(ValueError): self.module.verify(self.archive(blob=elf(relro_end=4096)), ['arm64-v8a'])

    def test_required_abi_is_enforced(self):
        with self.assertRaises(ValueError): self.module.verify(self.archive(), ['x86_64'])

    def test_malformed_elf_rejected(self):
        with self.assertRaises(ValueError): self.module.verify(self.archive(blob=b'broken'), ['arm64-v8a'])

    def test_all_64bit_libraries_checked(self):
        path = self.archive()
        with zipfile.ZipFile(path, 'a') as z: z.writestr('jni/arm64-v8a/libthirdparty.so', elf(4096))
        with self.assertRaises(ValueError): self.module.verify(path, ['arm64-v8a'])

    def test_compressed_apk_loads_are_extracted(self):
        self.assertEqual(len(self.module.verify(self.archive(apk=True), ['arm64-v8a'])['libraries']), 4)

    def test_unaligned_uncompressed_apk_rejected(self):
        path = self.archive(apk=True)
        with zipfile.ZipFile(path, 'a', compression=zipfile.ZIP_STORED) as z:
            z.writestr('lib/arm64-v8a/libthirdparty.so', elf())
        with self.assertRaises(ValueError): self.module.verify(path, ['arm64-v8a'])


if __name__ == '__main__': unittest.main()
