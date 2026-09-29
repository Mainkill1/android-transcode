# Runtime codec trials implementation plan

Goal: move the hardware research into the real Android export flow. The user's
2026-09-29 follow-up explicitly authorizes trying real device configurations
rather than treating advertised support as an absolute veto.

Architecture: exact-request trial planning in Android-free core; Android codec
inventory is advisory; native FFmpeg executes every trial; a bounded executor
retries only positively identified codec failures or invalid encoded outputs.
FFmpeg remains export owner. CPU decode/filter stages remain explicit.

## Constraints
- Preserve API 26, native source/licensing pins, immutable queue settings, originals.
- Try advertised and unadvertised YUV420P/NV12 + VBR/CBR combinations; never CBR frame-drop mode.
- Unknown hardware identity is reported as unknown, not inferred from codec names.
- Never silently reduce resolution/rate/duration, drop audio or remove edits.
- Software-only stays software; explicit hardware selection never falls back to software.
- Auto is opt-in for old queues; legacy serialized encoder names retain their meaning.
- Keep tests and fixtures in test-only source sets or external testing directories.

## Implementation / test order
1. Write and run failing pure Kotlin trial-planner/failure-classifier tests.
2. Implement bounded, deduplicated, fair component/buffer/rate-mode trials.
3. Write and run coroutine executor tests for fallback, verification, cleanup,
   cancellation, unknown failures, attempt limits, and preexisting output.
4. Implement bridge preparation, typed native error reporting and retry execution.
5. Integrate Auto/required-hardware choices, native preparation, UI and private
   attempt reports in the existing app path; preserve background-work ownership.
6. Add separate device tests using real FFmpeg, plus build/ADB instructions.
7. Run available host checks; review the diff, publish a draft PR, and read back
   the commit and file identities. Android compilation and phone testing must be
   marked unrun when unavailable, not replaced by host policy tests.

## Remaining independent work
Hardware decoding, GPU surfaces, NDK async qualification, HDR, native hang/crash
process containment, full native upload-byte-cap parity, and measured device
performance are not implied by codec trial success.
