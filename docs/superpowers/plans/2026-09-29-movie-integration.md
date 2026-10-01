# Complete native movie integration

Purpose: make the renderer introduced in 30318d9 usable from the existing Android workflow, with durable immutable jobs, full source preservation and verified decimal byte limits. This extends the previously approved editor framework; the user explicitly requested implementation inline and physical qualification remains parent-owned.

Design: retain MovieProject/ProjectHistory and the existing foreground service/RunCoordinator. A compact Movie inspector composes selected sources using typed timeline commands. Preview creates an independent low-resolution queued render; playback opens its verified output. Final export snapshots the document and cap. All jobs stage original sources, invoke FfmpegRenderSession and publish only after verification. No live-preview claim, alternate encoder, new service or native pin change.

### Task 1: Durable movie jobs

Add failing persistence tests for ordered clips, every clip setting, canvas, overlap, cap, schema 1/2 and malformed schema 3. Implement strict schema 3 with reusable typed source/settings/sequence codecs and JobPlans validation. Preserve schema 1/2 semantics. Verify app JVM persistence suites.

### Task 2: Production rendering and lifecycle

Add failing mixed-silent M4A and accepted-callback cancellation tests. Permit silent segments in audio movie validation. Stage every ordered source with cancellation and source-size checks; delegate the existing exporter to FfmpegRenderSession. Keep publication plus Completed notification in its existing NonCancellable transaction. Protect every original URI on document save. Add runtime tests for original-input retries, strict cap equality/exhaustion, decode failure, cleanup and cancellation. Verify core/app JVM suites and actual desktop fixtures.

### Task 3: Native document controls

Expose MovieProject/ProjectHistory through ViewModel actions and immutable UI state. Add native controls to append, move, remove, duplicate, split, trim, canvas, transition, cap, undo/redo, queued rendered preview and movie export. Keep current source clip controls. Enqueue ordinary conversions with their explicit Editor cap too. Use JobPlans for queued validation and progress. Add Compose accessibility/action tests; compile the lab app/test APK.

### Task 4: Reproducible qualification and documentation

Extend the isolated lab command with production movie smoke scenarios and captured preparation/attempt evidence; update its host CLI contracts. Update host harness to include sequence/project/runtime coverage and deterministic desktop sequence fixtures. Rewrite stale implementation status and gate instructions. Run core/readiness/UX checks, JVM tests, Android compile/lint and test-removal build. Physical signal, visual, listening, A/V and cancellation gates remain explicit and are executed by the parent agent.

Review focus: cancellation between accepted-attempt reporting and finalization; restart after movie queue persistence; silent included clips; source B used as save destination; original staged sources on every retry; exact planned frame grid, decoded clocks and strict cap; inaccessible controls or orphan production interfaces.

Timing ruling: preserve a video-leading gap by composing its timestamped picture over a finite canvas background, with explicit color/overlay requirements. Independently generated passthrough-timestamp fixtures prove the gap stays black while earlier audio is retained. Resets happen after materializing shared timing, never per-stream before composition.

### Inline implementation verification (2026-09-29)

The Android production path now stages the complete ordered snapshot and uses the shared renderer. Native Movie controls expose the document/history and enqueue real previews/exports through the existing foreground service. Schema 3 stores the full request; legacy schemas cannot erase newer fields. Publication rolls back its own output if durable completion fails, and Stop during successful completion retains the completed artifact.

Host checks passed: 108 core/editor/sequence/project assertions; fourteen ADB report-contract tests; eleven existing desktop clip exports; seven real production-session movie exports. The delayed-video RED fixture now keeps black canvas at 0.100 s and red picture at 0.500 s. Movie frame counts are 120/105/90/0/120/120/120 for cut/dissolve/speed/audio/cap/preview/delayed-video. Independent decoded PCM checks preserve leading audio gaps and silent segments. JVM tests exercise original-source retries, strict cap equality/exhaustion, failed decode, accepted-callback/rename failure, durable publication rollback and cancellation.

