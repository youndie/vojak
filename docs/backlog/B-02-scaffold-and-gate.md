---
id: B-02
title: "Scaffold the three modules and put the code in the same gate as the documents"
status: done
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

## Findings — 2026-09-29

- **Done, checked on the Linux box**: `make build` starts `vojak-test-pg` (Postgres 18), then
  `gradle build` — `jvmTest` and `linuxX64Test` each run `TestDatabaseTest` against it; `linuxArm64`
  compiles. With the container stopped, **both** suites go red (`linuxX64`: a failed `SQLError`, not
  an abort — a refused connection is not the `Io` path of B-13).
- **Found by the negative control**: the first version reported `jvmTest` UP-TO-DATE with Postgres
  stopped — green over nothing, because the database is an input Gradle does not see. Test tasks in
  `vojak-postgres` are now never up to date and never cached (`vojak-postgres/build.gradle.kts`).
- **CI not exercised**: there is no remote repository yet, so `.github/workflows/check.yaml`'s new
  `build` job has not run on GitHub; it calls the same `make build` that ran here.
- `.editorconfig` is the portfolio's reference copy; the sborka settings plugin refuses a build
  without one, and `updateEditorconfig` run on the replica writes into a tree the replica then
  overwrites — the file was taken from a sibling repository on the same sborka line, and
  `checkEditorconfig` accepts it.
- Research §1.4 corrected in passing: the Central check for kore had used a coordinate kore never
  had; the right one is also absent from Central, so D8 stands.
