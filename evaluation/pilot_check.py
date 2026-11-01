#!/usr/bin/env python3
"""The pilot's checks P2–P4 (docs/EVALUATION.md, "Pilot"). Each prints one line per check and
exits 1 if any check fails.

  pilot_check.py subset --pool <pool.jsonl> --size 200 --seed 0 --out <subset.jsonl>
      Writes a subset file for P2/P3 and <subset.jsonl>.indices.json (its pool indices).
  pilot_check.py p2 --full <P1 report> --subset <subset report> --indices <indices.json>
      Every subset prompt's counts must equal the full measurement's counts for that prompt.
  pilot_check.py p3 --reference <P2 report> --other <report> [<report> ...]
      Reports of the same subset on other nodes must equal P2's in every count.
  pilot_check.py p4 --reports <p4.json>
      p4.json: {"baseline": {"chat": path, "code": path}, "trunk": {...}, "branch25": {...},
      "branch300": {...}}, all measured on the P2 subset files.
"""

import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from draftwatch_eval.pilot import (  # noqa: E402
    count_mismatches, p4_verdict, paired_delta, subset_lines)


def _load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _report(ok, what):
    print("%s %s" % ("PASS" if ok else "FAIL", what))
    return ok


def subset(args):
    pool = [line for line in args.pool.read_bytes().split(b"\n") if line.strip()]
    indices, data = subset_lines(pool, args.size, args.seed)
    args.out.write_bytes(data)
    Path(str(args.out) + ".indices.json").write_text(json.dumps(indices) + "\n")
    print("wrote %d prompts to %s" % (len(indices), args.out))
    return True


def p2(args):
    indices = _load(args.indices)
    bad = count_mismatches(_load(args.full), _load(args.subset), indices)
    return _report(not bad, "P2 order independence: %d of %d prompts differ%s"
                   % (len(bad), len(indices), (" (positions %s)" % bad) if bad else ""))


def p3(args):
    reference = _load(args.reference)
    n = len(reference["seeds"][0]["per_prompt"])
    ok = True
    for other in args.other:
        report = _load(other)
        bad = count_mismatches(reference, report, list(range(n)))
        ok &= _report(not bad, "P3 %s on %s: %d of %d prompts differ"
                      % (other, report["hardware"]["gpu"], len(bad), n))
    return ok


def p4(args):
    paths = _load(args.reports)
    reports = {stage: {probe: _load(p) for probe, p in by_probe.items()}
               for stage, by_probe in paths.items()}
    deltas = {}
    for stage in ("trunk", "branch25", "branch300"):
        deltas[stage] = {probe: paired_delta(report, reports["baseline"][probe], None)
                         for probe, report in reports[stage].items()}
        for probe, (observed, lower, upper) in sorted(deltas[stage].items()):
            print("%s %s: delta %r, 95%% interval [%r, %r]" % (stage, probe, observed, lower,
                                                             upper))
    verdict = p4_verdict(deltas["trunk"], deltas["branch25"], deltas["branch300"])
    for probe, (_, lower, upper) in sorted(deltas["branch300"].items()):
        n = len(reports["branch300"][probe]["seeds"][0]["per_prompt"])
        print("expected paired half-width at 1,000 prompts (%s, branch300): %r"
              % (probe, (upper - lower) / 2 * math.sqrt(n / 1000)))
    return all([_report(verdict[name], "P4 " + name) for name in
                ("control", "strong_enough", "gradual_enough")])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("subset")
    s.add_argument("--pool", required=True, type=Path)
    s.add_argument("--size", type=int, default=200)
    s.add_argument("--seed", type=int, default=0)
    s.add_argument("--out", required=True, type=Path)
    s = sub.add_parser("p2")
    s.add_argument("--full", required=True)
    s.add_argument("--subset", required=True)
    s.add_argument("--indices", required=True)
    s = sub.add_parser("p3")
    s.add_argument("--reference", required=True)
    s.add_argument("--other", required=True, nargs="+")
    s = sub.add_parser("p4")
    s.add_argument("--reports", required=True)
    args = parser.parse_args(argv)
    ok = {"subset": subset, "p2": p2, "p3": p3, "p4": p4}[args.command](args)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
