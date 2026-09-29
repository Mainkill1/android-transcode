# Editor framework and feature roadmap

## Start here

**Select media → inspect → edit only when needed → choose output → convert → share.** Keep advanced controls out of the source-first home. This delivery adds a native editing foundation and its first executable feature slice, not a finished professional editor.

Read [device/ADB testing](../testing/README.md) for build commands, recipes, reports and test removal. The [implementation plan](superpowers/plans/2026-09-29-editor-framework.md) records the component boundaries and remaining gates.

## Implemented native slice

| Area | Implementation | Evidence boundary |
| --- | --- | --- |
| Typed clip edits | Crop; right-angle rotation; horizontal/vertical mirror; 25–400% speed; brightness, contrast, saturation, gamma; blur/sharpen; video fades; volume; audio fades; single-pass normalization | Host and physical native fixture exports passed; limits below |
| Native integration | Existing service/coordinator stages original inputs, invokes shared `FfmpegRenderSession`, prepares every retry, probes/decodes and publishes atomically with Completed | Physical foreground service and renderer passed with installed hashes below |
| Per-source settings | `SourceEdit.effects`, independent queued snapshots, presets do not erase edits | Core assertions checked |
| Persistence | Queue schema 3 saves every ordered clip/settings/trim, canvas, transition and byte cap; schemas 1/2 remain compatible manual single-file jobs | JVM restart/invalid-data regressions run |
| Native controls | Collapsed Edit selected clip section under advanced controls; crop dialog, touch controls and explicit reset | Native Movie controls and touch/action tests passed; human TalkBack remains unclaimed |
| Ordered movies | Native Movie inspector; append, move, remove, duplicate, split, bracket/exact trim, effects, canvas/fit/FPS, dissolve, undo/redo, rendered preview and export | Host and physical movie exports passed; broad A/V quality remains unclaimed |
| Removable tests | Android/JVM sources under `testing/`, Gradle references only, `-PformaTests=false` | Product-only minified native release built with test tree removed; release is unsigned |
| ADB automation | Separate lab app/test APK; capabilities, clip smoke, movie-smoke and recipe exports; movie preparation/attempt/frame evidence plus cancellation and cap-failure assertions | Host report contracts checked; physical runs are separately recorded |

**Preview:** Open original opens the source. Render preview queues an independent movie render at up to 480 pixels; Open rendered preview becomes available after verification, and later edits label that preview stale. This is a whole-movie rendered preview, not live playback or waveform scrubbing. **Current acceleration limitation:** edited video explicitly requires a software encoder; normal unedited hardware behavior remains unchanged. Crops on display-matrix/rotation-tagged inputs are held until display-coordinate mapping is qualified.

## Complete feature inventory, grouped by workflow

The inventory describes the long-term target. Entries not explicitly implemented above remain future work; no empty control should imply a working renderer.

