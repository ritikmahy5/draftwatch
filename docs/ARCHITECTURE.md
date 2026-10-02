# draftwatch — architecture

## Package layout

```
src/main/java/dev/draftwatch/
  app/          Bootstrap (all wiring), Main, CLI command classes, MeasurementRunner, Services
  config/       YAML → validated immutable config objects; ConfigValidator; CanonicalJson; ProbeHasher
  domain/       immutable classes: Target, Checkpoint, Probe, Measurement, AcceptanceReport, Provenance
  fingerprint/  Fingerprinter interface, SampledBlockFingerprinter, FullFileFingerprinter
  discovery/    CheckpointSource, completion policies, step extraction
  trigger/      TriggerRule interface + rules, TriggerChain
  exec/         Executor interface, LocalExecutor, SlurmExecutor, JobState, Job, JobSpec,
                MeasurementSpec, JobPoller, RetryPolicy
  harness/      HarnessInvocation builder, ReportParser (all MEASUREMENT_CONTRACT rules),
                PromptSetReader, ProbeResolver
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

Dependencies run one way (DECISIONS.md D40): `domain` ← `fingerprint` ← `config` ← `stats`,
`harness` ← `exec` ← `store` ← `discovery`, `app`. Executors see only a `JobSpec` (command,
working directory, run directory); what is being measured lives in `MeasurementSpec`.

## Pipeline

```
CheckpointSource ─discovers─▶ CheckpointDiscovered
  TriggerChain (per probe) ──▶ MeasurementRequested
  Executor.submit ───────────▶ JobStateChanged (CREATED → SUBMITTED)
  JobPoller ─────────────────▶ JobStateChanged … SUCCEEDED | FAILED | CANCELLED
  ReportParser ──────────────▶ MeasurementStored (Measurement + Provenance in ResultRepository)
  DetectionService ──────────▶ RegressionDetected | DetectionOk | DetectionError
                               | DetectionInsufficientData | DetectionDeferred | BaselinePinned
  detections.log subscribes to every DetectionEvent; notifiers to DetectionError;
  each target's on_regression actions to its RegressionDetected
```

As of M3, `MeasurementStored` onward runs on the bus: `MeasurementRunner` publishes it and
`DetectionService` subscribes (DECISIONS.md D44, D50). The discovery and job-state events arrive
with `watch` in M4.

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
  String name();                              // recorded in provenance, e.g. "local"
  JobHandle submit(JobSpec spec);             // one attempt: command, working dir, run dir
  ExecutorStatus status(JobHandle handle);   // executor-native status, mapped by JobPoller
  void cancel(JobHandle handle);
}

interface ResultRepository {
  void append(Measurement m);
  List<Measurement> history(String target, String probeHash);          // step order
  List<Measurement> find(String fingerprint, String probeHash);        // all attempts, oldest first
  Optional<Measurement> latest(String fingerprint, String probeHash);
  Path locate(Measurement m);                                          // the result file
}

final class DetectorVerdict {
  enum Kind { OK, REGRESSION, ERROR, INSUFFICIENT_DATA }      // DECISIONS.md D43
  Kind kind(); Metric metric(); /* observed, threshold, interval, baseline job, explanation */
}
interface RegressionDetector {
  DetectorVerdict evaluate(Measurement current, Optional<Measurement> baseline,
                           List<Measurement> history);       // baseline empty: first baseline result
}

interface BaselineRepository { Optional<Baseline> get(String target); void set(Baseline b); }
interface DetectionLog { void append(DetectionRecord r); List<DetectionRecord> all(); }

interface Notifier { void notify(DetectionEvent event) throws Exception; }
interface RegressionAction { void execute(RegressionDetected event) throws Exception; }
```

## Patterns and why each one is here

| Pattern | Where | Justification |
|---|---|---|
| Strategy | `Executor`, `Fingerprinter`, `EstimatorStrategy`, `CompletionPolicy`, `RegressionDetector`, renderers | Behavior varies by environment or policy and is selected by config. |
| Chain of Responsibility | `TriggerChain` over `TriggerRule`s | Ordered rules; first non-abstaining rule decides, with a reason. |
| Observer | `EventBus` + subscribers | Decouples stages; notifiers and actions plug in without touching producers. |
| State | `JobState` + transition table | Lifecycle has legal and illegal transitions; illegal ones throw. |
| Command | `RegressionAction`; `CliCommand` | Actions are configured data, executed later, and logged; CLI subcommands are looked up by name, so adding one never changes the dispatcher. |
| Builder | `JobSpec`, `HarnessInvocation`; domain `Target`, `Checkpoint`, `Provenance`, `AcceptanceReport`, `SeedReport` | Many fields; invalid combinations rejected at `build()`. |
| Template Method | `WeightFileFingerprinter` (base of both fingerprinters); `BaselineDetector` (base of the baseline-relative detectors) | The file walk, ordering, and encoding are shared, so the fingerprint methods cannot drift apart (DECISIONS.md D23); the Comparability guard runs before every baseline comparison, so no detector can skip it (D47). |
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
| FAILED | CREATED | retry: `RetryPolicy` allows it and retries so far (attempt − 1) < `max_retries` |

Every other transition throws `IllegalJobTransitionException`. Each FAILED state carries a
`FailureReason`; `RetryPolicy` retries only `NODE_FAILURE`, `PREEMPTED_NO_REQUEUE`, and
`UNEXPECTED_EXIT`. It never retries `BAD_ARGUMENTS`, `MODEL_LOAD`, `OUT_OF_MEMORY`,
`BACKEND_COUNTERS` (exit 5), `TIMEOUT`, `INVALID_REPORT`, or `SUBMISSION_FAILED`, since the same
inputs would fail the same way (DECISIONS.md D30). A job is attempted at most
`1 + max_retries` times (D31); every attempt keeps its own run directory (D32).

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
  raw/<job-id>/attempt-<n>/invocation.json the exact argv of attempt n
  raw/<job-id>/attempt-<n>/report.json     harness output exactly as written
  raw/<job-id>/attempt-<n>/stdout.log, stderr.log, exit_code
  baselines.json                           target → baseline checkpoint (fingerprint, path, step,
                                           set_at, source manual|auto)
  detections.log                           every detection outcome, one JSON object per line
  alerts.log                               one human-readable line per alert
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
