import unittest

import _paths  # noqa: F401  (puts evaluation/ on sys.path)
from draftwatch_eval.training_data import (
    TrainingDataError, Turn, disjoint_split, first_turns, keep, prompt_lines, read_jsonl,
    take, token_counts)


def chat(i, *roles):
    return {"prompt_id": "p%04d" % i,
            "messages": [{"role": r, "content": "%s %d" % (r, i)} for r in roles]}


class FirstTurnsTest(unittest.TestCase):
    def test_takes_the_first_user_and_assistant_messages(self):
        turns, skipped = first_turns([
            chat(1, "user", "assistant", "user", "assistant"),
            chat(2, "assistant", "user"),
            chat(3, "user"),
        ])
        self.assertEqual([Turn("p0001", "user 1", "assistant 1")], turns)
        self.assertEqual(2, skipped)


class DisjointSplitTest(unittest.TestCase):
    def setUp(self):
        self.turns = [Turn("p%04d" % i, "prompt %d" % i, "reply %d" % i) for i in range(100)]

    def test_trunk_and_branch_share_no_prompt_id(self):
        trunk, branch = disjoint_split(self.turns, [], 60, 30, 0)
        self.assertEqual((60, 30), (len(trunk), len(branch)))
        self.assertEqual(set(), {t.prompt_id for t in trunk} & {t.prompt_id for t in branch})

    def test_split_does_not_depend_on_input_order(self):
        self.assertEqual(disjoint_split(self.turns, [], 60, 30, 0),
                         disjoint_split(list(reversed(self.turns)), [], 60, 30, 0))

    def test_a_turn_matching_a_probe_prompt_fails_loudly(self):
        with self.assertRaises(TrainingDataError) as ctx:
            disjoint_split(self.turns, ["  PROMPT 42 "], 60, 30, 0)
        self.assertIn("p0042", str(ctx.exception))

    def test_too_few_turns_reports_counts(self):
        with self.assertRaises(TrainingDataError) as ctx:
            disjoint_split(self.turns, [], 80, 30, 0)
        self.assertIn("100", str(ctx.exception))
        self.assertIn("110", str(ctx.exception))


    def test_prompts_that_normalize_alike_are_used_once(self):
        turns = self.turns + [Turn("p9999", "  PROMPT 5 ", "other reply")]
        trunk, branch = disjoint_split(turns, [], 60, 40, 0)
        prompts = [t.prompt.strip().lower() for t in trunk + branch]
        self.assertEqual(1, prompts.count("prompt 5"))
        self.assertNotIn("p9999", [t.prompt_id for t in trunk + branch])

    def test_a_repeated_prompt_id_fails_loudly(self):
        turns = self.turns + [Turn("p0007", "different prompt", "reply")]
        with self.assertRaises(TrainingDataError) as ctx:
            disjoint_split(turns, [], 60, 30, 0)
        self.assertIn("p0007", str(ctx.exception))

class KeepTest(unittest.TestCase):
    def test_keep_applies_both_limits_and_requires_an_ending(self):
        self.assertTrue(keep(768, 1024, True))
        self.assertFalse(keep(769, 1000, True))
        self.assertFalse(keep(10, 1025, True))
        self.assertFalse(keep(10, 100, False))


class TakeTest(unittest.TestCase):
    def test_take_returns_the_first_needed(self):
        self.assertEqual([0, 1, 2], take(iter(range(10)), 3, "trunk examples"))

    def test_take_reports_counts_when_short(self):
        with self.assertRaises(TrainingDataError) as ctx:
            take(range(38399), 38400, "trunk examples")
        self.assertIn("38399", str(ctx.exception))
        self.assertIn("38400", str(ctx.exception))
        self.assertIn("trunk examples", str(ctx.exception))


class FakeTokenizer:
    """Tokens are characters; the template wraps each message as <role>content</role>."""

    def apply_chat_template(self, conversation, add_generation_prompt=False, tokenize=True,
                            return_dict=True):
        assert tokenize and not return_dict
        text = "".join("<%s>%s</%s>" % (m["role"], m["content"], m["role"])
                       for m in conversation)
        if add_generation_prompt:
            text += "<assistant>"
        return [ord(c) for c in text]


class TokenCountsTest(unittest.TestCase):
    def test_counts_response_and_whole_example(self):
        response, total = token_counts(FakeTokenizer(), "hi", "yo")
        self.assertEqual(len("<user>hi</user><assistant>yo</assistant>"), total)
        self.assertEqual(total - len("<user>hi</user><assistant>"), response)


class PromptLinesTest(unittest.TestCase):
    def test_reads_the_user_message_of_each_probe_line(self):
        data = (b'{"messages": [{"role": "user", "content": "a\\nb"}]}\n'
                b'{"messages": [{"role": "user", "content": "c"}]}\n')
        self.assertEqual(["a\nb", "c"], prompt_lines(data))


class MergingTokenizer(FakeTokenizer):
    """Like FakeTokenizer, but a response starting with a space merges with the prompt's end."""

    def apply_chat_template(self, conversation, add_generation_prompt=False, tokenize=True,
                            return_dict=True):
        ids = super().apply_chat_template(conversation, add_generation_prompt, tokenize,
                                          return_dict)
        if len(conversation) == 2 and conversation[1]["content"].startswith(" "):
            ids[len("<user>hi</user><assistant>") - 1] = -1
        return ids


class TokenCountsPrefixTest(unittest.TestCase):
    def test_an_example_whose_prompt_is_not_a_token_prefix_has_no_counts(self):
        self.assertIsNone(token_counts(MergingTokenizer(), "hi", " yo"))
        self.assertIsNotNone(token_counts(MergingTokenizer(), "hi", "yo"))


class ReadJsonlTest(unittest.TestCase):
    def test_unicode_line_separators_inside_strings_stay_in_their_record(self):
        data = ('{"id": "a", "prompt": "x\u2028y\u0085z"}\n{"id": "b", "prompt": "w"}\n'
                .encode("utf-8"))
        self.assertIn("\u2028".encode("utf-8"), data)
        self.assertEqual(["a", "b"], [r["id"] for r in read_jsonl(data)])


if __name__ == "__main__":
    unittest.main()
