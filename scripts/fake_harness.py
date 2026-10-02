#!/usr/bin/env python3
"""Fake measurement harness for draftwatch tests.

Implements MEASUREMENT_CONTRACT.md ("Fake harness") with the standard library only, so the whole
pipeline can be tested without a GPU. Every number it writes is synthetic: per-prompt and
positional counts come from the fixture named by DRAFTWATCH_FAKE_FIXTURE, and every total,
ratio, and aggregate is derived from them. A report is therefore internally consistent unless
DRAFTWATCH_FAKE_CORRUPT deliberately breaks one validation rule.

Environment:
  DRAFTWATCH_FAKE_FIXTURE  path of the fixture JSON (required unless DRAFTWATCH_FAKE_EXIT is set)
  DRAFTWATCH_FAKE_EXIT     exit with this code without writing a report
  DRAFTWATCH_FAKE_CORRUPT  id of the one validation rule to break, in the first seed
"""

import argparse
import hashlib
import json
import math
import os
import sys
import time

HARNESS_VERSION = "fake-0.1.0"
SCHEMA_VERSION = 1
EXIT_BAD_ARGUMENTS = 2
EXIT_MODEL_LOAD = 3
ESTIMATORS = ("token_weighted", "simple_mean")


def fail(code, message):
    print("fake_harness: " + message, file=sys.stderr)
    sys.exit(code)


def parse_args(argv):
    parser = argparse.ArgumentParser(prog="fake_harness", allow_abbrev=False)
    parser.add_argument("--target-checkpoint", required=True)
    parser.add_argument("--base-model")
    parser.add_argument("--draft-id", required=True)
    parser.add_argument("--draft-path", required=True)
    parser.add_argument("--prompts", required=True)
    parser.add_argument("--decoding-json", required=True)
    parser.add_argument("--estimator", required=True, choices=ESTIMATORS)
    parser.add_argument("--seeds", required=True)
    parser.add_argument("--out", required=True)
    return parser.parse_args(argv)  # argparse exits 2 on bad arguments, as the contract requires


def read_prompts(path):
    """SHA-256 and prompt count, per MEASUREMENT_CONTRACT.md "Prompt file"."""
    with open(path, "rb") as f:
        data = f.read()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        fail(EXIT_BAD_ARGUMENTS, "prompt file is not UTF-8")
    count = 0
    for number, line in enumerate(text.split("\n"), start=1):
        if line.strip(" \t\r") == "":
            continue
        try:
            value = json.loads(line)
        except ValueError:
            fail(EXIT_BAD_ARGUMENTS, "prompt line %d is not JSON" % number)
        if not isinstance(value, dict):
            fail(EXIT_BAD_ARGUMENTS, "prompt line %d is not a JSON object" % number)
        count += 1
    return hashlib.sha256(data).hexdigest(), count


def parse_seeds(text):
    try:
        seeds = [int(part) for part in text.split(",")]
    except ValueError:
        fail(EXIT_BAD_ARGUMENTS, "--seeds must be comma-separated integers")
    return seeds


def mean(values):
    total = 0.0
    for v in values:  # plain left-to-right sum, the same order the engine uses
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


def seed_report(seed, entry, num_prompts, k, estimator):
    counts = entry["per_prompt"]
    positions = entry["position_counts"]
    if len(counts) != num_prompts:
        fail(EXIT_BAD_ARGUMENTS, "fixture has %d prompts, prompt file has %d" % (len(counts), num_prompts))
    if len(positions) != k:
        fail(EXIT_BAD_ARGUMENTS, "fixture has %d positions, num_speculative_tokens is %d" % (len(positions), k))
    per_prompt = [
        {"prompt_index": i, "steps": c["steps"], "proposed": c["proposed"], "accepted": c["accepted"]}
        for i, c in enumerate(counts)
    ]
    total_steps = sum(c["steps"] for c in counts)
    total_proposed = sum(c["proposed"] for c in counts)
    total_accepted = sum(c["accepted"] for c in counts)
    if estimator == "token_weighted":
        if total_proposed == 0 or total_steps == 0:
            fail(EXIT_BAD_ARGUMENTS, "fixture leaves alpha or tau undefined")
        alpha = total_accepted / total_proposed
        tau = (total_accepted + total_steps) / total_steps
        excluded = 0
    else:
        ratios = [c["accepted"] / c["proposed"] for c in counts if c["proposed"] > 0]
        lengths = [(c["accepted"] + c["steps"]) / c["steps"] for c in counts if c["steps"] > 0]
        if not ratios or not lengths:
            fail(EXIT_BAD_ARGUMENTS, "fixture leaves alpha or tau undefined")
        alpha = mean(ratios)
        tau = mean(lengths)
        excluded = sum(1 for c in counts if c["proposed"] == 0)
    return {
        "seed": seed,
        "alpha": alpha,
        "tau": tau,
        "alpha_by_position": [
            p["accepted"] / p["eligible"] if p["eligible"] > 0 else None for p in positions
        ],
        "total_steps": total_steps,
        "total_proposed": total_proposed,
        "total_accepted": total_accepted,
        "excluded_prompts": excluded,
        "position_counts_exact": all(c["proposed"] == c["steps"] * k for c in counts),
        "per_prompt": per_prompt,
        "position_counts": [
            {"position": j + 1, "eligible": p["eligible"], "accepted": p["accepted"]}
            for j, p in enumerate(positions)
        ],
    }


