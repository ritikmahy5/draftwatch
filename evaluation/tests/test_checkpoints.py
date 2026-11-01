import os
import tempfile
import unittest
from pathlib import Path

import _paths  # noqa: F401  (puts evaluation/ on sys.path)
from draftwatch_eval.checkpoints import (
    build_checkpoint0, check_output_dir, finalize, save_steps)


class SaveStepsTest(unittest.TestCase):
    def test_trunk_saves_every_100_steps(self):
        self.assertEqual(list(range(100, 1201, 100)), save_steps("trunk"))

    def test_branch_saves_densely_then_every_100(self):
        self.assertEqual([625, 650, 675, 700, 725, 750, 775, 800, 900, 1000, 1100, 1200],
                         save_steps("branch"))

    def test_unknown_run_is_rejected(self):
        with self.assertRaises(ValueError):
            save_steps("pilot")


class CheckpointDirTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def _write(self, path, data=b"x"):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def test_checkpoint0_leaves_out_trainer_files(self):
        model = self.root / "model"
        allowed = {
            "model.safetensors": b"weights",
            "config.json": b"{}",
            "generation_config.json": b"{}",
            "tokenizer.json": b"{}",
            "tokenizer_config.json": b"{}",
            "special_tokens_map.json": b"{}",
            "vocab.json": b"{}",
            "merges.txt": b"m",
        }
        for name, data in allowed.items():
            self._write(model / name, data)
        for name in ("trainer_state.json", "training_args.bin", "README.md", "onnx/model.onnx",
                     "runs/events"):
            self._write(model / name)
        out = build_checkpoint0(model, self.root / "trunk")
        self.assertEqual(self.root / "trunk" / "checkpoint-0", out)
        self.assertEqual(sorted(list(allowed) + ["COMPLETE"]), sorted(os.listdir(out)))
        for name, data in allowed.items():
            self.assertEqual(data, (out / name).read_bytes())

    def test_checkpoint0_needs_safetensors_weights(self):
        model = self.root / "model"
        self._write(model / "config.json")
        self._write(model / "pytorch_model.bin")
        with self.assertRaises(ValueError) as ctx:
            build_checkpoint0(model, self.root / "trunk")
        self.assertIn("safetensors", str(ctx.exception))

    def test_finalize_writes_complete_last_and_removes_training_state(self):
        ckpt = self.root / "checkpoint-100"
        for name in ("model.safetensors", "optimizer.pt", "scheduler.pt", "scaler.pt",
                     "rng_state.pth", "rng_state_0.pth", "trainer_state.json"):
            self._write(ckpt / name)
        finalize(ckpt, keep_training_state=False, final=False)
        self.assertEqual(["COMPLETE", "model.safetensors", "trainer_state.json"],
                         sorted(os.listdir(ckpt)))
        complete = (ckpt / "COMPLETE").stat().st_mtime_ns
        for name in os.listdir(ckpt):
            self.assertLessEqual((ckpt / name).stat().st_mtime_ns, complete)

    def test_finalize_keeps_training_state_when_asked_and_marks_final(self):
        ckpt = self.root / "checkpoint-600"
        for name in ("model.safetensors", "optimizer.pt", "scheduler.pt"):
            self._write(ckpt / name)
        finalize(ckpt, keep_training_state=True, final=True)
        self.assertEqual(["COMPLETE", "FINAL", "model.safetensors", "optimizer.pt", "scheduler.pt"],
                         sorted(os.listdir(ckpt)))

    def test_refuses_an_output_dir_with_checkpoints_unless_resuming(self):
        out = self.root / "run"
        check_output_dir(out, resuming=False)  # missing is fine
        self._write(out / "checkpoint-100" / "COMPLETE")
        with self.assertRaises(ValueError) as ctx:
            check_output_dir(out, resuming=False)
        self.assertIn("checkpoint-100", str(ctx.exception))
        check_output_dir(out, resuming=True)


if __name__ == "__main__":
    unittest.main()
