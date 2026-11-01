import unittest

import _paths  # noqa: F401  (puts evaluation/ on sys.path)
from draftwatch_eval.text import normalize


class NormalizeTest(unittest.TestCase):
    def test_lowercases_and_collapses_whitespace(self):
        self.assertEqual("hello world", normalize("  Hello\t\n  WORLD \r\n"))

    def test_keeps_non_ascii(self):
        self.assertEqual("café ünïcode", normalize("Café  ÜNÏCODE"))


if __name__ == "__main__":
    unittest.main()
