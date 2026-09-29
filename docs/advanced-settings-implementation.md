# Advanced settings: consumers and qualification boundaries

Updated 2026-09-29 for reviewed head `a113131`. The native registry has **64 typed rows: 33 implemented,
31 Planned**. Implementation means there is a production consumer, not that every
codec, device, accessibility configuration or physical safety episode is qualified.
The original [catalog](advanced-settings-catalog.md), [design](superpowers/specs/2026-09-29-advanced-settings-design.md)
and [plan](superpowers/plans/2026-09-29-advanced-settings.md) remain the target;
their design-only status paragraphs are historical. Earlier physical evidence is
revision-bound in [device validation](advanced-settings-device-validation.md).

## Row-to-consumer map

| Rows | Consumer | Regression / boundary |
| --- | --- | --- |
| `ui.theme`, `ui.accent`, `ui.density`, `ui.technical_details` (4) | `FormaTheme`, `SettingsPanel` | Native theme/choice sheets; full theme contrast, RTL, TalkBack and 200% font qualification remain device gates |
| `video.codec`, `video.rate_control`, `video.quality`, `video.bitrate_kbps`, `video.max_height`, `video.frame_rate`, `video.deinterlace` (7) | `NativePreferences.apply` → immutable `Settings` → existing planner/executor | Explicit hardware validates without coercion; fractional rates and detected deinterlacing are disabled choices |
| `audio.codec`, `audio.bitrate_kbps`, `audio.channels` (3) | Native adapter and PR3 audio graph | Graph, tracks, timing and output policy preserved; MP3/copy choices remain unavailable |
| `engine.encode_backend`, `engine.decode_backend`, `engine.filter_backend`, `export.container` (4) | Native adapter/preparation and merged PR6 executor | H.264/H.265 Auto remains explicit through bounded runtime trials and same-codec fallback; VP9/AV1 need an explicit backend. Decode/filter accept CPU only; combined-device qualification is separate |
| `power.low_action`, `power.low_percent`, `power.only_when_not_charging`, `power.charging_only`, `power.resume_margin`, `power.unplug_action`, `power.thermal_action`, `power.thermal_threshold` (8) | `AndroidPowerMonitor` → `PowerRuntime` → `TranscodeService` / `RunCoordinator` | Synchronous refresh before every claim; cancellation holds native ownership until cleanup; user Stop beats power requeue. Physical unplug/thermal/service qualification is separate |
| `queue.auto_start_added` | `TranscodeViewModel.enqueue` | Manual Add default; Convert explicitly starts. Auto Add starts only with an idle run slot, and retains queue/capability/power checks |
| `queue.on_error` | Service queue boundary | Default waits for review after failure; explicit Continue advances. Stop/cancellation remains independent |
| `queue.interrupted_prompt` | `AppGraph.initialize` after recovery | Review message or quiet retention; never auto-resumes partial output or starts at boot |
| `queue.notification_detail` | Service notification formatter | Minimal hides source names; filename detail requires explicit selection; progress and mandatory FGS remain |
| `queue.completion_sound` | Separate completion notification channel | Off sets silent; Follow channel obeys Android channel preferences/permission/DND, once per finished batch |
| `queue.keep_screen_on` | Visible Activity lifecycle effect | Encoding only, removed on background/disposal/idle; distinct from worker wake lock. Preview choice is unavailable until playback ownership is integrated |
| `privacy.history_days` | Atomic queue pruning during process initialization | Default 7 days; removes only dated completed records and their managed outputs. Save previews affected items when shortening; applies next launch |

`implemented` combines the 18 original native/UI bindings, eight live power
bindings and seven queue/privacy bindings. Planned values cannot save as active
preferences; unavailable choices within implemented rows also fail validation.

## Persisted provenance and migration

`Editor` and `JobSpec` carry `MediaPreferences`: frozen app-media defaults and
revision, the selected preset layer/name, explicit job overrides, and a legacy
snapshot marker. Live safety/privacy/appearance values are excluded from a new
job's app-media layer. Saving defaults affects a fresh untouched editor once;
existing drafts, retries and queued/running settings are not rebased.

Reset removes a value from its layer. Reset/reopen therefore returns to the frozen
preset/app/factory parent, rather than turning the inherited concrete value back
into an override. Explicit Auto, false, zero, and values equal to their parent
remain explicit. The compact Overrides chip survives collapsed advanced controls;
Defaults & overrides and queue Settings snapshot show counts and origins.
Preset selection previews changed fields and offers Keep my overrides or Replace
overrides. Audio undo/redo restores channel inheritance alongside the graph.
Settings dialog layers/drafts are saveable through Activity recreation.

Queue schema **3** stores these layers plus a nullable completion timestamp.
Its decoder requires all saved writer fields, distinguishing an absent key from
explicit null, and rejects fractional/coerced integers, wrong primitive types and
unknown queue fields before any rewrite. Known saved audio nodes require their
complete parameter sets and EQ-band fields; sparse external recipes retain their
separate defaulting behavior. Future audio nodes/fields remain opaque and block
active processing until explicit bypass/removal.
Schema 1 preserves its concrete settings with a neutral audio graph; schema 2
preserves PR3 graph, unknown effect payloads, source stream facts and rational
timing. Both become explicit legacy snapshots, including H264_HW/H265_HW required
hardware. Undated legacy completed entries remain retained because their age
cannot be inferred honestly. New timestamped completed entries use wall-clock
age; future timestamps are retained rather than prematurely deleted.

