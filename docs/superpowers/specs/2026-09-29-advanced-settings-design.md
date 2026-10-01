# Forma advanced settings — native menu design

Date: 2026-09-29. Inspected base: `89ceb3b362872f193a54feacc99225c80e77f176`.

**Status: design and implementation handoff, not a shipped settings screen.** This change does not wire preferences, change encoders, implement battery monitoring, or claim Android/device tests have run. Proposed defaults below apply to newly created preferences after explicit migration; they must not rewrite existing jobs.

Read the [64-option catalog](../../advanced-settings-catalog.md), then the [implementation/test plan](../plans/2026-09-29-advanced-settings.md). Existing requirements in `AGENTS.md`, `docs/AGENT-START.md`, `docs/android-handoff.md`, `docs/android-acceleration.md`, and `docs/native-ux.md` remain authoritative.

## 1. Intended experience

The user wants powerful video, audio, appearance, execution and device-protection controls without turning the home screen into a control panel. Keep Select media, visible direct-media URL input, the 10 MB upload goal, bracket trimming and the expandable left shelf. Do not create a second editor, an HTML product, or a global Advanced mode that changes behavior. Settings are native Kotlin/Compose and in-process FFmpeg remains the export owner.

Three layouts were considered:

| Approach | Benefit | Cost | Decision |
| --- | --- | --- | --- |
| One enormous accordion | Everything technically on one page | Long scroll, weak discovery, confusing global versus job controls | Reject |
| Horizontal tabs for every category | Familiar on desktop | Eight tabs overflow phones and hide categories | Reject |
| Searchable category list with adaptive detail pane | Compact on phones; fast switching on tablets; matches the left shelf | Requires deliberate navigation and scope state | **Use** |

Settings uses the same field definitions as contextual More settings. They are two entry points into the same values, not independent copies of an editor.

## 2. Navigation and screen composition

### Entry points

- **Shelf → Settings:** opens persistent app defaults. Works without imported media.
- **Editor → More settings:** opens job-scoped video/audio/export/engine controls for the current editor draft. Never saves global defaults implicitly.
- **Editor → Overrides (n):** opens only explicit overrides for that draft. Always visible when the count is nonzero, even if advanced sections are collapsed.
- **Queue row → Details → Settings:** read-only snapshot and actual execution route. Editing requires Duplicate into editor or an explicit queued-job edit while idle; never change running work in place.
- **Shelf → Advanced:** retain the existing contextual jump into More settings; do not repurpose it silently into global preferences.

### Menus

| Menu | Count | Summary example |
| --- | ---: | --- |
| Appearance & interaction | 8 | System theme · Comfortable |
| Video & picture | 10 | H.264 · Source frame rate |
| Audio | 8 | AAC · 128 kb/s · Source channels |
| Encoders & performance | 8 | Auto encode · CPU filters |
| Battery & temperature | 10 | Wait below 20% when not charging |
| Export & defaults | 8 | Upload size · 10 MB · MP4 |
| Queue & notifications | 6 | Pause on errors · Screen may sleep |
| Privacy & diagnostics | 6 | Redacted reports · 7-day history |
| **Total** | **64** | Actions and informational rows are not counted |

### Phone overview

```text
┌──────────────────────────────────────┐
│ ‹  Settings                       ⋮  │
│ App defaults                         │
│ [ Search settings…                ]  │
│ [ All ] [ Changed 3 ] [ Planned ]     │
│                                      │
│ Appearance & interaction           › │
│ System theme · Comfortable           │
│ Video & picture                    › │
│ H.264 · Source frame rate             │
│ Audio                              › │
│ AAC · 128 kb/s                        │
│ Encoders & performance          1  › │
│ Software CPU · Changed               │
│ Battery & temperature           2  › │
│ Wait below 25% · Not charging         │
│ Export & defaults                  › │
│ Queue & notifications              › │
│ Privacy & diagnostics              › │
└──────────────────────────────────────┘
```

No large dashboard cards, duplicate toolbar buttons, permanent explanation banners or disabled export footer here. Category summaries use effective saved defaults; dirty drafts say Preview. A change count is text, not just a colored dot.

### Phone detail and job override

