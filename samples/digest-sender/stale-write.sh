#!/usr/bin/env bash
# B-11: the stale write. Two replicas of the reference consumer, one Postgres, a TCP proxy per replica.
# The leader's proxy is paused for longer than the server lease: its next claim is handed to the driver
# and waits in the proxy, the server ends its session, the other replica takes over and claims with a
# newer epoch — and then the proxy is unpaused and the old leader's claim arrives and runs (research
# §1.8: on linuxX64 cancelling the claim's coroutine does not stop it).
#
#   guarded    the store as shipped: the delayed claim must change no row
#   unguarded  THE CONTROL: the same run with the epoch dropped from the WHERE — the delayed claim must
#              change the row, or this stand has not been shown able to see a stale write at all
#
# What decides is the server's own record of what it ran, not what a replica believed: Postgres logs
# every statement it ran, and a trigger copies every row change into digest_audit. RUNS=<n> repeats
# both modes (default 2). Output: build/stale/<timestamp>/.
set -uo pipefail
cd "$(dirname "$0")"
ROOT=../..
RUNS=${RUNS:-2}
NET=vojak-stale
PG=vojak-stale-pg
PXA=vojak-stale-proxy-a
PXB=vojak-stale-proxy-b
OUT=build/stale/$(date +%Y%m%d-%H%M%S)
# Longer than serverLease (4 s) + the wait of research D12 (localLease, 2 s) + a few claims of the
# new leader.
PAUSE=10

cleanup() {
  for p in ${PIDS:-}; do kill -KILL "$p" 2>/dev/null; done
  docker unpause "$PXA" "$PXB" >/dev/null 2>&1
  docker rm -f "$PG" "$PXA" "$PXB" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
}
trap cleanup EXIT
cleanup

docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -e POSTGRES_PASSWORD=vojak postgres:18 \
  -c log_min_duration_statement=0 -c log_line_prefix='%m [%p] ' >/dev/null
docker run -d --name "$PXA" --network "$NET" -p 127.0.0.1:55471:5433 alpine/socat tcp-listen:5433,fork,reuseaddr "tcp-connect:$PG:5432" >/dev/null
docker run -d --name "$PXB" --network "$NET" -p 127.0.0.1:55472:5433 alpine/socat tcp-listen:5433,fork,reuseaddr "tcp-connect:$PG:5432" >/dev/null
for _ in $(seq 60); do docker exec "$PG" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 && break; sleep 0.5; done
sql() { docker exec "$PG" psql -U postgres -qtAc "$1"; }

(cd "$ROOT" && GRADLE_OPTS=-Dorg.gradle.daemon=false ./gradlew -q :samples:digest-sender:linkReleaseExecutableLinuxX64) || { echo "build failed"; exit 1; }
BIN=$(cd "$ROOT/samples/digest-sender/build/bin/linuxX64/releaseExecutable" && pwd)/digest-sender.kexe

mkdir -p "$OUT"; echo "logs: $OUT"
failures=0

scenario() {
  local mode=$1 run=$2 tag="$1-$2"
  # The sender's own table, created here first so the audit trigger can be put on it — the DDL is
  # DigestStore.CREATE, and the sender's `if not exists` then does nothing.
  sql "drop table if exists digest_state, digest_audit, vojak_epoch" >/dev/null
  sql "create table if not exists digest_state (id int primary key, last_sent bigint not null, epoch bigint not null)" >/dev/null
  sql "create table digest_audit (seq bigserial, epoch bigint, last_sent bigint)" >/dev/null
  sql "create or replace function digest_audit() returns trigger language plpgsql as \$\$ begin insert into digest_audit (epoch, last_sent) values (new.epoch, new.last_sent); return new; end \$\$" >/dev/null
  sql "create trigger digest_audit after update on digest_state for each row execute function digest_audit()" >/dev/null
  local from; from=$(docker logs "$PG" 2>&1 | wc -l)

  "$BIN" postgresql://127.0.0.1:55471/postgres a 18471 "$mode" >"$OUT/$tag-a.log" 2>&1 & local pa=$!
  "$BIN" postgresql://127.0.0.1:55472/postgres b 18472 "$mode" >"$OUT/$tag-b.log" 2>&1 & local pb=$!
  PIDS="$pa $pb"
  local who=""
  for _ in $(seq 150); do
    grep -q "SEND" "$OUT/$tag-a.log" && who=a && break
    grep -q "SEND" "$OUT/$tag-b.log" && who=b && break
    sleep 0.1
  done
  [ -n "$who" ] || { echo "VERDICT $tag FAIL nobody led"; failures=$((failures + 1)); kill -KILL $pa $pb 2>/dev/null; return; }
  local px=$PXA; [ "$who" = b ] && px=$PXB
  sleep 1
  docker pause "$px" >/dev/null
  sleep "$PAUSE"
  docker unpause "$px" >/dev/null
  sleep 3
  curl -s --max-time 2 http://127.0.0.1:18471/status http://127.0.0.1:18472/status >"$OUT/$tag-status.txt"
  kill -TERM $pa $pb 2>/dev/null
  for _ in $(seq 100); do kill -0 $pa 2>/dev/null || kill -0 $pb 2>/dev/null || break; sleep 0.1; done
  kill -KILL $pa $pb 2>/dev/null; wait $pa $pb 2>/dev/null

  docker logs "$PG" 2>&1 | tail -n +"$((from + 1))" >"$OUT/$tag-server.log"
  sql "select epoch from digest_audit order by seq" >"$OUT/$tag-audit.txt"
  python3 stale-oracle.py --scenario "$tag" --mode "$mode" --leader-log "$OUT/$tag-$who.log" \
    --server-log "$OUT/$tag-server.log" --audit "$OUT/$tag-audit.txt" || failures=$((failures + 1))
}

for run in $(seq "$RUNS"); do
  scenario guarded "$run"
  scenario unguarded "$run"
done
echo "STALE failures=$failures"
exit $(( failures > 0 ))
