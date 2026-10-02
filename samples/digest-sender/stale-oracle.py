#!/usr/bin/env python3
"""B-11's verdict, from the server's record only.

delivered: the partitioned leader's claim (its epoch) ran on the server after a claim with a newer
           epoch did. Without it the run did not exercise the case, whatever the table says.
applied:   the audit (a row trigger, so only changed rows) holds the old epoch after a newer one.

guarded passes on delivered and not applied; unguarded — the control — on delivered and applied.
"""
import argparse
import re
import sys

p = argparse.ArgumentParser()
p.add_argument("--scenario", required=True)
p.add_argument("--mode", choices=["guarded", "unguarded"], required=True)
p.add_argument("--leader-log", required=True)
p.add_argument("--server-log", required=True)
p.add_argument("--audit", required=True)
a = p.parse_args()

first = re.search(r"SEND digest=\d+ epoch=(\d+)", open(a.leader_log).read())
if not first:
    print(f"VERDICT {a.scenario} FAIL the leader's log has no SEND")
    sys.exit(1)
old = int(first.group(1))

claims = [int(m.group(1)) for m in re.finditer(r"update digest_state set last_sent = last_sent \+ 1, epoch = (\d+)", open(a.server_log).read())]
audit = [int(x) for x in open(a.audit).read().split()]


def after_newer(seq):
    """Whether `old` occurs in seq after some epoch greater than it."""
    newer = False
    for e in seq:
        if e > old:
            newer = True
        elif e == old and newer:
            return True
    return False


delivered = after_newer(claims)
applied = after_newer(audit)
newest = max(claims, default=None)
print(f"DETAIL {a.scenario} old_epoch={old} newest_epoch={newest} claims_run={len(claims)} rows_changed={len(audit)} "
      f"stale_claim_ran={delivered} stale_claim_changed_row={applied}")
if not delivered:
    print(f"VERDICT {a.scenario} FAIL the stale claim never reached the server after a newer one — not exercised")
    sys.exit(1)
ok = (not applied) if a.mode == "guarded" else applied
why = "the stale claim changed no row" if not applied else "the stale claim changed the row"
print(f"VERDICT {a.scenario} {'PASS' if ok else 'FAIL'} {why}")
sys.exit(0 if ok else 1)
