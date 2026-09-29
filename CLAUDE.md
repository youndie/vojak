# CLAUDE.md — vojak

Leader election and a distributed lock for Kotlin services on Postgres advisory locks.
**Native-first** — `linuxX64` decides the design, `jvm` follows, `linuxArm64` is compiled.

**There is no code yet.** Every path under `vojak-*/src/` or `samples/` named in a document is where
the code *will* live. `backlog.md` and the build are what say how far things have got; a sentence
here about the state of the project has no way to fail, so prefer them.

## How to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was read
   in the artefacts and what follows from it. Three findings decide the design and contradict the
   textbook recipe:
   - **an advisory lock belongs to a session, not a process** (§1.1) — a pool or a partition
     separates the two, and the lock alone cannot tell;
   - **Postgres does not notice a partitioned idle client for hours by default** (§1.2), which is why
     the lease is `idle_in_transaction_session_timeout` on the holding transaction (D2);
   - **neither Postgres driver of sqlx4k uses the Kotlin pool in sqlx4k's core** (§1.3) — native pools
     in Rust, the JVM in r2dbc-pool — so nothing may assume what a pool does with a session; hence
     a transaction-level lock, never a session-level one (D1).
2. [backlog.md](backlog.md) — the goal, the stages and the index. Items are one file each in
   `docs/backlog/`; the index between the markers is generated, so edit the item and run
   `python3 scripts/backlog_index.py`.
3. The layer document the task belongs to — `docs/features/`, `docs/services/`. The map is
   [docs/README.md](docs/README.md).

## Rules that are not visible in the code

- **The library depends on plain `io.github.smyrgeorge:sqlx4k` and takes a `Driver`.** Never a
  driver artefact: two sqlx4k drivers in one native binary do not link (research §1.4, D3).
- **Every SQL statement goes through one function.** A consumer has seen the native driver turn a
  bad parameter into a process-killing panic (research Consequence 8); a workaround needs one home.
- **A test that needs Postgres fails when Postgres is absent.** It never skips.
- **An oracle is accepted only after it has been shown to fail** — the fault suite is run once with
  the lease margin removed and must report an overlap (B-09).

## Documentation checks

```bash
make check
```

Documentation is in English; see [docs/README.md](docs/README.md) for the conventions.
