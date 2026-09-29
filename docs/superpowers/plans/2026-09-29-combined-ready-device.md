# Combined Ready PR Device Build

> **For agentic workers:** Use superpowers:executing-plans to integrate and verify the approved work inline. Preserve evidence in the workspace.

**Goal:** Install one Forma app containing every ready PR and remove the obsolete variants from the target phone.

**Architecture:** Integrate the exact reviewed heads of PR2, PR3, PR4, PR6 and PR8 in an isolated branch. Preserve each feature's execution and verification contracts, reconcile shared models and saved queue records, and use the image-capable native bundle. Replace the normal application in place after qualification and archive private data before removing lab variants.

**Tech Stack:** Kotlin, Compose, Android API26–36, FFmpegKitNext n9.0.1, arm64.

**Spec:** User instruction: “remove older versions of forma off the device please. Keep a build with all ready marked PRs implemented.” Approved PR designs and qualification notes remain authoritative.

## Global Constraints

- Include PR2 ea9f936, PR3 f837c85, PR4 9b0fa7f, PR6 d0f9409 and PR8 ae003a7; exclude blocked PR5.
- Keep source/native licensing pins, API26 minimum, SDK36 and compact native UI.
- Original media and existing normal-app data must survive the update.
- All native exports must remain verified, bounded and foreground-service owned.
- Test media, backups, native bundles and build evidence stay in this workspace; no /tmp.
- Keep main and the original PR branches intact.

## Review Focus

- Saved schema3 image/movie collisions must preserve intent or reject unsupported records explicitly.
- Clip/movie and audio/image jobs must retain distinct dispatch and immutable settings.
- Advanced power/cancellation rules must apply to every native worker.
- Byte limits and runtime codec fallback must not drop edits, duration or audio.
- Removing duplicate app packages must preserve existing data in local archives.

## Task 1: Reconcile approved branches

- [ ] Verify baseline and archive exact selected heads.
- [ ] Merge audio, settings, runtime codec and movie branches into the image baseline.
- [ ] Resolve models, queue serialization, worker dispatch, settings and Compose consumers without dropping approved behavior.
- [ ] Add integration regression coverage for the shared queue and dispatch boundaries.
- [ ] Run core/readiness/UX, JVM tests, native APK assembly and lint; commit the integrated result.

## Task 2: Qualify combined native behavior

- [ ] Inspect the actual native payload/alignment and record APK identities.
- [ ] Back up installed app data and use an isolated disposable package for device qualification.
- [ ] Run actual audio, image, movie/clip and runtime-byte-limit exports plus settings/UI/service checks.
- [ ] Fix any observed integration failures with a failing regression and verify the corrected build.
- [ ] Obtain independent review of shared integration boundaries.

## Task 3: Keep one installed app

- [ ] Build the final normal-package app and update it in place.
- [ ] Verify startup and included features on the phone.
- [ ] Uninstall only obsolete Forma lab variants and their test packages after preserving their private data.
- [ ] Verify exactly one Forma application remains and retain the installed APK and build provenance.
