#!/usr/bin/env python3
"""Temporarily make the pinned FFprobe source honor per-session cancellation.

The source revision stays pinned. The build helper validates a clean checkout,
applies this exact-profile patch, and restores all tracked files on exit.
"""

from pathlib import Path
import sys


CPP = Path("android/ffmpeg-kit-next-android-lib/src/main/cpp")
REPLACEMENTS = {
    CPP / "ffmpegkit.c": (
        (
            "static atomic_short sessionMap[SESSION_MAP_SIZE];",
            "static atomic_short sessionMap[SESSION_MAP_SIZE];\n"
            "static atomic_long activeSessionId[SESSION_MAP_SIZE];\n"
            "static atomic_long pendingCancelSessionId[SESSION_MAP_SIZE];",
            1,
        ),
        (
            "void addSession(long id) {\n    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 1);\n}",
            "void addSession(long id) {\n"
            "    atomic_store(&activeSessionId[id % SESSION_MAP_SIZE], id);\n"
            "    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 1);\n}",
            1,
        ),
        (
            "void removeSession(long id) {\n    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 0);\n}",
            "void removeSession(long id) {\n"
            "    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 0);\n"
            "    long expected = id;\n"
            "    atomic_compare_exchange_strong(&activeSessionId[id % SESSION_MAP_SIZE], &expected, 0);\n"
            "    expected = id;\n"
            "    atomic_compare_exchange_strong(&pendingCancelSessionId[id % SESSION_MAP_SIZE], &expected, 0);\n}",
            1,
        ),
        (
            "void cancelSession(long id) {\n    atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 2);\n}",
            "void cancelSession(long id) {\n"
            "    atomic_store(&pendingCancelSessionId[id % SESSION_MAP_SIZE], id);\n"
            "    if (atomic_load(&activeSessionId[id % SESSION_MAP_SIZE]) == id)\n"
            "        atomic_store(&sessionMap[id % SESSION_MAP_SIZE], 2);\n}",
            1,
        ),
        (
            "int cancelRequested(long id) {\n"
            "    if (atomic_load(&sessionMap[id % SESSION_MAP_SIZE]) == 2) {\n"
            "        return 1;\n"
            "    } else {\n"
            "        return 0;\n"
            "    }\n}",
            "int cancelRequested(long id) {\n"
            "    return atomic_load(&pendingCancelSessionId[id % SESSION_MAP_SIZE]) == id ||\n"
            "        (atomic_load(&activeSessionId[id % SESSION_MAP_SIZE]) == id &&\n"
            "         atomic_load(&sessionMap[id % SESSION_MAP_SIZE]) == 2);\n}",
            1,
        ),
        (
            "        atomic_init(&sessionMap[i], 0);\n"
            "        atomic_init(&sessionInTransitMessageCountMap[i], 0);",
            "        atomic_init(&sessionMap[i], 0);\n"
            "        atomic_init(&activeSessionId[i], 0);\n"
            "        atomic_init(&pendingCancelSessionId[i], 0);\n"
            "        atomic_init(&sessionInTransitMessageCountMap[i], 0);",
            1,
        ),
    ),
    CPP / "ffprobekit.c": (
        (
            "extern __thread long globalSessionId;",
            "extern __thread long globalSessionId;\nextern int cancelRequested(long sessionId);",
            1,
        ),
        (
            "int returnCode = ffprobe_execute(argumentCount, argv);",
            "int returnCode = cancelRequested((long)id) ? 255 : ffprobe_execute(argumentCount, argv);",
            2,
        ),
        (
            "int returnCode = cancelRequested((long)id) ? 255 : ffprobe_execute(argumentCount, argv);",
            "int returnCode = cancelRequested((long)id) ? 255 : ffprobe_execute(argumentCount, argv);\n"
            "    if (cancelRequested((long)id)) returnCode = 255;",
            2,
        ),
    ),
    CPP / "fftools/ffprobe.c": (
        (
            '#include "opt_common.h"',
            '#include "opt_common.h"\n\n'
            'extern __thread long globalSessionId;\n'
            'extern int cancelRequested(long sessionId);\n\n'
            'static int ffprobe_interrupted(void *opaque)\n'
            '{\n'
            '    return cancelRequested(globalSessionId);\n'
            '}\n',
            1,
        ),
        (
            "    fmt_ctx = avformat_alloc_context();\n    if (!fmt_ctx)\n        return AVERROR(ENOMEM);",
            "    fmt_ctx = avformat_alloc_context();\n    if (!fmt_ctx)\n        return AVERROR(ENOMEM);\n"
            "    fmt_ctx->interrupt_callback.callback = ffprobe_interrupted;",
            1,
        ),
        (
            "    while (!av_read_frame(fmt_ctx, pkt)) {",
            "    while (!cancelRequested(globalSessionId) && !av_read_frame(fmt_ctx, pkt)) {",
            1,
        ),
        (
            "    av_packet_unref(pkt);\n    //Flush remaining frames that are cached in the decoder",
            "    if (cancelRequested(globalSessionId)) {\n"
            "        ret = AVERROR_EXIT;\n"
            "        goto end;\n"
            "    }\n"
            "    av_packet_unref(pkt);\n    //Flush remaining frames that are cached in the decoder",
            1,
        ),
    ),
}


def patch(source: Path) -> None:
    revised = {}
    for relative, replacements in REPLACEMENTS.items():
        path = source / relative
        content = path.read_text()
        for before, after, expected in replacements:
            actual = content.count(before)
            if actual != expected:
                raise ValueError(f"{relative}: expected {expected} {before!r} occurrences, found {actual}")
            content = content.replace(before, after)
        revised[path] = content
    for path, content in revised.items():
        path.write_text(content)


if __name__ == "__main__":
    patch(Path(sys.argv[1]))
