#!/usr/bin/env bash
# B-01 probe: one Postgres, one TCP proxy in front of it, the same probe program on linuxX64 and on
# the JVM. The proxy is how a partition is made: `docker pause` freezes it, so the client's packets
# are accepted by the kernel and never forwarded — the connection stays open and nothing arrives,
# which is the case research Consequence 4 is about (a killed process closes its socket; this
# does not).
#
# Output: build/results/<target>.log (under build/, which the Mac-to-Linux replica leaves alone), each ending in an `exit=` line. The CHECK lines are the verdicts.
set -uo pipefail
cd "$(dirname "$0")"

NET=vojak-probe
PG=vojak-probe-pg
PX=vojak-probe-proxy
PG_IMAGE=${PG_IMAGE:-postgres:18}
# The driver under test; see build.gradle.kts.
SQLX4K=${SQLX4K:-1.13.1}

cleanup() {
  docker unpause "$PX" >/dev/null 2>&1
  docker rm -f "$PG" "$PX" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
}
trap cleanup EXIT
cleanup

docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -p 127.0.0.1:55432:5432 \
  -e POSTGRES_PASSWORD=vojak "$PG_IMAGE" >/dev/null
docker run -d --name "$PX" --network "$NET" -p 127.0.0.1:55433:5433 \
  alpine/socat tcp-listen:5433,fork,reuseaddr "tcp-connect:$PG:5432" >/dev/null

for _ in $(seq 60); do
  docker exec "$PG" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$PG" psql -U postgres -tAc 'select version()'

export GRADLE_OPTS=-Dorg.gradle.daemon=false
./gradlew -q -Psqlx4k="$SQLX4K" linkReleaseExecutableLinuxX64 jvmMainClasses || { echo "build failed"; exit 1; }
echo "driver: io.github.smyrgeorge:sqlx4k-postgres:$SQLX4K"

mkdir -p build/results
rm -f build/results/*.log
# One process per check: on linuxX64 a check can end its process, and a check that does would hide
# every check after it. N6 restarts Postgres, so it runs last on each target.
CHECKS=${CHECKS:-"H1 H2 H3 H4 N1 N2 N3 N4 N5 N6"}
run() {
  local target=$1; shift
  for check in $CHECKS; do
    local log="build/results/$target-$check.log"
    PROBE_CHECKS=$check timeout 120 "$@" >"$log" 2>&1
    echo "exit=$?" >>"$log"
    echo "== $target $check"
    grep -E '^(PROBE|CHECK|DONE)|^exit=|panicked|Unexpected error' "$log"
  done
}
run linuxX64 build/bin/linuxX64/releaseExecutable/probe.kexe
./gradlew -q -Psqlx4k="$SQLX4K" jvmJar >/dev/null
run jvm ./gradlew -q -Psqlx4k="$SQLX4K" jvmRun
