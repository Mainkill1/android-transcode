# Editor framework and feature roadmap

## Start here

**Select media → inspect → edit only when needed → choose output → convert → share.** Keep advanced controls out of the source-first home. This delivery adds a native editing foundation and its first executable feature slice, not a finished professional editor.

Read [device/ADB testing](../testing/README.md) for build commands, recipes, reports and test removal. The [implementation plan](superpowers/plans/2026-09-29-editor-framework.md) records the component boundaries and remaining gates.

## What this draft implements

| Area | Implementation | Evidence boundary |
| --- | --- | --- |
| Typed clip edits | Crop; right-angle rotation; horizontal/vertical mirror; 25–400% speed; brightness, contrast, saturation, gamma; blur/sharpen; video fades; volume; audio fades; single-pass normalization | Compiler and real desktop exports checked; Android export still requires qualification |
| Native integration | Same `Planner → FfmpegBridge.prepare → FfmpegTranscoder` path; settings validation and capability checks; edited-duration export verification/progress | Code integrated, not an Android build-pass claim |
| Per-source settings | `SourceEdit.effects`, independent queued snapshots, presets do not erase edits | Core assertions checked |
| Persistence | Queue schema 2 with typed effects; schema 1 reads as neutral; unknown effects rejected | JVM JSON tests added; Gradle execution still required |
| Native controls | Collapsed Edit selected clip section under advanced controls; crop dialog, touch controls and explicit reset | Source implementation; Compose/device interaction still requires testing |
| Ordered timeline | Immutable clip list; append, remove, move, duplicate, split, trim/settings commands; bounded undo/redo | Core assertions checked; not yet a displayed timeline or sequence renderer |
| Removable tests | Android/JVM sources under `testing/`, Gradle references only, `-PformaTests=false` | Source separation implemented; release dependency/APK inspection remains a gate |
| ADB automation | Separate lab app/test APK; capabilities, synthetic smoke and recipe-driven export commands; JSON reports and host failure detection | Host contracts checked; actual ADB execution still unrun |

**Current preview limitation:** Open original still opens the unmodified source. New effects are export operations, not a claimed live edited preview. **Current acceleration limitation:** edited video explicitly requires a software encoder; normal unedited hardware behavior remains unchanged. Crops on display-matrix/rotation-tagged inputs are held until display-coordinate mapping is qualified.

## Complete feature inventory, grouped by workflow

The inventory describes the long-term target. Entries not explicitly implemented above remain future work; no empty control should imply a working renderer.

