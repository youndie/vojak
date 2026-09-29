---
id: B-02
title: "Scaffold the three modules and put the code in the same gate as the documents"
status: open
priority: P1
size: S
stage: stage-1-lock
blocked_by: [B-01]
---

# B-02 — Scaffold the three modules and put the code in the same gate as the documents

There is no Gradle build. The modules are named in the research (D3, D7, D8): `vojak-core`,
`vojak-postgres`, `vojak-kore`, on `jvm`, `linuxX64`, `linuxArm64`, with the shared conventions
from sborka rather than copied build logic.

- **The code gate is a named target**, `make build`, and CI runs exactly it — the same reason the
  documentation gate is `make check`. A gate a contributor cannot run locally is found out about
  from a red pull request.
- **Postgres comes from a CI service container**, and tests that need it fail loudly when it is
  absent rather than skipping: a suite that skips its database is green over nothing.
- Blocked by B-01 because the module that holds the lock is shaped by its answer (D1 may flip).

- AC: `make build` compiles all three targets and runs `jvmTest` and `linuxX64Test`, each containing
  one test that talks to Postgres; stopping the Postgres container turns that test red.
- AC: `.github/workflows/check.yaml` runs `make check` and `make build`.
- Anchors: `settings.gradle.kts`, `vojak-core/build.gradle.kts`, `vojak-postgres/build.gradle.kts`,
  `vojak-kore/build.gradle.kts`, `Makefile`, `.github/workflows/check.yaml`.