```text
┌──────────────────────────────────────┐
│ ‹  Encoders & performance             │
│ Editing: This job  [Change scope ›]   │
│                                      │
│ Encoding backend                Auto ›│
│ App default: Auto                    │
│                                      │
│ Video decoder               Software ›│
│ Default · Hardware route planned      │
│                                      │
│ Filter processing            CPU     ›│
│ Default                              │
│                                      │
│ CPU thread request          2        ›│
│ This job override · Default: Auto     │
│ [ Use inherited value ]              │
│                                      │
│ Effective export                     │
│ Decode CPU → Filters CPU → Encode CPU│
│ Reason: constant-quality request      │
│ Not yet device-qualified              │
│                                      │
│ [Discard]           [Apply to job]    │
└──────────────────────────────────────┘
```

The effective export card appears only for an inspected job, not as invented device status on an empty Settings screen. Before inspection use: **“Auto — decided after source inspection.”** Show requested policy, prepared route and actual executed route separately. A provider being present is not proof that the job ran on it.

### Tablet, landscape and foldable

Use an adaptive list-detail arrangement: approximately 240–280 dp category pane and a readable detail pane, only when both fit with current font scale and fold posture. Otherwise navigate one pane at a time. Keep the detail column around 640 dp maximum; do not stretch switches across a desktop-width screen. On compact windows the shelf overlays content. Preserve selected category, query, scroll and draft across rotation; Back first closes a choice sheet/search, then returns from detail to overview.

Android documents settings as a valid list-detail use case [1,2]. These dimensions are Forma design targets, not mandatory Android breakpoints; use the repository-compatible Compose/adaptive APIs rather than forcing a dependency upgrade.

## 3. Visual and interaction specification

Use existing `FormaTheme` mint-accent surfaces as the starting point, Material typography and neutral tonal grouping. Prefer thin dividers and small section labels over nested outlined cards. Proposed spacing: 16 dp page padding, 12 dp group spacing, 52 dp minimum actionable rows/buttons; multi-line rows may grow. Compact density reduces excess spacing, **not** touch targets or system text scale. Primary titles approximately 20–24 sp, labels 16 sp, secondary text 12–14 sp. Never force fixed row heights under large fonts.

A setting row has: concise label, current value, provenance/status, and an optional one-line explanation. Opening it provides longer help, choices, dependency explanation and Reset. Use switches only for independent booleans; segmented buttons only for two or three short choices; bottom-sheet radio lists for longer enums; numeric fields plus coarse slider/tap nudges for ranges. A control that changes multiple values must preview those changes before Apply.

Choice sheets have a title, current selection, recommended choice, search when there are more than eight entries, and unavailable choices with a readable reason. Keyboard arrows move focus, Enter selects, Escape/Back dismisses without applying an uncommitted choice. Do not depend on hover, long press, color alone or swipe-only controls. Dangerous bulk operations never auto-apply on keyboard focus.

Search matches labels, help, stable IDs and synonyms such as CPU, GPU, codec, battery, theme and file size. Results include category breadcrumbs, current value and status. Searching reveals Planned entries explicitly; ordinary category browsing separates them under a collapsed **Planned / unavailable** subsection. Changed filters persisted departures from factory defaults globally and explicit overrides in job scope. A hidden value never stops applying merely because its row was filtered out.

TalkBack must announce label, current value, provenance, availability and action without duplicate switch nodes. Disabled controls still expose why they are unavailable through a focusable help affordance. Respect system text scaling, reduced motion, keyboard/insets and RTL. Meet the Android 48 dp minimum [3]; retain Forma's 52 dp actual bounds. Require tested 4.5:1 small-text and 3:1 large-text/essential-control contrast in every theme [4]. Accent color alone cannot communicate a warning. Pure-black mode is an appearance choice, not a guaranteed energy-saving claim.

### Editing, saving and reset

Maintain a draft for the entire Settings visit. Show a sticky action bar only while dirty: **Discard / Save defaults** or **Discard / Apply to job**. Theme/accent changes may preview live, but Discard restores the saved theme. Cross-category edits remain in that one draft. Back with unsaved changes offers Save/Discard/Keep editing. Invalid values disable Save and link to each error. Do not lose a draft on rotation.

Per-setting Reset removes the value from the current layer; it is not “set to whatever the inherited value happens to be today.” Reset section and Reset all show exactly the fields/layers affected. Reset all never deletes files, queue entries, URI grants or diagnostics. Import/export defaults and Save as preset are separate overflow actions with a validated preview; they are not counted as extra settings. Import cannot activate device-specific component IDs or paths from another phone without validation.

