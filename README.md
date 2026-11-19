# draftwatch

[![build](https://github.com/ritikmahy5/draftwatch/actions/workflows/build.yml/badge.svg)](https://github.com/ritikmahy5/draftwatch/actions/workflows/build.yml)

Continuous integration for speculative decoding.

Speculative decoding speeds up large-language-model inference: a small draft model proposes
tokens and the large target model verifies them in one pass. The speedup depends on how many draft
tokens the target accepts. Fine-tuning or RL post-training the target can quietly lower that
acceptance, so outputs stay correct while serving gets slower and more expensive. draftwatch is for
ML engineers who fine-tune target models on a Slurm cluster or a single GPU machine. It watches a
training run's checkpoint directory, measures draft acceptance on each new checkpoint, stores every
result with full provenance, compares only results that are comparable, and alerts at the
checkpoint where acceptance regresses.

```
new checkpoint ──▶ trigger rules ──▶ measurement job ──▶ result store ──▶ regression detectors ──▶ alerts / retrain draft
                                     (local or Slurm)    (provenance)      (noise-floor aware)
```

## Team

| Member | GitHub Username | Role |
|---|---|---|
| Ritik Pankaj Mahyavanshi | [@ritikmahy5](https://github.com/ritikmahy5) | Sole team member: design, implementation, tests, cluster runs, and evaluation |

## Repository Structure

The Java engine orchestrates everything and never touches tensors. Measurement runs in a Python
harness started as a subprocess, behind a written contract. The packages under
`src/main/java/dev/draftwatch/` follow the components of the Phase 1 system design (proposal,
Section 9):

```
draftwatch/
  src/main/java/dev/draftwatch/
    app/           command line and wiring: one CliCommand per command, WatchService (one watch
                   pass), DetectionService, and Bootstrap, the one place objects are built
    config/        YAML loading and validation into immutable configuration; probe hashing
    domain/        immutable domain objects: Checkpoint, Probe, Draft, Measurement, Provenance, ...
    discovery/     finding complete checkpoints and their training step (CheckpointSource,
                   CompletionPolicy strategies)
    fingerprint/   weight fingerprints: sampled-block and full-file Fingerprinters, with a caching
                   decorator
    trigger/       ordered trigger rules that decide which checkpoints to measure (TriggerChain)
    exec/          jobs and the job state machine, retry policy, and the local executor
    exec/slurm/    the Slurm executor: sbatch, squeue, sacct, and scancel behind SlurmCli
    harness/       invoking the harness and validating each report against the contract's rules
    stats/         estimators, the seeded paired bootstrap, and least-squares slopes
    store/         append-only result store, job, baseline, and detection repositories; the state lock
    detect/        the comparability guard and regression detectors (paired bootstrap, absolute
                   drop, noise floor, trend)
    events/        the event bus that delivers measurement and detection outcomes
    notify/        alerts to the console and alerts.log
    action/        what happens on a regression: notify, or submit a draft-retraining command
    report/        the static HTML report and the two-result diff
  src/test/java/dev/draftwatch/   JUnit tests, one package per main package
  src/test/resources/fixtures/    report and Slurm fixtures (synthetic_* invented, real_* recorded)
  python/          the reference vLLM harness (measure_acceptance.py) and its unit tests
  scripts/         demo.sh, the fake harness for tests, and test helpers
  docs/            SPEC (features, CLI, config), ARCHITECTURE (design and patterns),
                   MEASUREMENT_CONTRACT (engine/harness boundary), EVALUATION (evaluation plan),
                   uml/class-diagram.md (Mermaid class diagram)
```

## Setup and Run

**Prerequisites:**
- A locally installed JDK 11 or later. The build compiles and tests with a Java 11 toolchain and
  does not download one.
- Python 3.10 or later, `/bin/sh`, and `env`, on Linux or macOS.
- No GPU, network access, or API keys are needed for the build, the tests, or the demo.

Real measurements need a GPU and a Python environment with vLLM 0.31.0 (see "Real measurements"
below).

```bash
# Clone
git clone https://github.com/ritikmahy5/draftwatch.git
cd draftwatch

# Install dependencies: none to install by hand. The Gradle wrapper downloads Gradle and the
# Jackson libraries on the first build.

# Build and run all tests (Java and Python)
./gradlew build

# Run the core use case end to end, with the fake harness, in a new temporary directory
scripts/demo.sh

# Install the command-line launcher and use it
./gradlew installDist                       # build/install/draftwatch/bin/draftwatch
build/install/draftwatch/bin/draftwatch --help

# Run tests only
./gradlew test                              # Java tests (JUnit)
python3 -m unittest discover -s python/tests  # Python harness tests
```

The application is configured by one file, `draftwatch.yaml`. `draftwatch init` writes a commented
template, and `draftwatch validate` checks it and prints the resolved probes and trigger rules.
`docs/SPEC.md` describes every field. No secrets or environment variables are required.

```
draftwatch init                        # draftwatch.yaml with placeholders, and the state directory
draftwatch validate                    # resolve probes, fingerprint the draft, show trigger chains
draftwatch watch --once                # one pass; or 'watch --interval 60s' (local executor only)
draftwatch history <target> --probe <id>
draftwatch report --out report.html
```

**Real measurements:** set `harness.command` to `python/measure_acceptance.py`, run in a Python
environment with vLLM 0.31.0 (`docs/MEASUREMENT_CONTRACT.md`, "Reference backend").

**On a Slurm cluster:**
- **Executor:** set `executor.type: slurm`. `draftwatch schedule --interval 15m` runs
  `watch --once` as a CPU-only job that resubmits itself first, and `draftwatch unschedule` ends it.
  A looping `watch` is refused, because login nodes are not for long-running processes.
- **GPU model:** name it in the job's resources, for example `gres: "gpu:h200:1"`. Results from
  different GPU models are not compared.
- **On Northeastern's Explorer:** set `JAVA_HOME` to the `OpenJDK/22.0.2` module (`/usr/bin/java`
  is Java 8), run one-off commands in a job (`sbatch --wrap '<command>'`), use vLLM's `+cu129`
  wheel, and keep environments and models in `/scratch`.

## Progress So Far

The core engine is complete: every functional requirement in the Phase 1 proposal is implemented
and tested. The work in progress is the evaluation on real fine-tuning runs.

| Functional Requirement (from Phase 1) | Status | Notes |
|---|---|---|
| FR-1: Load and validate the YAML configuration | Complete | Every listed invalid configuration is rejected with an error naming the field |
| FR-2: Detect complete checkpoints, extract steps, recognize final ones | Complete | Marker-file and settle-time completion policies |
| FR-3: Fingerprint checkpoints by their weights | Complete | Sampled-block (default) and full-file SHA-256; adapters also cover their base model |
| FR-4: Ordered trigger rules; never measure twice | Complete | not_already_measured, max_pending, always_final, every_n_steps |
| FR-5: Local and Slurm execution with a job state machine and retries | Complete | Illegal transitions throw; only transient failures are retried |
| FR-6: Self-resubmitting scheduled watch on Slurm | Complete | `schedule` and `unschedule`; used for the evaluation runs |
| FR-7: Invoke the harness and validate every report | Complete | All 29 contract rules, each with its own negative test |
| FR-8: Append-only results with full provenance | Complete | Atomic writes |
| FR-9: Detectors with a comparability guard | Complete | Incomparable results are recorded as an error and alerted, never compared |
| FR-10: Baseline pinning | Complete | First measured checkpoint by default; `draftwatch baseline` sets or shows it |
| FR-11: Detection log, alerts, optional draft retraining | Complete | Retraining is submitted once per draft version |
| FR-12: Static HTML report and diff | Complete | Every displayed number links to the stored value it shows |
| FR-13: The eleven commands | Complete | init, validate, watch, submit, schedule, unschedule, status, history, diff, report, baseline |
| FR-14: One writer per state directory | Complete | State lock that is safe on network filesystems |

**Working end to end:** catching an acceptance regression during a training run, the proposal's
primary use case. `scripts/demo.sh` runs it on any machine without a GPU:
1. It writes a `draftwatch.yaml` with one target and one probe, and runs `draftwatch validate`.
2. It creates checkpoints 100 and 200 and runs `draftwatch watch --once` until no job is active.
   Each pass discovers the complete checkpoints, applies the trigger rules, runs a measurement job
   with the fake harness, and validates and stores the report.
3. Step 100 becomes the baseline automatically, and step 200 is compared with it: OK.
4. It points the fake harness at lower counts and creates checkpoint 300. Both detectors report a
   regression, and an alert is printed and written to `alerts.log`.
5. It runs `draftwatch history` and `draftwatch report`, which writes `report.html`.

Every number in the demo comes from synthetic fixtures in `src/test/resources/fixtures/`, not from
a measurement. The same pipeline has also run on Explorer: a scheduled CPU job sent measurements of
new checkpoints to H200 GPUs with the reference vLLM harness, then ran detection, `report`, and
`diff`.

**In progress:**
- **Evaluation on real fine-tuning runs** (`docs/EVALUATION.md`). Two model families
  (Qwen2.5-1.5B with a 0.5B draft, and SmolLM2-1.7B with a 360M draft) have been fine-tuned on
  Explorer and watched by draftwatch, with checkpoints measured on chat and code probes. The
  analysis of those results is under way. The evaluation tooling is in pull request #1.

## Testing So Far

- **Java:** JUnit 4.13.2, with one test package per main package. The tests cover every detector,
  trigger rule, job state transition, configuration rule, estimator, and report rule.
  - Statistical tests use seeded random number generators and assert exact values. The paired
    bootstrap is cross-checked against an independent Python implementation.
  - Slurm tests replay `squeue` and `sacct` output recorded on the cluster.
  - End-to-end tests run the real command line with the fake harness.
- **Python:** `unittest` tests for the reference harness, using the standard library only (no vLLM
  or GPU).
- **Totals:** 564 Java tests and 25 Python tests, all passing. `./gradlew build` runs both suites.
  GitHub Actions builds, tests, and runs the demo on every push to `main` and on every pull request.

## Next Steps (Phase 3)

- Finish the real-run evaluation: score the detectors against the measured series, re-measure
  checkpoints whose jobs failed, and merge the evaluation tooling.
- Adapter (LoRA) checkpoints: verify the adapter-merge path and base-model fingerprints on GPUs.
- Noise study: repeated measurements across seeds and GPUs, to set the noise-floor detector's
  sigma.
- Detector calibration: tuned defaults and guidance for choosing thresholds.
- Retraining loop: connect the retrain action to a real draft-training script.
- Robustness and performance: a multi-day scheduled run with injected failures, and the cost of
  fingerprinting large checkpoints.
- Documentation, final UML diagrams, the final report, and the demo.

## Team Contributions

| Member | Contributions This Phase |
|---|---|
| Ritik Pankaj Mahyavanshi | Sole team member, author of every commit. Built the Java engine, every package listed under Repository Structure, including the job state machine, the Slurm executor, the detectors and comparability guard, the result store and state lock, and the HTML report. Wrote the measurement contract and the reference vLLM harness (`python/`), the fake harness and `scripts/demo.sh`, and all 564 Java and 25 Python tests. Set up continuous integration, ran the end-to-end runs on Explorer, and built and ran the evaluation tooling (pull request #1). |

## License

MIT; see [LICENSE](LICENSE).
