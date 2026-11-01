"""Puts evaluation/ on sys.path and names the repository's root and fixtures directory."""

import os
import sys

EVALUATION_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(EVALUATION_DIR)
FIXTURES = os.path.join(REPO, "src", "test", "resources", "fixtures")
if EVALUATION_DIR not in sys.path:
    sys.path.insert(0, EVALUATION_DIR)
