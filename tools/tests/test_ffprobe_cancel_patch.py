from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.patch_ffprobe_cancel import patch


class FfprobeCancelPatchTest(unittest.TestCase):
    CONTROL_SOURCE = (
        "static atomic_short sessionMap[SESSION_MAP_SIZE];\n"
        "static atomic_int sessionInTransitMessageCountMap[SESSION_MAP_SIZE];\n"
        "void addSession(long id) {\n    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 1);\n}\n"
        "void removeSession(long id) {\n    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 0);\n}\n"
        "void cancelSession(long id) {\n    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 2);\n}\n"
        "int cancelRequested(long id) {\n    if (atomic_load(&sessionMap[id % SESSION_MAP_SIZE]) == 2) {\n"
        "        return 1;\n    } else {\n        return 0;\n    }\n}\n"
        "        atomic_init(&sessionMap[i], 0);\n"
        "        atomic_init(&sessionInTransitMessageCountMap[i], 0);\n"
    )

    def test_patch_preserves_cancellation_before_native_start_and_interrupts_reading(self):
        workspace = Path(__file__).resolve().parents[2] / "vendor"
        workspace.mkdir(exist_ok=True)
        with TemporaryDirectory(dir=workspace) as directory:
            source = Path(directory)
            cpp = source / "android/ffmpeg-kit-next-android-lib/src/main/cpp"
            (cpp / "fftools").mkdir(parents=True)
            session_map = cpp / "ffmpegkit.c"
            probe_jni = cpp / "ffprobekit.c"
            probe = cpp / "fftools/ffprobe.c"
            session_map.write_text(self.CONTROL_SOURCE)
            probe_jni.write_text("extern __thread long globalSessionId;\n" +
                                 "    int returnCode = ffprobe_execute(argumentCount, argv);\n" * 2)
            probe.write_text('#include "opt_common.h"\n' +
                             '    fmt_ctx = avformat_alloc_context();\n    if (!fmt_ctx)\n        return AVERROR(ENOMEM);\n' +
                             '    while (!av_read_frame(fmt_ctx, pkt)) {\n' +
                             '    av_packet_unref(pkt);\n    //Flush remaining frames that are cached in the decoder\n')
            patch(source)
            self.assertIn("pendingCancelSessionId", session_map.read_text())
            self.assertIn("activeSessionId", session_map.read_text())
            self.assertIn("atomic_load(&pendingCancelSessionId[id % SESSION_MAP_SIZE]) == id", session_map.read_text())
            self.assertEqual(probe_jni.read_text().count("cancelRequested((long)id) ? 255"), 2)
            self.assertEqual(probe_jni.read_text().count("if (cancelRequested((long)id)) returnCode = 255;"), 2)
            self.assertIn("fmt_ctx->interrupt_callback.callback", probe.read_text())
            self.assertIn("while (!cancelRequested(globalSessionId) && !av_read_frame", probe.read_text())
            self.assertIn("ret = AVERROR_EXIT;", probe.read_text())

    def test_mismatch_does_not_change_any_wrapper_file(self):
        workspace = Path(__file__).resolve().parents[2] / "vendor"
        workspace.mkdir(exist_ok=True)
        with TemporaryDirectory(dir=workspace) as directory:
            source = Path(directory)
            cpp = source / "android/ffmpeg-kit-next-android-lib/src/main/cpp"
            (cpp / "fftools").mkdir(parents=True)
            session_map = cpp / "ffmpegkit.c"
            probe_jni = cpp / "ffprobekit.c"
            probe = cpp / "fftools/ffprobe.c"
            session_map.write_text(self.CONTROL_SOURCE)
            probe_jni.write_text("unsupported revision")
            probe.write_text("unsupported revision")
            with self.assertRaises(ValueError):
                patch(source)
            self.assertIn("atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 1)", session_map.read_text())


if __name__ == "__main__":
    unittest.main()
