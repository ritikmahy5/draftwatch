# Measurement contract (Java ⇄ Python harness)

This document is the only interface between the Java engine and the measurement code.
Changing it requires bumping `schema_version` and updating `ReportParser` and its tests.

## Invocation

The engine runs the configured `harness.command` followed by these arguments:

```
--target-checkpoint <path>
[--base-model <path>]                    present only for adapter checkpoints
--draft-id <string>
--draft-path <path>
--prompts <path to .jsonl, one prompt object per non-empty line>
--decoding-json '<JSON object from probe.decoding>'
--estimator <token_weighted|simple_mean>
--seeds <comma-separated ints>
--out <path to report.json>
```

The harness must write exactly one JSON file at `--out` and exit.

### Prompt file

Both sides must read `--prompts` identically, or `num_prompts` and `prompt_index` disagree:
- The file is UTF-8 without a byte-order mark. Lines are separated by LF (`\n`).
- A line is **empty** if it contains only space, tab, and CR characters (in Python:
  `line.strip(" \t\r") == ""`). Empty lines are skipped and are not prompts.
- Every non-empty line is exactly one JSON object; anything else is an error. The fields of
  the object are the harness's to define.
- The k-th non-empty line, counting from 0, is the prompt with `prompt_index` k.
- `num_prompts` is the number of non-empty lines and must be at least 1.
- `prompt_set_sha256` is the SHA-256 of the file's exact bytes, as lowercase hex.

| Exit code | Meaning | Retry? |
|---|---|---|
| 0 | Success, report written | — (report is then validated; invalid ⇒ FAILED, no retry) |
| 2 | Bad arguments | No |
| 3 | Model load failure (missing or incompatible files) | No |
| 4 | Out of GPU memory | No (same resources will fail again) |
| 5 | Backend cannot report the required counters (see "Reference backend") | No |
| other | Unexpected error | Yes, up to `max_retries` |

## Scope: chain drafting only (v1)

v1 supports chain drafting only: each verification step proposes a linear sequence of up to
`num_speculative_tokens` draft tokens. Tree drafting changes what "proposed" and "position"
mean, so it is out of scope. The report must declare
`"draft_structure": "chain"`; any other value is rejected. The reference harness must not set
vLLM's speculative token tree option.

## Metric definitions

For one prompt, verification step i:
- `γ_i` = draft tokens proposed in step i (≤ `num_speculative_tokens`; fewer near the end of
  the generation budget)
- `a_i` = draft tokens accepted by the verifier in step i, 0 ≤ a_i ≤ γ_i
- each step also emits one target token (the correction or bonus token)

Counting rule: metrics count **verifier decisions**, not tokens kept in the final output.
Accepted tokens later discarded because of EOS or the length limit still count as accepted.
This is what the reference backend's counters measure.

Positions within a step are **1-indexed**: position 1 is the first draft token of the step.

| Metric | Key | Definition |
|---|---|---|
| Acceptance rate | `alpha` | accepted draft tokens / proposed draft tokens, where "proposed" includes tokens after the first rejection in a step |
| Mean accepted length | `tau` | mean over steps of (a_i + 1) — tokens emitted per target forward pass |
| Positional acceptance | `alpha_by_position[k]` | among steps where position k was proposed and positions 1 … k−1 were all accepted, the fraction where position k was accepted; `null` if no such step exists |

`alpha` here is the empirical fraction of proposed tokens that were accepted. It is **not**
the per-token acceptance probability α from the speculative decoding literature; do not label
it as such in reports. `alpha_by_position[1]` is the closest empirical analogue of that α.

### Estimator (how prompts are aggregated for `alpha` and `tau`)

- `token_weighted`: pool all steps across all prompts, compute the ratio once.
  `alpha = Σ_p accepted_p / Σ_p proposed_p`, `tau = (Σ_p accepted_p + Σ_p steps_p) / Σ_p steps_p`.
