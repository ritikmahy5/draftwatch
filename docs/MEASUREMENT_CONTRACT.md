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
mean, so it is out of scope (DECISIONS.md D5). The report must declare
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
This is what the reference backend's counters measure (DECISIONS.md D6).

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

`alpha_by_position` is always pooled across prompts, regardless of estimator.

The two estimators give different numbers when prompt lengths vary. Results with different
estimators are never compared.

### Seeds and uncertainty

Seeds matter only when sampling (`temperature > 0`). The harness reports each seed's
metrics; `aggregate` holds the seed mean and the sample standard deviation (`null` with one
seed). Uncertainty for regression detection comes from **resampling prompts** in the engine,
using `per_prompt` counts — the harness does not compute confidence intervals.

## Reference backend: vLLM (DECISIONS.md D4)

The reference harness uses vLLM's offline `LLM` API with a `speculative_config` and reads
these engine counters via `LLM.get_metrics()`, as vLLM's own `spec_decode.py` example does:

| vLLM metric | Contract quantity |
|---|---|
| `vllm:spec_decode_num_drafts` | steps |
| `vllm:spec_decode_num_draft_tokens` | proposed |
| `vllm:spec_decode_num_accepted_tokens` | accepted |
| `vllm:spec_decode_num_accepted_tokens_per_pos` (vector) | accepted at each position |

These counters are engine-wide and cumulative, not per request. To obtain `per_prompt`
counts, the harness generates **one prompt per `generate()` call, sequentially**, and records
the counter deltas between calls. `position_counts.eligible` is derived as: eligible at
position 1 = steps; eligible at position k > 1 = accepted at position k − 1. That derivation
is valid only if acceptance is prefix-based; the harness verifies it per prompt
(accepted at position k ≤ accepted at position k − 1) and exits with code 5 if it fails.
It is also exact only if every step proposed all `num_speculative_tokens` positions. Whether
vLLM shortens drafts near the end of a generation is not verified, so the harness sets
`position_counts_exact` to `true` only when `proposed == steps · num_speculative_tokens` for
every prompt, and `false` otherwise. Detectors never use positional acceptance; reports show
it with an "approximate" label when the flag is `false`.

At startup the harness runs one warm-up prompt and checks that all four counters exist and
advanced; if not, it exits with code 5 and names the missing metric. It never substitutes
estimated values. The harness looks these exact names up in the `get_metrics()` output, so if
a future vLLM version renames one, the lookup fails and the harness exits with code 5 instead
of silently producing zeros.

Adapter checkpoints are merged into the base model before measurement (PEFT
`merge_and_unload`, saved to a temporary directory) rather than served as a vLLM LoRA
adapter, because vLLM has an open report of outputs differing when LoRA and EAGLE-3 are
combined (DECISIONS.md D8). The report records `"adapter_handling": "merged"` or `"none"`.

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

Identity and comparability:
- `schema_version` equals the parser's supported version; `draft_structure == "chain"`.
- `estimator`, `draft_id`, and `decoding` equal what the engine passed.
- `prompt_set_sha256` equals the SHA-256 the engine computed over the prompt file bytes.
- `num_prompts` equals the number of non-empty lines in the prompt file.

Per seed:
- `len(per_prompt) == num_prompts`, prompt indices are exactly 0 … num_prompts−1.
- `total_steps`, `total_proposed`, `total_accepted` equal the sums over `per_prompt`.
- For every prompt: `0 ≤ accepted ≤ proposed ≤ steps · num_speculative_tokens`.
- `len(alpha_by_position) == len(position_counts) == num_speculative_tokens`, and each
  `alpha_by_position[k]` equals `accepted / eligible` for that position (null iff eligible = 0).
- Positional counts are monotone: eligible at position k+1 ≤ accepted at position k.
- `position_counts_exact` is `true` iff `proposed == steps · num_speculative_tokens` for every
  prompt.
- `alpha` and `tau` equal the values the engine recomputes from `per_prompt` under the
  declared estimator, within 1e-9.
- `alpha ∈ [0, 1]`; `tau ∈ [1, num_speculative_tokens + 1]`.

Aggregate:
- `alpha_mean`/`tau_mean` equal the seed means within 1e-9; `*_std` is `null` iff one seed.

A report failing any rule makes the job FAILED with the rule named in the error. The engine
never repairs a report.

## Comparability

Two measurements are comparable only if all of these match: probe hash (which covers draft
fingerprint, prompt-set SHA-256, decoding including dtype, estimator, and seeds), harness
version, backend, and draft structure. `hardware` and `wall_clock_seconds` are recorded but
never used by detectors.

## Fake harness

`scripts/fake_harness.py` implements this contract using the standard library only, so the
full pipeline can be tested without a GPU:
- It echoes `--estimator`, `--draft-id`, and `--decoding-json` into the report, and computes
  `prompt_set_sha256` and `num_prompts` from the `--prompts` file itself.
- It reads synthetic `per_prompt` and `position_counts` from the fixture named by
  `DRAFTWATCH_FAKE_FIXTURE` and derives every total, ratio, and aggregate from them, so its
  output is internally consistent by construction.
- `DRAFTWATCH_FAKE_EXIT=<code>` makes it exit with that code without writing a report.
- `DRAFTWATCH_FAKE_CORRUPT=<rule>` deliberately breaks one validation rule, for negative tests.

Its outputs are synthetic and must never appear in a report shown to a user as real data.
