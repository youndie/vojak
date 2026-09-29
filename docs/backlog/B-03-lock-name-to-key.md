---
id: B-03
title: "A lock name maps to the same 64-bit key on every target"
status: done
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

## Findings — 2026-09-29

- `LockName`, `Namespace` and `LockName.advisoryKey(namespace)` in
  `vojak-core/src/commonMain/kotlin/io/github/youndie/vojak/LockName.kt`; five tests in `LockNameTest`
  pass on `jvm` and `linuxX64` (`make build` on the Linux box).
- The vectors: the three published FNV-1a 64 reference values, and the §5a sample names computed in
  Python, not in Kotlin — a mistake shared by an implementation and a test written in the same
  language cannot hide there.
- **Mutation**: the FNV prime changed by one byte (`…1b3` → `…1b5`) turns
  `fnv1a64MatchesTheReferenceVectors` and `theKeyIsTheHashOfNamespaceColonName` red on both targets;
  restored.
- A `Namespace` type rather than a second `LockName`: the brief had the namespace as a plain string
  on `Vojak`; it takes the same validation, and the colon ban is what keeps the key unambiguous.
