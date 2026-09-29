# Upload-first Studio

**Pick an upload limit, add video/audio/a still image, then convert.** The default
is 10 MB, with 20/25/50/100/500 MB and custom goals. FFmpeg settings are selected
after inspection. The complete output file is measured, and valid oversized
results automatically retry with stronger compression. Only verified results
strictly below the selected limit are exposed for download.

Video uses MP4/H.264, audio uses M4A/AAC, and still images support WebP/JPEG/PNG.
Trimming and other existing media edits contribute to the size budget. More
settings offers an explicit manual/no-limit mode without removing advanced
conversion or the bracket editor.

See [upload limits, retries, image support and test contract](../docs/upload-limits.md).
This is the working desktop reference; the native Android APK is **not** updated
with these newer workflows yet.

---

# Forma Studio — touch-first reference and real FFmpeg runner

The repository's current UI reference. No account or cloud encoder. The home screen contains Select media and a media-URL field; conversion options appear after a source is supplied. The left shelf provides workspace and advanced-tool shortcuts.

## Run

Install Python 3.10+, FFmpeg and FFprobe on PATH, then run `python studio/server.py` from the repository root (or `python server.py` inside this directory). Windows can use `start.cmd`; Linux/macOS can use `sh start.sh`.

The runner binds only to `127.0.0.1:8765`. `--port`, `--data-dir`, and `--no-browser` are supported. `--allow-private-urls` permits trusted LAN media; it is off by default. This is a local desktop development reference, not a server to expose publicly.

`python studio/build.py --samples` generates optional eight-second fixtures and a single-file `studio/forma-studio.html`. Opening that file directly supports layout, file previews and editing settings, but not encoding. Generated media are not checked in. Samples in the running app need to be generated once with the same command.

## Workflow

Manual video: MP4/MKV/MOV/WebM, compatible H.264/H.265/VP9/AV1 encoders, quality or bitrate, frame rate, dimensions and audio settings. Available formats depend on the actual FFmpeg encoders.

Manual audio: MP3, M4A/AAC, FLAC, WAV and Opus; sound extraction from video, channel layout, sample rate and bitrate when applicable. Audio-only files open directly in Audio.

Edit: up to 24 kept sections of one source; trim, split, reorder, crop, rotate/mirror, speed, volume, normalization and fades. Tap the one-second seek buttons, then Set start/end here; +/- trim buttons adjust by 0.1 seconds. The primary trim control is a shared source timeline with draggable `[ ]` brackets, hatched removed regions, a separate playhead and 1x-16x zoom. Video shows real source thumbnails; audio-only files show a measured waveform when browser decoding and size limits permit. Exact numeric values remain available. Each bracket drag is one undo step. Preview either the selected clip or all kept clips. See [visual bracket trimming and editor research](../docs/timeline-editor.md). Edits are non-destructive and support undo/redo. Browser sound preview does not apply exported loudness, gain above 100%, or fades.

Touch UI: 48 CSS-pixel minimum interactive targets, 52-pixel export buttons, large slider thumbs, readable inputs, tap alternatives to dragging, safe-area spacing and measured footer clearance. Phone shelves are modal and lock background scrolling. Keyboard navigation and pinch zoom are retained. See [the implementation contract](../docs/touchscreen.md).

## Boundaries

Real progress and verified outputs come only from the local FFmpeg runner. Jobs snapshot their settings and execute sequentially. Cancellation stops the process and removes partial output. Queue/history lasts only for that runner session; files remain in `.forma-work/` until manually removed.

Direct downloadable HTTP(S) media files are supported. Webpage extraction, HLS/DASH, live streams, multi-source composition, transitions, overlays, subtitles and HDR video export are not implemented. The workbench has a 2 GiB input limit and finite-duration requirements. Originals are never selected as the output.

Nothing here makes the native Android editor complete. Keep the Kotlin engine and these development files separate; do not package Python or this UI into the APK.

## Tests

```bash
cd studio
python -m pip install -r requirements-dev.txt
python -m playwright install --with-deps chrome
python build.py --samples
python -m pytest tests -q
python tests/browser_checks.py
```

`FORMA_BROWSER` can select a codec-capable Chromium executable; alternatively set `FORMA_BROWSER_CHANNEL=chrome` before running the tests with the installed Chrome channel. CI installs browser dependencies and FFmpeg. `test_touch.py` uses actual Chromium touch events with phone viewports. `test_timeline.py` adds bracket gestures, cancellation, zoom, media previews, range/export synchronization and responsive checks. `test_encode.py` checks real output streams and dimensions. `test_api.py` checks upload, URL, queue, cancellation, verified download and request boundaries.

The optional `browser_checks.py --inline` uses an explicit transport to the real loopback runner when the renderer blocks HTTP navigation. It never fabricates FFmpeg responses. Ordinary browser networking must be tested separately when this transport is used.

The single HTML file is a generated build; edit `web/index.html`, `web/ui.css`, `web/touch.css`, `web/ui.js`, `web/timeline.css`, and `web/timeline.js`, `web/upload.css`, and `web/upload-ui.js`, then rebuild. Keep source changes, runner changes and regression tests together in GitHub.
