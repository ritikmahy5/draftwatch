package dev.draftwatch.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.events.EventBus;
import dev.draftwatch.events.MeasurementStored;
import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobIds;
import dev.draftwatch.exec.JobPoller;
import dev.draftwatch.exec.JobSpec;
import dev.draftwatch.exec.JobState;
import dev.draftwatch.exec.MeasurementSpec;
import dev.draftwatch.exec.RetryPolicy;
import dev.draftwatch.store.AtomicFiles;
import dev.draftwatch.store.JobRepository;
import dev.draftwatch.store.ResultRepository;
import dev.draftwatch.store.StoreException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs measurement jobs through their lifecycle: create, submit, poll, store the result, retry.
 * Every state change is saved before the next step, and a result is stored, and
 * {@link MeasurementStored} published, before its job is marked SUCCEEDED, so a crash at any point
 * loses nothing and duplicates nothing.
 */
public final class MeasurementRunner {
  public static final String INVOCATION_FILE = "invocation.json";
  private static final Duration FIRST_POLL = Duration.ofMillis(100);
  private static final Duration MAX_POLL = Duration.ofSeconds(5);

  private final Executor executor;
  private final JobPoller poller;
  private final RetryPolicy retryPolicy;
  private final JobRepository jobs;
  private final ResultRepository results;
  private final EventBus bus;
  private final JobIds ids;
  private final Clock clock;
  private final Path stateDir;
  private final List<String> harnessCommand;
  private final Path workingDir;
  private final ObjectMapper json = new ObjectMapper();

