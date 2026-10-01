# Queue and Finished Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Separate in-flight jobs from terminal results and expose each job's encoder snapshot.

**Architecture:** Add a core state classifier and use it for two Compose destinations. Preserve the repository's single persisted job list; the change is presentational. Existing native attempt events remain the source of truth for the live backend.

**Tech Stack:** Kotlin, Jetpack Compose, host Kotlin checks, Android Gradle.

**Spec:** `docs/superpowers/specs/2026-09-29-queue-finished.md`

## Global Constraints

- Queue snapshots stay immutable.
- Show actual hardware only from native attempt reports.
- Keep all tests and temporary files in the workspace.

## Review Focus

- Every nonterminal state appears in Queue.
- Every terminal state appears in Finished, including failures and interruptions.
- Retry creates a new queue entry without relabeling the old result.
- A saved H.264 software job keeps that label after the editor changes to H.265 device.
- The live backend text follows the attempt report, not the requested encoder.

### Task 1: State partition

**Files:** `core/src/main/kotlin/dev/forma/core/QueueLists.kt`, `testing/core/unit/dev/forma/core/CoreChecks.kt`

- [ ] Add failing host checks for all eight `JobState` values.
- [ ] Implement `QueueLists.inQueue` and `QueueLists.inFinished`.
- [ ] Run `tools/check-core.sh` until passing.

### Task 2: Compose destinations and truthful labels

**Files:** `app/src/main/kotlin/dev/forma/app/ui/FormaScreen.kt`

- [ ] Add Queue and Finished navigation items and destination content using the classifier.
- [ ] Show the saved encoder in each AV card and the native attempt route in active progress.
- [ ] Build and lint the Android app; inspect the two pages on the lab package without disturbing the active normal-package conversion.