# --- corruptions: each breaks exactly one rule and none checked before it ------------------------


def corrupt_position_monotone(report, k):
    positions = report["seeds"][0]["position_counts"]
    if k < 2:
        fail(EXIT_BAD_ARGUMENTS, "position_monotone corruption needs num_speculative_tokens >= 2")
    positions[1]["eligible"] = positions[0]["accepted"] + 1


def corrupt_position_totals(report, k):
    positions = report["seeds"][0]["position_counts"]
    for j in reversed(range(len(positions))):
        next_eligible = positions[j + 1]["eligible"] if j + 1 < len(positions) else 0
        if positions[j]["accepted"] > 0 and positions[j]["accepted"] - 1 >= next_eligible:
            positions[j]["accepted"] -= 1
            return
    fail(EXIT_BAD_ARGUMENTS, "fixture has no position whose accepted count can be lowered")


def corrupt_alpha_by_position(report, k):
    values = report["seeds"][0]["alpha_by_position"]
    if values[0] is None:
        values[0] = 0.5
    else:
        values[0] += 0.01 if values[0] <= 0.5 else -0.01


def nudge(value, low, high):
    """Moves value by 1e-6 (far above the 1e-9 tolerance) while staying in [low, high]."""
    return value + 1e-6 if value + 1e-6 <= high else value - 1e-6


def corrupt_aggregate_std(report, k):
    aggregate = report["aggregate"]
    aggregate["alpha_std"] = 0.0 if aggregate["alpha_std"] is None else aggregate["alpha_std"] + 1e-6


def flip(value, a, b):
    return b if value == a else a


def first(report):
    return report["seeds"][0]


CORRUPTIONS = {
    "shape": lambda r, k: r.pop("hardware"),
    "schema_version": lambda r, k: r.update(schema_version=SCHEMA_VERSION + 1),
    "draft_structure": lambda r, k: r.update(draft_structure="tree"),
    "estimator": lambda r, k: r.update(estimator=flip(r["estimator"], *ESTIMATORS)),
    "draft_id": lambda r, k: r.update(draft_id=r["draft_id"] + "-corrupt"),
    "decoding": lambda r, k: r["decoding"].update(max_new_tokens=r["decoding"]["max_new_tokens"] + 1),
    "prompt_set_sha256": lambda r, k: r.update(prompt_set_sha256="0" * 64),
    "num_prompts": lambda r, k: r.update(num_prompts=r["num_prompts"] + 1),
    "seeds": lambda r, k: first(r).update(seed=first(r)["seed"] + 1000),
    "adapter_handling": lambda r, k: r.update(adapter_handling=flip(r["adapter_handling"], "none", "merged")),
    "per_prompt_length": lambda r, k: first(r)["per_prompt"].pop(),
    "prompt_indices": lambda r, k: first(r)["per_prompt"][-1].update(prompt_index=len(first(r)["per_prompt"])),
    "prompt_counts": lambda r, k: first(r)["per_prompt"][0].update(accepted=first(r)["per_prompt"][0]["proposed"] + 1),
    "totals": lambda r, k: first(r).update(total_steps=first(r)["total_steps"] + 1),
    "position_lengths": lambda r, k: (first(r)["alpha_by_position"].pop(), first(r)["position_counts"].pop()),
    "position_counts": lambda r, k: first(r)["position_counts"][0].update(
        accepted=first(r)["position_counts"][0]["eligible"] + 1
    ),
    "position_monotone": corrupt_position_monotone,
    "position_totals": corrupt_position_totals,
    "alpha_by_position": corrupt_alpha_by_position,
    "position_counts_exact": lambda r, k: first(r).update(position_counts_exact=not first(r)["position_counts_exact"]),
    "excluded_prompts": lambda r, k: first(r).update(excluded_prompts=first(r)["excluded_prompts"] + 1),
    "alpha_range": lambda r, k: first(r).update(alpha=1.5),
    "tau_range": lambda r, k: first(r).update(tau=0.5),
    "alpha": lambda r, k: first(r).update(alpha=nudge(first(r)["alpha"], 0.0, 1.0)),
    "tau": lambda r, k: first(r).update(tau=nudge(first(r)["tau"], 1.0, k + 1.0)),
    "aggregate_mean": lambda r, k: r["aggregate"].update(alpha_mean=r["aggregate"]["alpha_mean"] + 1e-6),
    "aggregate_std": corrupt_aggregate_std,
}
SPECIAL_CORRUPTIONS = ("report_missing", "json")