- `simple_mean`: compute the metric per prompt, then take the unweighted mean over prompts.
  Prompts with `proposed_p = 0` are excluded from `alpha` and counted in `excluded_prompts`.
  Prompts with `steps_p = 0` are excluded from `tau` (a prompt with no steps proposed nothing,
  so it is already among the excluded prompts).
- `excluded_prompts` is the number of prompts with `proposed_p = 0` under `simple_mean`, and
  `0` under `token_weighted`, which excludes no prompt.
- A metric with nothing to average is **undefined**: `token_weighted` alpha when no draft token
  was proposed, `token_weighted` tau when there were no steps, `simple_mean` alpha or tau when
  every prompt is excluded. A report whose recomputed `alpha` or `tau` is undefined is
  invalid (rules `alpha` and `tau`): such a measurement says nothing about acceptance.

`alpha_by_position` is always pooled across prompts, regardless of estimator.

The two estimators give different numbers when prompt lengths vary. Results with different
estimators are never compared.

### Seeds and uncertainty

Seeds matter only when sampling (`temperature > 0`). The harness reports each seed's
metrics; `aggregate` holds the seed mean and the sample standard deviation (`null` with one
seed). Uncertainty for regression detection comes from **resampling prompts** in the engine,
using `per_prompt` counts — the harness does not compute confidence intervals.

## Reference backend: vLLM

The reference harness (`python/measure_acceptance.py`) uses vLLM's offline `LLM` API with
`speculative_config = {"method": "draft_model", "model": <--draft-path>,
"num_speculative_tokens": k}` and `disable_log_stats=False`. It reads these engine counters via
`LLM.get_metrics()`, as vLLM's own offline example does
(`examples/features/speculative_decoding/spec_decode_offline.py` in v0.31.0):

| vLLM metric | Contract quantity |
|---|---|
| `vllm:spec_decode_num_drafts` | steps |
| `vllm:spec_decode_num_draft_tokens` | proposed |
| `vllm:spec_decode_num_accepted_tokens` | accepted |
| `vllm:spec_decode_num_accepted_tokens_per_pos` (vector) | accepted at each position |

These counters are engine-wide and cumulative, not per request. To obtain `per_prompt` counts, the
harness generates **one prompt per `generate()` call, sequentially**, and records the counter deltas
between calls. `position_counts.eligible` is derived as: eligible at position 1 = steps; eligible at
position k > 1 = accepted at position k − 1. That derivation is valid only if acceptance is
prefix-based; the harness verifies it per prompt (accepted at position k ≤ accepted at position k −
1) and exits with code 5 if it fails. It is also exact only if every step proposed all
`num_speculative_tokens` positions. vLLM 0.31.0 did not shorten drafts in the acceptance run, but
that is not guaranteed in general. So the harness sets `position_counts_exact` to `true` only when
`proposed == steps · num_speculative_tokens` for every prompt, and `false` otherwise. Detectors
never use positional acceptance; reports show it with an "approximate" label when the flag is
`false`.

At startup the harness runs one warm-up prompt and checks that all four counters exist and
that the draft counters (`num_drafts`, `num_draft_tokens`) advanced. The accepted counts may
stay at zero for a draft that is always rejected. If not, it exits with code 5 and names the
metric. It never substitutes estimated values. After loading, it also exits with code 5 if vLLM
resolved any setting that changes what the counters count: a method other than `draft_model`,
non-standard rejection sampling, synthetic acceptance, adaptive verification, or a
per-batch-size k.

Each prompt line holds exactly one of `prompt` (a string, passed to `LLM.generate`) or `messages` (a
list of chat messages, passed to `LLM.chat`), and nothing else. Each call uses
`SamplingParams(temperature, max_tokens=max_new_tokens, seed=<seed>)`. The harness pins vLLM's
native sampler (`VLLM_USE_FLASHINFER_SAMPLER=0`), so a measurement never depends on the caller's
environment. The harness looks these exact names up in the `get_metrics()` output, so if a future
vLLM version renames one, the lookup fails and the harness exits with code 5 instead of silently
producing zeros.

