# draftwatch — architecture

## Package layout

```
src/main/java/dev/draftwatch/
  app/          Bootstrap (all wiring), Main, CLI command classes
  config/       YAML → validated immutable config objects; ConfigValidator; CanonicalJson
  domain/       immutable classes: Target, Checkpoint, Probe, Measurement, AcceptanceReport, Provenance
  fingerprint/  Fingerprinter interface, SampledBlockFingerprinter, FullFileFingerprinter
  discovery/    CheckpointSource, completion policies, step extraction
  trigger/      TriggerRule interface + rules, TriggerChain
  exec/         Executor interface, LocalExecutor, SlurmExecutor, JobState, JobPoller, RetryPolicy
  harness/      HarnessInvocation builder, ReportParser (all MEASUREMENT_CONTRACT rules)
  store/        ResultRepository, JobRepository, BaselineRepository, file implementations, StateLock
  stats/        MetricCalculator (both estimators), PairedBootstrap, LeastSquaresSlope
  detect/       RegressionDetector interface + detectors, Comparability guard, DetectorSuite
  events/       EventBus, immutable event classes
  notify/       Notifier interface, ConsoleNotifier, LogFileNotifier
  action/       RegressionAction (Command), NotifyAction, RetrainDraftAction
  report/       HtmlReportRenderer, DiffRenderer
```

