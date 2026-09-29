# Audio editor: native UI layout

**Design reference, not implemented UI.** Builds on the [audio design](audio-editor.md) and the existing Compose workspace. All numbers below are proposed UI defaults or acceptance targets unless identified as current repository requirements.

## Home stays simple

Before import, retain upload goal (default 10 MB), Select media and a visible direct-media URL field. The expandable left shelf holds navigation and quick jumps. Do not fill Home with an EQ, mixer, codec matrix or disabled future controls. Native URL/upload parity remains its existing implementation task, not something this document claims is finished.

After inspection, audio-only sources reveal the same editor with a waveform instead of a video preview. Video sources offer an Audio section linked to the video timeline. Track selection identifies language/title/codec/channel count when available; do not confuse selecting an embedded audio stream with adding a music track.

## Compact portrait workspace

```text
Menu   Audio: interview.m4a                 Undo  Redo
01:24.300 | AAC | Stereo | 48 kHz          Source details

[ Play/Pause ] [ Loop selection ]        00:12.450
Original [ A / B ] Edited       Rendered preview / Updating
+---------------------------------------------------+
| waveform       [ IN ==== playhead ==== OUT ]       |
| 00:00          00:10          00:20          00:30   |
+---------------------------------------------------+
[ - Zoom ] [ Fit ] [ + Zoom ]      IN 00:03.000  OUT ...
[ Trim ] [ Split ] [ Fade ]               [ More ]

Preset [ Original / Voice / Music / Podcast / Custom ]
Volume                 -6 ---------o--------- +12 dB
Normalize [ Off / Peak / Loudness ]       [ Settings ]
EQ [ Flat / Voice / Bass / Custom ]       [ Edit EQ  ]
Cleanup [ Off / Light / Custom ]          [ Adjust   ]
Dynamics [ Off / Voice / Custom ]         [ Adjust   ]
[ Effect chain (3 enabled) ]              [ Add effect ]

Measured: Input -22.1 LUFS | Output not measured yet
Output: M4A / AAC / Auto bitrate / Stereo  [ Settings ]
Estimate 7.8 MB | Limit 10 MB | Exact size checked later
[ Add to queue ]                         [ Convert   ]
```

The example waveform, times, levels and estimated bytes illustrate layout, not test evidence. Never populate production UI with these invented readings. The main action remains conversion; Play never starts an export. Existing queue progress/Stop remains reachable while editing. The bottom action bar respects keyboard and system insets.

Quick rows are views of the same typed effect nodes used in Advanced, not a separate simplified processing chain. Tapping Advanced does not reset anything. Editing a preset changes its label to Custom; applying another preset is an explicit action. Output-size presets may adjust encoding choices without replacing EQ/cleanup edits. Batch operations explain whether they apply to the selected clip, selected sources or future imports; queued jobs remain immutable.

## EQ editor

Open an inline expanded panel on wide layouts and a full-height sheet on compact layouts; do not navigate through three settings screens.

```text
Equalizer                 [ Bypass ] [ Reset ] [ Done ]
Preset [ Custom v ]               Input/output meters
+---------------------------------------------------+
| gain dB       draggable band handles               |
| +12                                               |
|   0 -------- logarithmic frequency axis ---------- |
| -12   20     100      1k      5k        20k Hz       |
+---------------------------------------------------+
Band [ 2 v ]       Type [ Bell v ]          [ Enabled ]
Frequency [ 250 Hz ]   Gain [ -2.0 dB ]   Q [ 1.20 ]
[ Previous band ] [ Next band ] [ Add ] [ Remove ]
[ Compare A/B ]               More: slope / channels
```

Initial advanced EQ: four neutral slots, up to eight active bands; all effects neutral until explicitly applied. Frequency controls use a logarithmic scale and clamp to valid values below the processing Nyquist frequency. Gain initially spans -24 to +24 dB; Q spans 0.1 to 18. Filter type determines available parameters: do not show a meaningless gain control for a plain high-pass filter. High/low-pass slopes are explicit supported choices, not an arbitrary Q substitute.

Dragging a handle changes frequency/gain; Q has its own control. Provide numeric entry and previous/next selection so precise edits do not require dragging. A selected band is distinguishable by label/shape, not just color. Show a summed response curve, a bypassed reference and peak warnings. Shelving/filter options must match the backend descriptor, not a decorative curve.

## Effect-chain inspector

