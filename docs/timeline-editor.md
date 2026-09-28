# Visual bracket trimming

The Studio reference uses a source timeline with two trim brackets and an independent playhead. The start screen remains source-first: Select media or enter a media URL, then choose Video, Audio or Edit. This change does not port the reference editor into the native Android APK.

## Editor research and decisions

Reviewed official documentation on 2026-09-28. These are established editors, not a claim about market-share ranking. The usefulness judgments and the choice of a smaller feature set are design decisions for Forma.

| Editor | Documented interaction | Application in Forma |
| --- | --- | --- |
| [iMovie](https://support.apple.com/guide/imovie-iphone/arrange-video-clips-and-photos-knac788312/ios) | Select a clip and drag its edge handles; reveal actions for the selected clip. | Visible `[ ]` boundaries on one filmstrip, not two unrelated sliders. Keep selection and cut regions readable while adjusting. |
| [Adobe Premiere](https://helpx.adobe.com/premiere/desktop/get-started/source-and-program-monitor-adjustments/set-in-and-out-points-in-the-source-monitor.html) | Source Monitor In/Out points and I/O marking shortcuts. | Independent playhead, I/O shortcuts and Set start/end here. Scrubbing does not edit the range. |
| [Final Cut Pro](https://support.apple.com/guide/final-cut-pro/filmstrip-ver8e3f34c6/mac) | Filmstrip imagery represents video; waveforms represent audio. | Decode real source thumbnails and measure an audio waveform instead of drawing decorative placeholders. |
| [DaVinci Resolve](https://www.blackmagicdesign.com/products/davinciresolve/cut) | Cut page provides an overview and a detailed timeline with interactive trimming. | Bounded 1x-16x zoom, scrolling and one-tap Fit. Forma does not implement Resolve's simultaneous dual timelines. |
| [CapCut](https://www.capcut.com/resource/how-to-use-capcut) | Timeline controls for split/delete and a separate properties area. | Preserve split/remove/reorder beside Output order; precision, picture and sound controls stay in the inspector. |

## What the timeline means

- Brackets surround the selected output clip's kept range. Start is inclusive; end is the boundary after the kept range.
- Hatched regions are source time not used by any output clip. Other kept clips remain separately marked. The removed-duration calculation uses the union of ranges, so reordered or overlapping clips are not counted twice.
- The white playhead and ruler are independent of the brackets. Tap footage or scrub the ruler to inspect source material without changing cuts.
- The preview reports whether the inspected source moment is kept or removed. During a trim, the main preview seeks to the boundary being adjusted. Browser decoding is asynchronous; this is not a certified frame-accurate monitor.
- Start, end, selected kept duration and total output duration update during a drag. Output duration accounts for speed and repeated source ranges.
- Output order below the source timeline remains the sequence editor. Selecting a different output clip moves the brackets to that clip's source range.

## Touch and precision

Each bracket has a 48 CSS-pixel-wide hit area outside the selection. The hit areas therefore do not overlap when the kept range is very short. Pointer capture keeps a drag attached to its handle. Swiping elsewhere pans a zoomed timeline; near-edge dragging auto-pans. Zoom and pan also have ordinary buttons.

One completed drag is one undo transaction. A draft changes only the preview and overlays; export settings commit on release. Pointer cancellation, capture loss, Escape, focus loss and source changes discard the draft. Existing queued exports retain their immutable snapshots.

Arrow keys on a bracket nudge by 0.1 seconds; Shift changes that to 1 second. Home/End move to the permitted boundary. I/O marks the playhead when not typing in a control. Numeric fields and tap nudges remain available. Requested edges are rounded to hundredths before boundary clamping; the source's fractional endpoint is preserved. This is time-based editing, not per-frame snapping or variable-frame-rate qualification.

All kept clips previews the output sequence while skipping removed gaps. Selected clip stops at that clip's end. This retains the existing browser-preview limitations for audio gain, loudness processing and fades.

## Media previews and limits

Video uses twelve sampled source frames, decoded progressively with a separate media element so thumbnail generation cannot move the user's playhead. These are navigation aids, not one thumbnail per frame. Source switches abort pending work and release decoder references.

Audio-only sources use a measured 240-bin peak envelope. Browser waveform decoding is limited to sources at most 24 MiB and ten minutes. Larger or unsupported sources display an explicit unavailable/omitted message and retain time-based trimming and playback where supported. There is no fabricated waveform or simulated codec success. A native or FFmpeg waveform worker is a separate follow-up for large files.

## Implementation and verification

`studio/web/timeline.js` owns gestures, overlays, zoom and preview assets. `timeline.css` owns the layout. `ui.js` remains the authoritative settings/history/export adapter. `index.html`, `build.py` and the runner's explicit static routes include both new assets. No planner argument format, native dependency or license pin changes.

The new regression file `studio/tests/test_timeline.py` has 27 cases covering mouse and emulated touch drags, cancellation, single-step history, independent scrubbing, zoom coordinates, narrow/fractional ranges, measured previews, clip selection/overlap, playback scope, responsive geometry and an actual FFmpeg export from a dragged range. Two API checks verify the new assets are served with the right bytes and MIME types.

```bash
cd studio
python build.py --samples
python -m pytest tests -q --junitxml=test-results/pytest.xml
python tests/browser_checks.py
```

Local verification: 118 pytest cases passed; 43 additional browser workflow checks passed through the documented `--inline` forwarding transport to the real loopback runner. Direct Chromium HTTP navigation was blocked by this execution environment, so that local transport result must not be reported as ordinary browser-network validation. Physical touchscreen, screen-reader and native Android editor qualification remain untested.
