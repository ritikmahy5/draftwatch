"""One measurement: warm-up, one call per prompt per seed, deltas, report (D82-D85)."""

import json
import os
import time

from . import HARNESS_VERSION
from .counters import check_warm_up, delta
from .errors import EXIT_BACKEND_COUNTERS, HarnessError
from .report import build_report, seed_report, to_json_text, validate

RECORD_COUNTERS = "DRAFTWATCH_RECORD_COUNTERS"


def measure(args, decoding, seeds, sha256, prompts, backend, clock=time.monotonic,
            environ=os.environ):
    """The report of one run; ``backend`` has ``snapshot()``, ``generate(prompt, seed)``,
    ``backend``, ``adapter_handling``, and ``hardware()``.

    :raises HarnessError: with the contract exit code
    """
    started = clock()
    k = decoding["num_speculative_tokens"]
    recorded = []

    def snapshot(label):
        s = backend.snapshot()
        recorded.append({"label": label, "counters": s.to_json()})
        return s

    before = snapshot("warm-up before")
    backend.generate(prompts[0], seeds[0])
    after = snapshot("warm-up after")
    check_warm_up(before, after)

    seed_reports = []
    for seed in seeds:
        counts = []
        for prompt in prompts:
            what = "prompt %d with seed %d" % (prompt.index, seed)
            before = snapshot("before " + what)
            backend.generate(prompt, seed)
            after = snapshot("after " + what)
            counts.append(delta(before, after, k, what))
        entry = seed_report(seed, counts, k, args.estimator)
        if entry["alpha"] is None or entry["tau"] is None:
            raise HarnessError(
                EXIT_BACKEND_COUNTERS,
                "seed %d proposed no draft tokens, so alpha is undefined (contract, Estimator)"
                % seed)
        seed_reports.append(entry)

    if environ.get(RECORD_COUNTERS):
        with open(environ[RECORD_COUNTERS], "w", encoding="utf-8") as f:
            json.dump({"source": "recorded by python/measure_acceptance.py with " + backend.backend,
                       "num_speculative_tokens": k, "seeds": seeds,
                       "num_prompts": len(prompts), "snapshots": recorded}, f, indent=2)
            f.write("\n")

    report = build_report(
        harness_version=HARNESS_VERSION,
        backend=backend.backend,
        adapter_handling=backend.adapter_handling,
        estimator=args.estimator,
        draft_id=args.draft_id,
        prompt_set_sha256=sha256,
        num_prompts=len(prompts),
        decoding=decoding,
        seed_reports=seed_reports,
        hardware=backend.hardware(),
        wall_clock_seconds=float(clock() - started),
    )
    validate(report, {
        "estimator": args.estimator, "draft_id": args.draft_id, "decoding": decoding,
        "prompt_set_sha256": sha256, "num_prompts": len(prompts), "seeds": seeds,
        "adapter_handling": "merged" if args.base_model is not None else "none",
    })
    return report


def write_report(report, path):
    """Writes the report at ``path`` (a temporary file, then a rename)."""
    text = to_json_text(report)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)
