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

M3 (detection and alerts) complete: every stored measurement is checked by the target's
detectors against its baseline (`draftwatch baseline`); regressions and errors are alerted on the
console and in `alerts.log`, and every outcome is recorded in `detections.log`. Tests use the
fake harness (`scripts/fake_harness.py`, synthetic data only). Next: M4. See `docs/ROADMAP.md`.

## Layout

```
docs/SPEC.md                  what draftwatch does (features, CLI, config)
docs/ARCHITECTURE.md          Java design: modules, interfaces, patterns
docs/MEASUREMENT_CONTRACT.md  Java ⇄ Python harness boundary and metric definitions
docs/uml/class-diagram.md     Mermaid class diagram of the current code
scripts/fake_harness.py       stdlib-only fake harness for tests (synthetic numbers only)
scripts/bootstrap_reference.py  independent reference for the paired bootstrap (used by tests)
```

Tests need `python3` (3.10+), `/bin/sh`, and `env` on the PATH (DECISIONS.md D41).
