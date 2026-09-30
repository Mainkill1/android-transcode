# Software video encoders

The native Android profile includes H.265/x265, VP9/libvpx and AV1/SVT-AV1 alongside the existing H.264/x264, MediaCodec, zlib and WebP support. The Video encoder picker enables each software choice only when the packaged native runtime reports that encoder. Selecting one never substitutes a different codec.

The checked-in source pin remains unchanged. No native downloads or generated AAR/APK enter Git. The build helper supports an explicit direct Android SDK/NDK route for hosts without Nix; both routes use the same encoder profile. A built profile needs static native verification and separate real-device exports for all three formats before this PR is ready.