`MetricCalculator` is shared by `ReportParser` (to verify the harness's numbers) and by the
detectors (to recompute metrics on bootstrap resamples), so there is one implementation of
each estimator.

## Pipeline

```
CheckpointSource ─discovers─▶ CheckpointDiscovered
  TriggerChain (per probe) ──▶ MeasurementRequested
  Executor.submit ───────────▶ JobStateChanged (CREATED → SUBMITTED)
  JobPoller ─────────────────▶ JobStateChanged … SUCCEEDED | FAILED | CANCELLED
  ReportParser ──────────────▶ MeasurementStored (Measurement + Provenance in ResultRepository)
  DetectorSuite ─────────────▶ RegressionDetected | DetectionOk | DetectionError
  Notifiers + RegressionActions subscribe to RegressionDetected and DetectionError
```

Each arrow is an event on the `EventBus`. Components subscribe; none call each other
directly across package boundaries except through interfaces injected by `Bootstrap`.
An exception thrown by a subscriber is caught by the bus, logged, and published as
`SubscriberFailed`; it never stops the `watch` loop.

## Key interfaces

```java
interface CheckpointSource { List<Checkpoint> poll(); }
interface Fingerprinter { String fingerprint(Path checkpointDir); }

final class TriggerDecision { enum Kind { ACCEPT, REJECT, ABSTAIN } Kind kind(); Optional<String> reason(); }
interface TriggerRule { TriggerDecision evaluate(Checkpoint ckpt, Probe probe, History history); }

interface Executor {
  JobHandle submit(JobSpec spec);
  ExecutorStatus status(JobHandle handle);   // executor-native status, mapped by JobPoller
  void cancel(JobHandle handle);
}

interface ResultRepository {
  void append(Measurement m);
  List<Measurement> history(String target, String probeHash);          // step order
  List<Measurement> find(String fingerprint, String probeHash);        // all attempts, oldest first
  Optional<Measurement> latest(String fingerprint, String probeHash);
}

final class DetectorVerdict { enum Kind { OK, REGRESSION, ERROR } Kind kind(); String metric(); /* observed, threshold, explanation */ }
interface RegressionDetector {
  DetectorVerdict evaluate(Measurement current, Measurement baseline, List<Measurement> recent);
}

interface Notifier { void notify(DetectionEvent event); }
interface RegressionAction { void execute(RegressionDetected event); }
```

## Patterns and why each one is here

| Pattern | Where | Justification |
|---|---|---|
| Strategy | `Executor`, `Fingerprinter`, `RegressionDetector`, completion policies, renderers | Behavior varies by environment or policy and is selected by config. |
| Chain of Responsibility | `TriggerChain` over `TriggerRule`s | Ordered rules; first non-abstaining rule decides, with a reason. |
| Observer | `EventBus` + subscribers | Decouples stages; notifiers and actions plug in without touching producers. |
| State | `JobState` + transition table | Lifecycle has legal and illegal transitions; illegal ones throw. |
| Command | `RegressionAction` | Actions are configured data, executed later, and logged. |
| Builder | `JobSpec`, `HarnessInvocation` | Many optional fields; invalid combinations rejected at `build()`. |
| Repository | `ResultRepository`, `JobRepository`, `BaselineRepository` | Storage swappable (files now) and testable with in-memory fakes. |
| Adapter | `SlurmExecutor` over `sbatch`/`squeue`/`sacct` text output | Isolates cluster CLI parsing behind `Executor`. |
| Factory | `Bootstrap` | The single place where config type names become objects. |

`EventBus` is created once in `Bootstrap` and injected; it is never accessed statically.

## Job state machine

Engine states (named to avoid confusion with Slurm's own `PENDING`):

| From | To | Cause |
|---|---|---|
| CREATED | SUBMITTED | executor accepted the job |
| CREATED | CANCELLED | cancelled before submission |
| CREATED | FAILED | submission rejected (e.g. sbatch error) |
| SUBMITTED | RUNNING | job started |
| SUBMITTED | FAILED | job failed before running (e.g. BOOT_FAIL) |
| SUBMITTED | CANCELLED | cancelled while queued |
| RUNNING | SUBMITTED | preempted and requeued |
| RUNNING | SUCCEEDED | exit 0 **and** report passed validation |
| RUNNING | FAILED | nonzero exit, invalid report, timeout, OOM, node failure |
| RUNNING | CANCELLED | cancelled while running |
| FAILED | CREATED | retry: `RetryPolicy` allows it and attempts < `max_retries` |

Every other transition throws `IllegalJobTransitionException`. Each FAILED state carries a
`FailureReason`; `RetryPolicy` retries only `NODE_FAILURE`, `PREEMPTED_NO_REQUEUE`, and
`UNEXPECTED_EXIT`. It never retries `BAD_ARGUMENTS`, `MODEL_LOAD`, `OUT_OF_MEMORY`,
`TIMEOUT`, or `INVALID_REPORT`, since the same inputs would fail the same way.

### Slurm state mapping

| Slurm state | Engine state |
|---|---|
| PENDING, CONFIGURING | SUBMITTED |
| RUNNING, COMPLETING | RUNNING |
| COMPLETED | SUCCEEDED or FAILED, after reading exit code and validating the report |
| FAILED | FAILED (reason from exit code, see contract) |
| OUT_OF_MEMORY | FAILED (OUT_OF_MEMORY) |
| TIMEOUT, DEADLINE | FAILED (TIMEOUT) |
| NODE_FAIL, BOOT_FAIL | FAILED (NODE_FAILURE) |
| PREEMPTED, REQUEUED | SUBMITTED if `requeue_on_preempt`, else FAILED (PREEMPTED_NO_REQUEUE) |
| CANCELLED (any suffix) | CANCELLED |

Unknown Slurm states are logged and treated as "no change" for one poll, then FAILED
(`UNEXPECTED_EXIT`) if still unknown. `sacct` can lag after a job leaves `squeue`; a job
missing from both is re-polled before being declared failed.

## Statistics

- `PairedBootstrap`: resample prompt indices with replacement (`resamples` times, seeded
  RNG); for each resample, recompute the metric for both current and baseline under the
  probe's estimator on the *same* indices; the CI is the percentile interval of the
  differences. With multiple seeds, per-prompt counts are summed across seeds first.
- `LeastSquaresSlope`: ordinary least squares of metric against checkpoint index.

## Persistence (file-based, v1)

```
.draftwatch/
  lock                                     single-writer lock (see below)
  jobs/<job-id>.json                       job spec + full state history
  results/<target>/<fingerprint>__<probe-hash>__<job-id>.json
  raw/<job-id>/report.json                 harness output exactly as written
  raw/<job-id>/stdout.log, stderr.log
  baselines.json                           target → baseline checkpoint fingerprint
  detections.log                           every detector outcome, including Ok and Error
  alerts.log
```

Writes are atomic: write a temp file in the same directory, then rename. Results are
append-only and keyed by job id, so re-measurements never overwrite.

**StateLock:** every command that writes state acquires `.draftwatch/lock` by creating it
exclusively, containing host, PID, timestamp, and `SLURM_JOB_ID` when set. A lock may be taken
over only when its holder is provably gone:
- holder ran inside a Slurm job: that job id no longer appears in `squeue`;
- holder ran outside Slurm on this host: its PID is not alive;
- holder ran outside Slurm on another host: never automatically; `draftwatch status` reports
  the lock and its holder.
This prevents a scheduled `watch --once` and a manual one from double-submitting, while a
scheduled job that crashed on some compute node does not block later runs. On network
filesystems exclusive-create is used instead of `flock`, which is unreliable there.

## Canonical hashing

`CanonicalJson` produces the bytes hashed for probe hashes: keys sorted, no whitespace,
numbers normalized (`0`, `0.0`, and `0e0` serialize identically; trailing zeros stripped),
arrays kept in order. Without this, `temperature: 0` and `temperature: 0.0` would produce
different probe hashes and silently break comparability.

## Concurrency

v1 is single-process per state directory (enforced by StateLock). One `watch` pass is:
poll sources → evaluate triggers → submit → poll jobs → handle completions. The local
executor may repeat passes in a loop; the slurm executor runs one pass per scheduled job
(SPEC.md F3).
