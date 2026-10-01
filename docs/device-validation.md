# Physical Android build and validation

This checkout was built and tested on the user's ADB target, not inferred from CI.
Generated binaries, media, toolchains, logs and reports stay in ignored workspace
directories. Use `vendor/tooling/env.sh` in this workspace to set JDK 17, SDK/NDK,
Gradle caches, ADB and temporary directories. That machine-specific file is not
committed.

## Build corrections

Compose 1.9.4 selects Espresso 3.5.0. On this API 36 phone its reflective
`InputManager.getInstance()` call fails before any UI assertion. The app's test
dependencies now explicitly select Espresso 3.7.0, which uses the system service.
The existing seven Compose interaction tests reproduce and verify this correction.
See the [official Espresso release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso_370).

The downloaded-video share test exposed another existing blocker: the capability
parser expected three filter flags. The pinned FFmpeg n9.0.1 prints two flags per
filter row but still prints three in its legend, so the old parser reported only
the legend's `=` and disabled the required scale filter. `FfmpegListing` now reads
actual two- or three-flag rows and excludes legend symbols from encoder/muxer
lists. Four host regression tests also preserve hyphenated codec names such as
`libvpx-vp9` and adjacent rows whose descriptions are omitted by a small native
build. Horizontal whitespace and nonconsuming boundaries keep those rows intact.
The explicit native smoke now requires the actual scale capability.
The row format is defined in the pinned
[FFmpeg listing source](https://github.com/FFmpeg/FFmpeg/blob/n9.0.1/fftools/opt_common.c).

The first actual Convert tap also reproduced an Android 16 foreground-service
failure. AndroidX Core 1.17.0 `ServiceCompat` masks types to its API 34 allowlist,
which excludes API 35's `MEDIA_PROCESSING`; Android then rejects type `NONE`.
The service now calls Android's three-argument `startForeground` on API 29+, using
the already selected media-processing or data-sync type, and the two-argument call
on older supported devices. The existing permissions and bounded wake lock remain.
A native phone regression taps Convert, waits for a completed audio job, probes its
track layout/duration and fully decodes the output. See the
[AndroidX implementation](https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/app/ServiceCompat.java)
and [platform API](https://developer.android.com/reference/android/app/Service#startForeground(int,android.app.Notification,int)).

The stock `androidx.graphics:graphics-path:1.0.1` native library fails the existing
APK verifier because its GNU_RELRO end is not 16 KB aligned. The official 1.1.0
arm64 binary was also checked and fails that same condition. Do not relax the
verifier or edit ELF headers. [Android's alignment guidance](https://developer.android.com/guide/practices/page-sizes#relro)
requires suitable native linker flags.

For this build, graphics-path's native sources were downloaded from the official
`androidx/androidx` mirror at commit
`794e3806700833665f48f56f7dd3581642a6057f`, a public pre-1.0.1 native-source
revision. The three upstream translation units and their headers are unchanged.
They were rebuilt for arm64-v8a and x86_64 with NDK 27.3.13750724, API 26,
`c++_static`, Release mode and both
`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`.
The official 1.0.1 AAR's managed classes, resources, metadata, remaining ABIs and
POM are retained. Only the two source-rebuilt native entries are replaced. This
is a local rebuilt artifact, not the byte-identical Google Maven artifact.
Source hashes and rebuilt artifact hashes are recorded in
`app/build/device-evidence/graphics-rebuild.json`.

`-PgraphicsPathRepo` optionally selects that local Maven repository exclusively
for `androidx.graphics:graphics-path`. Without the property, dependency resolution
is unchanged and the strict native verifier still detects the upstream problem.
The source checkout and rebuilt artifacts live under ignored `vendor/graphics-path`.
Upstream source copyright/license headers are retained; no app license is assigned.

Reproduce the graphics artifact from the repository root (network access required):

```bash
source vendor/tooling/env.sh
python3 - <<'PY'
from pathlib import Path
from urllib.request import urlopen
import subprocess, zipfile

root = Path('vendor/graphics-path').resolve()
root.mkdir(parents=True, exist_ok=True)
pin = '794e3806700833665f48f56f7dd3581642a6057f'
base = f'https://raw.githubusercontent.com/androidx/androidx/{pin}/graphics/graphics-path/src/main/cpp/'
files = ['CMakeLists.txt', 'Conic.cpp', 'Conic.h', 'PathIterator.cpp',
         'PathIterator.h', 'pathway.cpp', 'Path.h', 'scalar.h',
         'libandroidx.graphics.path.map', 'math/compiler.h',
         'math/vec2.h', 'math/TVecHelpers.h']
for name in files:
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(urlopen(base + name).read())
import os
ndk = Path(os.environ['ANDROID_NDK_ROOT'])
libs = {}
for abi in ['arm64-v8a', 'x86_64']:
    build = root / ('rebuild-' + abi)
    subprocess.run(['cmake', '-S', str(root), '-B', str(build),
        '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
        '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26',
        '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release',
        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'], check=True)
    subprocess.run(['cmake', '--build', str(build), '--parallel', '4'], check=True)
    libs[f'jni/{abi}/libandroidx.graphics.path.so'] = (build / 'libandroidx.graphics.path.so').read_bytes()
repo = root / 'maven/androidx/graphics/graphics-path/1.0.1'
repo.mkdir(parents=True, exist_ok=True)
google = 'https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1'
upstream = root / 'upstream-1.0.1.aar'
upstream.write_bytes(urlopen(google + '.aar').read())
(repo / 'graphics-path-1.0.1.pom').write_bytes(urlopen(google + '.pom').read())
with zipfile.ZipFile(upstream) as source, zipfile.ZipFile(repo / 'graphics-path-1.0.1.aar', 'w') as target:
    for entry in source.infolist():
        target.writestr(entry, libs.get(entry.filename, source.read(entry)))
PY
```

Run the APK verifier after rebuilding; source-built binaries can differ with host
tool versions. This procedure preserves all other AAR entries.

FFmpegKitNext remains pinned to
`5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3`, version 9.0.0. Its supported non-Nix
`android.sh` workflow was used with the project's GPL/x264/MediaCodec profile,
arm64 only, NDK r27d and the same two alignment flags. The actual native sources
resolve to FFmpeg n9.0.1 and x264
`b35605ace3ddf7c1a5d67a2eb553f034aef41d55`. No FFmpeg source or license pin changed.

## Workspace commands

After preparing the local toolchains and source-built repositories:

```bash
source vendor/tooling/env.sh
./gradlew -PffmpegEnabled=true -PffmpegRepo="$PWD/local-native" \
  -PgraphicsPathRepo="$PWD/vendor/graphics-path/maven" \
  :core:test :engine-ffmpeg:testDebugUnitTest :engine-ffmpeg:lintDebug \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug
python3 tools/verify_android_native.py app/build/outputs/apk/debug/app-debug.apk \
  --json app/build/device-evidence/native-apk-report.json
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 \
  app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r -g \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e formaNative true \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
```

The explicit native smoke generates software H.264/AAC, binds a checked hardware
component, encodes, probes tracks/dimensions/duration and fully decodes the result.
This is short media qualification on one 4 KB physical device. Static 16 KB
alignment does not prove execution on a 16 KB device, long-run thermal performance,
other codecs, strict upload-size targets, URL import, images or editor parity.

## Send To behavior

Android Share / Send To → Forma accepts single or multiple video/audio files via
`ACTION_SEND`, `ACTION_SEND_MULTIPLE` and URI ClipData. A single app task receives
warm shares without resetting settings. Incoming content is copied asynchronously
and cancellably into private `files/imports`, inspected, then shown on the editor;
conversion waits for the user's tap. Only an untouched, empty editor selects the
audio default for audio-only media. Explicit presets and custom settings survive.

Shared imports wait for cold initialization and existing file operations. Retained
ViewModel state prevents duplicate import on rotation; a fresh ViewModel retries
the last share intent on activity restoration. Startup cleanup retains every import
referenced by the persisted queue and removes abandoned editor-only/partial copies.
Originals are read only. No queue schema change is needed because jobs already
store content URIs and immutable settings. Notification intents still open the queue
when the app is already running.

Eight share/lifecycle instrumentation tests cover registration, cold preparation,
rotation, fresh-ViewModel restoration, multiple-file deduplication, preserved
settings/presets, missing streams, queue reloading after sender access ends, and
warm notification navigation. The external test sender owns a permission-protected
provider: an unreadable second URI becomes importable through the share grant, then
its private copy remains readable after grants are revoked. The test APK serves
generated tones only and is separate from the shipping provider.

An additional native Share/Convert test starts the real foreground service and
checks the completed audio output by probing and fully decoding it.

## Compact conversion display

The approved default UI shows source name, duration, dimensions/audio and decimal
file size. Buttons name their actions: Add files, Convert, Stop conversion, Play
output and Save copy. Advanced settings, engine details and longer errors expand
on request; implementation milestones and explanatory paragraphs are removed from
the default workflow. Opening advanced settings preserves the current settings.

Live progress is collected by a small child view and matched to the active job.
The four metrics are Done, Speed, ETA and Battery draw. Speed is the encoder's
real-time multiplier; ETA divides the remaining selected media duration by that
speed. Unknown or invalid readings stay indeterminate, and progress stays below
100% until output verification succeeds. Four host unit tests exercise percentage,
completion, ETA and battery-unit edge cases; Compose checks assert the labels and
that progress updates do not recompose the workspace owner.

Battery watts are approximate **net whole-device battery discharge**, not isolated
SoC consumption. Public API microamps multiplied by broadcast millivolts, divided
by 1e9, gives watts. Sampling runs off the main thread every two seconds while the
view's lifecycle is started. Unsupported readings display a dash; charging displays
Charging rather than a discharge estimate. See the
[Android battery API](https://developer.android.com/reference/android/os/BatteryManager#BATTERY_PROPERTY_CURRENT_NOW).

On this ROM, an unplugged diagnostic reported status DISCHARGING, 3,747 mV and
**+701** from CURRENT_NOW, contrary to the documented negative discharge direction.
The app correctly leaves watts unavailable for that inconsistent reading. Kernel
current/voltage nodes are permission denied to both shell and the app; no root or
unit/sign guessing is used. A charging diagnostic subsequently displayed Charging.
The physical conversion screenshots confirm readable percentage, real-time speed
and ETA; a representative capture shows 39%, 5.9× and approximately six seconds
remaining. Watts on this phone are therefore not qualified.

## Downloaded media and physical exports

Two real samples were downloaded into ignored `app/build/device-evidence/downloaded-media`
and pushed only into `/sdcard/Download/Forma-Test`:

| Sample | Download source | Inspected input |
| --- | --- | --- |
| `sintel-trailer.mp4` | [W3C Sintel trailer](https://media.w3.org/2010/05/sintel/trailer.mp4) | 4,372,373 bytes; H.264/AAC; 854 × 480; 52.208333 s |
| `sample-15s.wav` | [Samplelib WAV](https://download.samplelib.com/wav/sample-15s.wav) | 3,382,316 bytes; PCM; 19.173878 s despite its filename |

Both were prepared using physical Files → Share → Forma, then converted by tapping
Convert. The resulting MP4 contains H.264/AAC at 854 × 480, lasts 52.208333 s and
is 4,142,854 bytes. The M4A contains only AAC, lasts 19.172993 s and is 385,534 bytes.
Host FFprobe checks their structure and duration; full `ffmpeg -v error -xerror`
decodes pass for both. A subsequent video conversion using the compact UI produced
the same verified output. The audio test explicitly selected Just the audio in the
existing editor; the cold generated-audio regression separately checks automatic
M4A selection.

Input SHA-256 hashes match the downloaded originals, phone originals and private
imports, confirming the originals were unchanged:

```text
sintel-trailer.mp4 b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254
sample-15s.wav     33e2e7b2ffa021275a90a26704d923fe902d3600e4ffecf06253c57778a2a986
```

Output SHA-256 hashes:

```text
MP4 a1c652e183b6a7a32115f0c4388dcfb931bd854efa89aee80ba78ce81a09f856
M4A b92b1232b054672029a0f8102548f25647de6683588754f788b3281aff655784
```

The physical target is OnePlus 9 Pro LE2125, SDK 36, arm64-v8a, with 4,096-byte pages.
The separate native H.264 smoke binds `c2.qti.avc.encoder`, checks dimensions/tracks/
duration and fully decodes the result. This does not switch the existing quality
presets to hardware or qualify H.265 and every advertised codec.

Ignored evidence includes `downloaded-conversion-report.json`, `original-integrity.json`,
the individual job/probe/decode reports, `compact-live.xml`, screenshots and the
instrumentation/build logs. Shared media and converted outputs remain in the app's
private storage; downloaded originals remain in the dedicated phone test folder.

Save copy was exercised through Android's document picker into the dedicated test
folder as `sintel-trailer_forma.mp4`. Its 4,142,854 bytes and SHA-256 match the verified
private MP4 exactly. Play output opened the installed Glimpse player and displayed
the video in playback; a screenshot records 0:23 of 0:52. No output was sent to
another person or service.

## Final acceptance for this build

On 2026-09-29, the native debug APK was installed on `10.0.0.56:38535`; the installed
APK's SHA-256 matches the workspace artifact:

```text
34bf65a6b06e3c8cb7f48b905dc5b388a32c55b4374d01083b717d6ffa782b49
```

The final Gradle build, 18 host JUnit tests (core 4, FFmpeg parser 4, app 10), and
app/engine lint pass. App lint retains 20 warnings and no errors. Required CLI
checks pass: 39 core checks, 46 acceleration policy checks, 16 MediaCodec command
checks and 13 native-package verifier tests. Static APK native payload/alignment
verification and `zipalign -c -P 16` pass.

The final physical instrumentation run reports **OK (18 tests)**, including real
native H.264 qualification and the foreground-service audio conversion. The
separate test sender APK was removed after testing; Forma remains installed.
Downloaded-media conversions, output decoding, saved-copy integrity and physical
playback pass as described above. The power-reading limitation and unrun broader
device/product gates remain explicit rather than inferred from these results.

## Public save destination qualification (2026-10-01)

The isolated `feat/public-save-destinations` lab build was tested on the OnePlus 9 Pro
at `10.0.0.56:38477` (Android 16) and an Android 9/API 28 x86_64 emulator. The
physical native test generated short video, audio, and image sources, ran each
through the production verifier, then copied the verified private outputs into
`Movies/Forma`, `Music/Forma`, and `Pictures/Forma`. Each public URI's bytes matched
its private verified output; the video/audio outputs retained their expected tracks.
The aligned native lab APK SHA-256 was
`eed0eb4962073d627d44e0884ef0302c32878c29a09e55a54330eb1bb284afdc`.
The pinned native AAR SHA-256 was
`09bd962f7d3ad66b9bc29c2df67681b55b6dc6c2472135d48490e94ea51a7d3a`.

The final OnePlus instrumentation run reported **OK (29 tests)** for public save
publication, crash recovery, filename collision, foreign-URI protection, cancellation,
corrupt readback, missing private output, delivery cards, and native video/audio/image
publication. The API 28 emulator passed public-folder permission denial and grant
cases, plus custom document-tree creation, journaled-URI recovery, a provider-changed
filename, and safe refusal to overwrite an unjournaled same-name document. Denied permission
kept the conversion complete and its private output intact. The tree test used the
Android external-storage document provider; it checked the provider-returned name.
When a tree creation was interrupted before its URI was journaled, a same-name child
cannot be proven app-owned through SAF; Forma keeps it untouched and offers a retryable
conflict instead of overwriting it.

`tools/check-core.sh` passed 49 checks, `tools/check-android-readiness.sh` passed,
the core/engine/app unit suites and native-enabled lab build passed, and `:app:lintLab`
completed without errors. `tools/verify_android_native.py` accepted both the AAR
and the APK built with the local 16 KB-aligned graphics-path dependency. The physical
tests use disposable lab media and remove their public copies; they do not validate
the older normal-package queue or replace the user's installed normal build.
