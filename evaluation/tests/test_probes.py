import json
import unittest

import _paths  # noqa: F401  (puts evaluation/ on sys.path)
from draftwatch_eval.probes import ProbeDataError, code_prompts, jsonl, select_chat, split
from draftwatch_eval.text import normalize


def row(i, **overrides):
    """An OASST2 message that passes every chat filter, with ``overrides`` applied."""
    r = {
        "message_id": "m%05d" % i,
        "parent_id": None,
        "text": "Prompt number %d?" % i,
        "role": "prompter",
        "lang": "en",
        "synthetic": False,
        "deleted": False,
        "review_result": True,
    }
    r.update(overrides)
    return r


class SelectChatTest(unittest.TestCase):
    def test_chat_filters_keep_only_first_turn_english_human_reviewed_prompts(self):
        rows = [
            row(0),
            row(1, role="assistant"),
            row(2, parent_id="m00000"),
            row(3, lang="de"),
            row(4, synthetic=True),
            row(5, deleted=True),
            row(6, review_result=False),
            row(7, review_result=None),
            row(8, text="x" * 2001),
            row(9, text="y" * 2000),
        ]
        pool, live = select_chat(rows, pool_size=1, live_size=1, seed=0)
        self.assertEqual(sorted(pool + live), ["Prompt number 0?", "y" * 2000])

    def test_chat_row_missing_a_filter_field_names_it(self):
        bad = row(3)
        del bad["synthetic"]
        with self.assertRaises(ProbeDataError) as ctx:
            select_chat([row(0), bad], pool_size=1, live_size=0, seed=0)
        self.assertIn("synthetic", str(ctx.exception))
        self.assertIn("m00003", str(ctx.exception))

    def test_chat_dedupes_before_splitting(self):
        rows = [row(i) for i in range(1100)]
        rows.append(row(5000, text="  PROMPT number 7? "))
        pool, live = select_chat(rows, pool_size=1000, live_size=100, seed=0)
        self.assertEqual(1000, len(pool))
        self.assertEqual(100, len(live))
        self.assertEqual(set(), {normalize(p) for p in pool} & {normalize(p) for p in live})
        self.assertEqual(1100, len({normalize(p) for p in pool + live}))

    def test_chat_too_few_prompts_reports_counts(self):
        rows = [row(i) for i in range(1099)]
        with self.assertRaises(ProbeDataError) as ctx:
            select_chat(rows, pool_size=1000, live_size=100, seed=0)
        self.assertIn("1099", str(ctx.exception))
        self.assertIn("1100", str(ctx.exception))

    def test_chat_selection_does_not_depend_on_input_order(self):
        rows = [row(i) for i in range(1200)]
        self.assertEqual(
            select_chat(rows, 1000, 100, 0), select_chat(list(reversed(rows)), 1000, 100, 0))


class SplitTest(unittest.TestCase):
    def test_split_is_deterministic_for_a_seed(self):
        items = list(range(50))
        self.assertEqual(split(items, 40, 5, 0), split(items, 40, 5, 0))
        self.assertNotEqual(split(items, 40, 5, 0), split(items, 40, 5, 1))
        pool, live = split(items, 40, 5, 0)
        self.assertEqual((40, 5), (len(pool), len(live)))
        self.assertEqual(set(), set(pool) & set(live))
        self.assertEqual(list(range(50)), items)

    def test_split_needs_enough_items(self):
        with self.assertRaises(ProbeDataError) as ctx:
            split(list(range(44)), 40, 5, 0)
        self.assertIn("44", str(ctx.exception))
        self.assertIn("45", str(ctx.exception))


class CodePromptsTest(unittest.TestCase):
    def test_code_prompts_wrap_each_source_with_its_fixed_instruction(self):
        humaneval = [{"task_id": "HumanEval/0", "prompt": "def f():\n"}]
        mbpp = [{"task_id": 1, "text": "Write f.", "test_list": ["assert f() == 1"]}]
        self.assertEqual(
            [
                (
                    "humaneval/HumanEval/0",
                    "Complete the following Python function. Reply with the complete function"
                    " in one Python code block.\n\n```python\ndef f():\n```",
                ),
                (
                    "mbpp/1",
                    "Write f.\n\nYour code should pass this test:\nassert f() == 1\n\n"
                    "Reply with the function in one Python code block.",
                ),
            ],
            code_prompts(humaneval, mbpp),
        )

    def test_code_row_missing_a_field_names_it(self):
        with self.assertRaises(ProbeDataError) as ctx:
            code_prompts([], [{"task_id": 9, "text": "Write g."}])
        self.assertIn("test_list", str(ctx.exception))
        self.assertIn("9", str(ctx.exception))


class JsonlTest(unittest.TestCase):
    def test_jsonl_is_one_utf8_lf_line_per_prompt(self):
        data = jsonl(["a\nb", "c\r\nd", "é"])
        self.assertFalse(data.startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(b"\r", data)
        lines = data.decode("utf-8").split("\n")
        self.assertEqual("", lines[-1])
        self.assertEqual(
            [
                {"messages": [{"role": "user", "content": "a\nb"}]},
                {"messages": [{"role": "user", "content": "c\r\nd"}]},
                {"messages": [{"role": "user", "content": "é"}]},
            ],
            [json.loads(line) for line in lines[:-1]],
        )
        self.assertIn("é".encode("utf-8"), data)


if __name__ == "__main__":
    unittest.main()
