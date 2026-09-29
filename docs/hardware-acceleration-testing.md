# Android acceleration testing

Start with [Runtime codec trials](runtime-codec-trials.md) for the implementation,
production fallback rules, test isolation, and exact build/direct-ADB commands.

The supported device interface is raw `adb shell am instrument` followed by
`adb exec-out run-as`; no Python collector is required. Each test requires a fresh
canonical run ID and writes a non-overwriting run-specific JSON report.

Inventory reports describe advertised components only. Runtime reports exercise the
production preparer, retry executor, and output verifier. Neither is full physical-
device qualification or an equal-quality speed benchmark.

No test helper, receiver, command server, or network endpoint is included in the
production app.
