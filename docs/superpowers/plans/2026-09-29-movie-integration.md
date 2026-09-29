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
