"""The report (contract "Report schema") built from per-prompt counts, and its validation.

Every total, ratio, and aggregate is derived from the per-prompt counts by one function, with
plain left-to-right sums in the engine's order, so a report is consistent by construction.
``validate`` then checks contract rules 3-29 under their ids and in their order before the
report is written; the engine's ``ReportParser`` remains the authority.
"""

import json
import math

from . import SCHEMA_VERSION

TOLERANCE = 1e-9


class ReportInvalid(Exception):
    """A contract rule the report breaks: ``rule`` is its id."""

    def __init__(self, rule, message):
        super().__init__("report rule %s: %s" % (rule, message))
        self.rule = rule


def mean(values):
    total = 0.0
    for v in values:
        total += v
    return total / len(values)


def sample_std(values):
    if len(values) == 1:
        return None
    m = mean(values)
    squares = 0.0
    for v in values:
        squares += (v - m) * (v - m)
    return math.sqrt(squares / (len(values) - 1))


def estimate(per_prompt, estimator):
    """``(alpha, tau, excluded_prompts)`` under the estimator; ``None`` where undefined."""
    if estimator == "token_weighted":
        steps = sum(p["steps"] for p in per_prompt)
        proposed = sum(p["proposed"] for p in per_prompt)
        accepted = sum(p["accepted"] for p in per_prompt)
        alpha = accepted / proposed if proposed > 0 else None
        tau = (accepted + steps) / steps if steps > 0 else None
        return alpha, tau, 0
    ratios = [p["accepted"] / p["proposed"] for p in per_prompt if p["proposed"] > 0]
    lengths = [(p["accepted"] + p["steps"]) / p["steps"] for p in per_prompt if p["steps"] > 0]
    excluded = sum(1 for p in per_prompt if p["proposed"] == 0)
    return (mean(ratios) if ratios else None), (mean(lengths) if lengths else None), excluded


def seed_report(seed, counts, k, estimator):
    """One seed's entry from its prompts' counts, in prompt index order."""
    per_prompt = [
        {"prompt_index": i, "steps": c.steps, "proposed": c.proposed, "accepted": c.accepted}
        for i, c in enumerate(counts)
    ]
    accepted_at = [sum(c.per_pos[j] for c in counts) for j in range(k)]
    total_steps = sum(c.steps for c in counts)
    eligible = [total_steps] + accepted_at[:-1]
    alpha, tau, excluded = estimate(per_prompt, estimator)
    return {
        "seed": seed,
        "alpha": alpha,
        "tau": tau,
        "alpha_by_position": [
            accepted_at[j] / eligible[j] if eligible[j] > 0 else None for j in range(k)
        ],
        "total_steps": total_steps,
        "total_proposed": sum(c.proposed for c in counts),
        "total_accepted": sum(c.accepted for c in counts),
        "excluded_prompts": excluded,
        "position_counts_exact": all(c.proposed == c.steps * k for c in counts),
        "per_prompt": per_prompt,
        "position_counts": [
            {"position": j + 1, "eligible": eligible[j], "accepted": accepted_at[j]}
            for j in range(k)
        ],
    }


def build_report(harness_version, backend, adapter_handling, estimator, draft_id,
                 prompt_set_sha256, num_prompts, decoding, seed_reports, hardware,
                 wall_clock_seconds):
    alphas = [s["alpha"] for s in seed_reports]
    taus = [s["tau"] for s in seed_reports]
    defined = all(a is not None for a in alphas) and all(t is not None for t in taus)
    return {
        "schema_version": SCHEMA_VERSION,
        "harness_version": harness_version,
        "backend": backend,
        "adapter_handling": adapter_handling,
        "draft_structure": "chain",
        "estimator": estimator,
        "draft_id": draft_id,
        "prompt_set_sha256": prompt_set_sha256,
        "num_prompts": num_prompts,
        "decoding": decoding,
        "seeds": seed_reports,
        "aggregate": {
            "alpha_mean": mean(alphas) if defined else None,
            "alpha_std": sample_std(alphas) if defined else None,
            "tau_mean": mean(taus) if defined else None,
            "tau_std": sample_std(taus) if defined else None,
        },
        "hardware": hardware,
        "wall_clock_seconds": wall_clock_seconds,
    }


