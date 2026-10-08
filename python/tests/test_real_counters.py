import json
import os
import re
import tempfile
import unittest

import _paths
from replay_backend import ReplayBackend

import measure_acceptance

PROMPTS = os.path.join(_paths.FIXTURES, "m8_acceptance_prompts.jsonl")
NAME = re.compile(r"real_(\d+)_counters(_[a-z]+)?\.json$")


class RealCountersTest(unittest.TestCase):
    """Counters recorded on the GPU run, replayed through the harness, give the report that run
    wrote: the report depends on nothing but the counter deltas."""

    def test_replaying_recorded_counters_reproduces_the_real_report(self):
        names = [n for n in sorted(os.listdir(_paths.FIXTURES)) if NAME.match(n)]
        if not names:
            self.skipTest("no real_<job>_counters.json from a GPU run yet")
        for name in names:
            job, suffix = NAME.match(name).groups()
            suffix = suffix or ""
            with open(os.path.join(_paths.FIXTURES, "real_%s_run%s.json" % (job, suffix))) as f:
                run = json.load(f)
            with open(os.path.join(_paths.FIXTURES, "real_%s_report%s.json" % (job, suffix))) as f:
                real = json.load(f)
            out = os.path.join(tempfile.mkdtemp(), "report.json")
            argv = ["--target-checkpoint", run["target_checkpoint"], "--draft-id", run["draft_id"],
                    "--draft-path", run["draft_path"], "--prompts", PROMPTS,
                    "--decoding-json", json.dumps(run["decoding"]), "--estimator", run["estimator"],
                    "--seeds", ",".join(str(s) for s in run["seeds"]), "--out", out]
            backend = ReplayBackend(os.path.join(_paths.FIXTURES, name), backend=real["backend"],
                                    hardware=real["hardware"])
            self.assertEqual(0, measure_acceptance.run(argv, lambda a, d: backend), name)
            with open(out) as f:
                replayed = json.load(f)
            for key in ("seeds", "aggregate", "num_prompts", "prompt_set_sha256", "decoding"):
                self.assertEqual(real[key], replayed[key], "%s: %s" % (name, key))


if __name__ == "__main__":
    unittest.main()
