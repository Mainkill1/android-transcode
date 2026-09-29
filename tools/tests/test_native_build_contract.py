from pathlib import Path
import unittest


class BuildContractTest(unittest.TestCase):
    def test_profile_explicitly_enables_android_mediacodec(self):
        source = (Path(__file__).resolve().parents[1] / 'build-ffmpeg.sh').read_text()
        invocation = next(line for line in source.splitlines() if line.startswith('./nix-android.sh '))
        self.assertIn('--enable-lib-android-media-codec', invocation.split())
        self.assertIn('--enable-lib-x264', invocation.split())
        self.assertIn('5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3', source)

    def test_image_profile_enables_official_png_and_webp_dependencies(self):
        source = (Path(__file__).resolve().parents[1] / 'build-ffmpeg.sh').read_text()
        invocation = next(line for line in source.splitlines() if line.startswith('./nix-android.sh '))
        self.assertIn('--enable-lib-android-zlib', invocation.split())
        self.assertIn('--enable-lib-libwebp', invocation.split())

    def test_no_native_release_is_blocked(self):
        source = (Path(__file__).resolve().parents[2] / 'app/build.gradle.kts').read_text()
        self.assertIn('check(nativeEnabledForRelease)', source)
        self.assertIn('preReleaseBuild', source)


if __name__ == '__main__': unittest.main()