def main(argv):
    started = time.monotonic()
    args = parse_args(argv)
    forced_exit = os.environ.get("DRAFTWATCH_FAKE_EXIT")
    if forced_exit is not None:
        sys.exit(int(forced_exit))
    corrupt = os.environ.get("DRAFTWATCH_FAKE_CORRUPT")
    if corrupt is not None and corrupt not in CORRUPTIONS and corrupt not in SPECIAL_CORRUPTIONS:
        fail(EXIT_BAD_ARGUMENTS, "unknown DRAFTWATCH_FAKE_CORRUPT rule '%s'" % corrupt)
    for label, path in (("--target-checkpoint", args.target_checkpoint),
                        ("--base-model", args.base_model),
                        ("--draft-path", args.draft_path)):
        if path is not None and not os.path.isdir(path):
            fail(EXIT_MODEL_LOAD, "%s %s is not a directory" % (label, path))
    try:
        decoding = json.loads(args.decoding_json)
        k = decoding["num_speculative_tokens"]
    except (ValueError, KeyError, TypeError):
        fail(EXIT_BAD_ARGUMENTS, "--decoding-json must be an object with num_speculative_tokens")
    seeds = parse_seeds(args.seeds)
    sha256, num_prompts = read_prompts(args.prompts)
    fixture_path = os.environ.get("DRAFTWATCH_FAKE_FIXTURE")
    if not fixture_path:
        fail(EXIT_BAD_ARGUMENTS, "DRAFTWATCH_FAKE_FIXTURE is not set")
    with open(fixture_path, "r", encoding="utf-8") as f:
        fixture = json.load(f)
    entries = fixture["per_seed"]
    if len(entries) != len(seeds):
        fail(EXIT_BAD_ARGUMENTS, "fixture has %d seed entries, %d seeds requested" % (len(entries), len(seeds)))

    seed_reports = [seed_report(s, e, num_prompts, k, args.estimator) for s, e in zip(seeds, entries)]
    alphas = [s["alpha"] for s in seed_reports]
    taus = [s["tau"] for s in seed_reports]
    report = {
        "schema_version": SCHEMA_VERSION,
        "harness_version": HARNESS_VERSION,
        "backend": "fake",
        "adapter_handling": "merged" if args.base_model else "none",
        "draft_structure": "chain",
        "estimator": args.estimator,
        "draft_id": args.draft_id,
        "prompt_set_sha256": sha256,
        "num_prompts": num_prompts,
        "decoding": decoding,
        "seeds": seed_reports,
        "aggregate": {
            "alpha_mean": mean(alphas),
            "alpha_std": sample_std(alphas),
            "tau_mean": mean(taus),
            "tau_std": sample_std(taus),
        },
        "hardware": {"gpu": "none", "count": 0},
        "wall_clock_seconds": 0.0,
    }

    if corrupt == "report_missing":
        sys.exit(0)
    if corrupt in CORRUPTIONS:
        CORRUPTIONS[corrupt](report, k)
    if corrupt == "json":
        report["wall_clock_seconds"] = float("nan")  # json.dumps writes NaN, which is not JSON
    else:
        report["wall_clock_seconds"] = time.monotonic() - started
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