| Workflow | Capabilities | Next extension boundary |
| --- | --- | --- |
| Import and organize | Video/audio/stills/GIFs/subtitles; files/gallery/camera/share sheet/direct-media URLs; multiple sources; metadata; relink; proxies; VFR and orientation handling; duplicate projects; autosave/recovery | Native source/project persistence and input adapters |
| Timeline navigation | Video/audio/overlay/caption tracks; zoom, scrub, frame stepping, thumbnails/waveforms, markers/ranges, snap toggle; track lock/hide/mute; keyboard/touch input | Native timeline UI, then track model and edited preview |
| Cut and arrange | Bracket IN/OUT and exact times; split/trim; insert/overwrite; ripple delete/gap removal; lift/extract; append/replace/reorder/duplicate; slip/slide/roll; group/nest; link/unlink sound; undo/redo | Ordered clip commands and single-canvas movie composition exist; multitrack composition and filmstrip scrubbing remain |
| Preview | Range loop, playback speed, jump between cuts, fullscreen, fit/fill/100%, preview quality, audio monitoring, before/after, dropped-frame/time display | Renderer consumer of the same project/effect model, not a second implementation |
| Transform and canvas | Position, scale/axis scale, pivot, rotation/mirror, crop, opacity; fit/fill/stretch; aspect locks; 16:9/9:16/1:1/4:3/21:9/custom; straighten; solid/blur backgrounds; reframing | Crop/right-angle/mirror and fitted movie canvas exist; layers and free transforms follow |
| Time | Constant speed, pitch preservation, reverse, speed ramps, freeze/hold, duration targeting, frame blending/interpolation | Constant speed exists; bounded memory and temporal-effect remapping required for the rest |
| Audio | Tracks, extraction/replacement, music/voiceover, mute/gain/pan/fades, normalization, compressor/limiter/EQ, noise reduction, high/low pass, delay, resampling, channel routing, ducking, pitch | Per-clip processing exists; multitrack audio mixer and recording are separate |
| Color | Brightness/contrast/saturation/gamma, exposure, temperature/tint, highlights/shadows/blacks/whites, levels, RGB/hue curves, white balance, LUT/intensity, scopes, color-space/range conversion and HDR tone mapping | Basic SDR adjustments exist; color-managed HDR/LUT pipeline needs separate qualification |
| Effects | Blur, sharpen, denoise, grain/vignette/pixelate/glow, monochrome/sepia, keying/background removal, stabilization, lens correction, deinterlace/deflicker; extensibility | Capability-checked typed built-ins first; no arbitrary filter strings in ordinary job JSON |
| Transitions | Crossfade/dissolve, fade to black/color, wipe/slide/zoom, editable duration, preview | Common-frame-grid dissolve and audio crossfade exist; additional transition styles remain |
| Titles and graphics | Text/fonts/size/alignment/style/outline/shadow/background; title/lower-third templates; logos/watermarks/shapes/stickers; positioning/animation | Typed external assets, text layout and layer renderer |
| Captions | Manual editor, SRT/VTT/ASS import/export, split/merge/timing, styling/positioning, language selection, burn-in or separate streams, speech-to-text | Caption track and renderer; optional transcription provider |
| Keyframes and composition | Position/scale/rotation/opacity/crop/volume/color/effect animation; linear/eased/hold interpolation; rectangle/ellipse/freeform masks, feather/invert/tracking; chroma/luma key; alpha/blend modes/picture-in-picture | Time-based property model and compositing graph |
| Compression | Size/quality/bitrate/resolution/FPS/duration goals; edited-duration budget, audio reserve, multipass, bounded retries; estimates; explicit resolution/FPS compromises; upload presets | Shared native session applies a strict cap and bounded bitrate retries to clips and movies; never silently shorten/mute |
| Encoding | Container/codec/profile/level; quality/bitrate/preset/GOP/pixel format/bit depth/B-frames; audio codec/rate/channels; output resolution/FPS/scaler; hardware/software capability/fallback explanations | Reuse existing validated settings/native bridge; do not silently change requested hardware policy |
| Stream handling | Track selection/default/language; remove streams; remux/stream copy; chapters/attachments; metadata inspect/preserve/strip including timestamps/GPS/orientation | Separate no-reencode planning and compatible-container rules |
| Export and batches | Whole project/range/clip; video/audio/still/GIF; multiple versions; queue reorder/pause/cancel/retry; presets/per-item overrides; rename/destination/share; explicit overwrite | Durable single-file and compound movie jobs; additional export types remain |
| Progress and reliability | Processed time, speed, bytes, estimates, cancellation, supported pause, background notification, logs/retry/recovery; source preservation; storage/thermal checks; preview/export agreement | Same runtime and bounded evidence, not test-only success paths |
| Professional optional scope | Multicam/timecode, nested sequences, interchange, review/collaboration, plugins and broadcast delivery | Optional extensions; not prerequisites for a useful mobile editor |

## Architecture and extension rules

