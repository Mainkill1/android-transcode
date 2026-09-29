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
