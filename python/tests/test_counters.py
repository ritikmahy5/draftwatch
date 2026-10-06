import json
import os
import unittest

import _paths
from draftwatch_harness.counters import (ACCEPTED_PER_POS, DRAFTS, Snapshot, check_warm_up,
                                         delta)
from draftwatch_harness.errors import EXIT_BACKEND_COUNTERS, HarnessError


class Metric:
    def __init__(self, name, value=None, values=None):
        self.name = name
        self.value = value
        self.values = values


def metrics(drafts, tokens, accepted, per_pos, engines=1):
    out = []
    for _ in range(engines):
        out += [Metric(DRAFTS, drafts), Metric("vllm:spec_decode_num_draft_tokens", tokens),
                Metric("vllm:spec_decode_num_accepted_tokens", accepted),
                Metric(ACCEPTED_PER_POS, values=per_pos), Metric("vllm:num_requests_running", 1)]
    return out


class CountersTest(unittest.TestCase):
    def assertCounterError(self, fn, *args, contains=""):
        with self.assertRaises(HarnessError) as c:
            fn(*args)
        self.assertEqual(EXIT_BACKEND_COUNTERS, c.exception.code)
        self.assertIn(contains, str(c.exception))

    def test_snapshot_sums_engines_and_ignores_other_metrics(self):
        s = Snapshot.from_metrics(metrics(4, 12, 5, [3, 1, 1], engines=2), 3)
        self.assertEqual((8, 24, 10, [6, 2, 2]), (s.drafts, s.draft_tokens, s.accepted, s.per_pos))

    def test_a_missing_counter_or_a_wrong_vector_length_is_exit_5(self):
        ms = [m for m in metrics(4, 12, 5, [3, 1, 1]) if m.name != DRAFTS]
        self.assertCounterError(Snapshot.from_metrics, ms, 3, contains=DRAFTS)
        ms = [m for m in metrics(4, 12, 5, [3, 1, 1]) if m.name != ACCEPTED_PER_POS]
        self.assertCounterError(Snapshot.from_metrics, ms, 3, contains=ACCEPTED_PER_POS)
        self.assertCounterError(Snapshot.from_metrics, metrics(4, 12, 5, [3, 1]), 3,
                                contains="has 2 positions")

    def test_delta_is_one_prompts_counts(self):
        c = delta(Snapshot(4, 12, 5, [3, 1, 1]), Snapshot(8, 24, 11, [6, 3, 2]), 3, "p")
        self.assertEqual((4, 12, 6, [3, 2, 1]), (c.steps, c.proposed, c.accepted, c.per_pos))

    def test_inconsistent_deltas_are_exit_5(self):
        base = Snapshot(4, 12, 5, [3, 1, 1])
        self.assertCounterError(delta, base, Snapshot(3, 12, 5, [3, 1, 1]), 3, "p",
                                contains="decreased")
        self.assertCounterError(delta, base, Snapshot(8, 24, 9, [4, 3, 2]), 3, "p",
                                contains="not prefix-based")
        self.assertCounterError(delta, base, Snapshot(8, 24, 12, [6, 3, 2]), 3, "p",
                                contains="sums to 6, but 7")
        self.assertCounterError(delta, base, Snapshot(5, 24, 5, [3, 1, 1]), 3, "p",
                                contains="out of order")
        self.assertCounterError(delta, base, Snapshot(5, 15, 7, [5, 2, 1]), 3, "p",
                                contains="more accepted at position 1 than steps")

    def test_warm_up_must_draft(self):
        s = Snapshot(4, 12, 5, [3, 1, 1])
        check_warm_up(Snapshot(0, 0, 0, [0, 0, 0]), s)
        self.assertCounterError(check_warm_up, s, s, contains="max_new_tokens 1")

    def test_the_recorded_fixture_gives_consistent_deltas(self):
        for name in sorted(os.listdir(_paths.FIXTURES)):
            if not name.endswith("counter_snapshots.json") and "_counters" not in name:
                continue
            with open(os.path.join(_paths.FIXTURES, name), encoding="utf-8") as f:
                fixture = json.load(f)
            snaps = [Snapshot.from_json(s["counters"]) for s in fixture["snapshots"]]
            for before, after in zip(snaps[0::2], snaps[1::2]):
                delta(before, after, fixture["num_speculative_tokens"], name)


if __name__ == "__main__":
    unittest.main()
