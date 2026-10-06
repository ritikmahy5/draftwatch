"""Puts python/ on sys.path and names the repository's fixtures directory."""

import os
import sys

PYTHON_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(PYTHON_DIR)
FIXTURES = os.path.join(REPO, "src", "test", "resources", "fixtures")
if PYTHON_DIR not in sys.path:
    sys.path.insert(0, PYTHON_DIR)
