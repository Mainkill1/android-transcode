from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from tools.patch_ffmpeg_static_cxx import patch


class BuildContractTest(unittest.TestCase):
    def test_both_routes_use_16k_elf_alignment(self):
        source = (Path(__file__).resolve().parents[1] / 'build-ffmpeg.sh').read_text()
        for command in ('./android.sh ', './nix-android.sh '):
            invocation = next(line.strip() for line in source.splitlines() if line.strip().startswith(command))
            self.assertIn('--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384', invocation)
        self.assertIn('patch_ffmpeg_static_cxx.py', source)
        self.assertIn('patch_ffprobe_cancel.py', source)

    def test_direct_route_keeps_mediacodec_and_applies_probe_patch(self):
        source = (Path(__file__).resolve().parents[1] / 'build-ffmpeg.sh').read_text()
        invocation = next(line.strip() for line in source.splitlines() if line.strip().startswith('./android.sh '))
        self.assertIn('--enable-lib-android-media-codec', invocation.split())
        self.assertIn('--enable-lib-x264', invocation.split())
        self.assertIn('patch_ffprobe_cancel.py', source)

    def test_profile_explicitly_enables_android_mediacodec(self):
        source = (Path(__file__).resolve().parents[1] / 'build-ffmpeg.sh').read_text()
        invocation = next(line.strip() for line in source.splitlines() if line.strip().startswith('./nix-android.sh '))
        self.assertIn('--enable-lib-android-media-codec', invocation.split())
        self.assertIn('--enable-lib-x264', invocation.split())
        self.assertIn('--enable-lib-x265', invocation.split())
        self.assertIn('--enable-lib-libvpx', invocation.split())
        self.assertIn('--enable-lib-libsvtav1', invocation.split())
        self.assertIn('--enable-lib-dav1d', invocation.split())
        self.assertIn('--enable-lib-android-zlib', invocation.split())
        self.assertIn('--enable-lib-libwebp', invocation.split())
        self.assertIn('5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3', source)

    def test_direct_route_uses_the_same_software_profile(self):
        source = (Path(__file__).resolve().parents[1] / 'build-ffmpeg.sh').read_text()
        invocation = next(line.strip() for line in source.splitlines() if line.strip().startswith('./android.sh '))
        for option in ('--enable-lib-x264', '--enable-lib-x265', '--enable-lib-libvpx',
                       '--enable-lib-libsvtav1', '--enable-lib-dav1d', '--enable-lib-android-media-codec',
                       '--enable-lib-android-zlib', '--enable-lib-libwebp'):
            self.assertIn(option, invocation.split())

    def test_static_cxx_patch_checks_inputs_before_changing_any_file(self):
        workspace = Path(__file__).resolve().parents[2] / 'vendor'
        workspace.mkdir(exist_ok=True)
        with TemporaryDirectory(dir=workspace) as directory:
            source = Path(directory)
            wrapper = source / 'scripts/function-android.sh'
            svt = source / 'scripts/android/libsvtav1.sh'
            x265 = source / 'scripts/android/x265.sh'
            svt.parent.mkdir(parents=True)
            wrapper.write_text('echo "c++_shared"\n' + '-lc++_shared\n' * 5)
            svt.write_text('ANDROID_STL=unexpected\n')
            arm64 = '  ASM_OPTIONS="-DENABLE_ASSEMBLY=1 -DCROSS_COMPILE_ARM64=1 -DENABLE_SVE=0 -DENABLE_SVE2=0 -DENABLE_SVE2_BITPERM=0"'
            x265.write_text('x86)\n  ASM_OPTIONS="-DENABLE_ASSEMBLY=0"\n  ;;\narm64-v8a)\n' + arm64 + '\n  ;;\n')
            with self.assertRaises(ValueError):
                patch(source)
            self.assertEqual(wrapper.read_text(), 'echo "c++_shared"\n' + '-lc++_shared\n' * 5)
            svt.write_text('ANDROID_STL=c++_shared\n')
            patch(source)
            self.assertEqual(wrapper.read_text(), 'echo "c++_static"\n' + '-lc++_static -lc++abi -lunwind\n' * 5)
            self.assertEqual(svt.read_text(), 'ANDROID_STL=c++_static\n')
            self.assertIn(arm64[:-1] + ' -DENABLE_NEON_DOTPROD=0 -DENABLE_NEON_I8MM=0"', x265.read_text())
            self.assertIn('x86)\n  ASM_OPTIONS="-DENABLE_ASSEMBLY=0"', x265.read_text())

    def test_image_profile_enables_official_png_and_webp_dependencies(self):
        source = (Path(__file__).resolve().parents[1] / 'build-ffmpeg.sh').read_text()
        invocation = next(line.strip() for line in source.splitlines() if line.strip().startswith('./nix-android.sh '))
        self.assertIn('--enable-lib-android-zlib', invocation.split())
        self.assertIn('--enable-lib-libwebp', invocation.split())

    def test_no_native_release_is_blocked(self):
        source = (Path(__file__).resolve().parents[2] / 'app/build.gradle.kts').read_text()
        self.assertIn('check(nativeEnabledForRelease)', source)
        self.assertIn('preReleaseBuild', source)


if __name__ == '__main__': unittest.main()
