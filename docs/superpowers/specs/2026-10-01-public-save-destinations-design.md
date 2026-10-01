# Public save destinations and visible output location

## Intent and scope

The user should know where every completed conversion went and find it in the phone's media library without a second save step. New video, audio, and still-image jobs default to `Movies/Forma/`, `Music/Forma/`, and `Pictures/Forma/` respectively. A saved setting may instead point to a user-chosen document tree. The original is never overwritten. Existing completed jobs retain their current private output and are labeled as such; the migration does not publish old media without a new user action.

This is the first independently useful PR in the approved editor-experience work. It changes delivery of *verified output*, not encoding, image rendering, queue order, media imports, or the conversion success criteria. The later Edit screen and precision timeline may rely on its Finished-card language, but this PR works without them.

## User flow

The Convert and Queue actions show a short destination line, for example `Saves to Movies/Forma`. The app-wide **Save location** setting offers **Forma folders** (default) and **Choose a folder**. Choosing a folder uses Android's document-tree picker and persists its grant. Settings shows the selected provider/folder label; it does not invent a filesystem path for a document provider. A new queue item snapshots the effective destination when added. Changing the setting never reroutes an existing queued item.

After strict export verification and durable private publication, Forma copies the completed file into the selected destination. Finished shows one of: `Saved to Movies/Forma`, `Saving to …`, `Converted; save failed`, or `Private in Forma` for legacy jobs. The saved card offers **View saved file**, **Share**, and **Save another copy**; a failed save offers **Retry save** and an actionable error. The conversion remains completed if delivery fails. A custom-folder grant that is lost requires the user to choose that folder again; the app does not silently switch to a default folder. A save operation reports copied bytes separately from encoding progress.

The public copy stays in the chosen location when private queue history expires or the app is uninstalled. Removing a queue entry removes only app-private data. Failed or waiting deliveries protect their private verified output from automatic history pruning until the user saves it or explicitly discards it.

## Model and ownership

Add a platform-neutral destination snapshot and delivery record to each queue entry. The snapshot records `Forma library` plus media category, or a document-tree URI plus a human-readable folder label. Delivery has explicit waiting, copying, saved, and failed states and retains the created destination URI once publication begins. A missing snapshot on decoded schema 1–4 entries means `Private in Forma`; it never triggers automatic publication. Bump the queue schema with strict backward decoding and rejection of unknown fields consistent with `JobCodec`. Keep Android `Uri`, `MediaStore`, and document-provider APIs in `app`, outside `core`.

The global setting stores the mode through the existing settings system. A small app-owned grant record stores the chosen tree URI and label. Enqueue validates that a selected custom folder still has a writable persisted grant, then freezes that tree in the queue entry. When the default changes, old grants stay available while queued or unfinished deliveries reference them. The destination adapter is the only component allowed to create and delete a public artifact. The foreground worker processes a verified file's delivery after conversion; startup recovery resumes waiting delivery without rerunning the encode. One delivery at a time is enough, and it must not hold a native encoding session while copying.

## Publication and recovery

Use a sanitized `{source}_forma_{first-eight-job-id}.{extension}` display name so retry and recovery can identify a job without exposing a source URI. On Android 10+ insert into the matching `MediaStore.Video`, `Audio`, or `Images` collection with `RELATIVE_PATH` under the corresponding `Forma` folder and `IS_PENDING=1`. Journal the job's publication intent before insertion and persist the returned URI before copying. Copy with cancellation checks and bounded buffers, verify the copied length and content digest, sync where the provider allows it, then clear `IS_PENDING` and durably mark the delivery saved. A retry inspects the recorded URI: a complete matching copy becomes saved; an owned partial/pending copy is removed before a fresh attempt. Startup reconciles an insertion that occurred before its URI was journaled by finding only app-owned pending items in the `Forma` folders with that job's filename marker. A collision creates a numbered filename and records the actual published name. No existing item is opened for truncation.

For a selected tree, create a new document under the persisted grant using a unique job marker, record its URI before writing, verify it, and mark saved. On failure or recovery, find and remove only that job's newly created partial document; a journaled creation intent covers a crash before the URI is recorded. Document providers do not universally hide unfinished files, so the UI must say `Saving to …` until verification ends and must not claim atomic visibility. If cleanup is denied, surface the retained URI and require explicit recovery rather than creating an unbounded series of duplicates. A provider-generated filename is displayed as returned, not guessed.

Android 8–9 retain the current minimum SDK. The default folders use the legacy public-media writer only after the required runtime storage permission; denied permission leaves the verified private result and a **Retry save** action. Media scanning makes a successfully written file visible. The custom-tree route uses its persisted grant on those versions as well. This compatibility path needs an API 26–28 device or emulator check before the PR is marked ready.

## Failure boundaries and validation

Delivery never changes a `COMPLETED` conversion to `FAILED`; it does not run before the private artifact is verified. Source URI equality checks, private staging, byte-cap enforcement, and queue completion rollback remain intact. Stop during encoding has existing native-cleanup ownership; Stop during public copy ends the copy, retains the private output, and records a retryable delivery state. A process death between public insertion and queue update is reconciled from the persisted URI and content check. Grant revocation, storage exhaustion, provider errors, stale output, and filename collisions produce distinct actionable messages.

Write a failing queue migration/snapshot test before the model change; a failing destination-adapter test before each writer; and Compose tests for the four Finished states. Device checks on the target OnePlus should convert one short video, audio, and image, inspect each public collection and folder, play/open the saved URI, restart Forma to prove settings and receipts persist, revoke a custom grant, retry safely, and compare source/output hashes. API 26–28 behavior is a separate required emulator/device gate. Run the repository's core/readiness checks, Android unit and Compose tests, build/lint, and native package checks. Record artifact hashes and report phone evidence separately from host and emulator results.

## References

- [Android shared media and pending publication](https://developer.android.com/training/data-storage/shared/media)
- [Android document-tree access and persisted grants](https://developer.android.com/training/data-storage/shared/documents-files)
