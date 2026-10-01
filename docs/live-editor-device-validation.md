# Focused editor and rendered-preview validation

The lab APK was tested on the OnePlus 9 Pro (Android 16) and an API 28 emulator.
The lab package is isolated from the user's normal Forma installation. The APK
checked on the phone was SHA-256
`b925ee79cd1ea6cbcc61af2135bc5096fcab8227686526240098b74ad26a7623`.

## Evidence

- Host: `:core:test`, `:engine-ffmpeg:testDebugUnitTest`,
  `:app:testLabUnitTest`, `:app:assembleLab`, and `:app:lintLab` passed. The
  readiness script's Kotlin and 20 Python checks passed. The APK's FFmpeg
  payload and 16 KB ZIP/ELF alignment passed `verify_android_native.py` and
  `zipalign -c -P 16`.
- Phone: focused video/image routes, video and image crop gestures, draft
  atomic-write recovery, quick-frame source/revision invalidation, rotated
  metadata frame extraction, preview cancellation, private-file cleanup, and
  Media3 play/pause passed. The native preview test decoded a 60 × 80 output
  from an 80 × 60 crop rotated clockwise, then sampled red/green pixels at
  opposite ends to prove spatial orientation. The native geometry test also
  checked a 90-degree metadata source. Review repairs additionally passed
  foreground-conversion preemption, extractor cleanup after ViewModel scope
  cancellation, save-before-exit completion, and an odd-dimension rotated
  crop gesture, one advanced-slider undo commit per drag, and rollback of an
  unfinished crop before Save or tool switching (15 focused tests). The strict queued-export editor smoke
  passed all five cases: neutral, speed, crop/color, fades, and audio-only.
- Emulator (API 28): focused editor and quick-frame tests passed
  (10 tests). The phone's repeated cached-frame crop gesture produced one undo
  commit per drag and a 14.8 ms p95 state-to-Compose update across 12 drags.
  This measures Compose commit latency, not display-photon latency.

The quick video frame is nearest-decodable navigation feedback. Android may
apply source rotation during extraction; the controller records whether its
bitmap is already upright. HDR quick-frame color is labelled approximate.
Rendered preview uses a private five-second H.264/AAC MP4 at up to 720p and
30 fps. It reserves an idle preview slot that foreground conversion preempts;
conversion waits for native preview cleanup. The shared native bridge mutex
also prevents overlapping native calls. The player releases
its Media3 instance when the editor leaves; superseded files are removed, and
startup cleanup removes any abandoned preview file.

The existing export validator remains the authority for complete output,
timing, frame/sample counts, and publication. A rendered preview is not a
Finished job and does not grant final-export verification. HDR final export
continues to depend on the app's separately qualified color pipeline.
