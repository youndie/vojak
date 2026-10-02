# Backlog: one leader per role, on the Postgres the service already has

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated)
> and everything that is not an item: the goal, the stages and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.

## Goal

A Kotlin/Native service can run as two replicas and still run each of its singletons on exactly one
of them — with no coordinator but its own Postgres. The order of work follows one rule: **measure
the holding transaction before anything is built on it.** The two decisions everything compiles
against ([research](docs/research/research-architecture.md) D1 and D2) rest on hypotheses about
vacuum, a server timeout and a Rust driver's failure mode, and a wrong answer to any of them
changes the design rather than a detail.

Deliberately not goals: coordination without Postgres, spreading work across replicas, fair
queues, a faster handover through `LISTEN/NOTIFY`, and making a stale leader harmless by itself —
each with its reason in the research.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from documents in every
layer, so re-prioritising an item must never move its file.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-0-evidence` | Evidence | H1–H5 of the research answered by measurement; D1 and D2 confirmed or replaced. No library code. |
| `stage-1-lock` | Lock | A named critical section on every target: the build, the key, `withLock`, the caller's transaction. |
| `stage-2-election` | Election | Election, `whileLeader`, the fencing epoch — accepted by a fault suite whose oracle has been shown to fail. |
| `stage-3-adoption` | Adoption | The kore handover, a reference consumer, and 0.1.0 resolved by someone outside this build. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (8)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-07](docs/backlog/B-07-while-leader.md) `[ ]` | whileLeader runs the singleton's loop and cancels it before leadership is observably lost | P1 | M | B-06 |
| [B-08](docs/backlog/B-08-fencing-epoch.md) `[ ]` | Each leadership gets the next epoch, so a store can reject a stale leader | P1 | S | B-06 |
| [B-09](docs/backlog/B-09-fault-suite.md) `[ ]` | Fault suite: kill, partition and restart, judged by an overlap oracle that is shown to fail | P1 | L | B-07, B-08, B-13, B-15 |
| [B-15](docs/backlog/B-15-wait-out-the-previous-leader.md) `[ ]` | A new leader waits until the previous one must have stepped down, unless it let go cleanly | P1 | M | B-08 |
| [B-10](docs/backlog/B-10-kore-participant.md) `[ ]` | vojak-kore: hand leadership over during the drain instead of after the lease | P2 | S | B-07 |
| [B-11](docs/backlog/B-11-reference-sample.md) `[ ]` | Reference consumer: two replicas, one singleton, a store that rejects a stale epoch | P2 | M | B-09, B-10 |
| [B-12](docs/backlog/B-12-release-0-1-0.md) `[ ]` | Release 0.1.0: vojak-core and vojak-postgres on Maven Central, checked by an outside consumer | P2 | S | B-11 |
| [B-14](docs/backlog/B-14-report-the-abort-upstream.md) `[?]` | Report the I/O-error abort to sqlx4k upstream, with the fork's patch, once it has run | P3 | S | B-13 |

## Closed (7)

**Evidence**

- [B-01](docs/backlog/B-01-probe-the-holding-transaction.md) `[x]` - Measure what a held leadership costs and how it dies, before any code depends on it

**Lock**

- [B-02](docs/backlog/B-02-scaffold-and-gate.md) `[x]` - Scaffold the three modules and put the code in the same gate as the documents
- [B-03](docs/backlog/B-03-lock-name-to-key.md) `[x]` - A lock name maps to the same 64-bit key on every target
- [B-04](docs/backlog/B-04-with-lock.md) `[x]` - A named critical section: withLock, tryWithLock, and no unbounded wait
- [B-05](docs/backlog/B-05-lock-in-callers-transaction.md) `[x]` - Take a lock inside the caller's transaction and release it with that transaction

**Election**

- [B-06](docs/backlog/B-06-election.md) `[x]` - Election: hold the lock, keep the lease, publish Leadership as a StateFlow
- [B-13](docs/backlog/B-13-native-driver-aborts-on-io-errors.md) `[x]` - A patched sqlx4k-postgres that reports I/O errors instead of aborting, published from a fork

<!-- END INDEX -->

## Decisions worth not re-litigating

**The first item writes no library code.** [B-01](docs/backlog/B-01-probe-the-holding-transaction.md)
is a probe whose output is research, because the cheapest time to learn that an idle transaction
holds back vacuum is before three modules assume it does not.

**No session-level advisory lock, anywhere.** Not "not yet": research D1 and Consequence 6. The
pool under a session lock belongs to someone else, in some cases in another language, and its reset
behaviour is unverified. If B-01 refutes H1, the fallback is a session lock on a *dedicated*
connection — still not one from the caller's pool.

**On linuxX64 the driver, not the design, is the blocker.** B-01 confirmed D1 and D2 with numbers
and found that `sqlx4k-postgres` 1.13.1 aborts the process on any I/O error
([research §1.5](docs/research/research-architecture.md)). Catching it in Kotlin is impossible —
the abort happens on a Rust thread — so the way out is a decision about the driver,
[B-13](docs/backlog/B-13-native-driver-aborts-on-io-errors.md), which is a `question` on purpose:
filing in someone else's repository, or keeping a patched build, is the owner's call.
