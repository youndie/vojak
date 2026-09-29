---
id: B-14
title: "Report the I/O-error abort to sqlx4k upstream, with the fork's patch, once it has run"
status: question
priority: P3
size: S
stage: stage-3-adoption
blocked_by: [B-13]
---

# B-14 — Report the I/O-error abort to sqlx4k upstream, with the fork's patch, once it has run

[B-13](B-13-native-driver-aborts-on-io-errors.md) patches `sqlx4k-postgres` in a fork so that I/O
errors come back as failed `Result`s instead of aborting the process (research §1.5). The owner's
decision: report it upstream **after** the patched build has run in a consumer — with the probe as
the reproduction and the fork's commit as the proposed fix.

## Question

When has the fork run long enough to report it? That is the owner's call, which is why this item is
a `question` and not something the backlog loop can pick: filing in someone else's repository is
never done without the owner saying so.

- AC (once the owner says go): an issue in `smyrgeorge/sqlx4k` with the reproduction and, if the
  owner wants it, a pull request with the patch; the link recorded here and in research §1.5.
