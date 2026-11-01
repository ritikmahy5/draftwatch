import copy
import json
import os
import unittest

import _paths
from draftwatch_eval.pilot import count_mismatches, p4_verdict, paired_delta, subset_lines

# A real report (Slurm job 10859767, A100, 20 prompts), so the checks run on real per-prompt
# counts; no number below is derived from it except by the functions under test.
with open(os.path.join(_paths.FIXTURES, "real_10859767_report.json"), encoding="utf-8") as f:
    REAL = json.load(f)


def subset_report(full, indices):
    """The report a measurement of ``indices`` (in that order) would give, if counts are
    independent of which other prompts were measured."""
    report = copy.deepcopy(full)
    per_prompt = full["seeds"][0]["per_prompt"]
    report["seeds"][0]["per_prompt"] = [
        dict(per_prompt[j], prompt_index=i) for i, j in enumerate(indices)]
    return report


class SubsetLinesTest(unittest.TestCase):
    def test_subset_includes_prompt_zero_but_not_first(self):
        pool = [("line %d" % i).encode() for i in range(50)]
        for seed in range(10):
            indices, data = subset_lines(pool, 10, seed)
            self.assertEqual(10, len(indices))
            self.assertEqual(10, len(set(indices)))
            self.assertIn(0, indices)
            self.assertNotEqual(0, indices[0])
            self.assertEqual(b"".join(pool[i] + b"\n" for i in indices), data)

    def test_subset_is_deterministic_for_a_seed(self):
        pool = [b"x%d" % i for i in range(50)]
        self.assertEqual(subset_lines(pool, 10, 3), subset_lines(pool, 10, 3))


class CountMismatchesTest(unittest.TestCase):
    def test_identical_counts_have_no_mismatches(self):
        indices = [3, 0, 7, 19]
        self.assertEqual([], count_mismatches(REAL, subset_report(REAL, indices), indices))

    def test_one_changed_prompt_is_reported(self):
        indices = [3, 0, 7, 19]
        changed = subset_report(REAL, indices)
        changed["seeds"][0]["per_prompt"][2]["accepted"] -= 1
        self.assertEqual([2], count_mismatches(REAL, changed, indices))


class PairedDeltaTest(unittest.TestCase):
    def test_paired_delta_of_a_report_with_itself_is_zero(self):
        self.assertEqual((0.0, 0.0, 0.0), paired_delta(REAL, REAL, None))
        self.assertEqual((0.0, 0.0, 0.0), paired_delta(REAL, REAL, [1, 2, 3]))


class P4VerdictTest(unittest.TestCase):
    OK = (0.0, -0.005, 0.005)

    def test_control_needs_every_probe_at_or_above_minus_half_delta(self):
        self.assertTrue(p4_verdict({"chat": (-0.01, -0.02, 0.0), "code": self.OK},
                                   {"chat": self.OK, "code": self.OK},
                                   {"chat": self.OK, "code": self.OK})["control"])
        self.assertFalse(p4_verdict({"chat": (-0.011, -0.02, 0.0), "code": self.OK},
                                    {"chat": self.OK, "code": self.OK},
                                    {"chat": self.OK, "code": self.OK})["control"])

    def test_strong_enough_needs_one_probe_clearly_below_minus_half_delta(self):
        verdict = p4_verdict({"chat": self.OK, "code": self.OK},
                             {"chat": self.OK, "code": self.OK},
                             {"chat": (-0.03, -0.05, -0.0101), "code": self.OK})
        self.assertTrue(verdict["strong_enough"])
        verdict = p4_verdict({"chat": self.OK, "code": self.OK},
                             {"chat": self.OK, "code": self.OK},
                             {"chat": (-0.03, -0.05, -0.01), "code": self.OK})
        self.assertFalse(verdict["strong_enough"])

    def test_gradual_enough_fails_if_any_probe_is_already_clearly_below_minus_delta(self):
        verdict = p4_verdict({"chat": self.OK, "code": self.OK},
                             {"chat": self.OK, "code": (-0.04, -0.06, -0.021)},
                             {"chat": self.OK, "code": self.OK})
        self.assertFalse(verdict["gradual_enough"])
        verdict = p4_verdict({"chat": self.OK, "code": self.OK},
                             {"chat": self.OK, "code": (-0.04, -0.06, -0.02)},
                             {"chat": self.OK, "code": self.OK})
        self.assertTrue(verdict["gradual_enough"])


if __name__ == "__main__":
    unittest.main()