```text
Clip effects                      [ Bypass all ] [ + ]
1  High-pass        80 Hz          [ On ] [ Edit ] [...]
2  Parametric EQ    Custom         [ On ] [ Edit ] [...]
3  Compressor      Voice          [ On ] [ Edit ] [...]

Output processing (fixed after creative effects)
Loudness            Off                         [ Edit ]
Peak protection     Off                         [ Edit ]
```

Each node supports bypass, edit, duplicate, reset, remove and move up/down. Drag reorder is optional, never the only method. Reordering commits one undo action and updates the preview revision. Show required assets and availability before applying an effect. A missing model/filter gets a specific explanation, not a control that appears to work. Unimplemented roadmap effects belong in a separate informational area, not the active picker.

The searchable picker groups EQ & filters, Dynamics, Cleanup, Time & pitch, Space & modulation and Channels. Cards explain the audible purpose in one sentence. Advanced details include required backend, preview mode, latency/tail policy and supported automation. The inspector exposes common controls first: compressor threshold/ratio/attack/release and gain reduction; reverb dry/wet/decay; denoise strength/profile; gate threshold/hysteresis/hold. Reset applies to the current node only.

## Large-screen layout and future mixer

```text
+-------------+--------------------------------+-------------------+
| Shelf       | Source / video / audio preview | Inspector         |
| Convert     | Waveform + brackets            | Selected EQ or FX |
| Queue       | Transport + A/B                | Parameters        |
| Audio       |                                | Chain / bypass    |
| Analysis    | Timeline / later track lanes   | Output settings   |
+-------------+--------------------------------+-------------------+
| Measured levels | Preview state | Output budget | Queue / Convert |
+------------------------------------------------------------------+
```

Use available width, not device-model detection. Proposed breakpoints: below 600 dp one column/sheet; 600-839 dp optional inspector when space permits; 840 dp and above side-by-side workspace. Preserve selection, panel state and graph across rotation and resizing.

Stage B adds tracks below the same timeline: video-linked sound, music and voiceover. Each track has name, mute, solo, gain and pan; selection opens its inspector. Clearly distinguish clip, track and master scope. Ducking selects a speech sidechain and target music track; recording includes input selection, countdown and explicit microphone permission only at recording time. No mixer grid or recording permission is required to trim an existing file.

Automation has an explicit parameter lane and Add point at playhead. Show time/value fields, interpolation and reset; default Stage B supports gain/pan. Use microsecond storage even if the display rounds to milliseconds. Do not pretend arbitrary filter parameters are automatable until their backend is qualified.

## Interaction and accessibility acceptance

Preserve the repository's 52 dp minimum interaction target. Small bracket handles, EQ dots and waveform markers receive enlarged hit regions; their drawing need not be 52 dp wide. Pinch zoom, horizontal pan and bracket dragging must have distinct gesture ownership. Provide time-entry and zoom-button alternatives. At sample-level zoom, snap edits to sample positions; never infer precision from a rounded timestamp alone.

TalkBack announces effect name, enabled state, unit/value and IN/OUT identity. Test large fonts without hiding Convert/Stop, landscape keyboards, RTL layout and contrast through the native theme. Graphs need semantic summaries and numeric controls. External keyboard arrows change a focused slider/band without needing an Apply click; commit a gesture as one undo transaction, not hundreds of history entries.

Use `AudioEditorPanel`, `AudioTransport`, `AudioWaveform`, `AudioQuickControls`, `AudioEqEditor`, `AudioEffectChain`, `AudioEffectInspector` and `AudioOutputPanel` as proposed composable boundaries under `app/.../ui/audio/`. Pass state/actions, not native sessions. Shared touch wrappers remain in `TouchControls.kt`; do not copy them.

## Honest states and useful analysis

Show loading, no audio, missing native engine, unsupported effect, render queued, updating, stale preview, cancelled, low storage and failed output verification explicitly. Stop preview and Stop export are different actions. Throttle meter/progress recomposition to their small child views, not the entire editor. Mark unmeasured values as such; preserve the last valid measurements with a Stale badge when appropriate.

Analysis expands below the waveform or in the inspector: original versus processed waveform, peak/RMS, integrated/short-term loudness, true peak, loudness range, spectrum, spectrogram and phase correlation. Label channel, units and whether data covers the selection or entire program. Detailed analysis is optional work with cancellable progress. Estimated quality impact is explanatory guidance, never a fabricated objective quality score.
