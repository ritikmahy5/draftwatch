# draftwatch — product spec

## Problem

A target model's draft acceptance determines the real speedup of speculative decoding.
Post-training (SFT, preference tuning, RL) shifts the target's output distribution, and
acceptance can drop without anyone noticing until serving latency gets worse. Today this is
checked manually, if at all, after training finishes.

draftwatch makes acceptance a tracked, tested property of every checkpoint — the way unit
tests make correctness a tracked property of every commit.

## Users

Researchers and ML engineers who fine-tune target models that are served (or will be served)
with a draft model, and who train on a shared cluster (Slurm) or a single GPU machine.

## Core concepts

| Term | Meaning |
|---|---|
| Target | The model being trained. Identified by a name and one or more checkpoint directories. |
| Checkpoint | One saved state of a target: path, training step, fingerprint, type (full or adapter). |
| Draft | The speculator model. v1 supports chain drafting only (see MEASUREMENT_CONTRACT.md). |
| Probe | A fixed measurement recipe: prompt set + decoding config + draft + estimator + seeds. |
| Measurement | One execution of a probe against one checkpoint. Produces an AcceptanceReport. |
| Baseline | Per (target, probe): the checkpoint all others are compared to. |
| Regression | A detector's judgment that acceptance dropped beyond its threshold. |

## Features

### F1 — Checkpoint discovery
- Watch one or more checkpoint directories. A subdirectory becomes a candidate checkpoint
  when it is **complete**, decided by one of:
  - `completion: { marker: <filename> }` — a file the user's training script writes last
    (recommended; most reliable), or
  - `completion: { settle_seconds: N }` (default, N = 120) — every file's size and mtime
    are unchanged across two polls at least N seconds apart.
  Do not use `model.safetensors.index.json` as a marker: it is absent for single-file
  checkpoints and its write order relative to the shards is not guaranteed.
- **Step extraction:** `global_step` from `trainer_state.json` if present; otherwise the
  first capture group of `step_regex` (default `checkpoint-(\d+)$`) applied to the directory
  name; otherwise the checkpoint is rejected with an error naming the directory.
- **Final checkpoints:** a checkpoint is final if `final_marker` (default `FINAL`) exists in
  its directory.
- **Checkpoint type:** `checkpoint_type: full | adapter`. For `adapter`, the target must also
  set `base_model`, which is passed to the harness; the harness merges the adapter before
  measuring (MEASUREMENT_CONTRACT.md, "Reference backend").
- **Fingerprint:** SHA-256 over the sorted list of (relative path, size, SHA-256 of sampled
  blocks) for every weight file (`*.safetensors`, `*.bin`). Sampled blocks are 8 × 1 MiB at
  evenly spaced offsets, always including the final MiB. Config option
  `fingerprint: full` hashes entire files instead. Metadata files (index JSON, config JSON)
  are never sufficient on their own: they are identical across checkpoints of one run.
  For an adapter checkpoint, the fingerprint also covers the base model's weights, so the same
  adapter on a different base model is a different checkpoint (DECISIONS.md D42).
- Manual submission: `draftwatch submit <target> <checkpoint-path>` runs the same
  completion, step, and fingerprint logic.

### F2 — Trigger rules
Decide whether a discovered checkpoint gets measured for a given probe. Each rule returns
`Accept`, `Reject(reason)`, or `Abstain`. Rules are evaluated in configured order; the first
non-`Abstain` decision wins; if every rule abstains, the checkpoint is accepted.

| Rule | Returns |
|---|---|
| `not_already_measured` | Reject if a result exists for (fingerprint, probe hash); else Abstain |
| `max_pending(k)` | Reject if ≥ k jobs for this target are not terminal; else Abstain |
| `always_final` | Accept if the checkpoint is final; else Abstain |
| `every_n_steps(n)` | Abstain if step % n == 0; else Reject |

Order matters. The default chain (used when `triggers` is omitted) is
`not_already_measured, max_pending(4), always_final, every_n_steps(1)`. The validator rejects
configs that place `always_final` before `not_already_measured`, because that ordering
re-measures final checkpoints forever.

### F3 — Measurement execution
- A measurement is a job that runs the Python harness with arguments defined in
  `MEASUREMENT_CONTRACT.md`.
- Executors: `local` (subprocess on this machine) and `slurm` (`sbatch`, then `squeue` while
  queued/running and `sacct` after the job leaves the queue).
