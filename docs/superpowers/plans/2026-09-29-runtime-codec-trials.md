# Runtime codec trials implementation plan

Goal: move the hardware research into the real Android export flow. The user's
2026-09-29 follow-up explicitly authorizes trying real device configurations rather
than treating advertised support as an absolute veto.

Architecture: exact-request trial planning in Android-free core; Android codec
inventory is advisory; native FFmpeg executes every trial; a bounded executor
retries only positively identified codec failures or invalid encoded outputs.
FFmpeg remains export owner. CPU decode/filter stages remain explicit.

## Constraints

- Preserve API 26, native source/licensing pins, immutable queue settings, originals.
- Try advertised and unadvertised YUV420P/NV12 plus VBR/CBR combinations; never use
  the frame-dropping CBR mode.
- Report unknown hardware identity as unknown; never infer it from codec names.
- Never silently reduce resolution/rate/duration, drop audio, or remove edits.
- Software-only stays software; explicit hardware selection never falls back.
- Automatic is opt-in for old queues; legacy encoder names retain their meaning.
- Keep tests and fixtures in test-only source sets or external testing directories.
- Use direct ADB for phone execution; no Python collector or production command
  receiver is required.
- Device reports must use a caller-supplied canonical run ID, run-specific filename,
  and create-new/no-overwrite semantics.

## Implementation and test order

1. Write and run failing pure Kotlin trial-planner/failure-classifier tests.
2. Implement bounded, deduplicated, fair component/buffer/rate-mode trials.
3. Write and run coroutine executor tests for fallback, verification, cleanup,
   cancellation, unknown failures, attempt limits, and preexisting output.
4. Implement bridge preparation, typed native error reporting, and retry execution.
5. Integrate Automatic/required-hardware choices, native preparation, UI, and private
   attempt reports in the existing app path; preserve background-work ownership.
6. Add separate instrumentation tests driven directly with `adb shell am instrument`
   and collect reports with `adb exec-out run-as`.
7. Require explicit run IDs/encoder choices and non-overwriting run-specific reports
   so a failed or skipped raw-ADB invocation cannot be mistaken for fresh evidence.
8. Run host and Android CI checks; review the diff and keep physical-device/native
   payload gates explicit rather than replacing them with host tests.

## Remaining independent work

Hardware decoding, GPU surfaces, NDK async qualification, HDR, native hang/crash
process containment, full native upload-byte-cap parity, and measured device
performance are not implied by codec trial success.
