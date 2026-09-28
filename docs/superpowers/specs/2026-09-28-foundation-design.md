# Forma foundation

User intent: initialize the empty repository directly on main with a native Android UI/framework, FFmpeg backend direction, and a simple default experience that reveals advanced controls without replacing the workflow.

Three modules: app (Compose, Android storage, queue/lifecycle), core (immutable models, presets, validation, argument planner, queue transitions), and engine-ffmpeg (native binding and capability/probe/execute contracts). No privileged WebView, root requirement, network permission, shell command concatenation, or simulated success.

The first native slice supports one video and selected audio track, MP4/MKV/WebM/M4A, quality/bitrate, resize, trim, frame rate and basic filters. Multi-track authoring, subtitles, chapters, strict target size, HDR processing, previews, and device-qualified hardware profiles remain explicit extension points. Unsupported controls cannot silently alter or drop job intent.

A no-native build opens the full shell and can prepare/persist jobs, but cannot encode. An explicit build property selects a source-built FFmpegKitNext local Maven artifact. Jobs snapshot settings and per-source trims, stage input in app storage, validate output before completion, and export through the system picker. Interrupted work needs an explicit restart; no resumability claim.
