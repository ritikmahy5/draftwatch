#!/usr/bin/env python3
"""measure_acceptance.py with the vLLM backend replaced by recorded counter snapshots.

Used by the Java tests to check that the reference harness's reports pass ReportParser without
a GPU. Environment: DRAFTWATCH_REPLAY, the snapshot fixture to replay (DECISIONS.md D86).
"""

import os
import sys

import _paths  # noqa: F401
from replay_backend import ReplayBackend

import measure_acceptance


def backend(args, decoding):
    return ReplayBackend(
        os.environ["DRAFTWATCH_REPLAY"],
        backend="vllm==replay",
        adapter_handling="merged" if args.base_model is not None else "none")


if __name__ == "__main__":
    sys.exit(measure_acceptance.run(sys.argv[1:], backend))
