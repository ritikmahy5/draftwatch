"""The pilot's checks (docs/EVALUATION.md, "Pilot").

Intervals come from scripts/bootstrap_reference.py, the repository's independent implementation
of draftwatch's paired bootstrap, with the defaults of paired_bootstrap: 2,000 resamples,
confidence 0.95, seed 0.
"""

import importlib.util
import random
from pathlib import Path

DELTA = 0.02
RESAMPLES, CONFIDENCE, SEED = 2000, 0.95, 0
_REFERENCE = Path(__file__).resolve().parents[2] / "scripts" / "bootstrap_reference.py"


def _bootstrap_reference():
    spec = importlib.util.spec_from_file_location("bootstrap_reference", _REFERENCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def subset_lines(pool_lines, size, seed):
    """A subset of a reference pool for P2/P3: its pool indices in file order, and the file.

    Pool prompt 0 is always included, never as the first line: the harness warms up on its
    file's first prompt, so prompt 0 is the one P1 measured right after warming up on itself.
    """
    rng = random.Random(seed)
    indices = [0] + rng.sample(range(1, len(pool_lines)), size - 1)
    rng.shuffle(indices)
    if indices[0] == 0:
        indices[0], indices[1] = indices[1], indices[0]
    return indices, b"".join(pool_lines[i] + b"\n" for i in indices)


# Report fields that must agree for two measurements to be compared (MEASUREMENT_CONTRACT.md,
# "Comparability"); the pilot reads report files directly, so draftwatch's own check never runs.
SETUP_FIELDS = ("harness_version", "backend", "hardware")


class MixedSetup(Exception):
    """Reports to be compared were measured with different setups."""


def check_one_setup(reports):
    """Raises MixedSetup naming the first field and file that differs, given {path: report}."""
    (first_path, first), *rest = reports.items()
    for path, report in rest:
        for field in SETUP_FIELDS:
            if report.get(field) != first.get(field):
                raise MixedSetup("%s: %s is %r, but %s has %r" % (
                    path, field, report.get(field), first_path, first.get(field)))


def _counts(report, i):
    p = report["seeds"][0]["per_prompt"][i]
    return p["steps"], p["proposed"], p["accepted"]


def count_mismatches(full_report, subset_report, indices):
    """Subset positions whose (steps, proposed, accepted) differ from the full report's counts
    of the same pool prompt."""
    return [i for i, j in enumerate(indices)
            if _counts(subset_report, i) != _counts(full_report, j)]


def paired_delta(current, baseline, indices):
    """(observed, lower, upper) of alpha(current) − alpha(baseline), token_weighted, over the
    pool prompts ``indices`` (all prompts if None)."""
    n = len(current["seeds"][0]["per_prompt"])
    chosen = range(n) if indices is None else indices
    result = _bootstrap_reference().bootstrap({
        "current": [list(_counts(current, j)) for j in chosen],
        "baseline": [list(_counts(baseline, j)) for j in chosen],
        "estimator": "token_weighted",
        "metric": "alpha",
        "resamples": RESAMPLES,
        "confidence": CONFIDENCE,
        "seed": SEED,
    })
    return result["observed"], result["lower"], result["upper"]


def p4_verdict(trunk, branch25, branch300, delta=DELTA):
    """P4's three conditions, given {probe: (observed, lower, upper)} for the pilot's trunk
    checkpoint and its branch checkpoints 25 and 300 steps after the switch."""
    return {
        "control": all(observed >= -delta / 2 for observed, _, _ in trunk.values()),
        "strong_enough": any(upper < -delta / 2 for _, _, upper in branch300.values()),
        "gradual_enough": all(upper >= -delta for _, _, upper in branch25.values()),
    }