## 4. Defaults, presets and obvious overrides

For media and eligible execution defaults, resolve:

```text
factory defaults → saved app defaults → explicitly selected preset → this job overrides
                                                           ↓
                           validate against source, capabilities and hard constraints
                                                           ↓
                         immutable queued snapshot + provenance + settings revision
```

Selecting media does not secretly select a new preset. Selecting a preset previews every changed value and asks **Keep my job overrides** or **Replace listed overrides**. Presets cannot change theme, storage permissions, safety policy or privacy defaults. Distinguish **Use inherited value**, explicit **Auto**, zero, false and unset. For example a job can explicitly request Auto even if the saved default is Software CPU. Preserve an explicit override even if it temporarily equals its parent, because its future inheritance behavior differs.

Global UI/privacy changes apply after Save. Global media defaults affect new drafts, not existing drafts or queued/running work. An existing draft shows “Defaults changed — Review” rather than silently rebasing. Engine/media settings freeze when a job is queued. Safety-policy changes use the separate live policy contract below and must never rewrite that media snapshot. Every prepared attempt rechecks capabilities, including after size-fit retry changes.

In job scope show **Use app/preset value (Auto)**, then explicit choices. Near Convert show a compact summary such as **10 MB · H.264 · Auto · 2 overrides**. A sticky custom-setting indicator survives navigation/collapse. The export details screen lists requested value, origin, effective value and reason when they differ. Unsupported explicit requirements cause validation failure, not a silently ignored override. A deliberate one-run battery exception is a visible action with a confirmation and expiry, not a hidden mutation of defaults.

## 5. Battery and thermal behavior

Proposed defaults: battery threshold 20%; only when not charging; low-battery action **Finish current file, then wait**; resume margin +5 percentage points; automatic continuation when eligible; charging-only start Off. These are configurable product choices, not manufacturer safety limits. Read battery status and level using Android battery APIs and runtime events while work is relevant [5]. Never poll in a permanent loop or wake the app solely to repaint Settings.

Treat **actively charging**, **plugged but not charging**, **discharging**, **full while plugged**, and **unknown** separately. Charging/full-while-plugged satisfies the exemption; a cable alone does not. At exactly the configured threshold, the low condition is active. An invalid level/scale produces Unknown, not 0% or a division error. Charging-only work must wait on Unknown; ordinary work warns that protection cannot currently be evaluated rather than claiming protection is active.

Actions must be precisely named:

| Action | Running attempt | Next job/size retry | Later behavior |
| --- | --- | --- | --- |
| Warn only | Continues | May start | One deduplicated warning per episode |
| Finish current file, then wait | Finishes the current attempt through verification/publication if valid | Blocked, including another size-fit attempt | Continue queued work after eligibility |
| Stop current file and wait | Cooperative cancellation; wait for native cleanup | Blocked | Restart affected file from staged original, never append to partial output |
| Ignore optional low-battery guard | Continues | May start | Does not disable critical thermal/OS handling |

“Finish current file” does not authorize unlimited upload-size retries. If its current attempt is oversized, preserve the immutable job and enter Waiting before another attempt; never publish the oversized file. The UI says “Attempt ended; waiting before size retry.” User Stop/Cancel always wins over an automatic continuation.

Use explicit states: Running, Finishing current, Stopping safely, Waiting for battery, Waiting to cool, Waiting for user, Interrupted and Restart required. Do not label a cancelled FFmpeg process as Paused at 63%. If actual checkpoint resume is implemented later, it needs a separate proven format and test suite.

A waiting episode clears when power is eligible or battery reaches threshold + margin. Debounce recovery for 10 continuous seconds, and thermal recovery below the chosen severity for 30 continuous seconds; keep timers in the worker/policy layer, not the Activity. Debounce triggers eligibility only: cleanup must have finished and the run slot must be free before another attempt starts. A manual cancellation clears pending automatic continuation. Full process death marks in-flight output interrupted and requires user review; it is not a license to restart work on boot.

The battery and charging-only conditions are independent: charging-only still blocks an unplugged phone at 90%. Unplug action applies when a running job transitions away from eligible power; Continue still honors the low-battery guard. Safety policies are global/live and versioned separately from job media settings. Saving a relaxation applies only after explicit Save; a queued job uses the latest safety policy at each boundary. A confirmed “Continue this file once” may bypass the optional low-battery guard only, never critical thermal/OS limits, and expires at attempt end or process death. Log policy version and bypass reason without storing private media names.

