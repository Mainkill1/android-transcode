# Working in Forma

Keep the simple default and advanced controls on one workflow. Toggling Advanced must not reset settings. Presets may replace settings only after an explicit user action. Queue entries own immutable snapshots and per-file trims.

Keep Android and native APIs out of `core`. Build arguments as tokens, never shell strings. Do not pretend a codec, hardware path, progress value or output exists. Missing native binaries must remain an unavailable state, not simulated success.

Run `tools/check-core.sh` when Kotlin CLI is available, then Android Gradle tests/build/lint when the SDK is available. Add regression checks for planner changes. Distinguish API compilation, emulator UI tests and actual native media/device qualification in every report.

Do not change the FFmpeg source pin or application/native licensing implicitly. Never commit native downloads, signing keys, local.properties, source media or generated builds. Extend versioned queue serialization deliberately. Never overwrite originals or resume partial output without a proven design.