  /**
   * Creates a runner.
   *
   * @param stateDir where {@code raw/<job-id>/} directories are created
   * @param harnessCommand the configured {@code harness.command}
   * @param workingDir the harness's working directory: the config file's directory
   */
  public MeasurementRunner(
      Executor executor,
      JobPoller poller,
      RetryPolicy retryPolicy,
      JobRepository jobs,
      ResultRepository results,
      EventBus bus,
      JobIds ids,
      Clock clock,
      Path stateDir,
      List<String> harnessCommand,
      Path workingDir) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.poller = Objects.requireNonNull(poller, "poller");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.results = Objects.requireNonNull(results, "results");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.stateDir = Objects.requireNonNull(stateDir, "stateDir");
    this.harnessCommand = List.copyOf(harnessCommand);
    this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
  }

  /** Creates and saves a CREATED job measuring {@code probe} on {@code checkpoint}. */
  public Job create(Checkpoint checkpoint, ResolvedProbe probe) {
    String id = ids.next();
    MeasurementSpec spec =
        MeasurementSpec.of(
            checkpoint,
            probe,
            harnessCommand,
            workingDir,
            stateDir.resolve("raw").resolve(id),
            executor.name());
    Job job = Job.created(id, spec, clock.instant());
    jobs.save(job);
    return job;
  }

  /**
   * Submits a CREATED job's current attempt, recording the exact command in the attempt's
   * {@code invocation.json} first. A refused submission makes the job FAILED.
   */
  public Job submit(Job job) {
    if (job.state() != JobState.CREATED) {
      throw new IllegalStateException(job.id() + " is " + job.state() + ", not CREATED");
    }
    JobSpec spec = job.spec().jobSpec(job.id(), job.attempt());
    Job next;
    try {
      writeInvocation(spec);
      JobHandle handle = executor.submit(spec);
      next = job.submitted(handle, handle.submittedAt());
    } catch (ExecutorException e) {
      next = job.failed(FailureReason.SUBMISSION_FAILED, e.getMessage(), clock.instant());
    }
    jobs.save(next);
    return next;
  }

  private void writeInvocation(JobSpec spec) {
    Path file = spec.runDir().resolve(INVOCATION_FILE);
    ArrayNode command = json.createArrayNode();
    spec.command().forEach(command::add);
    try {
      AtomicFiles.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(command));
    } catch (IOException e) {
      throw new StoreException(file, "cannot record the invocation: " + e.getMessage(), e);
    }
  }

  /**
   * Moves {@code job} one step forward: submits it if CREATED, polls it if SUBMITTED or RUNNING
   * (storing its result if it just succeeded), and retries it if it is FAILED and the retry
   * policy allows. Done jobs are returned unchanged.
   *
   * @throws ExecutorException if the executor cannot be asked about a submitted job; nothing is
   *     saved then, so the next call tries again
   */
  public Job advance(Job job) {
    Job next = job;
    if (next.state() == JobState.CREATED) {
      next = submit(next);
    } else if (next.state() == JobState.SUBMITTED || next.state() == JobState.RUNNING) {
      JobPoller.Result polled = poller.poll(next);
      next = polled.job();
      if (polled.report().isPresent()) {
        Measurement m = measurement(next, polled.report().get());
        results.append(m);
        bus.publish(new MeasurementStored(clock.instant(), m, results.locate(m)));
      }
      if (!next.equals(job)) {
        jobs.save(next);
      }
    }
    if (next.state() == JobState.FAILED && retryPolicy.allowsRetry(next)) {
      next = next.retried(retryPolicy, clock.instant());
      jobs.save(next);
      next = submit(next);
    }
    return next;
  }

  /** True when {@code job} will not change any more: terminal and not retryable. */
  public boolean isDone(Job job) {
    return !job.state().isActive()
        && !(job.state() == JobState.FAILED && retryPolicy.allowsRetry(job));
  }

  /**
   * Advances {@code toRun} until every job is done, polling with a backoff from 100 ms to 5 s.
   *
   * @return the jobs' final values, in the same order
   * @throws InterruptedException if interrupted while waiting; job files remain consistent
   */
  public List<Job> runToCompletion(List<Job> toRun, Sleeper sleeper)
      throws InterruptedException {
    List<Job> current = new ArrayList<>(toRun);
    Duration wait = FIRST_POLL;
    while (true) {
      boolean allDone = true;
      for (int i = 0; i < current.size(); i++) {
        if (!isDone(current.get(i))) {
          current.set(i, advance(current.get(i)));
        }
        allDone &= isDone(current.get(i));
      }
      if (allDone) {
        return List.copyOf(current);
      }
      sleeper.sleep(wait);
      wait = wait.multipliedBy(2).compareTo(MAX_POLL) > 0 ? MAX_POLL : wait.multipliedBy(2);
    }
  }

  /** The stored result of a SUCCEEDED job, if any. */
  public Optional<Measurement> result(Job job) {
    if (job.state() != JobState.SUCCEEDED) {
      return Optional.empty();
    }
    return results.find(job.spec().checkpoint().fingerprint(), job.spec().probe().hash()).stream()
        .filter(m -> m.jobId().equals(job.id()))
        .findFirst();
  }

  /** Where {@code m} is stored. */
  public Path locate(Measurement m) {
    return results.locate(m);
  }

  /** The measurement of a job that just SUCCEEDED, with every SPEC.md F4 provenance field. */
  Measurement measurement(Job job, AcceptanceReport report) {
    MeasurementSpec spec = job.spec();
    ResolvedProbe probe = spec.probe();
    Instant end = job.lastChange().at();
    Provenance provenance =
        Provenance.builder()
            .checkpoint(spec.checkpoint())
            .probeId(probe.probe().id())
            .probeHash(probe.hash())
            .draftId(probe.probe().draft().id())
            .draftFingerprint(probe.draftFingerprint())
            .harnessVersion(report.harnessVersion())
            .backend(report.backend())
            .dtype(probe.probe().decoding().dtype())
            .estimator(probe.probe().estimator())
            .seeds(probe.probe().seeds())
            .promptSetSha256(probe.promptSet().sha256())
            .executor(spec.executor())
            .jobId(job.id())
            .attempt(job.attempt())
            .startTime(job.runningSince().orElse(end))
            .endTime(end)
            .rawReportPath(spec.reportPath(job.attempt()))
            .build();
    return Measurement.of(provenance, report);
  }
}