Use `PowerManager` thermal status on supported Android versions; the listener was added at API 29 [6]. Do not infer CPU/GPU temperature from battery temperature or display an invented Celsius cutoff. Below supported APIs show thermal status Unavailable and keep the documented bounded execution path. At the default Severe threshold request cancellation/wait; Critical or higher always requests cancellation and blocks starts regardless of the optional action. Never suppress OS thermal throttling or shutdown. **No cross-device “safe CPU temperature” claim is made.**

When waiting, release the wake lock and media-processing service after native cleanup unless short-lived legitimate work remains. Automatic continuation is best-effort only while process/lifecycle rules permit it; otherwise present **Conditions ready — tap Resume**. Do not hold a foreground service indefinitely waiting for charge, misuse WorkManager as an execution owner, or bypass Android media-processing timeouts [7].

## 6. Honest backend controls and unimplemented options

“Hardware video encoder (MediaCodec)” is the accurate label; “GPU encoder” is not a universal synonym. Offer Auto, Software CPU and Hardware required. Keep decode, filters and encode separate. Software CPU on the encode row does not claim the decoder/GPU is disabled. Provide the explicit convenience action **Use CPU for the whole pipeline**, previewing encode=Software, decode=Software and filters=CPU as three separate overrides.

Hardware required fails when the exact requested configuration cannot be prepared. Auto may fall back only under its documented policy and reports that decision. Manual component selection displays actual enumerated components, MIME/profile/size support, and known qualification evidence; never manufacture a device-specific list. Android hardware flags are vendor reports, not execution proof [8]. Missing native libraries, unsupported inputs, cancellation, storage failure and oversized results are not interchangeable “try CPU” reasons. CPU preset/CRF arguments must not reach MediaCodec.

For every row keep availability distinct from value: **Working**, **Planned**, **Unavailable on this device**, or **Blocked by another choice**. This PR leaves all new preference integration unimplemented, even where a related job control already exists. Default product builds must not persist a planned selection, show “Saved,” and then ignore it. A future developer-only design preview can permit temporary in-memory exploration, explicitly labeled **Preview — does not affect exports**, without mutating real preferences. No preview/test payload belongs in release output.

Conditional rows stay understandable: CRF only for supported software constant-quality mode; bitrate for manual bitrate mode; normalization targets only when normalization is enabled; component overrides only for a hardware-capable policy. Preserve blocked custom values in the draft but mark them inactive. Reject incompatible explicit requirements at Apply; do not coerce them silently.

## 7. Boundaries and review acceptance

Reuse the current `app`, `core`, `engine-ffmpeg`, `RunCoordinator`, `FfmpegBridge.prepare`, touch controls and versioned queue. Introduce small typed preference/resolver/power-policy components, not a generic runtime plugin framework. Do not repurpose `Settings` into a bag containing theme, private paths, callbacks and codec options. Store app preferences transactionally in an Android adapter; keep resolution and power decisions Android-free. Tests and fixtures stay under an external `testing/settings/` tree and are explicitly referenced only from test source sets.

This design is accepted for implementation when every catalog item has one stable ID, a proposed default, a defined scope, a dependency/availability rule and a corresponding acceptance group; every visible control can explain its current effect; the source-first workflow remains unchanged; and no production claim is based on a disabled placeholder. Implementation is accepted only after the gates in the linked plan, with Android build, UI, native exports and physical-device power behavior reported separately.

## Primary references checked 2026-09-29

1. [Android settings patterns](https://developer.android.com/design/ui/mobile/guides/patterns/settings)
2. [Adaptive list-detail](https://developer.android.com/develop/adaptive-apps/guides/list-detail)
3. [Accessible controls](https://developer.android.com/guide/topics/ui/accessibility/apps.html)
4. [Core app quality / contrast](https://developer.android.com/develop/adaptive-apps/quality-guidelines/core-app-quality)
5. [Battery level and charging state](https://developer.android.com/training/monitoring-device-state/battery-monitoring)
6. [Thermal status listener](https://developer.android.com/reference/android/os/PowerManager.OnThermalStatusChangedListener)
7. [Foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
8. [MediaCodecInfo](https://developer.android.com/reference/android/media/MediaCodecInfo)
