#!/usr/bin/env bash
# B-09: two replicas of the native binary, one Postgres, a TCP proxy per replica. Each scenario
# starts both, waits for a leader, injects one fault into it, watches, stops them and asks oracle.py.
#
#   kill       SIGKILL of the leader — its socket closes, the server ends the session at once
#   partition  `docker pause` of the leader's proxy — the connection stays open and nothing arrives
#   restart    `docker restart` of Postgres — every session ends
#   term       SIGTERM to the leader — kore's shutdown closes the election and releases cleanly, so the
#              other replica leads within a poll, without the wait (B-10); the leader must exit in time
#   terminate  `pg_terminate_backend` of the leader's session only — the lock is free at once while
#              the leader still believes (research §1.6); the case D12 exists for
#   freeze     SIGSTOP of the leader for longer than the server lease, then SIGCONT — THE CONTROL:
#              a paused process is the one case research D9 excludes, so the oracle must see the
#              overlap there, or it has not been shown able to see one at all
#
# RUNS=<n> repeats every scenario (default 3); ONLY="kill partition" picks scenarios. Output:
# build/faults/<timestamp>/ — one directory per invocation and never cleared, because the one early
# takeover this suite has seen (B-16) had its logs wiped by the next run. The VERDICT lines are the
# result; <scenario>-server.log is the server's side of each run, for when a timing needs explaining.
set -uo pipefail
cd "$(dirname "$0")"
ROOT=../..
RUNS=${RUNS:-3}
NET=vojak-faults
PG=vojak-faults-pg
PXA=vojak-faults-proxy-a
PXB=vojak-faults-proxy-b
OUT=build/faults/$(date +%Y%m%d-%H%M%S)
# Timing of every replica: serverLease, localLease, renewEvery, pollEvery (ms).
SERVER=4000; LOCAL=2000; RENEW=500; POLL=200
TIMING="$SERVER $LOCAL $RENEW $POLL"

cleanup() {
  for p in ${PIDS:-}; do kill -CONT "$p" 2>/dev/null; kill -KILL "$p" 2>/dev/null; done
  docker unpause "$PXA" "$PXB" >/dev/null 2>&1
  docker rm -f "$PG" "$PXA" "$PXB" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
}
trap cleanup EXIT
cleanup

docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -e POSTGRES_PASSWORD=vojak postgres:18 >/dev/null
docker run -d --name "$PXA" --network "$NET" -p 127.0.0.1:55461:5433 alpine/socat tcp-listen:5433,fork,reuseaddr "tcp-connect:$PG:5432" >/dev/null
docker run -d --name "$PXB" --network "$NET" -p 127.0.0.1:55462:5433 alpine/socat tcp-listen:5433,fork,reuseaddr "tcp-connect:$PG:5432" >/dev/null
pg_ready() { for _ in $(seq 60); do docker exec "$PG" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 && return 0; sleep 0.5; done; return 1; }
pg_ready || { echo "Postgres did not start"; exit 1; }

(cd "$ROOT" && GRADLE_OPTS=-Dorg.gradle.daemon=false ./gradlew -q :samples:two-replicas:linkReleaseExecutableLinuxX64) || { echo "build failed"; exit 1; }
BIN=$(cd "$ROOT/samples/two-replicas/build/bin/linuxX64/releaseExecutable" && pwd)/two-replicas.kexe
now() { "$BIN" now; }

mkdir -p "$OUT"; echo "logs: $OUT"
failures=0

