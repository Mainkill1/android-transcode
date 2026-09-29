# Upload-size goals

## Working path and scope

This feature is implemented in the **Studio HTML app and local FFmpeg runner**.
The native Compose application and its FFmpeg adapter are unchanged. Port this
contract into that executor before describing the APK as supporting size goals
or still-image conversion. Desktop exports do not qualify an Android device.

The default workflow is: **choose a limit -> select media or enter a direct URL
-> inspect -> convert -> verify the actual output -> share/save**. The home page
starts at **10 MB**, with 20, 25, 50, 100, 500 MB and custom limits. Technical
settings remain behind More settings. Existing bracket trimming remains available.

Limits are decimal: **1 MB = 1,000,000 bytes**. Custom values range from 0.032 to
2,000 MB. The API accepts an integer `targetBytes`; null or omission is manual
mode with no size guarantee. Booleans, strings, fractions, non-finite numbers and
out-of-range limits are rejected before a job is created. The final file must be
**strictly smaller** than the selected limit, not merely rounded to the same MB.

Discord's official File Attachments FAQ, updated August 13, 2026, states that its
free upload limit is now 20 MB (previously 10 MB), with experiments possible.
The requested 10 MB remains Forma's conservative default; presets are generic
size goals, not promises about a user's account, destination policy or embedded
playback. Source: https://support.discord.com/hc/en-us/articles/25444343291031-File-Attachments-FAQ

## Planning and retry contract

`upload_limits.py` owns the upload policy. `planner.make_plan()` routes target
jobs through it, while existing manual plans remain available. `/api/plan` and
`/api/jobs` use the same server-side planner. Every job owns a deep copy of the
requested settings; stricter attempts do not rewrite that request or the editor.

Video upload output uses MP4/H.264 and AAC when sound is present. Audio-only
output uses M4A/AAC. The planner reserves 4% plus 16,384 bytes for container and
encoding overhead, then calculates average bitrate from the **edited duration**
(including kept sections and speed). Audio receives its own allowance. A
pixels-per-bit heuristic chooses a no-upscale picture size, caps frame rate at
30 fps (15 at very low video budgets), and reduces channels at low audio rates.
This is a practical policy, not a perceptual-quality optimizer or two-pass encode.
FFmpeg's successful exit and a bitrate estimate never establish size compliance.

After a successful encode, the runner checks output existence, tracks, duration
and dimensions, and measures the complete file with `stat`. If it is oversized,
the next attempt reduces its budget using the measured overshoot, normally reducing it by at least
15%, subject to the supported minimum bitrate budget. It retains the full selected duration and all requested
cuts, speed, volume and other supported edits. It never uses `-fs` to truncate a
file, silently removes sound to fit, or encodes from the preceding lossy result.

Video/audio allow at most **four attempts**. Budgets that cannot retain the whole
selection at the supported minimum rates stop earlier with an instruction to trim
or increase the limit. Cancellation, corrupt input, codec errors, disk errors and
structural verification failures are not treated as an oversized-file problem.

## Still images

The runner probes actual file bytes, not filename suffixes. Supported still-image
inputs are PNG, JPEG and WebP; output can be automatic, WebP, JPEG or PNG. Automatic
output prefers WebP when available, with appropriate fallback when it is not.
Transparency is retained by WebP/PNG; JPEG is rejected for an alpha-bearing source
instead of flattening it without consent. Metadata is stripped from image exports.

The first attempt preserves source dimensions where the format permits. Oversize
images lower quality and dimensions, always from the original, for at most
**seven attempts**. PNG is lossless at its chosen dimensions: a smaller PNG may
require fewer pixels rather than an invented lossy-quality setting. Every output
is decoded for verification before publication. A 40-megapixel decode limit
applies. GIF, APNG and animated WebP are explicitly rejected, not flattened.
HEIC/AVIF, HDR/color-managed image conversion, image crop/rotation and animation
editing are not implemented. Browser-only previews depend on browser codecs.

## UI and result semantics

Before inspection, dimensions, audio presence and selected settings are not
invented. After inspection, the simple view shows the chosen output type and
picture dimensions; rates and encoder details stay under More settings.
An already-small source is labeled as such, with conversion optional.

More settings does not remove the size goal. **Use manual settings — no size
limit** is the explicit way to release the automatic compression policy. Changing
limits keeps existing edits. Importing another file uses the chosen home goal.

The queue shows the byte limit, current attempt and phase, measured sizes for
completed attempts, and the final effective settings. Oversized candidates never
receive a download URL. Exhausted retries fail without exposing an oversized
output. Successful size-goal jobs set `verified`, `fitsLimit`, `size`,
`effectiveSettings` and their attempt history. The original is not overwritten.
The Studio queue remains session-only. Nothing uploads to Discord automatically.

## Tests

Run `python build.py --samples`, then `python -m pytest tests -q` in `studio/`.
New suites cover target validation, duration/speed budgeting, portrait dimensions,
missing encoders, real video/audio/image byte caps, sources larger than 10 MB,
alpha preservation, real HTTP/URL import and verified downloads, oversize retries,
exhaustion, cancellation, immutable goals, and touch-friendly UI transitions.
An oversized first candidate is also fault-injected to deterministically exercise
the retry branch while retaining a valid playable file.

`python tests/browser_checks.py` exercises ordinary browser HTTP. The optional
`--inline` test transport forwards to a real local FFmpeg service when browser
networking is restricted; it is not equivalent to validating an ordinary origin.
CI uses the Chrome channel for H.264/AAC browser media coverage, since bundled
Chromium does not include every branded-browser codec. Browser source:
https://playwright.dev/python/docs/browsers#media-codecs

The raw CDP drag test allows a 150 ms compositor settle before the next gesture;
without it, Chromium can suppress the next synthetic tap's click. No product
latency, trim assertion or encoder duration tolerance is changed for that test.