- **Where `watch` runs:** with `executor.type: local`, `watch` may loop. With
  `executor.type: slurm`, looping `watch` is refused, because cluster login nodes are not for
  long-running processes (Explorer's policy: do not run jobs on login nodes). Instead,
  `draftwatch schedule` submits a small CPU-only Slurm job that runs `watch --once` and then
  resubmits itself with `--begin=now+<interval>` (default 15 minutes). A one-off
  `watch --once` from a login node is allowed because it only fingerprints sampled blocks and
  submits jobs (DECISIONS.md D9).
- Lifecycle and retry policy: see ARCHITECTURE.md, "Job state machine".

### F4 — Result store with provenance
Every result records: target; checkpoint path, step, fingerprint, type, and base model and
its weight fingerprint (if adapter); probe id and probe hash; draft id and draft fingerprint; harness version; backend;
dtype; estimator; seeds; prompt-set SHA-256; executor; job id and attempt; start/end time;
raw report path. Results are append-only.

### F5 — Regression detection
Detectors run after each successful measurement. Every detector first calls the
Comparability guard (MEASUREMENT_CONTRACT.md). Detectors read the aggregate value of a
metric (the seed mean when more than one seed is used).

| Detector | Flags a regression when |
|---|---|
| `paired_bootstrap(metric, confidence=0.95, min_effect=0.0, resamples=2000)` | The bootstrap confidence interval of (current − baseline), resampling prompts with pairing, lies entirely below −min_effect. Default detector. |
| `absolute_drop(metric, max_drop)` | baseline − current > max_drop. |
| `noise_floor(metric, k, sigma)` | baseline − current > k · √2 · sigma. `sigma` is required and means the standard deviation of a *single* measurement from an external source (for example, replicate training runs); √2 converts it to the standard deviation of a difference of two measurements. |
| `trend(metric, window, max_slope)` | The least-squares slope of the metric against checkpoint index (0 … window−1) over the last `window` comparable measurements is below `max_slope`. Units: metric per checkpoint. |

Bootstrap uses a seeded RNG (`bootstrap_seed`, default 0) so results are reproducible.

**Seeds:** with `temperature: 0` decoding is greedy and seeds do not change the output, so the
validator rejects probes with `temperature: 0` and more than one seed.

**Incomparable measurements:** the measurement is stored, the detection outcome is recorded
as `ERROR(incomparable: <field>)`, an alert is sent, and `watch` continues. Nothing is
silently skipped and nothing crashes the loop.

**Missing baseline measurement:** if the baseline checkpoint has no result for a probe,
`watch` submits one and defers detection for that probe until it exists.

### F6 — Alerts and actions
- Notifiers: console and append-only `alerts.log`. No network notifiers in v1 (DECISIONS.md D12).
- Actions on regression (optional, per target): `notify`; `retrain_draft` submits a
  user-configured training command through the same executor abstraction.

### F7 — Reports
- `draftwatch report` generates a static HTML file: acceptance vs. training step per target
  and probe, positional acceptance for the latest checkpoint, regression and error markers,
  and a table where each number links to its raw result file.
- `draftwatch diff <ckptA> <ckptB> --probe <id>` prints both results side by side and lists
  every provenance field that differs.

## CLI

```
draftwatch init                              create draftwatch.yaml template + state directory
draftwatch validate                          validate config, print resolved probes and trigger chains
draftwatch watch [--once] [--interval 60s]   discover → trigger → submit → poll → detect
draftwatch submit <target> <ckpt>            queue measurements for one checkpoint
draftwatch schedule [--interval 15m]         Slurm only: self-resubmitting watch --once job
draftwatch unschedule                        cancel the scheduled watch job
draftwatch status                            non-terminal and recently failed jobs
draftwatch history <target> --probe <id>     results in step order
draftwatch diff <ckptA> <ckptB> --probe <id> compare two measurements with provenance
draftwatch report [--out report.html]        static HTML report
draftwatch baseline <target> [<ckpt>]        set or show the baseline checkpoint
```

## Configuration (`draftwatch.yaml`)

```yaml
state_dir: ./.draftwatch

executor:
  type: slurm            # local | slurm
  slurm:
    partition: gpu
    gres: gpu:1
    time: "00:45:00"
    requeue_on_preempt: true
    extra_sbatch_args: []
  max_retries: 2

harness:
  command: ["python", "python/measure_acceptance.py"]

probes:
  - id: chat-default
    draft: { id: my-draft, path: /path/to/draft, structure: chain }
    prompts: { path: probes/chat.jsonl }
    decoding: { temperature: 0, max_new_tokens: 256, num_speculative_tokens: 5, dtype: bfloat16 }
    estimator: token_weighted     # default; matches the backend's pooled counters
    seeds: [0]

targets:
  - name: my-sft-run
    checkpoint_dirs: [/path/to/run/checkpoints]
    checkpoint_type: full         # full | adapter
    # base_model: /path/to/base   # required when checkpoint_type: adapter
    completion: { settle_seconds: 120 }
    probes: [chat-default]
    triggers:
      - not_already_measured: {}
      - max_pending: 4
      - always_final: {}
      - every_n_steps: 500
    detectors:
      - paired_bootstrap: { metric: alpha, confidence: 0.95, min_effect: 0.0 }
      - trend: { metric: tau, window: 4, max_slope: -0.02 }
    on_regression: [notify]
```

All paths and thresholds above are placeholders. The schema is the contract; values are the user's.
