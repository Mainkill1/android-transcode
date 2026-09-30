from pathlib import Path
import unittest


class BuildContractTest(unittest.TestCase):
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
        self.assertIn('5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3', source)

    def test_no_native_release_is_blocked(self):
        source = (Path(__file__).resolve().parents[2] / 'app/build.gradle.kts').read_text()
        self.assertIn('check(nativeEnabledForRelease)', source)
        self.assertIn('preReleaseBuild', source)


if __name__ == '__main__': unittest.main()