Adapter checkpoints are merged into the base model before measurement (PEFT
`merge_and_unload`, saved to a temporary directory) rather than served as a vLLM LoRA
adapter, because vLLM has an open report of outputs differing when LoRA and EAGLE-3 are
combined. The report records `"adapter_handling": "merged"` or `"none"`.

## Report schema (`schema_version: 1`)

```json
{
  "schema_version": 1,
  "harness_version": "0.1.0",
  "backend": "vllm==<installed version>",
  "adapter_handling": "none",
  "draft_structure": "chain",
  "estimator": "token_weighted",
  "draft_id": "my-draft",
  "prompt_set_sha256": "<hex>",
  "num_prompts": 400,
  "decoding": { "temperature": 0, "max_new_tokens": 256, "num_speculative_tokens": 5, "dtype": "bfloat16" },
  "seeds": [
    {
      "seed": 0,
      "alpha": 0.0,
      "tau": 1.0,
      "alpha_by_position": [0.0, 0.0, null, null, null],
      "total_steps": 0,
      "total_proposed": 0,
      "total_accepted": 0,
      "excluded_prompts": 0,
      "position_counts_exact": true,
      "per_prompt": [ { "prompt_index": 0, "steps": 0, "proposed": 0, "accepted": 0 } ],
      "position_counts": [ { "position": 1, "eligible": 0, "accepted": 0 } ]
    }
  ],
  "aggregate": { "alpha_mean": 0.0, "alpha_std": null, "tau_mean": 1.0, "tau_std": null },
  "hardware": { "gpu": "string", "count": 1 },
  "wall_clock_seconds": 0.0
}
```

All numeric values above are placeholders showing types, not example results. `NaN` and
`Infinity` are not valid; undefined values are `null`.

### Validation rules enforced by `ReportParser`

Every rule has a stable id. The parser checks the rules in the order below and stops at the
first violation, so a job's failure names exactly one rule. The fake harness's
`DRAFTWATCH_FAKE_CORRUPT=<id>` breaks exactly that rule and none checked before it. `k` is
`num_speculative_tokens`. Comparisons "within 1e-9" are absolute differences.

| Order | Rule id | The report is valid only if |
|---|---|---|
| 1 | `report_missing` | the harness exited 0 and a file exists at `--out` |
| 2 | `json` | the file is one valid JSON value: no `NaN`/`Infinity`, no repeated keys, nothing after it |
| 3 | `shape` | every key above is present with the type shown, no other key exists, `hardware.count` and `wall_clock_seconds` are ≥ 0 |
| 4 | `schema_version` | `schema_version` equals the parser's supported version |
| 5 | `draft_structure` | `draft_structure == "chain"` |
| 6 | `estimator` | `estimator` equals what the engine passed |
| 7 | `draft_id` | `draft_id` equals what the engine passed |
| 8 | `decoding` | `decoding` equals the object the engine passed (compared as canonical JSON) |
| 9 | `prompt_set_sha256` | it equals the SHA-256 the engine computed over the prompt file bytes |
| 10 | `num_prompts` | it equals the number of non-empty lines in the prompt file |
| 11 | `seeds` | `seeds[i].seed` are the `--seeds` values, in order, one entry each |
| 12 | `adapter_handling` | it is `"merged"` if `--base-model` was passed, else `"none"` |

Then, for each seed in order:

