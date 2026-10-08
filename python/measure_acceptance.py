#!/usr/bin/env python3
"""draftwatch's reference measurement harness (MEASUREMENT_CONTRACT.md).

Measures draft-model acceptance against one target checkpoint with vLLM's offline API and
writes one report at --out. Arguments, exit codes, and the report are defined by
MEASUREMENT_CONTRACT.md; its "Reference backend" section describes how vLLM is driven.

Environment:
  DRAFTWATCH_RECORD_COUNTERS  if set, also write every counter snapshot taken to this file,
                              for the harness's replay tests
"""

import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from draftwatch_harness.errors import (  # noqa: E402
    EXIT_OK, EXIT_OUT_OF_MEMORY, EXIT_UNEXPECTED, HarnessError, is_out_of_memory)
from draftwatch_harness.inputs import (  # noqa: E402
    parse_args, parse_decoding, parse_seeds, read_prompts)
from draftwatch_harness.measure import measure, write_report  # noqa: E402
from draftwatch_harness.report import ReportInvalid  # noqa: E402


def run(argv, backend_factory):
    """Runs one measurement and returns the exit code; ``backend_factory(args, decoding)``
    creates the backend, so tests can replace vLLM."""
    backend = None
    try:
        args = parse_args(argv)
        decoding = parse_decoding(args.decoding_json)
        seeds = parse_seeds(args.seeds)
        sha256, prompts = read_prompts(args.prompts)
        backend = backend_factory(args, decoding)
        report = measure(args, decoding, seeds, sha256, prompts, backend)
        write_report(report, args.out)
        return EXIT_OK
    except HarnessError as e:
        print("measure_acceptance: %s" % e, file=sys.stderr)
        return e.code
    except ReportInvalid as e:
        print("measure_acceptance: the report breaks the contract, so it was not written: %s" % e,
              file=sys.stderr)
        return EXIT_UNEXPECTED
    except Exception as e:  # the contract's "other": unexpected, retried by the engine
        if is_out_of_memory(e):
            print("measure_acceptance: out of GPU memory: %s" % e, file=sys.stderr)
            return EXIT_OUT_OF_MEMORY
        traceback.print_exc()
        return EXIT_UNEXPECTED
    finally:
        if backend is not None and hasattr(backend, "close"):
            backend.close()


def vllm_backend(args, decoding):
    from draftwatch_harness.vllm_backend import VllmBackend

    return VllmBackend(args.target_checkpoint, args.base_model, args.draft_path, decoding)


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:], vllm_backend))
