#!/usr/bin/env python3
"""The fault suite's oracle (B-09).

Reads the replicas' logs (`<ns> <candidate> <event…>`, every line on CLOCK_MONOTONIC) and the
harness's events (`<KIND> <ns> [candidate]`), and answers two questions per run:

* overlaps — did two `whileLeader` blocks ever run at once? A block runs from its `BLOCK start` to its
  `BLOCK end`; a block whose process was killed ends at the kill, and one still running when the run
  ended ends at `END`. Two spans of different replicas overlap when the later start is before the
  earlier end.
* timings — when, after the fault, the old leader stopped saying `Leader` and the other one started.

Prints one `VERDICT` line and exits 1 when the run broke its expectation: `--expect none` for a
scenario that must not overlap, `--expect overlap` for the control, which must.
"""
import argparse
import sys


def read(path):
    with open(path) as f:
        for line in f:
            parts = line.rstrip("\n").split(" ", 2)
            if len(parts) == 3 and parts[0].isdigit():
                yield int(parts[0]), parts[1], parts[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", required=True)
    ap.add_argument("--events", required=True)
    ap.add_argument("--expect", choices=["none", "overlap"], required=True)
    ap.add_argument("--max-takeover-ms", type=float, default=None)
    ap.add_argument("logs", nargs="+")
    a = ap.parse_args()

    events = {}
    for line in open(a.events):
        kind, ns, *rest = line.split()
        events[kind] = (int(ns), rest[0] if rest else None)
    fault_at = events["FAULT"][0]
    end_at = events["END"][0]
    killed = {c: ns for k, (ns, c) in events.items() if k == "KILL"}
    leader = events["LEADER"][1]

    spans, states = [], {}
    for path in a.logs:
        open_start = {}
        for ns, cand, ev in read(path):
            if ev.startswith("BLOCK start"):
                open_start[cand] = ns
            elif ev.startswith("BLOCK end") and cand in open_start:
                spans.append((cand, open_start.pop(cand), ns))
            elif ev.startswith("STATE "):
                states.setdefault(cand, []).append((ns, ev[6:]))
        for cand, start in open_start.items():
            spans.append((cand, start, killed.get(cand, end_at)))

    overlaps = []
    for i, (c1, s1, e1) in enumerate(spans):
        for c2, s2, e2 in spans[i + 1:]:
            if c1 != c2 and max(s1, s2) < min(e1, e2):
                overlaps.append((c1, s1, e1, c2, s2, e2))

    def first_after(cand, pred):
        for ns, st in states.get(cand, []):
            if ns >= fault_at and pred(st):
                return ns
        return None

    others = [c for c in states if c != leader]
    old_down = killed.get(leader) or first_after(leader, lambda s: not s.startswith("Leader"))
    # Whoever leads after the old leader stepped down — the old leader itself included: after a
    # restart it may well win the lease back, and "somebody leads again" is what recovery means.
    def first_leading_after(cand, t):
        for ns, st in states.get(cand, []):
            if ns > t and st.startswith("Leader"):
                return ns
        return None
    since = old_down if old_down is not None else fault_at
    new_up = min((t for t in (first_leading_after(c, since) for c in states) if t), default=None)
    other_up = min((t for t in (first_after(c, lambda s: s.startswith("Leader")) for c in others) if t), default=None)
    ms = lambda t: None if t is None else round((t - fault_at) / 1e6)

    print(f"TIMING {a.scenario} leader={leader} old_leader_down_after_ms={ms(old_down)} next_leader_after_ms={ms(new_up)} other_replica_leads_after_ms={ms(other_up)}")
    for o in overlaps:
        print(f"OVERLAP {a.scenario} {o[0]} {ms(o[1])}..{ms(o[2])} ms  {o[3]} {ms(o[4])}..{ms(o[5])} ms (relative to the fault)")

    problems = []
    if a.expect == "none" and overlaps:
        problems.append(f"{len(overlaps)} overlap(s)")
    if a.expect == "overlap" and not overlaps:
        problems.append("the control saw no overlap — the oracle has not been shown able to see one")
    if a.expect == "none":
        if new_up is None:
            problems.append("nobody led after the fault")
        if other_up is not None and old_down is not None and other_up < old_down:
            problems.append("the other replica led before the old leader stepped down")
        if a.max_takeover_ms is not None and new_up is not None and ms(new_up) > a.max_takeover_ms:
            problems.append(f"takeover after {ms(new_up)} ms, bound {a.max_takeover_ms}")
    verdict = "PASS" if not problems else "FAIL " + "; ".join(problems)
    print(f"VERDICT {a.scenario} {verdict} spans={len(spans)} overlaps={len(overlaps)}")
    sys.exit(0 if not problems else 1)


if __name__ == "__main__":
    main()