| Workflow | Capabilities | Next extension boundary |
| --- | --- | --- |
| Import and organize | Video/audio/stills/GIFs/subtitles; files/gallery/camera/share sheet/direct-media URLs; multiple sources; metadata; relink; proxies; VFR and orientation handling; duplicate projects; autosave/recovery | Native source/project persistence and input adapters |
| Timeline navigation | Video/audio/overlay/caption tracks; zoom, scrub, frame stepping, thumbnails/waveforms, markers/ranges, snap toggle; track lock/hide/mute; keyboard/touch input | Native timeline UI, then track model and edited preview |
| Cut and arrange | Bracket IN/OUT and exact times; split/trim; insert/overwrite; ripple delete/gap removal; lift/extract; append/replace/reorder/duplicate; slip/slide/roll; group/nest; link/unlink sound; undo/redo | Ordered clip commands exist; precise timeline interaction and composition rendering remain |
| Preview | Range loop, playback speed, jump between cuts, fullscreen, fit/fill/100%, preview quality, audio monitoring, before/after, dropped-frame/time display | Renderer consumer of the same project/effect model, not a second implementation |
| Transform and canvas | Position, scale/axis scale, pivot, rotation/mirror, crop, opacity; fit/fill/stretch; aspect locks; 16:9/9:16/1:1/4:3/21:9/custom; straighten; solid/blur backgrounds; reframing | Crop/right-angle/mirror foundation exists; canvas/layers and free transforms follow |
| Time | Constant speed, pitch preservation, reverse, speed ramps, freeze/hold, duration targeting, frame blending/interpolation | Constant speed exists; bounded memory and temporal-effect remapping required for the rest |
| Audio | Tracks, extraction/replacement, music/voiceover, mute/gain/pan/fades, normalization, compressor/limiter/EQ, noise reduction, high/low pass, delay, resampling, channel routing, ducking, pitch | Per-clip processing exists; multitrack audio mixer and recording are separate |
| Color | Brightness/contrast/saturation/gamma, exposure, temperature/tint, highlights/shadows/blacks/whites, levels, RGB/hue curves, white balance, LUT/intensity, scopes, color-space/range conversion and HDR tone mapping | Basic SDR adjustments exist; color-managed HDR/LUT pipeline needs separate qualification |
| Effects | Blur, sharpen, denoise, grain/vignette/pixelate/glow, monochrome/sepia, keying/background removal, stabilization, lens correction, deinterlace/deflicker; extensibility | Capability-checked typed built-ins first; no arbitrary filter strings in ordinary job JSON |
| Transitions | Crossfade/dissolve, fade to black/color, wipe/slide/zoom, editable duration, preview | Two-clip overlap semantics and a compositor; not a per-file queue trick |
| Titles and graphics | Text/fonts/size/alignment/style/outline/shadow/background; title/lower-third templates; logos/watermarks/shapes/stickers; positioning/animation | Typed external assets, text layout and layer renderer |
| Captions | Manual editor, SRT/VTT/ASS import/export, split/merge/timing, styling/positioning, language selection, burn-in or separate streams, speech-to-text | Caption track and renderer; optional transcription provider |
| Keyframes and composition | Position/scale/rotation/opacity/crop/volume/color/effect animation; linear/eased/hold interpolation; rectangle/ellipse/freeform masks, feather/invert/tracking; chroma/luma key; alpha/blend modes/picture-in-picture | Time-based property model and compositing graph |
| Compression | Size/quality/bitrate/resolution/FPS/duration goals; edited-duration budget, audio reserve, multipass, bounded retries; estimates; explicit resolution/FPS compromises; upload presets | Native byte-fit engine remains separate; never promise exact size from CRF alone or silently shorten/mute |
| Encoding | Container/codec/profile/level; quality/bitrate/preset/GOP/pixel format/bit depth/B-frames; audio codec/rate/channels; output resolution/FPS/scaler; hardware/software capability/fallback explanations | Reuse existing validated settings/native bridge; do not silently change requested hardware policy |
| Stream handling | Track selection/default/language; remove streams; remux/stream copy; chapters/attachments; metadata inspect/preserve/strip including timestamps/GPS/orientation | Separate no-reencode planning and compatible-container rules |
| Export and batches | Whole project/range/clip; video/audio/still/GIF; multiple versions; queue reorder/pause/cancel/retry; presets/per-item overrides; rename/destination/share; explicit overwrite | Existing durable per-file queue; compound project export is not yet implemented |
| Progress and reliability | Processed time, speed, bytes, estimates, cancellation, supported pause, background notification, logs/retry/recovery; source preservation; storage/thermal checks; preview/export agreement | Same runtime and bounded evidence, not test-only success paths |
| Professional optional scope | Multicam/timecode, nested sequences, interchange, review/collaboration, plugins and broadcast delivery | Optional extensions; not prerequisites for a useful mobile editor |

## Architecture and extension rules

```text
Selected SourceEdit + encoder Settings
          ↓ immutable snapshot
Settings.effects / queue schema 2
          ↓
Planner + EditPipeline (Android-free validated argument tokens)
          ↓
FfmpegBridge.prepare (actual native/device qualification)
          ↓
FfmpegTranscoder (stage → execute → verify → publish)

EditTimeline + TimelineHistory → future native timeline/sequence renderer

Separate test APK → same production exporter, not a duplicate encoder
```

Keep API 26, SDK 36 and JDK 17; preserve native FFmpeg pins/licensing. Trim precedes speed, and audio/video share a trim origin while retaining intentional relative offsets. Verify **edited** duration. Deinterlace before rotating scan lines. Fades are in output time and may not overlap beyond the selected duration. Timeline splits with fades fail until remapping is implemented rather than silently changing the effect.

Unknown edits, unavailable filters/native code, incompatible formats and unqualified hardware routes must return actionable errors. Version migrations must not erase unsupported edits. Normalization here is single-pass processing, not broadcast loudness certification. A timeline data model is not proof of a multitrack renderer, and repeated single-file exports are not a merged movie.

## Verification record for this implementation session

`python testing/run_host.py --exports` passed: **39 existing core checks, 30 new editor/timeline checks, 13 Python CLI-contract tests and 11 desktop FFmpeg exports**. Real media checks include transformed geometry, track layout, decode, source hashes, speed/duration and intentional A/V-delay preservation. The A/V-delay regression and CLI cleanup-timeout false-PASS regression were observed failing before their fixes.

**Not executed in this environment:** Gradle Android builds/lint; JVM JSON persistence tests; test-APK execution; native Android bundle verification; physical-device exports; touchscreen/visual/listening checks; release APK test-dependency inspection. No Android SDK, Gradle installation, native Android bundle or ADB device was available. Keep this PR a draft until those gates pass and record evidence for the exact installed APK.
