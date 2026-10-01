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

M0 (scaffold) complete: `./gradlew build` and `./gradlew run --args="--help"` work.
Next: M1. See `docs/ROADMAP.md`.

## Layout

```
docs/SPEC.md                  what draftwatch does (features, CLI, config)
docs/ARCHITECTURE.md          Java design: modules, interfaces, patterns
docs/MEASUREMENT_CONTRACT.md  Java ⇄ Python harness boundary and metric definitions
```
