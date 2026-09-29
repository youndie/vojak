---
id: B-12
title: "Release 0.1.0: vojak-core and vojak-postgres on Maven Central, checked by an outside consumer"
status: open
priority: P2
size: S
stage: stage-3-adoption
blocked_by: [B-11]
---

# B-12 — Release 0.1.0: vojak-core and vojak-postgres on Maven Central, checked by an outside consumer

- `vojak-kore` stays off Central until kore is there (research D8); it goes to the same repository
  as kore.
- **Checked by resolving, not by reading metadata**: a consumer outside this build declares the
  coordinate, brings its own `sqlx4k-postgres`, and links on `linuxX64`.

- AC: a project outside this repository resolves `io.github.youndie.vojak:vojak-postgres:0.1.0`
  from Central and links a `linuxX64` binary that takes a lock against Postgres.
- Anchors: `.github/workflows/publish.yaml`, `gradle.properties`.
