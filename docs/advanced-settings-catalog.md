# Advanced settings catalog — 64 options

**Proposal, not an implementation status report.** Native Compose layout, inheritance and battery semantics are defined in the [design](superpowers/specs/2026-09-29-advanced-settings-design.md); execution work and acceptance groups are in the [plan](superpowers/plans/2026-09-29-advanced-settings.md).

These are 64 actual configurable options. Search, reset, import/export preferences, Save as preset, capability reports and CPU-only convenience actions are additional UI actions, not inflated option counts. Bold values are proposed defaults for new preferences. Preserve existing queued jobs and explicitly migrate old stored values instead of replacing them with this table.

## Reading the table

Scopes: **G** = global app preference, applied after Save; **J** = default for new editor drafts plus explicit preset/job override, frozen when queued; **P** = global/live safety policy with a separate version, checked at run boundaries and on power events. P is not allowed inside presets. Mixed G/P effects are stated explicitly. Setting-specific action timing overrides the general scope only where stated.

Foundation: **E** = a related native job control or behavior exists, but this new preference/inheritance integration is still unwired; **N** = new preference/policy work; **D** = deferred capability that must remain Planned until a real adapter and tests exist. **None of these letters means a working new Settings screen.** Unsupported choices must explain their reason and cannot be saved as if effective. Every key requires the common persistence, reset, search and availability tests plus its category acceptance group.

Every J field supports an additional tagged **Use inherited value** selection. This is distinct from an explicit Auto, false, zero or numeric value; it is not repeated in every row. For non-media categories, per-job scope is absent rather than displaying a meaningless disabled scope switch. Default values here describe intent; UI must separately show resolved and actual execution values.

## 1. Appearance & interaction — 8 options · T-UI

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 1 | `ui.theme` — Theme | **System**, Light, Dark, Pure black | G / N | Preview immediately in draft; Save persists, Discard restores. Pure black is appearance, not a battery-life promise. |
| 2 | `ui.accent` — Accent color | **Forma mint**, System dynamic, Blue, Violet, Amber | G / N | System dynamic requires the platform capability; use the selected accessible static fallback if absent and show why. Never change semantic error colors to the accent. |
| 3 | `ui.contrast` — Contrast | **Standard**, High | G / N | Both must meet acceptance contrast; High strengthens separation rather than allowing Standard to be inaccessible. |
| 4 | `ui.density` — Row spacing | **Comfortable**, Compact | G / N | Compact removes surplus padding only. Every actionable target remains at least 52 dp and grows for large fonts. |
| 5 | `ui.motion` — Motion | **Follow system**, Reduced | G / N | Reduced removes decorative transitions, not progress or status. Cannot force motion against an OS accessibility preference. |
| 6 | `ui.haptics` — Touch feedback | **Follow system**, Off | G / N | Respect platform settings; no repeated vibration for slider movement or progress callbacks. |
| 7 | `ui.remember_sections` — Remember expanded sections | **On**, Off | G / N | Saves disclosure state, never resets hidden media settings. Off opens category defaults on the next visit. |
| 8 | `ui.technical_details` — Technical labels | **Contextual**, Always visible | G / N | Contextual keeps component names/units in detail sheets; warnings and provenance remain visible in both modes. |

## 2. Video & picture — 10 options · T-VIDEO

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 9 | `video.codec` — Video codec | **H.264**, H.265, VP9, AV1 | J / E | Decoupled from backend; do not model H.264 CPU and H.264 hardware as different content codecs. Gate by compiled encoder and actual device route. |
| 10 | `video.rate_control` — Manual rate control | **Constant quality**, Average bitrate | J / E | Used only when Export goal is Manual. Upload size owns its bitrate plan; changing this row there offers an explicit switch to Manual instead of silently defeating the size cap. |
| 11 | `video.quality` — Constant quality value | **23**, integer 0–51 for H.264/H.265 or 0–63 for VP9/AV1 | J / E | Software constant-quality only; lower is the requested higher quality. Values are codec-specific, not perceptually equivalent across codecs. Preserve inactive value; never pass CRF to MediaCodec. |
| 12 | `video.bitrate_kbps` — Manual video bitrate | **4000 kb/s**, integer 100–200000 | J / E | Manual average-bitrate mode only; narrow further to supported backend range. Upload-size mode displays its calculated bitrate separately. |
| 13 | `video.max_height` — Resolution ceiling | **Source**, 480, 720, 1080, 1440, 2160, 4320 pixels | J / E | Keep aspect ratio and never enlarge smaller sources. Upload fitting may go below this ceiling; record effective dimensions. |
| 14 | `video.frame_rate` — Frame rate | **Source**, 23.976, 24, 25, 29.97, 30, 50, 59.94, 60, 120 | J / E | Store exact rational rates (24000/1001 etc.), not rounded display decimals. Fractional-rate extension needs tests. Current explicit hardware route still needs a known rate; Source does not waive that gate. |
| 15 | `video.profile` — Codec profile | **Auto**, supported codec-specific profiles | J / N | List only profiles for current codec/bit depth. Revalidate manual profile after codec changes; do not reuse H.264 High as HEVC Main10. |
| 16 | `video.bit_depth` — Output bit depth | **Preserve when supported**, 8-bit, 10-bit | J / D | Source inspection required. Preserve-or-block when unsupported; no hidden HDR-to-8-bit conversion. Manual 10-bit needs a qualified encoder/container path. |
| 17 | `video.deinterlace` — Deinterlacing | **Off**, Detected interlaced only, Always | J / E | Existing boolean is a foundation; detection needs trustworthy metadata and tests. “Detected” must explain Unknown rather than pretend every input is progressive. |
| 18 | `video.hdr_policy` — HDR handling | **Preserve or stop**, Convert to SDR | J / D | SDR input is unaffected. Unsupported HDR export blocks with an explanation; SDR conversion stays Planned until color transforms and metadata are qualified. |

