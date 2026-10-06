import hashlib
import os
import tempfile
import unittest

import _paths  # noqa: F401
from draftwatch_harness.errors import EXIT_BAD_ARGUMENTS, HarnessError
from draftwatch_harness.inputs import parse_args, parse_decoding, parse_seeds, read_prompts

DECODING = '{"temperature": 0, "max_new_tokens": 16, "num_speculative_tokens": 3, "dtype": "bfloat16"}'


class InputsTest(unittest.TestCase):
    def prompts(self, data):
        fd, path = tempfile.mkstemp()
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        self.addCleanup(os.remove, path)
        return path

    def assertBadArguments(self, fn, *args, contains=""):
        with self.assertRaises(HarnessError) as c:
            fn(*args)
        self.assertEqual(EXIT_BAD_ARGUMENTS, c.exception.code)
        self.assertIn(contains, str(c.exception))

    def test_the_contract_arguments_parse_and_a_missing_one_is_exit_2(self):
        argv = ["--target-checkpoint", "t", "--draft-id", "d", "--draft-path", "dp", "--prompts",
                "p", "--decoding-json", DECODING, "--estimator", "token_weighted", "--seeds", "0",
                "--out", "o"]
        args = parse_args(argv)
        self.assertIsNone(args.base_model)
        self.assertBadArguments(parse_args, argv[2:], contains="--target-checkpoint")
        self.assertBadArguments(parse_args, argv[:-6] + ["--estimator", "median"] + argv[-4:])

    def test_decoding_has_exactly_the_four_keys_with_valid_values(self):
        self.assertEqual(3, parse_decoding(DECODING)["num_speculative_tokens"])
        self.assertBadArguments(parse_decoding, "{", contains="not JSON")
        self.assertBadArguments(parse_decoding, '{"temperature": 0}', contains="exactly the keys")
        for bad in ('"temperature": -1', '"temperature": true'):
            self.assertBadArguments(
                parse_decoding, DECODING.replace('"temperature": 0', bad), contains="temperature")
        self.assertBadArguments(
            parse_decoding, DECODING.replace('"num_speculative_tokens": 3',
                                             '"num_speculative_tokens": 0'),
            contains="num_speculative_tokens")

    def test_seeds_are_distinct_integers(self):
        self.assertEqual([0, 7], parse_seeds("0,7"))
        self.assertBadArguments(parse_seeds, "0,x")
        self.assertBadArguments(parse_seeds, "1,1", contains="repeated")

    def test_prompt_file_rules(self):
        data = b'{"prompt": "a"}\n\n \t\r\n{"messages": [{"role": "user", "content": "b"}]}\r\n'
        sha, prompts = read_prompts(self.prompts(data))
        self.assertEqual(hashlib.sha256(data).hexdigest(), sha)
        self.assertEqual([0, 1], [p.index for p in prompts])
        self.assertEqual("a", prompts[0].text)
        self.assertEqual("b", prompts[1].messages[0]["content"])
        for bad, why in ((b'\xef\xbb\xbf{"prompt": "a"}', "byte-order mark"),
                         (b'\xff\xfe', "not UTF-8"),
                         (b'{"prompt": "a"}\nnot json\n', "line 2 is not JSON"),
                         (b'[1]', "not a JSON object"),
                         (b'{"prompt": "a", "messages": []}', "exactly one of"),
                         (b'{"text": "a"}', "exactly one of"),
                         (b'\n  \n', "no prompts")):
            self.assertBadArguments(read_prompts, self.prompts(bad), contains=why)


if __name__ == "__main__":
    unittest.main()