```text
SourceEdit + Settings / MovieProject + SequenceSpec + explicit byte cap
          ↓ immutable JobSpec / queue schema 3
stage each original source (duplicate references share staged bytes)
          ↓
FfmpegRenderSession → Planner / SequencePlanner → FfmpegBridge.prepare
          ↓ each retry reads those same original inputs
probe + full decode + exact fixture frame checks + strict byte cap
          ↓ existing service owns cancellation and completion
private publication + durable Completed notification

MovieProject + ProjectHistory → native Movie inspector
rendered preview → separate low-resolution queue job → verified artifact playback
Separate lab APK → the same production exporter
```

Keep API 26, SDK 36 and JDK 17; preserve native FFmpeg pins/licensing. Trim precedes speed, and audio/video share a trim origin while retaining intentional relative offsets. Verify **edited** duration. Deinterlace before rotating scan lines. Fades are in output time and may not overlap beyond the selected duration. Timeline splits with fades fail until remapping is implemented rather than silently changing the effect.

Unknown edits, unavailable filters/native code, incompatible formats and unqualified hardware routes must return actionable errors. Version migrations must not erase unsupported edits. Normalization here is single-pass processing, not broadcast loudness certification. A timeline data model is not proof of a multitrack renderer, and repeated single-file exports are not a merged movie.

## Verification and remaining gates

The [movie integration plan](superpowers/plans/2026-09-29-movie-integration.md) adds production staging, rendering, verification, strict byte budgets and native controls. Real desktop oracles check audio silence, ordered pictures, dissolve pixels and delayed-video gaps. App JVM tests cover migration, original-input retries, cap equality/exhaustion, decode/rename/completion failure and cancellation. Host, packaging and physical execution are recorded separately below.

## Physical qualification checkpoint (2026-09-29)

The attached OnePlus 9 Pro LE2125, API 36, arm64, 4 KB pages ran the actual pinned source-built FFmpeg through the production renderer. Matching lab APK identities are recorded below.

- **47 JVM tests**, native lab/test assembly and lint passed. Desktop verification passed **22 exports**, including WebM/MKV and clockless WAV; those are desktop results.
- **13 physical exports passed**: seven movies (cut, dissolve, speed, mixed-silent audio-only, strict byte cap, rendered preview and delayed video), five clip-effect cases and a downloaded WAV trimmed to 3.5-second M4A. All thirteen pulled files passed an independent strict full decode. Movie decoded frames were exactly 120/105/90/120/120/120 for their selected durations; delayed video retained a black opening gap then its red picture.
- Native movies reread unchanged originals and passed queue round-trip, cancellation, impossible-cap rejection and failed durable-completion rollback checks. Per-stream verification rejects full-length audio hiding truncated video; source-rate video must supply positive decoded-frame evidence. Missing WAV clocks use observed packet/frame timestamps.
- **Four instrumentation tests passed with no skips**: two MovieControls cases, real foreground MovieService completion and byte-exact preservation of incomplete saved intent. Sparse external recipes stay supported; saved trim/effect snapshots are strict.
- The product-only minified release built with the entire testing directory removed and `formaTests=false`. Release DEX excludes lab classes, runner and command arguments. Lab/release native payload and 16 KB ELF/APK ZIP alignment pass; this is not testing on a 16 KB phone.

Fresh native run IDs: movie `405d9dc943714da38cad5efe3fb0d12e`, clip `1084745b80174e8787d86dc2a3a9f401`, downloaded WAV `0a1c586f674a4a539f313b38a437843c`.

Artifact SHA-256:

```text
lab      dcf8cd9666941f43cd2d45097bec1c25bbbf2536eb2f87778be78e03ca2e5572
test     02305a99e6228076dc54ff1b09e6e265af9d12c8fec0b5b87d4293ddfe680d25
release  0e36c884ebeda0e266636eacbd1831ed7ba2b5fc4d7e7b5657e27c104ee1180a
```

Human listening, TalkBack exploration, larger VFR/rotation fixtures and sustained thermal/quality qualification remain unclaimed. Draft movie edits survive Activity recreation; durable process-restart persistence applies to queued snapshots. Still images, multitrack editing and draft-project persistence remain documented later scope. Integrating the other editor/settings PRs requires reconciling their queue envelopes.