## 3. Audio — 8 options · T-AUDIO

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 19 | `audio.codec` — Audio codec | **AAC**, Opus, MP3, FLAC, Copy if compatible, Remove audio | J / E | New Copy mode requires compatible container and no processing request. Block conflicts rather than dropping effects. Compiled encoder availability is mandatory. |
| 20 | `audio.bitrate_kbps` — Audio bitrate | **128 kb/s**, 32, 48, 64, 96, 160, 192, 256, 320 | J / E | Effective for lossy encoding only; hidden/inactive for Copy, FLAC and Remove. Upload fitting may change a planned bitrate only with an explicit effective-value explanation. |
| 21 | `audio.sample_rate` — Sample rate | **Source**, 44100, 48000, 96000 Hz | J / N | Gate against encoder/container support. Resampling is an actual filter operation and disables stream copy. |
| 22 | `audio.channels` — Channels | **Source**, Stereo, Mono | J / E | Existing stereo flag needs a deliberate enum migration. Downmix coefficients/channel layout require verification; cannot merely relabel multichannel audio. |
| 23 | `audio.track_rule` — Default source track | **Source default track**, First track, Preferred language | J / N | Preferred language carries a language code. Resolve to a concrete inspected track when queued; no language match asks for a choice rather than silently picking unrelated commentary. |
| 24 | `audio.normalize` — Loudness normalization | **Off**, Measured loudness normalization | J / D | Requires an analysis pass, actual loudness filter and source-to-output measurement. It is not player volume; no fake EQ/effects implementation belongs in this menu work. |
| 25 | `audio.target_lufs` — Loudness target | **−16 LUFS**, numeric −24 to −14 in 0.5 steps | J / D | Only when normalization is enabled. This is a configurable product starting point, not a universal streaming-service requirement. |
| 26 | `audio.true_peak_dbtp` — True-peak ceiling | **−1 dBTP**, numeric −6 to −0.1 in 0.1 steps | J / D | Normalization only; validate with decoded-output measurement. No promised clipping prevention from an unwired number field. |

## 4. Encoders & performance — 8 options · T-ENGINE

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 27 | `engine.encode_backend` — Encoding backend | **Auto**, Software CPU, Hardware required | J / E | Reuse existing acceleration policy. Auto is intent, not hardware proof. Hardware required blocks incompatible or missing routes; Software CPU forbids hardware encoding. |
| 28 | `engine.software_effort` — Software encoding effort | Fast, **Balanced**, Thorough | J / N | Map through a codec-specific adapter, never a universal preset flag. Show actual mapped preset; unsupported codec mappings stay unavailable. Does not apply to hardware encoding. |
| 29 | `engine.cpu_threads` — CPU thread request | **Auto bounded**, integer 1–8 | J / E | Auto retains max(1, min(4, processors−1)); explicit requests are validated against the app's bounded budget and shown as requested/effective. Never claim vendor or codec auxiliary thread pools are hard-capped. |
| 30 | `engine.decode_backend` — Video decoding | **Software CPU**, Auto, Hardware required | J / D | Current known route remains software. Hardware decoding needs separate qualification; enabling hardware encode does not change this preference. |
| 31 | `engine.filter_backend` — Filter processing | **CPU**, Auto, GPU required | J / D | GPU option needs the exact requested filter graph, synchronization and color path. No “GPU enabled” badge for CPU FFmpeg filters. Required GPU cannot silently drop a filter. |
| 32 | `engine.hardware_component` — Hardware encoder component | **Auto**, enumerated compatible component ID | J / N | Device-local advanced override, not an arbitrary command string. Show exact component and availability; invalidate/review after device/OS/catalog change. Unavailable in Software CPU mode. |
| 33 | `engine.fallback` — Automatic fallback | **Software on classified codec-init failure**, Ask, Never | J / E | Only for Auto policy; Hardware required/explicit component selection never silently falls back. Cancel, corruption, disk errors and size overshoot are not codec-init failures. Bound retries and log reason. |
| 34 | `engine.preview_during_encode` — Preview while encoding | **Reduce preview work**, Keep normal preview, Pause preview | G / N | Changes preview only, not export frames/quality. No duplicate decoder or animation busy loop hidden in Settings. |

