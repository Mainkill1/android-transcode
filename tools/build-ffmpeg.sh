#!/usr/bin/env bash
# Opt-in source build. Review docs/ffmpeg.md before distributing the GPL-enabled profile.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/vendor/ffmpeg-kit-next"
REV=5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3
command -v git >/dev/null || { echo 'git is required.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3 is required.' >&2; exit 1; }
direct=false
if [[ "${1:-}" == "--direct" ]]; then direct=true; shift; fi
if [[ "$direct" == true ]]; then
    [[ -n "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_NDK_ROOT:-}" ]] || { echo 'The direct build needs ANDROID_SDK_ROOT and ANDROID_NDK_ROOT.' >&2; exit 1; }
else
    command -v nix >/dev/null || { echo 'Install Nix or pass --direct with a configured Android SDK/NDK; see docs/ffmpeg.md.' >&2; exit 1; }
fi
if [[ ! -d "$SOURCE" ]]; then
    mkdir -p "$ROOT/vendor"
    git clone --filter=blob:none --no-checkout https://github.com/arthenica/ffmpeg-kit-next.git "$SOURCE"
    git -C "$SOURCE" checkout --detach "$REV"
fi
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$REV" ]] || { echo 'Existing native checkout does not match the pinned revision.' >&2; exit 1; }
[[ -z "$(git -C "$SOURCE" status --porcelain --untracked-files=no)" ]] || { echo 'Native checkout has tracked modifications; refusing to build.' >&2; exit 1; }
python3 "$ROOT/tools/patch_ffprobe_cancel.py" "$SOURCE"
trap 'git -C "$SOURCE" restore -- android/ffmpeg-kit-next-android-lib/src/main/cpp/ffmpegkit.c android/ffmpeg-kit-next-android-lib/src/main/cpp/ffprobekit.c android/ffmpeg-kit-next-android-lib/src/main/cpp/fftools/ffprobe.c' EXIT
cd "$SOURCE"
if [[ "$direct" == true ]]; then
    ./android.sh --enable-gpl --enable-lib-x264 --enable-lib-android-media-codec "$@"
else
    ./nix-android.sh -p android-r27d --enable-gpl --enable-lib-x264 --enable-lib-android-media-codec "$@"
fi
printf '\nNative Maven repository: %s/prebuilt/bundle-android-aar-24-maven\n' "$SOURCE"
printf 'Required next: verify the AAR/APK with tools/verify_android_native.py, then run the physical-device smoke test.\n'
