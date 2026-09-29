# Combined ready-PR build

This integration branch produces one native Forma installation containing the reviewed clip/movie editor, audio editor, settings, runtime-codec/size-limit workflow, and image editor.

| PR | Included reviewed head |
| --- | --- |
| #2 Clip and movie editing | ea9f93603c4f8e55a50b4bfe278f4d805d967256 |
| #3 Audio editing | f837c85b31561d8327fb624f5814658370447b42 |
| #4 Settings and power safeguards | 9b0fa7fc73527f97b95734ffbaed69494375f2af |
| #6 Runtime codec trials and byte caps | d0f9409d2fd8844473cf7c41e9bbadbffd7b5993 |
| #8 Image editing | ae003a7916bf18efd0224635c4eaa7e9ae82d9ed |

PR #5 remains a draft and is excluded. The original branches and main are preserved.

The queue writes schema 4 with explicit AV/image tags, frozen preferences, completion times, and complete AV cap/sequence/settings intent. Readers distinguish the previously published schema-2 audio/clip/runtime and schema-3 image/settings/movie envelopes. Unknown or hybrid envelopes fail without rewriting the original file. Future audio graphs remain preserved and visible; unsupported processing stays blocked.

Single files and movies share staged-original execution, one bounded codec/size retry budget, native preparation, full decoding and stream-clock verification, audio analysis and publication rollback. Movie clips carry their own DSP and clip effects. Unsupported measured-normalization combinations and per-clip file caps fail clearly rather than being ignored. Fixed/lossless audio gets one unchanged verified candidate; the selected output format is retained.

The app remains Kotlin/Compose with in-process source-built FFmpeg. API26 minimum, SDK36, source pins and GPL/native licensing are unchanged. The image-capable bundle adds the already-reviewed PNG/JPEG/WebP support. Test-only sources are outside product source sets and can be disabled with `formaTests=false`.

Qualification, installed APK hashes and private device backups are retained under the workspace's `vendor/combined-ready/`; they are not committed. Host tests, APK alignment checks and physical media results are separate evidence. Installation updates `dev.forma.transcode` in place; the older lab/test packages are removed only after its data is archived and the replacement is qualified.
