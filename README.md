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

Every milestone in `docs/ROADMAP.md` (M0–M8) is complete. On 2026-10-06 the whole system ran on
Northeastern's Explorer cluster. A scheduled CPU job ran `watch --once`, which sent measurements
of two new checkpoints to H200 GPUs with the reference vLLM harness, then ran detection, `report`,
and `diff` (DECISIONS.md D88; evidence in `docs/evidence/e2e_explorer/`).

- **Discovery and triggers:** `watch` polls checkpoint directories, waits until a checkpoint is
  complete, and passes it through the target's trigger rules (D52–D57).
- **Measurement:** the harness runs as a subprocess behind a fixed contract
  (`docs/MEASUREMENT_CONTRACT.md`). Every report is validated against the contract's rules
  before it is stored with full provenance.
- **Executors:** jobs run locally or on Slurm via `sbatch`, followed through `squeue` and `sacct`.
  Slurm tests replay output recorded on Explorer (D58–D67).
- **Detection:** paired bootstrap, absolute drop, and noise floor compare each result with the
  target's baseline, and trend fits a slope over recent results. Results are compared only when
  they are comparable: same probe, harness, backend, draft structure, and GPU. A refused comparison is an
  alerted error, never silently skipped (D43–D51, D89).
- **Reports:** `report` writes a static HTML page on which every number links to the stored value
  it comes from (D68). `diff` compares two results with their provenance.
- **Retraining:** `on_regression` can submit a draft-training command, once per draft version
  (D75–D79).
- **Reference harness:** `python/measure_acceptance.py` uses vLLM 0.31.0. Its reports from real
  A100 and H200 runs are committed. On one GPU model, repeated runs give identical counts. On
  different models they do not, which is why the GPU is part of comparability (D80–D87, D91).

## Quick start (no GPU)

Requirements: a JDK 11 installed locally (the build compiles and tests with a Java 11
toolchain and does not download one, D18), Python 3.10+, `/bin/sh`, and `env`.

```
./gradlew build      # compile, then run the Java and Python tests
scripts/demo.sh      # the whole pipeline with the fake harness, in a new temporary directory
```

`scripts/demo.sh` writes a config and creates checkpoints 100, 200, and 300. It runs
`watch --once` until no job is active, then `history` and `report`. Step 100 becomes the baseline
automatically, and step 200 is OK. Before step 300, the fake harness is pointed at lower counts,
so both detectors report a regression and alert. Every number in the demo comes from synthetic
fixtures in `src/test/resources/fixtures/`, not from a measurement.

## Using it

```
./gradlew installDist                  # launcher: build/install/draftwatch/bin/draftwatch
draftwatch init                        # draftwatch.yaml with placeholders, and the state directory
draftwatch validate                    # resolve probes, fingerprint the draft, show trigger chains
draftwatch watch --once                # one pass; or 'watch --interval 60s' (local executor only)
draftwatch history <target> --probe <id>
draftwatch report --out report.html
```

`draftwatch --help` lists every command. `docs/SPEC.md` describes the configuration. For real
measurements, set `harness.command` to `python/measure_acceptance.py`, run in a Python
environment with vLLM 0.31.0 (MEASUREMENT_CONTRACT.md, "Reference backend").

## On a Slurm cluster

- **Executor:** set `executor.type: slurm`. `draftwatch schedule --interval 15m` runs
  `watch --once` as a CPU-only job that resubmits itself first, and `draftwatch unschedule` ends
  it. A looping `watch` is refused, because login nodes are not for long-running processes.
- **GPU model:** name it in the job's resources, for example `gres: "gpu:h200:1"`. Results from
  different GPU models are not compared. After a deliberate GPU change, measure the baseline
  checkpoint again with `draftwatch submit <target> <baseline checkpoint>` (D92).
- **Explorer, as verified on 2026-10-06:**
  - Set `JAVA_HOME` to the `OpenJDK/22.0.2` module, because `/usr/bin/java` is Java 8 (D14).
  - Run one-off draftwatch commands in a job (`sbatch --wrap '<command>'`), because the login
    node killed some of them (D66, D88).
  - Use vLLM's `+cu129` wheel, because the GPU driver supports CUDA 12.8 (D87).
  - Keep environments and models in `/scratch`.

## Layout

```
docs/SPEC.md                  what draftwatch does (features, CLI, config)
docs/ARCHITECTURE.md          Java design: modules, interfaces, patterns
docs/MEASUREMENT_CONTRACT.md  Java ⇄ Python harness boundary and metric definitions
docs/uml/class-diagram.md     Mermaid class diagram of the current code
scripts/demo.sh               the whole pipeline on this machine with the fake harness
scripts/fake_harness.py       stdlib-only fake harness for tests (synthetic numbers only)
scripts/bootstrap_reference.py  independent reference for the paired bootstrap (used by tests)
scripts/record_slurm_fixtures.py  records real Slurm output on the cluster for the tests (D64)
python/measure_acceptance.py  the reference vLLM harness (MEASUREMENT_CONTRACT.md; D80-D87)
python/tests/                 its unit tests: standard library only, no vLLM or GPU
```
