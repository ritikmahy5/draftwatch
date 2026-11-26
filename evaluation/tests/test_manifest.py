import json
import os
import tempfile
import unittest
from pathlib import Path

import _paths  # noqa: F401  (puts evaluation/ on sys.path)
from draftwatch_eval.manifest import ManifestMismatch, write_files

A_SHA = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"  # sha256(b"a")


class WriteFilesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.out = Path(self._tmp.name) / "out"

    def tearDown(self):
        self._tmp.cleanup()

    def test_writes_files_and_manifest(self):
        hashes = write_files(self.out, {"x.jsonl": b"a"}, "m.json")
        self.assertEqual({"x.jsonl": A_SHA}, hashes)
        self.assertEqual(b"a", (self.out / "x.jsonl").read_bytes())
        self.assertEqual({"x.jsonl": A_SHA}, json.loads((self.out / "m.json").read_text()))

    def test_identical_rebuild_passes(self):
        write_files(self.out, {"x.jsonl": b"a"}, "m.json")
        self.assertEqual({"x.jsonl": A_SHA}, write_files(self.out, {"x.jsonl": b"a"}, "m.json"))

    def test_changed_rebuild_raises_and_writes_nothing(self):
        write_files(self.out, {"x.jsonl": b"a"}, "m.json")
        with self.assertRaises(ManifestMismatch) as ctx:
            write_files(self.out, {"x.jsonl": b"b", "y.jsonl": b"c"}, "m.json")
        self.assertIn("x.jsonl", str(ctx.exception))
        self.assertIn(A_SHA, str(ctx.exception))
        self.assertEqual(b"a", (self.out / "x.jsonl").read_bytes())
        self.assertFalse((self.out / "y.jsonl").exists())

    def test_new_files_are_added_to_an_existing_manifest(self):
        write_files(self.out, {"x.jsonl": b"a"}, "m.json")
        write_files(self.out, {"y.jsonl": b"a"}, "m.json")
        self.assertEqual({"x.jsonl": A_SHA, "y.jsonl": A_SHA},
                         json.loads((self.out / "m.json").read_text()))
        self.assertEqual(["m.json", "x.jsonl", "y.jsonl"], sorted(os.listdir(self.out)))


if __name__ == "__main__":
    unittest.main()
