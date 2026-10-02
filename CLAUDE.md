# CLAUDE.md — vojak

Leader election and a distributed lock for Kotlin services on Postgres advisory locks.
**Native-first** — `linuxX64` decides the design, `jvm` follows, `linuxArm64` is compiled.

**The lock and the election are built; `whileLeader`, the epoch and `vojak-kore` are not.**
`vojak-core` has names, keys, the lock API and `Election`; `vojak-postgres` the lock store and the
lease; `whileLeader`, the fencing epoch and `vojak-kore` are still paths where code *will* live
(B-07 onwards). `samples/probe/` is the B-01 probe — a separate Gradle build outside any gate.
`backlog.md` and the build are what say how far things have got; a sentence here about the state of
the project has no way to fail, so prefer them.

## How to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was read
   in the artefacts and what follows from it. Four findings decide the design and contradict the
   textbook recipe:
   - **an advisory lock belongs to a session, not a process** (§1.1) — a pool or a partition
     separates the two, and the lock alone cannot tell;
   - **Postgres does not notice a partitioned idle client for hours by default** (§1.2), which is why
     the lease is `idle_in_transaction_session_timeout` on the holding transaction (D2);
   - **neither Postgres driver of sqlx4k uses the Kotlin pool in sqlx4k's core** (§1.3) — native pools
     in Rust, the JVM in r2dbc-pool — so nothing may assume what a pool does with a session; hence
     a transaction-level lock, never a session-level one (D1).
   - **when the server ends the leader's session, the follower leads before the old leader notices**
     (§1.6, measured in every round) — taking the lock is not proof the previous leader stopped;
     D12 and B-15 are the fix, the epoch is the only guard until then.
2. [backlog.md](backlog.md) — the goal, the stages and the index. Items are one file each in
   `docs/backlog/`; the index between the markers is generated, so edit the item and run
   `python3 scripts/backlog_index.py`.
3. The layer document the task belongs to — `docs/features/`, `docs/services/`. The map is
   [docs/README.md](docs/README.md).

## Where things build

This repository is a mutagen session (`vojak`, one-way replica to the Linux box). Gradle, Docker and
native binaries run there, through the wrapper; edits, `git` and the documentation checks stay on
the Mac:

```bash
~/.claude/bin/wsl-run make build                             # the code gate: test Postgres, then gradle build
cd samples/probe && ~/.claude/bin/wsl-run ./run.sh          # the B-01 probe, both targets
CHECKS=N1 ~/.claude/bin/wsl-run ./run.sh                    # one check
```

`make build` starts `vojak-test-pg` (Postgres 18 on `127.0.0.1:55440`) and reuses it afterwards;
`docker stop vojak-test-pg` is how to see the suite fail without it. What a green `build` covers:
`jvmTest` and `linuxX64Test` run; `linuxArm64` is compiled only — Kotlin/Native has no arm64 test task
on an x86-64 host.

Results land under `samples/probe/build/results/` on the Linux side — under `build/`, because the
replica deletes anything else it did not send.

## Rules that are not visible in the code

- **The library depends on plain `io.github.smyrgeorge:sqlx4k` and takes a `Driver`.** Never a
  driver artefact: two sqlx4k drivers in one native binary do not link (research §1.4, D3).
- **Every SQL statement goes through one function.** On linuxX64 the released driver
  (`sqlx4k-postgres` 1.13.1) aborts the process on any I/O error; the fork's
  `1.13.1.1-yio` in reposilite does not (research §1.5, B-13). vojak cannot tell which one a
  consumer linked, so a workaround needs one home.
- **A statement that failed because the connection is gone is the last call on that transaction on
  linuxX64** — a `rollback()` after it aborts the process; skipping it costs one pool connection.
  A `rollback()` that is itself the first call since the session died is safe on both targets.
  Both rules live in `Sql.kt` (`endAfterFailure`, `release`); research Consequence 12 and its
  correction.
- **A test that needs Postgres fails when Postgres is absent.** It never skips, and its task is never
  up to date or taken from the build cache — the database is an input Gradle cannot see, and a cached
  green over a stopped database is exactly what B-02 found (`vojak-postgres/build.gradle.kts`).
- **An oracle is accepted only after it has been shown to fail** — the fault suite is run once with
  the lease margin removed and must report an overlap (B-09).

## Documentation checks

On the Mac, with `LOCAL=1` — the build hook otherwise sends anything that looks like a build to the
Linux box, and the checks read the working tree git is in:

```bash
LOCAL=1 make check
```

Documentation is in English; see [docs/README.md](docs/README.md) for the conventions.
