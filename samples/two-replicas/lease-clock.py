#!/usr/bin/env python3
"""B-16: does the server lease end early when the host's wall clock jumps?

On the Linux box CLOCK_REALTIME catches up with real time in jumps of about +2.93 s every ~32 s, and
Postgres schedules its timeouts by the wall clock (GetCurrentTimestamp). This opens a transaction with
`SET LOCAL idle_in_transaction_session_timeout = '4s'`, goes idle, and times on CLOCK_MONOTONIC how
long the server takes to end the session — over TRIALS trials, while a second thread records every
jump of realtime against monotonic. A lease cut short by a jump shows up as a short trial that has a
jump inside its window.

    python3 lease-clock.py            # needs Docker; starts and removes its own Postgres
"""
import os
import subprocess
import threading
import time

PG = "vojak-lease-clock-pg"
TRIALS = int(os.environ.get("TRIALS", "40"))
LEASE_S = 4

jumps = []
stop = False


def watch_jumps():
    last = time.time() - time.monotonic()
    while not stop:
        time.sleep(0.01)
        off = time.time() - time.monotonic()
        if abs(off - last) > 0.5:
            jumps.append((time.monotonic(), off - last))
        last = off


def psql(sql):
    return subprocess.run(["docker", "exec", PG, "psql", "-U", "postgres", "-qtAc", sql],
                          capture_output=True, text=True).stdout.strip()


subprocess.run(["docker", "rm", "-f", PG], capture_output=True)
subprocess.run(["docker", "run", "-d", "--name", PG, "-e", "POSTGRES_PASSWORD=x", "postgres:18"], check=True, capture_output=True)
for _ in range(120):
    if subprocess.run(["docker", "exec", PG, "pg_isready", "-U", "postgres", "-h", "127.0.0.1"], capture_output=True).returncode == 0:
        break
    time.sleep(0.5)
time.sleep(1)

threading.Thread(target=watch_jumps, daemon=True).start()
# A long-lived observer session: one query per poll, no `docker exec` per sample.
obs = subprocess.Popen(["docker", "exec", "-i", PG, "psql", "-U", "postgres", "-qtA"],
                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)


def alive():
    obs.stdin.write("select count(*) from pg_stat_activity where application_name = 'lease';\n")
    obs.stdin.flush()
    return obs.stdout.readline().strip() == "1"


try:
    for trial in range(1, TRIALS + 1):
        holder = subprocess.Popen(["docker", "exec", "-i", "-e", "PGAPPNAME=lease", PG, "psql", "-U", "postgres", "-qtA"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        holder.stdin.write(f"begin; set local idle_in_transaction_session_timeout = '{LEASE_S}s'; select 'idle';\n")
        holder.stdin.flush()
        holder.stdout.readline()
        idle_at = time.monotonic()
        while alive() and time.monotonic() - idle_at < 3 * LEASE_S:
            time.sleep(0.01)
        ended = time.monotonic() - idle_at
        inside = [round(j, 2) for t, j in jumps if idle_at <= t <= idle_at + ended]
        print(f"TRIAL {trial} ended_after_mono_s={ended:.2f} realtime_jumps_inside={inside}", flush=True)
        holder.kill()
        time.sleep(0.3)
finally:
    stop = True
    obs.kill()
    subprocess.run(["docker", "rm", "-f", PG], capture_output=True)