**Convenience action, not option #65:** Use CPU for the whole pipeline previews keys 27, 30 and 31 set to Software CPU, Software CPU and CPU at the selected scope. It never claims the GPU is unused by Android display rendering. At app-default scope it affects future drafts, not queued jobs.

## 5. Battery & temperature — 10 options · T-POWER

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 35 | `power.low_action` — Low-battery action | Warn only, **Finish current file then wait**, Stop current file and wait, Ignore optional guard | P / N | “Stop” means cooperative cancellation then restart from original later. Finish allows only the current attempt, not an unbounded series of retries. |
| 36 | `power.low_percent` — Battery threshold | **20%**, integer 5–50 | P / N | Triggers at or below the threshold. Reject invalid sensor level/scale as Unknown; do not pretend it is 0%. |
| 37 | `power.only_when_not_charging` — Apply low-battery rule only when not charging | **On**, Off | P / N | On exempts active charging or full-and-plugged, not simply cable present. Thermal rules remain active on power. |
| 38 | `power.charging_only` — Start work only while charging | **Off**, On | P / N | Gates next job and size retry independently of battery percentage. Unknown charging state waits when this is On. |
| 39 | `power.auto_continue` — Continue when conditions recover | **On**, Off | P / N | On is conditional on valid foreground-start rules and completed cleanup; otherwise offer tap-to-resume. Never resurrect force-stopped/cancelled work. |
| 40 | `power.resume_margin` — Battery recovery margin | **5 percentage points**, integer 2–20 | P / N | Require threshold + margin ≤95; eligible power or this recovery level clears a low-battery episode after 10 stable seconds. Avoid threshold oscillation. |
| 41 | `power.unplug_action` — When charging stops | **Continue**, Finish current file then wait, Stop current file and wait | P / N | Continue still honors low-battery and charging-only guards. A transition to plugged-but-not-charging is loss of eligible charging. |
| 42 | `power.thermal_action` — When the device is too hot | Warn only, Finish current file then wait, **Stop current file and wait** | P / N | Uses Android thermal severity, not guessed Celsius. Critical or higher always cancels/blocks regardless of this optional action. |
| 43 | `power.thermal_threshold` — Thermal guard threshold | Moderate, **Severe** | P / N | API/capability-gated. Recovery requires below chosen severity for 30 seconds. Unavailable telemetry is shown honestly; never disable OS protection. |
| 44 | `power.respect_saver` — Respect Android Battery Saver | **On**, Off | P / N | On reduces optional preview/telemetry and requests a lower CPU budget at the next attempt; content settings stay unchanged. It does not reserve cores or modify OS Battery Saver. |

The runnable policy must combine all reasons, not overwrite one with another: an eligible battery does not clear a thermal or user pause. Per-run exceptions exist only as confirmed actions, are visibly shown, and cannot bypass critical thermal/OS limits. See the design's state table for cancellation, service lifetime and recovery rules.

## 6. Export & defaults — 8 options · T-EXPORT

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 45 | `export.goal` — Export goal | **Upload size**, Manual encoding | J / N | Upload size uses the native size-fit planner once implemented; Manual uses codec rate-control controls. Show which fields are inactive before Apply. Preserve full chosen duration. |
| 46 | `export.target_bytes` — Upload size limit | **10,000,000 bytes (10 MB)**; 20, 25, 50, 100, 500 MB or Custom 1–10000 MB | J / N | Store positive 64-bit decimal bytes; presets are convenience values, not live claims about third-party service quotas. Success remains 0 < actual bytes < target bytes. |
| 47 | `export.max_attempts` — Video/audio size-fit attempts | **4 total attempts**, integer 1–4 | J / N | Includes initial attempt; all size-fit and allowed backend retry attempts share this total. Cancellation does not trigger another attempt. Still-image fitting retains its separate existing seven-attempt contract. |
| 48 | `export.container` — Default container | **MP4**, MKV, WebM, M4A | J / E | Validate codec/container/media combinations. M4A requires audio-only output; no silently discarded video. A job can explicitly override the default. |
| 49 | `export.destination` — Save destination | **Ask where to save**, Last approved folder, App-managed exports | G / N | Use SAF grants where relevant, not fabricated filesystem paths. Resolve and freeze the destination per queued job; a later preference change does not reroute old work. Revoked grants require user action. |
| 50 | `export.filename` — Output naming | **{source}_forma**, {source}_{date}, Custom token template | J / N | Allowlist source/date/sequence tokens, sanitize names, bound length and reject path separators/traversal. Never interpolate shell text. |
| 51 | `export.collision` — Existing output name | **Add numbered suffix**, Ask | J / N | No overwrite-original option. Publication rechecks collisions, including simultaneous jobs and document-provider behavior. |
| 52 | `export.metadata` — Metadata retention | **Remove optional metadata**, Keep non-location descriptive metadata, Custom reviewed fields | J / E | Strip optional privacy data, not timing/color/HDR/orientation information required for correct playback. Existing keepMetadata boolean needs deliberate versioned migration. No automatic source URL/token copy. |