scenario() {
  local name=$1 run=$2 expect=$3 bound=${4:-} floor=${5:-}
  local tag="$name-$run" ev="$OUT/$name-$run.events"
  docker exec "$PG" psql -U postgres -qtAc "drop table if exists vojak_epoch" >/dev/null
  "$BIN" postgresql://127.0.0.1:55461/postgres a $TIMING >"$OUT/$tag-a.log" 2>&1 & local pa=$!
  "$BIN" postgresql://127.0.0.1:55462/postgres b $TIMING >"$OUT/$tag-b.log" 2>&1 & local pb=$!
  PIDS="$pa $pb"
  local who=""
  for _ in $(seq 150); do
    grep -q "BLOCK start" "$OUT/$tag-a.log" && who=a && break
    grep -q "BLOCK start" "$OUT/$tag-b.log" && who=b && break
    sleep 0.1
  done
  [ -n "$who" ] || { echo "VERDICT $tag FAIL nobody led before the fault"; failures=$((failures + 1)); kill -KILL $pa $pb 2>/dev/null; return; }
  local pid=$pa px=$PXA; [ "$who" = b ] && pid=$pb px=$PXB
  # The server's side, on the same clock: who holds the singleton's advisory lock (by
  # application_name), how long each vojak session has been idle, and the epoch row. Read by a person
  # when a timing needs explaining; the oracle does not use it. Stamped when the query starts and again
  # when its answer is back: on a loaded box one `docker exec` took ~10 s (B-16), and one stamp then
  # names the wrong moment.
  ( while :; do
      t0=$(now); s=$(docker exec "$PG" psql -U postgres -qtAc "select coalesce(string_agg(a.application_name || '@' || a.state || ':' || coalesce(extract(epoch from now() - a.state_change)::int::text, '?'), ' '), '-') || ' | lock=' || coalesce((select string_agg(x.application_name, ',') from pg_locks l join pg_stat_activity x on x.pid = l.pid where l.locktype = 'advisory' and l.granted and l.objsubid = 1), '-') || ' | epoch=' || coalesce((select epoch || '/' || released from vojak_epoch limit 1), '-') from pg_stat_activity a where a.application_name like 'vojak %'" 2>/dev/null); echo "$t0 $s | answered=$(now)"
      sleep 0.05
    done ) >"$OUT/$tag-server.log" 2>&1 &
  local obs=$!
  sleep 2
  echo "LEADER 0 $who" >"$ev"
  echo "FAULT $(now)" >>"$ev"
  case "$name" in
    kill) kill -KILL "$pid"; echo "KILL $(now) $who" >>"$ev" ;;
    term)
      kill -TERM "$pid"
      local waited=0
      while kill -0 "$pid" 2>/dev/null && [ $waited -lt 100 ]; do sleep 0.1; waited=$((waited + 1)); done
      if kill -0 "$pid" 2>/dev/null; then echo "VERDICT $tag FAIL the leader did not exit within 10 s of SIGTERM"; failures=$((failures + 1)); fi
      echo "EXITED $(now) $who" >>"$ev" ;;
    partition) docker pause "$px" >/dev/null ;;
    restart) docker restart "$PG" >/dev/null; pg_ready ;;
    terminate) docker exec "$PG" psql -U postgres -qtAc "select pg_terminate_backend(pid) from pg_stat_activity where application_name = 'vojak $who'" >/dev/null ;;
    freeze) kill -STOP "$pid"; sleep $(( (SERVER + LOCAL) / 1000 + 2 )); kill -CONT "$pid" ;;
  esac
  sleep 10
  [ "$name" = partition ] && docker unpause "$px" >/dev/null
  kill -TERM $pa $pb 2>/dev/null
  for _ in $(seq 100); do kill -0 $pa 2>/dev/null || kill -0 $pb 2>/dev/null || break; sleep 0.1; done
  kill -KILL $pa $pb 2>/dev/null; wait $pa $pb 2>/dev/null
  echo "END $(now)" >>"$ev"
  kill $obs 2>/dev/null; wait $obs 2>/dev/null
  python3 oracle.py --scenario "$tag" --events "$ev" --expect "$expect" ${bound:+--max-takeover-ms "$bound"} ${floor:+--min-takeover-ms "$floor"} \
    "$OUT/$tag-a.log" "$OUT/$tag-b.log" || failures=$((failures + 1))
}

ONLY=${ONLY:-kill term partition restart terminate freeze}
want() { [[ " $ONLY " == *" $1 "* ]]; }
for run in $(seq "$RUNS"); do
  # After a kill the server ends the session at once; the other replica takes the lock at its next
  # poll and, the predecessor not having released cleanly, waits localLease (research D12).
  want kill && scenario kill "$run" none $((LOCAL + POLL + 1000))
  # A clean release: the other replica polls once and does not wait (research D12).
  want term && scenario term "$run" none $((POLL + 1000))
  # After a partition the server ends the session at serverLease since the last renewal; then the
  # same wait. The old leader is down by localLease since its last renewal, long before. The floor:
  # the last renewal reached the server at most renewEvery before the fault, so the session cannot
  # end before SERVER - RENEW after it, and the wait adds LOCAL (B-16).
  want partition && scenario partition "$run" none $((SERVER + LOCAL + POLL + 1000)) $((SERVER - RENEW + LOCAL))
  want restart && scenario restart "$run" none 30000
  # The other replica takes the freed lock at its next poll and waits localLease (D12).
  want terminate && scenario terminate "$run" none $((LOCAL + POLL + 1000))
  want freeze && scenario freeze "$run" overlap
done
echo "SUITE failures=$failures"
exit $(( failures > 0 ))
