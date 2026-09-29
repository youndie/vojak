# vojak

Leader election and a distributed lock for Kotlin/Native and JVM services, on the Postgres they
already have.

Run a service as two replicas and every singleton in it — a long-polling bot client, a daily
scheduler, a sweeper — runs twice. vojak keeps each of them on one replica at a time, using
Postgres advisory locks: no etcd, no ZooKeeper, no Kubernetes `Lease`, and it works on
Kotlin/Native, where the JVM libraries that do this do not exist.

**Status: design.** There is no code yet. What the library will promise, and why it is built the
way it is, is in [docs/](docs/README.md) — start with the
[research](docs/research/research-architecture.md); the plan is the [backlog](backlog.md).

## License

MIT — see [LICENSE](LICENSE).
