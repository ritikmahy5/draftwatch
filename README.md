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

draftwatch is a command-line tool; there is no hosted application.

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
  src/scoring/java/dev/draftwatch/scoring/
                   the evaluation's scoring tool (its own Gradle source set, which depends on the
                   engine and never the other way round): archive export, ground truth, probe
                   subsets, replay through the engine's detectors, metrics, and traced output
  src/test/java/dev/draftwatch/   JUnit tests, one package per main package, plus scoring/
  src/test/resources/fixtures/    report and Slurm fixtures (synthetic_* invented, real_* recorded)
  python/          the reference vLLM harness (measure_acceptance.py) and its unit tests
  evaluation/      the evaluation's tooling: probe and training-data builders, training, pilot
                   checks, cluster job scripts, and its run guide (evaluation/README.md)
  evaluation/results/full-run/
    state/         the full runs' exported state: 196 results, raw reports, logs, sacct times
    score/         the scoring output: score.json, alarm_rates.csv, and manifest.json
  scripts/         demo.sh, the fake harness for tests, and test helpers
  docs/            SPEC (features, CLI, config), ARCHITECTURE (design and patterns),
                   MEASUREMENT_CONTRACT (engine/harness boundary), EVALUATION (evaluation design),
                   uml/class-diagram.md (Mermaid class diagrams)
```

## Setup and Run

**Prerequisites:**
- A locally installed JDK 11 or later. The build compiles and tests with a Java 11 toolchain and
  does not download one.
- Python 3.10 or later, `/bin/sh`, and `env`, on Linux or macOS.
- No GPU, network access beyond the first build, or API keys are needed for the build, the tests,
  the demo, or the scoring tool.

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

# Run the primary use case end to end, with the fake harness, in a new temporary directory
scripts/demo.sh

# Install the command-line launcher and use it
./gradlew installDist                       # build/install/draftwatch/bin/draftwatch
build/install/draftwatch/bin/draftwatch --help
```

`scripts/demo.sh` takes about 20 seconds and prints each command it runs. At the end it prints the
demo directory, which holds the configuration, the checkpoints, the state directory, and
`report.html`.

The application is configured by one file, `draftwatch.yaml`. `draftwatch init` writes a commented
template, and `draftwatch validate` checks it and prints the resolved probes and trigger rules.
`docs/SPEC.md` describes every field. No secrets or environment variables are required.

```
draftwatch init                               # draftwatch.yaml with placeholders, and the state directory
draftwatch validate                           # resolve probes, fingerprint the draft, show trigger chains
draftwatch watch --once                       # one pass; or 'watch --interval 60s' (local executor only)
draftwatch submit <target> <ckpt>             # queue measurements of one checkpoint now
draftwatch status                             # non-terminal and recently failed jobs
draftwatch history <target> --probe <id>      # results in step order
draftwatch diff <ckptA> <ckptB> --probe <id>  # two results field by field, with provenance
draftwatch report --out report.html           # static HTML report
draftwatch baseline <target> [<ckpt>]         # set or show the baseline checkpoint
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

**Re-scoring the evaluation** from the committed results (about 90 seconds, no GPU):

```bash
S=evaluation/results/full-run/state
./gradlew score --args="score --state $S --out /tmp/score \
    --sacct $S/sacct-elapsed.txt --pilot $S/pilot-p2"
