# Native UX and responsive background work

## Product criteria and the changes they caused

A good transcoder is easy to start, honest about what it is doing, interruptible,
and responsive while doing the expensive part. This update changes native Kotlin
and Compose, not the Studio HTML. It builds on e6fb225.

- Source first: the empty home offers Select media, not disabled export buttons,
  codec controls, an empty queue and extension-point documentation.
- Progressive disclosure: choose the result after import; More settings preserves
  values; the left shelf provides Convert, Queue, Advanced and engine information.
  Choices use large bottom-sheet rows, not tiny nested dropdown targets.
- Work stays visible without monopolizing the screen: a compact activity area
  shows the current phase and opens a separate lazy queue. Stop remains actionable
  while file import or queue mutation is busy. Finish current drains only that job.
- Errors persist until dismissed, expose useful details, and never clear a newer
  message through an old snackbar callback. Completion offers Share/Open/Save.
- Layout uses wrapping actions, minimum Material touch targets, keyboard/system
  insets, stable item keys, and state text rather than color-only status.

## Threading and ownership

`RunCoordinator` belongs to AppGraph, not the Activity. A foreground service owns
a numbered run ticket. The slot stays occupied during cancellation and native
NonCancellable cleanup, so rapid taps or a recreated service cannot overlap an old
encode. A destroyed old service cannot cancel a newer ticket. Persisted queue
entries retain existing recovery semantics; no force-stop resurrection or resume
from partial native output is claimed.

The service starts foreground before work, uses the correct service type by API,
handles notification actions, and holds a partial wake lock only while servicing
its run, with a six-hour safety timeout and release in completion/destruction/
timeout paths. The screen can sleep. This is not an exemption from Android's
mediaProcessing allowance or OEM background policy. Device screen-off/Doze tests
are still required. A force-stop or process kill ends execution; restart marks
unfinished jobs interrupted. `onTimeout` stops the service promptly and requests
native cancellation; native cleanup still holds the process-wide slot.

`ManagedFfmpegBridge` serializes capability/probe/prepare/execute native calls off
Main. Cached capabilities can be read while a job encodes; UI recreation does not
launch competing native encoder-listing work. FFmpeg's default CPU thread requests
are replaced by `max(1, min(4, processors - 1))` for video decode/encode and two
threads per filter pipeline. This is a concurrency request, not a reserved CPU or
a hard limit on vendor/x265/SVT auxiliary pools. Benchmark those separately.

Callback publication is capped to one UI update per 250 ms and one ongoing
notification update per second, with immediate job/phase transitions. Intermediate
callbacks are dropped synchronously; there is no coroutine per callback and no
unbounded progress buffer. Only the tiny progress composable collects that flow.
The root editor does not subscribe. UI collection is lifecycle-aware; the worker
is not, so hiding the UI does not stop conversion. Progress is not saved to disk
on every callback; phase transitions still use the existing AtomicFile queue.

Source/settings validation runs on Dispatchers.Default and ignores obsolete
results. Provider access, inspection, copying and output-URI preparation run on
IO. Import/save is separately cancellable from queue actions, and completed
imports are shown incrementally. Cancel stops cooperatively between provider/
extractor operations or 64 KiB copy chunks; a blocked provider call can delay
cancellation, but it does not execute on Main. These file operations remain
ViewModel-scoped, unlike foreground conversions; permanent screen destruction or
process death can cancel them. Large resumable transfers are still a handoff item.

Publishing the verified file and recording COMPLETED now share a non-cancellable
section, preventing a cancellation on dispatcher return from labeling an already
published job cancelled. Rename plus queue fsync is not a cross-filesystem atomic
transaction: abrupt process death between them still uses interrupted recovery.

## Scope preserved

FFmpeg remains required. Hardware choices are visible only when their wrappers
are compiled; selecting one explicitly uses bitrate and a known output fps.
Actual device configuration checks still happen in the existing native bridge.
No speedup, HDR, GPU or NPU support is inferred from a toggle.

The full native upload-byte-target, media-URL, still-image and filmstrip timeline
ports remain in the Android handoff. This update does not represent Studio's
features as already in the APK. The existing native range control now has bracket
handles and coarse tap adjustments; it is not the complete Studio timeline.

## Verification and phone performance acceptance

`tools/check-ux.sh` runs pure policy and coroutine concurrency checks. JUnit also
checks the managed bridge's cache/serialization. Compose tests cover empty-home
minimalism, unchanged advanced settings, cancellation during unrelated busy work,
no-native conversion gating, shelf navigation and progress ownership recomposition.

On a physical arm64 phone with the actual FFmpeg bundle, use a profileable/release
build, not debug UI timing as a speed benchmark. Record Perfetto FrameTimeline or
Macrobenchmark frameTimingMetric while encoding the same representative source.
Compare baseline/candidate with equal output settings and thermal state; report
p50/p95/p99 frame duration and missed frames against the device refresh budget
(16.67 ms at 60 Hz; 8.33 ms at 120 Hz), encoder throughput, peak memory and temperature.
Exercise scrolling/typing/trim gestures, 100–200 queue rows, rotation, font scale,
background/return, screen-off, Stop/drain and repeated start/stop. No universal
"zero lag" or phone performance improvement is claimed by JVM or Compose tests.

Primary design/implementation references, checked 2026-09-28:
- https://developer.android.com/develop/ui/compose/performance/phases
- https://developer.android.com/develop/ui/compose/performance
- https://developer.android.com/topic/architecture/ui-layer/state-production
- https://developer.android.com/guide/topics/ui/accessibility/apps
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices
- https://ffmpeg.org/ffmpeg.html
