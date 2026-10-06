# vojak

Leader election and a distributed lock for Kotlin/Native and JVM services, on the Postgres they
already have.

Run a service as two replicas and every singleton in it — a long-polling bot client, a daily
scheduler, a sweeper — runs twice. vojak keeps each of them on one replica at a time, using
Postgres advisory locks: no etcd, no ZooKeeper, no Kubernetes `Lease`, and it works on
Kotlin/Native, where the JVM libraries that do this do not exist.

**Status: built, not yet released to Maven Central.** Four modules: `vojak-core` (names, waiting, the
election), `vojak-postgres` (the store over an sqlx4k driver — Kotlin/Native and the JVM),
`vojak-jdbc` (the store over a `javax.sql.DataSource`, for a JVM service that already has JDBC) and
`vojak-kore` (the election as a kore participant). Why it is built the way it is: the
[research](docs/research/research-architecture.md); what is left: the [backlog](backlog.md).

## License

MIT — see [LICENSE](LICENSE).