```

The tool refuses to run on a working tree with uncommitted changes, so the commit it records is
the code that ran. Its output is byte-identical to `evaluation/results/full-run/score/` when run
at the commit that manifest names. Running the evaluation itself (training and measurement on a
cluster) is described in `evaluation/README.md`.

## Feature Status

Every functional requirement in the Phase 1 proposal (Section 6) is implemented and tested.

| Functional Requirement (from Phase 1) | Status | Where to Find It |
|---|---|---|
| FR-1: Load one YAML configuration; reject invalid ones naming the field | Complete | `config/ConfigLoader`, `config/ConfigValidator`; `ConfigValidatorTest` |
| FR-2: Detect complete checkpoints, extract steps, recognize final ones | Complete | `discovery/MarkerCompletionPolicy`, `SettleCompletionPolicy`, `StepExtractor` |
| FR-3: Fingerprint checkpoints by their weights (adapters cover their base model) | Complete | `fingerprint/SampledBlockFingerprinter`, `FullFileFingerprinter`, `CachingFingerprinter` |
| FR-4: Ordered trigger rules; never submit a checkpoint twice per probe | Complete | `trigger/TriggerChain` and its four rules |
| FR-5: Local and Slurm execution, job state machine, retry only transient failures | Complete | `exec/JobState`, `RetryPolicy`, `LocalExecutor`, `exec/slurm/SlurmExecutor` |
| FR-6: Self-resubmitting scheduled watch on Slurm; `unschedule` | Complete | `app/ScheduleCommand`, `UnscheduleCommand`, `Schedule` |
| FR-7: Invoke the harness and validate every report against the 29 contract rules | Complete | `harness/HarnessInvocation`, `ReportParser`, `ReportRule` |
| FR-8: Append-only results with full provenance | Complete | `store/FileResultRepository`, `AtomicFiles`, `domain/Provenance` |
| FR-9: Detectors with a comparability guard | Complete | `detect/Comparability`, `BaselineDetector`, the four detectors |
| FR-10: Baseline pinning (automatic, or set and shown with `baseline`) | Complete | `app/BaselineCommand`, `DetectionService`, `store/FileBaselineRepository` |
| FR-11: Detection log, alerts, optional draft retraining once per draft | Complete | `store/FileDetectionLog`, `notify/`, `action/RetrainDraftAction` |
| FR-12: Static HTML report with traceable numbers, and a diff of two results | Complete | `report/HtmlReportRenderer`, `ReportModel`, `MeasurementDiff` |
| FR-13: The eleven commands | Complete | `app/` (one `CliCommand` per command) |
| FR-14: One writer per state directory; take over only a provably stale lock | Complete | `store/StateLock` |

The proposal's objective 7 (validation on real runs, Section 11's timeline) is tracked separately:

| Evaluation milestone (from Phase 1) | Status | Where to Find It |
|---|---|---|
| Evaluation design: two model families, chat and code probes, two recipes, metrics | Complete | `docs/EVALUATION.md` |
| Real runs 1 and 2, watched end to end on Explorer | Complete | `evaluation/`; results in `evaluation/results/full-run/state/` |
| A planted regression flagged at the right checkpoint | Complete for Qwen2.5; not for SmolLM2 (see Known Issues) | `evaluation/results/full-run/score/score.json` |
| Detector scoring at probe sizes 25 to 200 | Complete | `src/scoring/`; `evaluation/results/full-run/score/` |
| Adapter (LoRA) checkpoints measured on GPUs | Not started | Adapter path is implemented and unit-tested; no GPU run |
| Noise study for the noise-floor detector's sigma | Not started | |
| Detector calibration (tuned defaults) | Not started | Scoring output is its input |
| Retraining loop with a real draft-training script | Partial | `action/RetrainDraftAction`, tested end to end with a fake script |
| Multi-day scheduled run with injected failures | Partial | The full runs' scheduled watch ran for about five days, but needed restarting (see Known Issues); no failures were injected |
| Performance of fingerprinting large checkpoints | Not started | |

## Scope and Design Changes Since Phase 1

| Change | Reason |
|---|---|
| Added a pilot (P1–P4) before the full runs | To measure throughput, check that a prompt's counts do not depend on the other prompts, and choose the fine-tuning recipe, before spending about 47 GPU-hours on the full runs. |
| The measurement harness moved to version 0.2.0, with vLLM prefix caching turned off and checked after loading | The pilot found that with prefix caching on, 3 to 4 of 200 code prompts gave different counts depending on which prompts ran before them. The new version keeps old and new reports from being compared. |
| Training uses eager attention and stops when the weights stop being finite | PyTorch's SDPA backward pass gave NaN gradients on one padded micro-batch in the pilot. |
| The second model family is SmolLM2 (1.7B target, 360M draft) | Llama 3.2 and Gemma 3 are gated on the Hugging Face Hub behind manual license approval; SmolLM2 is not. |
| Adapter checkpoints became a separate study | Both targets are small enough to fine-tune fully on one GPU, so the runs use full checkpoints. |
| Detectors are scored offline, by a separate scoring tool | Probes of 25 to 200 prompts are built as subsets of each stored 1,000-prompt measurement and replayed through the same detector classes `watch` uses. This scores 500 probes per size from one measurement, with no extra GPU time. It lives in its own Gradle source set so the engine does not depend on it. |
| The evaluation's results and scoring output are committed to the repository, with cluster paths replaced by `/scratch/user` | So every reported number traces to a committed file and its SHA-256. |
| The schedule slipped by about two weeks | The full runs were due by November 1 and ran on November 5 and 6. The noise study, calibration, retraining-loop, robustness, and performance milestones have not started. |

## Design Patterns and Principles Applied

Each pattern is justified in its class's Javadoc; `docs/ARCHITECTURE.md` has the full table and
`docs/uml/class-diagram.md` the class diagrams.

| Pattern / Principle | Where | Why |
|---|---|---|
| Strategy | `Executor`, `Fingerprinter`, `EstimatorStrategy`, `CompletionPolicy`, `CheckpointSource`, `RegressionDetector`, `Notifier`, `CommandRunner` | Behavior varies by environment or policy and is chosen by the configuration; `CommandRunner` lets tests replay recorded Slurm output. |
| Chain of Responsibility | `TriggerChain` over `TriggerRule`s | Rules are ordered; the first that does not abstain decides, and gives its reason. |
| State | `JobState` and its transition table | A job's lifecycle has legal and illegal transitions; illegal ones throw. |
| Observer | `EventBus` and its subscribers | Logs, notifiers, and actions subscribe to outcomes without the producers knowing about them; a failing subscriber never stops the others. |
| Command | `CliCommand`; `RegressionAction` | Subcommands are looked up by name, so adding one never changes the dispatcher; actions are configured data, run later, and logged. |
| Template Method | `BaselineDetector`; `WeightFileFingerprinter` | The comparability guard runs before every baseline comparison, so no detector can skip it; both fingerprinters share one file walk. |
| Decorator | `CachingFingerprinter` | Adds caching by file signature to any fingerprinter. |
| Adapter | `SlurmCli` | Isolates `sbatch`, `squeue`, `sacct`, and `scancel` arguments and parsing behind `Executor`. |
| Builder | `JobSpec`, `HarnessInvocation`, `Checkpoint`, `Provenance`, `AcceptanceReport` | Many fields; invalid combinations are rejected at `build()`. |
| Repository | `ResultRepository`, `JobRepository`, `BaselineRepository`, `RetrainRequestRepository` | Storage stays swappable and is tested with in-memory fakes. |
| Factory | `Bootstrap` | The one place where configuration type names become objects. |
| Dependency injection, single composition root | Constructors everywhere; wiring only in `Bootstrap` | Every collaborator can be replaced by a test double. |
| Immutability | Domain objects: final classes, private final fields, builders, no setters | Results and provenance cannot change after they are stored. |
| Fail loudly | Typed exceptions that name the field or file (`ConfigException`, `ReportViolationException`, `DataException`) | An invalid input stops the run with a message that says what to fix. |
| Reuse over reimplementation | The scoring tool's `Replayer` and `GroundTruth` call the engine's own detectors and bootstrap | The evaluation scores the code that users run, not a second implementation of it. |

## Testing

- **Unit tests:** JUnit 4.13.2, 674 Java tests in 83 classes, one test package per main package.
  They cover every detector, trigger rule, job state transition, configuration rule, estimator,
  and report rule, and every scoring metric. Statistical code uses seeded random number
  generators and the tests assert exact values. The paired bootstrap is cross-checked against an
  independent Python implementation.
- **Integration tests:** each of the 29 report rules has a negative test in which a fake harness
  breaks exactly that rule. The Slurm executor is tested against `squeue` and `sacct` output
  recorded on Explorer. `StateLockTest` checks that racing processes see exactly one lock holder,
  and that a lock is taken over only when its holder is provably gone.
- **End-to-end tests:** 34 tests run the real command line or the scoring tool in temporary
  directories:
  - `WatchEndToEndTest`: checkpoints appearing over time are measured exactly as the trigger rules
    allow, and two concurrent watch processes never both submit;
  - `DetectionEndToEndTest`, `DiffEndToEndTest`, `RetrainEndToEndTest`, `SlurmEndToEndTest`;
  - `ScorerTest`: a synthetic state directory written by the real store classes, scored twice,
    with byte-identical output.
- **Python:** 28 `unittest` tests for the reference harness and 62 for the evaluation tooling,
  standard library only (no vLLM or GPU).
- **Coverage:** 89.5% of lines and 74.5% of branches of the engine and the scoring tool, measured
  with JaCoCo 0.8.12 (run separately; it is not a build dependency).
- **Continuous integration:** GitHub Actions builds, runs every test, and runs the demo on every
  push to `main` and every pull request.
- **System test on a cluster:** the full evaluation runs on Explorer, with draftwatch watching four
  targets for about five days (453 passes). The scoring tool's cross-check reproduces all 188 compared B0 and A
  verdicts of the live `detections.log` exactly.

```bash
./gradlew build                                          # everything: Java and Python
./gradlew test                                           # Java tests only
./gradlew test --tests 'dev.draftwatch.scoring.*'        # the scoring tool's tests
python3 -m unittest discover -s python/tests             # reference harness tests
python3 -m unittest discover -s evaluation/tests         # evaluation tooling tests
scripts/demo.sh                                          # the primary use case end to end
```

## Known Issues

- **SmolLM2's planted regression did not happen,** and its control run regressed on code from
  step 200. On SmolLM2 the detectors can be scored for false alarms and, on code, for detection
  on the trunk's checkpoints, but not for detection delay.
- **A refused resubmission stops the scheduled watch.** During the full runs, Slurm refused the
  schedule's own resubmission 13 times ("Unexpected message received"), and each time the
  schedule had to be started again. The fix is to retry the resubmission with a backoff before
  stopping, and to alert when it stops.
- **Four measurements were never taken.** Slurm refused four submissions ("Unexpected message
  received"); they were alerted but not resubmitted. The scoring tool lists them and scores
  without them.
- **B0's intervals are too narrow at small probe sizes:** they cover the reference value 92.0% to
  93.2% of the time at 25 prompts, against an expected 95.3%.
- **Not yet tested on GPUs or over long periods:** adapter checkpoints, repeated measurements of
  one checkpoint (the noise study), and a scheduled run of three days or more.
- **Detector defaults are not yet calibrated** on the real runs.
- **The cost estimates for probes of 25 and 50 prompts include a pilot measurement** taken with
  harness version 0.1.0; the output labels each row with its harness versions.

## Team Contributions (Phase 3)

| Member | Contributions This Phase | Commits (total) |
|---|---|---|
| Ritik Pankaj Mahyavanshi | Ran the pilot and the full evaluation runs on Explorer (training, measurement, and the scheduled watch over four targets), and archived their state. Built the scoring tool (`src/scoring/`, 43 classes, and its 109 tests): archive export with placeholder paths, validation, ground truth, probe subsets, replay through the engine's detectors, the cross-check against the live log, every metric, cost, and the traced output. Exported and scored the full runs (`evaluation/results/full-run/`), merged the evaluation tooling (pull request #1), measured test coverage, and wrote the QA documentation, the final slides, and this README. | 23 (147) |

## License

MIT; see [LICENSE](LICENSE).