Upload fitting remains a future native integration gate where the current base has only the Studio contract. These defaults must not make a no-native APK appear to export successfully. All retries read the staged original and call `FfmpegBridge.prepare` again. Never use file-size truncation to fake a passing upload target.

## 7. Queue & notifications — 6 options · T-QUEUE

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 53 | `queue.auto_start_added` — Start newly added queue items | **Only after Start**, Automatically when idle | G / N | Applies to Add to queue, not the explicit Convert action. All power/capability gates still apply; never create a second active video run. |
| 54 | `queue.on_error` — After an item fails | **Wait for review**, Continue remaining items | G / N | Applies at next queue boundary. Cancel is not an error; retries require classified policy, not this switch. Running job settings stay frozen. |
| 55 | `queue.interrupted_prompt` — Interrupted-work prompt | **Offer review on next launch**, Keep in queue without prompt | G / N | Both keep unfinished jobs Interrupted and preserve originals. Neither automatically resumes partial files or restarts work on boot. |
| 56 | `queue.notification_detail` — Notification detail | **Minimal**, Include filename and progress | G / N | Mandatory foreground notification cannot be disabled by this setting. Respect OS permission/channel controls; detailed filenames are an explicit privacy choice. |
| 57 | `queue.completion_sound` — Completion sound | **Off**, Follow notification channel | G / N | Follow OS channel/DND behavior, once per completed batch rather than per retry. No sound for failed/cancelled output. |
| 58 | `queue.keep_screen_on` — Keep screen awake | **Off**, During preview, While encoding and app visible | G / N | Screen visibility only; no permanent wake lock. Worker wake-lock ownership remains separate and ends during long waits after cleanup. |

## 8. Privacy & diagnostics — 6 options · T-DIAGNOSTICS

| # | Stable key / visible label | Choices and proposed default | Scope / foundation | Behavior and gate |
| --- | --- | --- | --- | --- |
| 59 | `privacy.history_days` — Completed-history retention | Off, 1, **7**, 30, 90 days | G / N | Prunes completed history records only, never user output, active queue entries or interrupted recovery data. Apply shorter retention with an explicit deletion preview. |
| 60 | `diagnostics.level` — Local diagnostic detail | Errors only, **Normal**, Debug for this session | G / N | Debug is bounded and resets on process restart. No per-frame disk logs by default; redact tokens/URLs/private paths at collection, not only export. |
| 61 | `diagnostics.retention_days` — Local-log retention | 1, **7**, 30 days | G / N | Bound logs to 10 MiB total as well as age; rotate, not unbounded accumulation. Do not remove active attempt state required for recovery. |
| 62 | `diagnostics.include_filenames` — Include filenames in shared reports | **Off**, On with confirmation for each report | G / N | Never includes URL tokens, credentials, media payloads or private paths. On is only a default request; show exact report preview and destination every time. |
| 63 | `privacy.temporary_retention` — Completed temporary files | **Remove when no longer needed**, Keep up to 24 hours, Keep up to 7 days | G / N | Reference-aware cleanup only after native readers and retry needs are gone. Never delete originals, exported files, or staged inputs for waiting/interrupted jobs. No release-time test fixture cleanup hook. |
| 64 | `privacy.metered_downloads` — Direct-media downloads on metered networks | **Ask**, Allow, Unmetered only | G / D | Relevant to the future bounded Android URL-import layer, not local exports. No blanket cleartext HTTP enablement or website/DRM extraction. |

## Implementation order and invariants

Start with appearance, settings persistence/provenance, existing video/audio controls and clear effective-route display. Add tested power gating, then native size-fit defaults, then device-qualified/deferred options. Keep deferred controls discoverable without fabricating results. Coordinate with the editor/ADB framework draft rather than creating a competing test APK or editing its branch.

For each setting the implementation must provide: typed value validation; a factory default; explicit layer membership; dependency/availability resolution; search strings; UI semantics; persistence and reset tests; and a behavior assertion or a verified Planned/unavailable state. This table is the human-readable contract; future generated Kotlin catalogs must be checked against it so the design and app do not drift.