def to_json_text(report):
    """The report as JSON; NaN and Infinity are refused (rule ``json``)."""
    try:
        return json.dumps(report, indent=2, allow_nan=False) + "\n"
    except ValueError as e:
        raise ReportInvalid("json", str(e))


# --- validation (contract rules 3-29) -------------------------------------------------------

_TOP = {
    "schema_version": int, "harness_version": str, "backend": str, "adapter_handling": str,
    "draft_structure": str, "estimator": str, "draft_id": str, "prompt_set_sha256": str,
    "num_prompts": int, "decoding": dict, "seeds": list, "aggregate": dict, "hardware": dict,
    "wall_clock_seconds": float,
}
_SEED = {
    "seed": int, "alpha": float, "tau": float, "alpha_by_position": list, "total_steps": int,
    "total_proposed": int, "total_accepted": int, "excluded_prompts": int,
    "position_counts_exact": bool, "per_prompt": list, "position_counts": list,
}


def _is(value, kind):
    if kind is float:
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if kind is int:
        return isinstance(value, int) and not isinstance(value, bool)
    return isinstance(value, kind)


def _keys(obj, spec, where):
    if not isinstance(obj, dict) or set(obj) != set(spec):
        raise ReportInvalid("shape", "%s must have exactly the keys %s" % (where, sorted(spec)))
    for key, kind in spec.items():
        if not _is(obj[key], kind):
            raise ReportInvalid("shape", "%s.%s must be %s" % (where, key, kind.__name__))


def _close(a, b):
    return a is not None and b is not None and abs(a - b) <= TOLERANCE


def validate(report, expected):
    """Checks rules 3-29 in order.

    :param expected: what the engine passed: ``estimator``, ``draft_id``, ``decoding``,
        ``prompt_set_sha256``, ``num_prompts``, ``seeds``, ``adapter_handling``
    :raises ReportInvalid: naming the first rule broken
    """
    _keys(report, _TOP, "report")
    _keys(report["aggregate"], {"alpha_mean": float, "alpha_std": object, "tau_mean": float,
                                "tau_std": object}, "aggregate")
    _keys(report["hardware"], {"gpu": str, "count": int}, "hardware")
    if report["hardware"]["count"] < 0 or report["wall_clock_seconds"] < 0:
        raise ReportInvalid("shape", "hardware.count and wall_clock_seconds must be >= 0")
    for i, s in enumerate(report["seeds"]):
        _keys(s, _SEED, "seeds[%d]" % i)
        for p in s["per_prompt"]:
            _keys(p, {"prompt_index": int, "steps": int, "proposed": int, "accepted": int},
                  "seeds[%d].per_prompt[]" % i)
        for p in s["position_counts"]:
            _keys(p, {"position": int, "eligible": int, "accepted": int},
                  "seeds[%d].position_counts[]" % i)
    checks = [
        ("schema_version", report["schema_version"] == SCHEMA_VERSION),
        ("draft_structure", report["draft_structure"] == "chain"),
        ("estimator", report["estimator"] == expected["estimator"]),
        ("draft_id", report["draft_id"] == expected["draft_id"]),
        ("decoding", report["decoding"] == expected["decoding"]),
        ("prompt_set_sha256", report["prompt_set_sha256"] == expected["prompt_set_sha256"]),
        ("num_prompts", report["num_prompts"] == expected["num_prompts"]),
        ("seeds", [s["seed"] for s in report["seeds"]] == list(expected["seeds"])),
        ("adapter_handling", report["adapter_handling"] == expected["adapter_handling"]),
    ]
    for rule, ok in checks:
        if not ok:
            raise ReportInvalid(rule, "does not match what the engine passed")
    k = report["decoding"]["num_speculative_tokens"]
    for s in report["seeds"]:
        _validate_seed(s, k, report["num_prompts"], report["estimator"])
    alphas = [s["alpha"] for s in report["seeds"]]
    taus = [s["tau"] for s in report["seeds"]]
    agg = report["aggregate"]
    if not (_close(agg["alpha_mean"], mean(alphas)) and _close(agg["tau_mean"], mean(taus))):
        raise ReportInvalid("aggregate_mean", "alpha_mean or tau_mean is not the seeds' mean")
    for key, values in (("alpha_std", alphas), ("tau_std", taus)):
        std = sample_std(values)
        if (std is None) != (agg[key] is None) or (std is not None and not _close(agg[key], std)):
            raise ReportInvalid("aggregate_std", "%s is not the seeds' sample std" % key)


