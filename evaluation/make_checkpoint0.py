#!/usr/bin/env python3
"""Builds checkpoint-0, the released target that is both runs' baseline (docs/EVALUATION.md).

Usage: make_checkpoint0.py --model <released model dir> --trunk <trunk output dir>
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from draftwatch_eval.checkpoints import build_checkpoint0  # noqa: E402


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, type=Path, help="the released target model")
    parser.add_argument("--trunk", required=True, type=Path, help="run 1's output directory")
    args = parser.parse_args(argv)
    print(build_checkpoint0(args.model, args.trunk))
    return 0


if __name__ == "__main__":
    sys.exit(main())
