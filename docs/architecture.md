# Architecture and extension guide

## State and ownership

`TranscodeViewModel` owns the editor and selection. `Editor.settings` is the one settings object for both Simple and Advanced. Applying a preset is an explicit replacement; opening Advanced is not. Each `SourceEdit` carries its own trim. Enqueue constructs immutable `JobSpec` snapshots with fresh UUIDs.

`QueueRepository` owns durable status and uses a mutex plus Android `AtomicFile`. It persists before publishing the new StateFlow value. An unsupported schema or corrupt record surfaces an error without overwriting that file. Version migrations must be explicit in `JobCodec`.

`TranscodeService` owns execution, not the Activity. Starting requires a visible user action. Jobs execute sequentially, and STOP cancels the current native session while leaving waiting jobs queued. On process restart, PREPARING/RUNNING/VERIFYING become INTERRUPTED. Retry makes a new job ID; it never appends to an incomplete output. There is no promise of seekless pause/resume, automatic reboot recovery or unlimited background time.

## Processing flow

```text
System document picker -> SourceEdit -> settings validation -> immutable JobSpec
    -> QUEUED -> PREPARING
    -> copy source into private work/<job-id>/source.media
    -> FFprobe -> revalidate actual source + available native capabilities
    -> RUNNING -> FFmpeg argument array -> session-local progress/cancellation
    -> VERIFYING -> nonempty output, track counts, duration and dimensions
    -> private outputs/<job-id>.<extension> -> COMPLETED
    -> FileProvider Open/Share or ACTION_CREATE_DOCUMENT export
```

Progress stays below 100% until the state machine finishes verification. Exit code zero alone is not completion. Verification is structural, **not** full decode, perceptual quality, accurate HDR handling or bit-exact codec verification.

Input/output strings are distinct argument tokens, not a shell command. The planner emits `-n`; output paths are private, per-job paths. Failed/cancelled staging is removed only after native cancellation completes. Private publication is a same-filesystem rename; document providers are not assumed to support atomic rename. Export failure attempts to delete only the newly created document, never the original.

## Native boundary

`FfmpegBridge` exposes `capabilities`, `probe`, and suspending `execute`. `KitNextBridge` adapts the callback API, retains a bounded session history and waits for native cancellation before returning. There are no global progress callbacks shared among jobs.

A build property selects `src/native` or `src/missing`; both provide `createFfmpegBridge()`. Only the native source set depends on the local AAR. Missing native support is a visible unavailable state, never a fake implementation. The interface permits a future directly maintained JNI/libav backend without coupling Compose to that choice.

Device MediaCodec encoders are withheld even when listed by FFmpeg: codec names do not prove a device supports a requested size, profile or color mode. Planar Main 10 (`yuv420p10le`, up to 10 bits) with absent/BT.709 transfer, primaries and matrix, and no HDR side metadata is accepted for explicit 8-bit `yuv420p` output. Untagged Main 10 is an SDR assumption, not proof of color intent. Explicit wide-gamut/HDR metadata, other high-depth layouts and unrecognized pixel formats require a separate qualified color pipeline. Output verification independently rejects high-bit-depth video.

## Adding features without hidden behavior

1. Add an immutable model field and serializer migration. Extend preset mapping only where intended.
2. Add planner validation and token-level regression checks. Never silently ignore a live control.
3. Extend the native adapter/capability model and structural verification where appropriate.
4. Add the control to the existing advanced section and give Simple a reasonable mapping.
5. Qualify actual media output on devices; a UI toggle or successful API compilation is not evidence of codec support.

Current limits are deliberate first-slice boundaries, not a claim that the full HTML prototype has been ported. Source preview is external; multi-track, subtitle, chapter, custom filtergraph and HDR editors still need model and engine work. Native FFprobe inspects document-provider media through FFmpegKit's SAF protocol; UI-only builds fall back to `MediaExtractor`. Disk-space budgeting, queue history cleanup, foreground-service lifecycle races and provider-specific exports need further device tests before production use.

## Platform references

- [Foreground-service timeout rules](https://developer.android.com/develop/background-work/services/fgs/timeout): mediaProcessing on Android 15+ has a time allowance; `onTimeout` stops the service.
- [System document access](https://developer.android.com/training/data-storage/shared/documents-files): persistable grants and create-document export.
- [FFmpeg options](https://ffmpeg.org/ffmpeg.html): argument ordering, stream mapping, rate control and filters.
