import copy
import json
import unittest

import _paths  # noqa: F401
from draftwatch_harness.counters import PromptCounts
from draftwatch_harness.report import (ReportInvalid, build_report, seed_report, to_json_text,
                                       validate)

DECODING = {"temperature": 0.7, "max_new_tokens": 16, "num_speculative_tokens": 3,
            "dtype": "bfloat16"}
COUNTS = [PromptCounts(4, 12, 6, [3, 2, 1]), PromptCounts(2, 6, 2, [2, 0, 0]),
          PromptCounts(3, 8, 4, [2, 1, 1])]


def report(seeds=(0,), estimator="token_weighted", counts=COUNTS):
    entries = [seed_report(s, counts, 3, estimator) for s in seeds]
    return build_report("0.1.0", "vllm==test", "none", estimator, "d", "a" * 64, len(counts),
                        DECODING, entries, {"gpu": "test", "count": 1}, 1.5)


def expected(r):
    return {"estimator": r["estimator"], "draft_id": "d", "decoding": DECODING,
            "prompt_set_sha256": "a" * 64, "num_prompts": r["num_prompts"],
            "seeds": [s["seed"] for s in r["seeds"]], "adapter_handling": "none"}


class ReportTest(unittest.TestCase):
    def test_counts_become_totals_eligibility_and_ratios(self):
        s = report()["seeds"][0]
        self.assertEqual((9, 26, 12), (s["total_steps"], s["total_proposed"], s["total_accepted"]))
        self.assertEqual(
            [{"position": 1, "eligible": 9, "accepted": 7},
             {"position": 2, "eligible": 7, "accepted": 3},
             {"position": 3, "eligible": 3, "accepted": 2}],
            s["position_counts"])
        self.assertEqual([7 / 9, 3 / 7, 2 / 3], s["alpha_by_position"])
        self.assertEqual(12 / 26, s["alpha"])
        self.assertEqual((12 + 9) / 9, s["tau"])
        self.assertFalse(s["position_counts_exact"], "prompt 2 proposed 8 of 9 positions")
        self.assertEqual(0, s["excluded_prompts"])

    def test_simple_mean_excludes_prompts_that_proposed_nothing(self):
        counts = COUNTS + [PromptCounts(0, 0, 0, [0, 0, 0])]
        s = report(estimator="simple_mean", counts=counts)["seeds"][0]
        self.assertEqual(1, s["excluded_prompts"])
        self.assertAlmostEqual((6 / 12 + 2 / 6 + 4 / 8) / 3, s["alpha"], places=15)
        validate(report(estimator="simple_mean", counts=counts),
                 expected(report(estimator="simple_mean", counts=counts)))

    def test_undefined_positions_are_null(self):
        counts = [PromptCounts(2, 6, 0, [0, 0, 0])]
        s = report(counts=counts)["seeds"][0]
        self.assertEqual([0.0, None, None], s["alpha_by_position"])

    def test_built_reports_pass_every_rule(self):
        for seeds in ((0,), (0, 1, 2)):
            r = report(seeds=seeds)
            validate(r, expected(r))
            if len(seeds) == 1:
                self.assertIsNone(r["aggregate"]["alpha_std"])
            else:  # identical seeds: zero up to the rounding of their mean
                self.assertAlmostEqual(0.0, r["aggregate"]["alpha_std"], places=15)

    def test_each_kind_of_damage_is_named_by_its_rule(self):
        cases = [
            ("shape", lambda r: r.update(extra=1)),
            ("shape", lambda r: r["seeds"][0].update(alpha="x")),
            ("schema_version", lambda r: r.update(schema_version=2)),
            ("draft_structure", lambda r: r.update(draft_structure="tree")),
            ("seeds", lambda r: r["seeds"][0].update(seed=5)),
            ("per_prompt_length", lambda r: r["seeds"][0]["per_prompt"].pop()),
            ("prompt_counts", lambda r: r["seeds"][0]["per_prompt"][0].update(accepted=13)),
            ("totals", lambda r: r["seeds"][0].update(total_steps=10)),
            ("position_monotone", lambda r: r["seeds"][0]["position_counts"][1].update(
                eligible=8, accepted=3)),
            ("position_totals", lambda r: r["seeds"][0]["position_counts"][2].update(accepted=1)),
            ("alpha_by_position", lambda r: r["seeds"][0]["alpha_by_position"].__setitem__(0, 0.5)),
            ("position_counts_exact", lambda r: r["seeds"][0].update(position_counts_exact=True)),
            ("alpha", lambda r: r["seeds"][0].update(alpha=0.5)),
            ("tau_range", lambda r: r["seeds"][0].update(tau=5.0)),
            ("aggregate_mean", lambda r: r["aggregate"].update(alpha_mean=0.1)),
            ("aggregate_std", lambda r: r["aggregate"].update(alpha_std=0.0)),
        ]
        for rule, damage in cases:
            r = report()
            want = expected(r)
            r = copy.deepcopy(r)
            damage(r)
            with self.assertRaises(ReportInvalid, msg=rule) as c:
                validate(r, want)
            self.assertEqual(rule, c.exception.rule)

    def test_json_refuses_nan(self):
        r = report()
        r["wall_clock_seconds"] = float("nan")
        with self.assertRaises(ReportInvalid) as c:
            to_json_text(r)
        self.assertEqual("json", c.exception.rule)
        json.loads(to_json_text(report()))


if __name__ == "__main__":
    unittest.main()
