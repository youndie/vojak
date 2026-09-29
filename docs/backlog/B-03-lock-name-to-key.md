---
id: B-03
title: "A lock name maps to the same 64-bit key on every target"
status: open
priority: P1
size: S
stage: stage-1-lock
blocked_by: [B-02]
---

# B-03 — A lock name maps to the same 64-bit key on every target

D4: the key is 64-bit FNV-1a of `"<namespace>:<name>"`, computed in Kotlin. Two replicas built for
different targets — a JVM one mid-migration next to a native one — must compute the same key, or
both lead and nothing says so.

- **Pinned vectors, not a round-trip test.** A test that hashes and compares to itself passes for
  any hash; the vectors are literal numbers checked in, and the same test file runs on every target.
- Name validation lives here too: `[a-z0-9-]{1,63}`, so that a name is safe in a log line and in
  `application_name`.

- AC: the vector test passes on `jvm` and `linuxX64` from `commonTest`; changing one byte of the
  hash function turns it red on both.
- Anchors: `vojak-core/src/commonMain/kotlin/io/github/youndie/vojak/LockName.kt`,
  `vojak-core/src/commonTest/kotlin/io/github/youndie/vojak/LockNameTest.kt`.