| Order | Rule id | The report is valid only if |
|---|---|---|
| 13 | `per_prompt_length` | `len(per_prompt) == num_prompts` |
| 14 | `prompt_indices` | the prompt indices are exactly 0 … num_prompts−1, in order |
| 15 | `prompt_counts` | for every prompt, `0 ≤ accepted ≤ proposed ≤ steps · k` |
| 16 | `totals` | `total_steps`, `total_proposed`, `total_accepted` equal the sums over `per_prompt` |
| 17 | `position_lengths` | `len(alpha_by_position) == len(position_counts) == k` |
| 18 | `position_counts` | `position_counts[i].position == i + 1`; at every position `0 ≤ accepted ≤ eligible`; eligible at position 1 ≤ `total_steps` |
| 19 | `position_monotone` | eligible at position j+1 ≤ accepted at position j |
| 20 | `position_totals` | the accepted counts over all positions sum to `total_accepted` |
| 21 | `alpha_by_position` | each entry equals `accepted / eligible` for its position within 1e-9, and is `null` iff eligible = 0 |
| 22 | `position_counts_exact` | it is `true` iff `proposed == steps · k` for every prompt |
| 23 | `excluded_prompts` | it equals the count defined under "Estimator" |
| 24 | `alpha_range` | `alpha ∈ [0, 1]` |
| 25 | `tau_range` | `tau ∈ [1, k + 1]` |
| 26 | `alpha` | the engine's recomputation from `per_prompt` under the declared estimator is defined and equals `alpha` within 1e-9 |
| 27 | `tau` | the same for `tau` |

Then the aggregate:

| Order | Rule id | The report is valid only if |
|---|---|---|
| 28 | `aggregate_mean` | `alpha_mean` and `tau_mean` equal the means of the seeds' values within 1e-9 |
| 29 | `aggregate_std` | `alpha_std` and `tau_std` are `null` iff there is one seed, and otherwise equal the sample standard deviation (n − 1 denominator) of the seeds' values within 1e-9 |

Rule 20 holds for the reference backend by construction: vLLM's `SpecDecodingStats.observe_draft`
adds a draft's accepted count to `num_accepted_tokens` and increments
`num_accepted_tokens_per_pos` at positions 1 … accepted.

A report failing any rule makes the job FAILED with `INVALID_REPORT` and the rule id named in
the error. The engine never repairs a report.

## Comparability

Two measurements are comparable only if all of these match: probe hash (which covers draft
fingerprint, prompt-set SHA-256, decoding including dtype, estimator, and seeds), harness
version, backend, draft structure, and `hardware` (GPU model and count). The same probe on an
A100 and on an H200 gave different counts, greedy and sampled, while repeats on one H200 gave
identical counts.
`wall_clock_seconds` is recorded but never used by detectors.

## Fake harness

`scripts/fake_harness.py` implements this contract using the standard library only, so the
full pipeline can be tested without a GPU:
- It accepts exactly the arguments under "Invocation" (bad arguments exit 2), exits 3 if
  `--target-checkpoint` or `--base-model` is not a directory, echoes `--estimator`,
  `--draft-id`, `--decoding-json`, and the seeds into the report, and computes
  `prompt_set_sha256` and `num_prompts` from the `--prompts` file itself.
- It reads synthetic `per_prompt` and `position_counts` from the fixture named by
  `DRAFTWATCH_FAKE_FIXTURE` and derives every total, ratio, and aggregate from them, so its output
  is internally consistent by construction. A fixture is `{"source": "<where the numbers came
  from>", "per_seed": [{"per_prompt": [{"steps": .., "proposed": .., "accepted": ..}, ...],
  "position_counts": [{"eligible": .., "accepted": ..}, ...]}, ...]}`; entry i is used for the i-th
  seed. A fixture whose entry count, prompt count, or position count does not match the invocation
  makes it exit 2.
- It reports `harness_version: "fake-<version>"`, `backend: "fake"`, and
  `hardware: {"gpu": "none", "count": 0}`. `DRAFTWATCH_FAKE_HARNESS_VERSION=<v>` reports `<v>`
  as the harness version instead, so tests can produce incomparable results on purpose.
- `DRAFTWATCH_FAKE_EXIT=<code>` makes it exit with that code without writing a report.
- `DRAFTWATCH_FAKE_CORRUPT=<rule id>` breaks that one validation rule (see the table above) in
  the first seed, for negative tests.

Its outputs are synthetic and must never appear in a report shown to a user as real data.
