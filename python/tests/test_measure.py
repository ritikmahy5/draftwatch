import json
import os
import tempfile
import unittest

import _paths
from draftwatch_harness.errors import EXIT_MODEL_LOAD, HarnessError
from replay_backend import ReplayBackend

import measure_acceptance

FIXTURE = os.path.join(_paths.FIXTURES, "synthetic_counter_snapshots.json")
DECODING = '{"temperature": 0, "max_new_tokens": 16, "num_speculative_tokens": 3, "dtype": "bfloat16"}'


class MeasureTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.prompts = os.path.join(self.dir, "prompts.jsonl")
        with open(self.prompts, "w", encoding="utf-8") as f:
            for i in range(3):
                f.write(json.dumps({"prompt": "synthetic prompt %d" % i}) + "\n")
        self.out = os.path.join(self.dir, "report.json")

    def argv(self, seeds="0"):
        return ["--target-checkpoint", self.dir, "--draft-id", "my-draft", "--draft-path",
                self.dir, "--prompts", self.prompts, "--decoding-json", DECODING, "--estimator",
                "token_weighted", "--seeds", seeds, "--out", self.out]

    def test_a_replayed_run_writes_a_valid_report_from_the_counter_deltas(self):
        backend = ReplayBackend(FIXTURE, backend="vllm==replay")
        code = measure_acceptance.run(self.argv(), lambda args, decoding: backend)
        self.assertEqual(0, code)
        with open(self.out, encoding="utf-8") as f:
            report = json.load(f)
        seed = report["seeds"][0]
        self.assertEqual(
            [{"prompt_index": 0, "steps": 4, "proposed": 12, "accepted": 6},
             {"prompt_index": 1, "steps": 2, "proposed": 6, "accepted": 2},
             {"prompt_index": 2, "steps": 3, "proposed": 8, "accepted": 4}],
            seed["per_prompt"], "the warm-up's counts are not part of any prompt")
        self.assertEqual("vllm==replay", report["backend"])
        self.assertEqual([(0, 0), (0, 0), (1, 0), (2, 0)], backend.calls)
        self.assertEqual(len(backend.snapshots), backend.next)
        self.assertTrue(backend.closed)

    def test_recording_writes_every_snapshot_in_the_fixture_format(self):
        record = os.path.join(self.dir, "counters.json")
        os.environ["DRAFTWATCH_RECORD_COUNTERS"] = record
        self.addCleanup(os.environ.pop, "DRAFTWATCH_RECORD_COUNTERS")
        backend = ReplayBackend(FIXTURE)
        self.assertEqual(0, measure_acceptance.run(self.argv(), lambda a, d: backend))
        with open(record, encoding="utf-8") as f:
            recorded = json.load(f)
        self.assertEqual(backend.fixture["snapshots"], recorded["snapshots"])
        self.assertEqual(3, recorded["num_speculative_tokens"])

    def test_exit_codes(self):
        def refuse(args, decoding):
            raise HarnessError(EXIT_MODEL_LOAD, "cannot load")

        self.assertEqual(3, measure_acceptance.run(self.argv(), refuse))
        self.assertEqual(2, measure_acceptance.run(self.argv()[2:], refuse))

        oom = type("OutOfMemoryError", (RuntimeError,), {"__module__": "torch.cuda"})

        def out_of_memory(args, decoding):
            raise oom("CUDA out of memory")

        self.assertEqual(4, measure_acceptance.run(self.argv(), out_of_memory))
        # Two seeds against a one-seed recording: the replay runs out of snapshots.
        self.assertEqual(1, measure_acceptance.run(self.argv("0,1"),
                                                   lambda a, d: ReplayBackend(FIXTURE)))
        self.assertFalse(os.path.exists(self.out), "no report on failure")


if __name__ == "__main__":
    unittest.main()
