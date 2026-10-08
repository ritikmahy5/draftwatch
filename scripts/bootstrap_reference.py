#!/usr/bin/env python3
"""Independent reference implementation of draftwatch's paired bootstrap.

Reimplements, from their published specifications, java.util.Random (the 48-bit linear
congruential generator and nextInt(bound) as documented in its Javadoc), the two estimators of
MEASUREMENT_CONTRACT.md, and the bootstrap procedure documented in PairedBootstrap. The Java tests
compare their output with this script's, so the Java code is not checked against itself.

Usage: bootstrap_reference.py <input.json>
Input:  {"current": [[steps, proposed, accepted], ...], "baseline": [...],
         "estimator": "token_weighted" | "simple_mean", "metric": "alpha" | "tau",
         "resamples": B, "confidence": c, "seed": s}
Output: {"observed": ..., "lower": ..., "upper": ...} with every float in repr (round-trip) form.
"""

import json
import math
import sys


class JavaRandom:
    """java.util.Random, per its Javadoc."""

    MULTIPLIER = 0x5DEECE66D
    ADDEND = 0xB
    MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ self.MULTIPLIER) & self.MASK

    def next(self, bits):
        self.seed = (self.seed * self.MULTIPLIER + self.ADDEND) & self.MASK
        return self.seed >> (48 - bits)  # bits <= 31 here, so the result is non-negative

    def next_int(self, bound):
        if bound & -bound == bound:  # a power of two
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):  # Java retries when this int overflows
                return val


def alpha(prompts, estimator):
    if estimator == "token_weighted":
        proposed = sum(p[1] for p in prompts)
        return None if proposed == 0 else sum(p[2] for p in prompts) / proposed
    ratios = [p[2] / p[1] for p in prompts if p[1] > 0]
    return mean(ratios) if ratios else None


def tau(prompts, estimator):
    if estimator == "token_weighted":
        steps = sum(p[0] for p in prompts)
        return None if steps == 0 else (sum(p[2] for p in prompts) + steps) / steps
    lengths = [(p[2] + p[0]) / p[0] for p in prompts if p[0] > 0]
    return mean(lengths) if lengths else None


def mean(values):
    total = 0.0
    for v in values:  # left-to-right, as the Java code sums
        total += v
    return total / len(values)


def quantile(sorted_values, q):
    h = (len(sorted_values) - 1) * q
    lo = math.floor(h)
    hi = min(lo + 1, len(sorted_values) - 1)
    return sorted_values[lo] + (h - lo) * (sorted_values[hi] - sorted_values[lo])


def bootstrap(spec):
    statistic = alpha if spec["metric"] == "alpha" else tau
    estimator = spec["estimator"]
    current, baseline = spec["current"], spec["baseline"]
    n = len(current)

    def value(prompts):
        v = statistic(prompts, estimator)
        if v is None:
            raise ValueError("undefined statistic")
        return v

    observed = value(current) - value(baseline)
    rng = JavaRandom(spec["seed"])
    differences = []
    for _ in range(spec["resamples"]):
        indices = [rng.next_int(n) for _ in range(n)]
        differences.append(
            value([current[j] for j in indices]) - value([baseline[j] for j in indices]))
    differences.sort()
    c = spec["confidence"]
    return {
        "observed": observed,
        "lower": quantile(differences, (1 - c) / 2),
        "upper": quantile(differences, (1 + c) / 2),
    }


def main(argv):
    with open(argv[1], "r", encoding="utf-8") as f:
        spec = json.load(f)
    result = bootstrap(spec)
    print(json.dumps({k: repr(v) for k, v in result.items()}))


if __name__ == "__main__":
    main(sys.argv)