The previous planner accepts some values outside the newer menu lists, for example
33 fps, 384 kb/s and a 1000-pixel height. A **legacy media snapshot only** decoder
preserves those values and their explicit origin; normal app preference decoding
and saving remain strict. Applying edits to an unsupported legacy menu value
requires choosing/resetting it explicitly; the queue executor keeps its concrete
old snapshot. Future/corrupt schemas and incompatible preference storage surface
an error and preserve the original file.

## Storage, ownership and privacy

App preferences use bounded UTF-8, atomic replacement, process serialization and
revision conflict checks. Queue schema upgrades use the existing AtomicFile owner.
A failed write does not publish a newer settings/queue revision.

History pruning runs at initialization before any native/preview reader can start;
it is never called by a live settings save. Recoverable, interrupted, active,
failed/cancelled and undated legacy records are not retention candidates.
Pruning follows durable queue replacement; only the selected completed UUIDs'
private output files are removed. Referenced private imports remain protected.
Unreadable app preferences skip history pruning. Source display names never form
working paths: managed paths require exact UUIDs; revoked content grants fail with
reselection guidance; existing job outputs are never overwritten.

Export verification checks each retained audio/video stream's start and end,
intended offsets and decoded video frame count after strict full decode. Explicit
CFR counts allow floor/ceiling only for fractional frame windows; format duration
alone cannot certify a complete video track. Clockless WAV origins are observed
from native packet timestamps. The existing audio DSP/rate/sample-count and
normalization verification remains in force.

Publication waits for durable Completed state after renaming a verified new
artifact. Any failed/cancelled completion callback removes that new output;
Stop after durable completion keeps it. Save copy checks that the destination
is observably empty before opening it for writing or deleting it on failure.
Nonempty originals/aliases and unreadable destinations are rejected, including
after Share has staged a private source copy.

There is no persistent product diagnostic-log collector or shared report exporter
on this branch. `diagnostics.level`, `diagnostics.retention_days` and
`diagnostics.include_filenames` remain Planned, so a Debug setting cannot persist
or pretend to collect/redact a report. The requested session-only Debug, 10 MiB
cap, retention, exact-content preview and token/path redaction need that backend.
`privacy.temporary_retention` remains Planned: the existing executor discards its
working directory after native cleanup and startup cleans interrupted work;
there is no consumer retaining completed work for 24 hours/7 days.
`privacy.metered_downloads` stays Planned until a downloader exists.

Other deferred media/appearance/backend rows remain Planned. In particular this
combined tree retains PR6's per-job byte fitting and same-codec automatic fallback.
The separate Settings rows for export goal/limit/attempt defaults and fallback policy
remain Planned until they have their own bindings. A device component selector,
HDR transformation and a metadata allowlist remain deferred. Integrate their owners
using deliberate model/schema changes; do not silently drop fields.

## Verification and remaining gates

The removable `testing/settings/` tree now includes pure provenance/consumer
contracts, schema-1/schema-2 fixtures, queue codec/audio-history host regressions,
Compose equal-choice/reset checks and disposable real-Android queue retention
checks. `settingsTests=false` removes all external settings source/resource
references; `audioTests=false` removes the PR3 instrumentation reference.
See [test commands and boundaries](../testing/settings/README.md).

At `a113131`, **163 JVM tests passed** (core 98, engine 20, app 45), with
native-enabled debug/test assembly, lint, pinned-native API compilation and both
debug APK payload/16 KB alignment checks. Fourteen desktop media cases cover
per-stream truncation, CFR counts, offsets, trims, Matroska and real WAV exports.
The parent also built the unsigned minified release with `testing/` physically
absent and both test references disabled; native packaging and structural DEX
exclusion checks passed. Exact artifacts are recorded in
[device validation](advanced-settings-device-validation.md). Local compilation
of instrumentation does **not** count as executing its device tests.
The parent session owns phone installation and qualification of this exact head,
including the new provenance/retention tests, notification privacy/channel behavior,
visible screen-awake lifecycle, real native power cancellation and service limits.
Earlier device reports do not qualify these changes automatically. Full process
death recovery of the whole unsaved source/audio editor, broad accessibility and
other-vendor codec qualification remain separate gates.

## Preserved upstream live-power handoff

Upstream `00a5a807` makes unreadable saved power policy fail closed: no new queue
claim substitutes factory safeguards; an active run drains for Settings review.
Mandatory Critical thermal cancellation and its recovery latch remain in force
even while preference storage is unreadable. The last readable thermal recovery
threshold is retained; a cold unreadable policy uses the conservative Moderate
recovery boundary only for mandatory Critical safety, while all starts remain blocked. Automatic background continuation
and Battery Saver thread-budget integration remain Planned.

`AndroidPowerMonitor` treats framework callbacks as invalidations and rereads
protected sticky/system state; thermal telemetry remains API29+ severity, not a
guessed temperature. Runtime recovery uses monotonic time and 10-second battery/
unplug and 30-second thermal intervals. Power-stopped attempts clean native files
then requeue from the original; explicit user Stop overrides power requeue.

Upstream historical evidence is retained: `7fcda00` workflow `36571847155` passed
wrapper/no-native unit/build/lint/emulator gates; `cf87fc2` workflow `36572511109`
reproduced the unreadable-policy failure before its fix. These are separate from
this session's source-built native packaging and the parent phone qualification.

The earlier merged-head gate recorded 142 JVM tests plus core CLI39,
settings/consumer/provenance/power suites, readiness13 Python and UX ownership/
race checks. That historical count is superseded by the 163-test checkpoint
above. Settings scenario reports use synthetic power samples and explicitly
report `nativeExecution=false`; their passing assertions do not certify physical
battery/thermal events or native encoder execution. Final phone tests for
`a113131` remain pending in the parent session.
