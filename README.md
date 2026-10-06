# draftwatch

Continuous integration for speculative decoding.

Fine-tuning or RL post-training a target model can silently erode how often its draft model's
tokens get accepted — and with it, the speedup speculative decoding was supposed to deliver.
draftwatch watches for new target checkpoints, measures draft acceptance on each one, keeps a
provenance-tracked history, and alerts when acceptance regresses.

```
new checkpoint ──▶ trigger rules ──▶ measurement job ──▶ result store ──▶ regression detectors ──▶ alerts / retrain draft
                                     (local or Slurm)    (provenance)      (noise-floor aware)
```

## Status

M7 (retrain action, stretch) complete: `on_regression` can include
`retrain_draft: { command: [...] }`, which submits the training command through the configured
executor, once per target and draft version, with the regression's context in `DRAFTWATCH_*`
environment variables. draftwatch does not deploy the new draft; once the probe points at it,
its results are never compared with the old draft's (DECISIONS.md D75–D79). Next: M8, the
reference vLLM harness.

M6 (reports) complete: `draftwatch report` writes a static HTML page (inline SVG, no external
assets) with acceptance against step for each comparable series, regression and error markers,
and positional acceptance of the latest checkpoint. Every number on it is a link to the value in
the stored result file it comes from, which a test checks (DECISIONS.md D68). `draftwatch diff`
prints two stored results side by side, with every provenance field that differs.

M5 (Slurm executor) complete: with `executor.type: slurm`, measurements are submitted with
`sbatch` and followed through `squeue` and `sacct`. `draftwatch schedule` runs `watch --once` on
the cluster as a CPU-only job that resubmits itself first, and `unschedule` ends it. The Slurm
tests replay output recorded on Explorer (Slurm 23.11.6) where the cluster can produce a state,
and hand-written output in Slurm's documented formats where it cannot (DECISIONS.md D66, D67).

M4 (discovery and triggers) is complete: `draftwatch watch` polls each target's checkpoint
directory, passes every complete checkpoint through the target's trigger rules, submits the
accepted ones, and advances running jobs. Each pass holds the state lock (`--once` for one pass,
or a local-only loop with `--interval`). Every stored measurement is checked by the target's
detectors against its baseline (`draftwatch baseline`). Regressions and errors are alerted on
the console and in `alerts.log`, and every outcome is recorded in `detections.log`. Tests use
the fake harness (`scripts/fake_harness.py`, synthetic data only). See `docs/ROADMAP.md`.

## Layout

```
docs/SPEC.md                  what draftwatch does (features, CLI, config)
docs/ARCHITECTURE.md          Java design: modules, interfaces, patterns
docs/MEASUREMENT_CONTRACT.md  Java ⇄ Python harness boundary and metric definitions
docs/uml/class-diagram.md     Mermaid class diagram of the current code
scripts/fake_harness.py       stdlib-only fake harness for tests (synthetic numbers only)
scripts/bootstrap_reference.py  independent reference for the paired bootstrap (used by tests)
scripts/record_slurm_fixtures.py  records real Slurm output on the cluster for the tests (D64)
```

Tests need `python3` (3.10+), `/bin/sh`, and `env` on the PATH (DECISIONS.md D41).