def _validate_seed(s, k, num_prompts, estimator):
    pp = s["per_prompt"]
    if len(pp) != num_prompts:
        raise ReportInvalid("per_prompt_length", "%d prompts, expected %d" % (len(pp), num_prompts))
    if [p["prompt_index"] for p in pp] != list(range(num_prompts)):
        raise ReportInvalid("prompt_indices", "prompt indices are not 0..num_prompts-1 in order")
    for p in pp:
        if not 0 <= p["accepted"] <= p["proposed"] <= p["steps"] * k:
            raise ReportInvalid("prompt_counts", "prompt %d: %s" % (p["prompt_index"], p))
    if (s["total_steps"], s["total_proposed"], s["total_accepted"]) != (
            sum(p["steps"] for p in pp), sum(p["proposed"] for p in pp),
            sum(p["accepted"] for p in pp)):
        raise ReportInvalid("totals", "totals are not the sums over per_prompt")
    pc = s["position_counts"]
    if not len(s["alpha_by_position"]) == len(pc) == k:
        raise ReportInvalid("position_lengths", "expected %d positions" % k)
    for j, p in enumerate(pc):
        if p["position"] != j + 1 or not 0 <= p["accepted"] <= p["eligible"]:
            raise ReportInvalid("position_counts", "position entry %d: %s" % (j, p))
    if pc[0]["eligible"] > s["total_steps"]:
        raise ReportInvalid("position_counts", "eligible at position 1 exceeds total_steps")
    for j in range(k - 1):
        if pc[j + 1]["eligible"] > pc[j]["accepted"]:
            raise ReportInvalid("position_monotone", "eligible at %d > accepted at %d" % (j + 2, j + 1))
    if sum(p["accepted"] for p in pc) != s["total_accepted"]:
        raise ReportInvalid("position_totals", "per-position accepted does not sum to total_accepted")
    for p, a in zip(pc, s["alpha_by_position"]):
        if p["eligible"] == 0:
            if a is not None:
                raise ReportInvalid("alpha_by_position", "must be null where eligible is 0")
        elif a is None or not _close(a, p["accepted"] / p["eligible"]):
            raise ReportInvalid("alpha_by_position", "position %d" % p["position"])
    if s["position_counts_exact"] != all(p["proposed"] == p["steps"] * k for p in pp):
        raise ReportInvalid("position_counts_exact", "does not match the per-prompt counts")
    alpha, tau, excluded = estimate(pp, estimator)
    if s["excluded_prompts"] != excluded:
        raise ReportInvalid("excluded_prompts", "expected %d" % excluded)
    if not 0 <= s["alpha"] <= 1:
        raise ReportInvalid("alpha_range", "alpha %r" % s["alpha"])
    if not 1 <= s["tau"] <= k + 1:
        raise ReportInvalid("tau_range", "tau %r" % s["tau"])
    if not _close(alpha, s["alpha"]):
        raise ReportInvalid("alpha", "recomputed %r, reported %r" % (alpha, s["alpha"]))
    if not _close(tau, s["tau"]):
        raise ReportInvalid("tau", "recomputed %r, reported %r" % (tau, s["tau"]))
