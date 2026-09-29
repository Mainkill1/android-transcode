# SDD ledger — plan: docs/superpowers/plans/2026-09-29-combined-ready-device.md

User authorized one installed build containing all ready PRs and removal of old variants.
Selected heads: PR2 ea9f936, PR3 f837c85, PR4 9b0fa7f, PR6 d0f9409, PR8 ae003a7. Blocked PR5 excluded.
Baseline: image8 39 core CLI checks passed.
Task 1: in progress. Audio3 merged; settings4 conflict resolution underway. Root owns remaining config/media; UI agent owns four app files; model agent owns models/queue serializer. Runtime6 and movie2 still pending.
Ruling: current shared schema3 envelopes must be recognized by explicit structure; unified modern queue writes schema4 — otherwise overlapping branch schemas could discard saved intent.
Task 2: backup prerequisite complete. All five app APKs/private data archived in source/vendor/combined-ready/device-backup. Normal app has18 completed schema2 audio jobs and no active work. Preserve and decode actual queue before update.
Task 3: pending. No package removed and no normal app update performed yet.
Task1 intermediate proof: audio3+settings4+image8 combined build/JVM249 (core140/engine29/app80), no failures/skips; normal device18 completed audio jobs decode/round-trip without loss. Commitf533271. Runtime6 merge underway with disjoint pipeline/UI/model ownership.