The complete Gradle core/engine/app suite passed (39 JUnit methods), together with `assembleLab`, `assembleLabAndroidTest` and `lintDebug`. `check-core`, `check-android-readiness` and `check-ux` passed. The product-only `-PformaTests=false :app:assembleDebug` build passed; its runtime dependency report and DEX contain no test runner, Espresso, Compose test dependencies or command/control test classes. Native APK payload/alignment verification passed and explicitly reports runtime qualification as false.

| Artifact | SHA-256 |
| --- | --- |
| Lab application | `9ff120c51a42b4a53f3dc40cd85852bde3c5c0ca505644dd2cc4689896911ca3` |
| Matching lab instrumentation | `ab217c4c04176c5ac9545cbe18afad5acb4cf89fdf45c17ba677ca791963f1f3` |
| Product-only debug application | `4d765bb8a0322d6ea6545216ee339e4fd09231279b138c2850a990e6d2bc7017` |

These are local build identities, not installed-phone claims. The parent must independently review the final tree, then run `movie-smoke` and `MovieControlsTest` on these exact lab artifacts, inspect/listen to outputs and exercise compact/wide/TalkBack controls before promotion. Editing drafts survive Activity recreation; durable queue snapshots survive process restart. A persisted editable project, interactive scrubbing, still-image clips and multitrack editing remain deferred boundaries.

## Production completeness and service follow-up

The independent final review reproduced a fully decodable output with five seconds of audio but only one second / 30 frames of picture being accepted for a five-second / 150-frame request. The committed real desktop regression now rejects truncated video, truncated audio, incorrect CFR counts and a displaced video start, while accepting the complete fixture. Production verification reads fresh typed FFprobe stream clocks and decoded counts before publication; single-source checks retain the shared input origin and intentional stream offsets, while movie composition checks its materialized zero-based common frame grid. Missing clocks/counts fail closed. Matroska/WebM duration tags supply stream-end evidence when FFprobe lacks duration fields. Full decode still remains mandatory.

The Android movie lab now reads frame counts from FFprobe instead of parsing asynchronous showinfo log fragments. A separate opt-in `MovieServiceTest` qualifies the real foreground service, durable Completed state and its own output on an idle empty isolated lab queue. It restores that empty queue byte-for-byte only after native/coordinator ownership ends. The SDK-guarded platform foreground call preserves MEDIA_PROCESSING on API 35+, avoiding the pinned AndroidX compatibility mask that removed that bit. No native version/license pins changed.

Fresh host evidence: `build/movie-completeness-red.log` reproduced the acceptance bug, `build/movie-completeness-expanded.log` passed the completeness regression, `build/movie-completeness-full-build.log` passed all JVM suites, native lab/test packaging and debug lint. `build/movie-completeness-host-containers.log` / `testing/results/host/756ad5d7508c441a990e33d968466788` passed the existing clip exports and nine production-session movie exports, including WebM and Matroska stream-clock evidence. These desktop/build results do not replace the parent-owned fresh APK phone qualification.

A further intentional-audio-offset regression at 200% speed reproduced an extra terminal CFR frame (76 versus 75); materializing the explicit `fps` filter in the single-clip graph fixes that boundary while preserving the audio delay. A RED capability regression verifies that a missing fps filter blocks that route before encoding. The MovieControlsTest now scrolls each wrapped action independently and requires its full bounds inside the viewport before tapping; captured actions remain strictly asserted and are included in any failure message.

Final follow-up gates: `build/movie-completeness-full-build.log` passes 41 JVM tests (29 app, 4 engine, 8 core), native lab application and instrumentation packaging, and debug lint. `build/movie-completeness-host-final.log` / `testing/results/host/4bcf74fe0b3a49dc8a40e7719401b0da` passes the core/editor/project/report contracts and all 21 desktop exports (12 clips, 9 movies). `build/movie-offset-green.log` verifies preserved delayed audio at 100% and 200% speed. The parent owns physical tests against the rebuilt matching APK pair.
