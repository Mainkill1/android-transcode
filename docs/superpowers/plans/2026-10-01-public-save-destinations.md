# Public Save Destinations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically publish verified conversions to obvious Forma media folders or a persisted user-selected folder, with a visible, retryable delivery state.

**Architecture:** Keep private output verification and `JobState.COMPLETED` intact. Add a platform-neutral destination/receipt to `QueueEntry`, then an Android-only delivery worker and publisher that journal each public creation and reconcile it after crashes. The settings/UI choose a destination; enqueue freezes it per job.

**Tech Stack:** Kotlin, Compose, `AtomicFile`, `MediaStore`, SAF, foreground service, JUnit/Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-10-01-public-save-destinations-design.md`

## Global Constraints

- Defaults: `Movies/Forma/`, `Music/Forma/`, `Pictures/Forma/`; custom tree grant persists across launches.
- A job snapshots its destination at enqueue; schema 1–4 entries stay private and are never auto-published.
- Public writing starts only after strict verification and durable private publication; conversion completion survives copy failure.
- Keep minSdk 26, native FFmpeg, source originals, and immutable queued settings intact.
- Do not commit sample media, native payloads, private queue contents, or generated builds.

## Review Focus

- Revoked custom grant after queueing: retain private output and show Retry save/reselect; Task 3 test.
- Process death after public insert but before URI journaling: reconcile only the app-owned pending marker; Task 3 test.
- Retry after partial copy: delete only the owned partial item and avoid duplicate published files; Task 3 test.
- An old completed queue entry: retain private-only state across migration and restart; Task 1 test.
- Android 8–9 permission denial: keep completed conversion and a retryable private output; Task 3 test.

---

### Task 1: Destination and delivery wire model

**Files:** Modify `core/src/main/kotlin/dev/forma/core/Models.kt`, `app/src/main/kotlin/dev/forma/app/data/JobCodec.kt`, `app/src/main/kotlin/dev/forma/app/data/QueueRepository.kt`; test `testing/app/unit/dev/forma/app/data/JobCodecTest.kt`.

**Interfaces:** `SaveDestination` is `FormaLibrary(category)` or `DocumentTree(uri,label)`; `DeliveryReceipt` is `PrivateLegacy`, `Waiting`, `Copying(intentName,uri?)`, `Saved(uri,displayName,bytes,digest)`, or `Failed(message,uri?)`. `QueueEntry.delivery` owns both frozen destination and receipt; `QueueRepository.updateDelivery(id, expected, next)` persists a compare-and-set transition.

- [ ] Write failing codec tests: schema 1–4 completed jobs decode as `PrivateLegacy`, new schema round-trips all receipt states, unknown fields and malformed URIs preserve the original queue, and changing defaults does not alter stored destinations.
- [ ] Run `./gradlew :app:testLabUnitTest --tests '*JobCodecTest*'`; confirm the new cases fail.
- [ ] Add the typed model and schema 5 with strict decoding; add `updateDelivery` and protect waiting/failed private artifacts in history pruning.
- [ ] Run the same tests plus `./gradlew :core:test :app:testLabUnitTest`; confirm pass, then commit this model slice.

### Task 2: Saved destination setting and queue snapshot

**Files:** Modify `core/src/main/kotlin/dev/forma/core/settings/SettingCatalog.kt`, `app/src/main/kotlin/dev/forma/app/settings/SettingsRepository.kt`, `app/src/main/kotlin/dev/forma/app/TranscodeViewModel.kt`, `app/src/main/kotlin/dev/forma/app/MainActivity.kt`, `app/src/main/kotlin/dev/forma/app/ui/settings/SettingsPanel.kt`, `app/src/main/kotlin/dev/forma/app/ui/FormaScreen.kt`; create `app/src/main/kotlin/dev/forma/app/settings/TreeGrantStore.kt`; test `testing/settings/junit/SettingsContractTest.kt`, `testing/settings/android/SettingsScreenTest.kt`.

**Interfaces:** `TreeGrantStore.choose(uri,label)` takes a persistable writable grant; `TreeGrantStore.validate(uri)` reports missing access. `TranscodeViewModel` resolves the setting and passes one `SaveDestination` per new AV/image/movie queue entry.

- [ ] Add failing setting and UI tests for default folders, persistent custom label, grant revocation, destination line on Convert/Queue, and independent queued snapshots.
- [ ] Run the targeted `:core:test`, `:app:testLabUnitTest`, and Compose test commands from `app/build.gradle.kts`; confirm the new cases fail.
- [ ] Implement the picker, persisted grant record, enqueue snapshot, and concise labels; leave the existing Save another copy action available.
- [ ] Rerun targeted tests; commit the setting/UI slice.

### Task 3: Recoverable Android publication

**Files:** Create `app/src/main/kotlin/dev/forma/app/data/PublicOutputPublisher.kt`, `app/src/main/kotlin/dev/forma/app/data/DeliveryWorker.kt`; modify `app/src/main/kotlin/dev/forma/app/service/TranscodeService.kt`, `app/src/main/kotlin/dev/forma/app/FormaApplication.kt`, `app/src/main/AndroidManifest.xml`; test `testing/app/device/dev/forma/app/PublicOutputDeviceTest.kt`.

**Interfaces:** `PublicOutputPublisher.publish(entry,privateFile)` returns a verified URI/name/byte count/digest; `DeliveryWorker.resumePending()` reconciles journaled work and `retry(id)` restarts a failed copy. Only the publisher creates/removes its own public artifacts.

- [ ] Add failing adapter/device tests for MediaStore pending publication, SAF provider filename, interruption/restart at each journal boundary, collision, revoked grant, missing storage, checksum mismatch, cancellation, and API 26–28 permission denial.
- [ ] Run the relevant device suite and confirm the new cases fail for missing delivery behavior.
- [ ] Implement bounded copy, byte/digest verification, durable intent/URI receipts, owned-partial cleanup, startup recovery, and permission/media-scan compatibility; never hold the FFmpeg native slot while copying.
- [ ] Rerun the adapter/device cases; commit the publisher slice.

### Task 4: Finished card, retry, and qualification

**Files:** Modify `app/src/main/kotlin/dev/forma/app/ui/FormaScreen.kt`, `app/src/main/kotlin/dev/forma/app/TranscodeViewModel.kt`, `app/src/main/kotlin/dev/forma/app/MainActivity.kt`; test `testing/app/device/dev/forma/app/FormaScreenTest.kt`, `testing/app/device/dev/forma/app/PublicOutputDeviceTest.kt`.

**Interfaces:** Finished shows `Saved to …`, `Saving to …`, `Converted; save failed`, or `Private in Forma`; actions are `View saved file`, `Share`, `Save another copy`, and `Retry save` where applicable.

- [ ] Add failing Compose tests for all four states and actions, including a failed delivery that stays in Finished.
- [ ] Run the targeted Compose test; confirm failure, implement the state labels and actions, then rerun it.
- [ ] Run `tools/check-core.sh`, `tools/check-android-readiness.sh`, `./gradlew :core:test :engine-ffmpeg:testDebugUnitTest :app:testLabUnitTest :app:assembleLab :app:lintLab`, emulator Compose checks, native-payload validation, and the physical video/audio/image save matrix on a native-enabled build. Record hashes, collection/URI, grant-retry behavior, and API 26–28 result separately.
- [ ] Commit verification notes and code, open the save-destinations PR as Draft, and mark Ready only after all required gates pass.
