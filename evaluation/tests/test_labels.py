import unittest

import _paths  # noqa: F401  (puts evaluation/ on sys.path)
from draftwatch_eval.labels import IGNORE, check_first_batch, mask_prompt


class MaskPromptTest(unittest.TestCase):
    def test_prompt_tokens_get_no_loss(self):
        self.assertEqual([IGNORE, IGNORE, 7, 8], mask_prompt([1, 2], [1, 2, 7, 8]))
        self.assertEqual(-100, IGNORE)

    def test_non_prefix_prompt_raises(self):
        with self.assertRaises(ValueError) as ctx:
            mask_prompt([1, 3], [1, 2, 7, 8])
        self.assertIn("prefix", str(ctx.exception))

    def test_a_response_with_no_tokens_raises(self):
        with self.assertRaises(ValueError):
            mask_prompt([1, 2], [1, 2])


class FirstBatchTest(unittest.TestCase):
    def test_first_batch_matching_passes(self):
        check_first_batch(["p0001", "p0002"], "p0001")

    def test_first_batch_mismatch_names_both_ids(self):
        with self.assertRaises(ValueError) as ctx:
            check_first_batch(["p0009", "p0002"], "p0001")
        self.assertIn("p0009", str(ctx.exception))
        self.assertIn("p0001", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
