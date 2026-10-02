package dev.draftwatch.exec;

import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.harness.ReportViolationException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Advances one submitted job by one poll: asks the executor for the attempt's status, maps it
 * onto the engine state machine, and on exit 0 validates the report. SUCCEEDED therefore always
 * means "exit 0 and the report passed every contract rule". A failure or cancellation the
 * executor reports for a job that never looked RUNNING is preceded by RUNNING when the executor
 * knows a start time, so the history shows that the attempt ran.
 */
public final class JobPoller {
  private final Executor executor;
  private final ReportParser parser;
  private final Clock clock;

  public JobPoller(Executor executor, ReportParser parser, Clock clock) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** The outcome of one poll: the job's new value and, if it just succeeded, its report. */
  public static final class Result {
    private final Job job;
    private final Optional<AcceptanceReport> report;

    private Result(Job job, Optional<AcceptanceReport> report) {
      this.job = job;
      this.report = report;
    }

    public Job job() {
      return job;
    }

    /** Present exactly when this poll moved the job to SUCCEEDED. */
    public Optional<AcceptanceReport> report() {
      return report;
    }
  }

  /**
   * Polls {@code job} once. Jobs that are not SUBMITTED or RUNNING are returned unchanged.
   *
   * @throws IllegalStateException if a SUBMITTED or RUNNING job has no handle
   * @throws ExecutorException if the executor cannot be asked; the job is unchanged
   */
  public Result poll(Job job) {
    if (job.state() != JobState.SUBMITTED && job.state() != JobState.RUNNING) {
      return new Result(job, Optional.empty());
    }
    JobHandle handle =
        job.handle()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        job.id() + " is " + job.state() + " without a handle"));
    ExecutorStatus status = executor.status(handle);
    Instant now = clock.instant();
    switch (status.kind()) {
      case QUEUED:
        return withoutReport(
            job.state() == JobState.RUNNING ? job.requeued(now, "requeued by executor") : job);
      case RUNNING:
        return withoutReport(
            job.state() == JobState.SUBMITTED
                ? job.running(status.startedAt().orElse(now), "started")
                : job);
      case EXITED:
        return exited(job, status, now);
      case LOST:
        return withoutReport(job.failed(FailureReason.UNEXPECTED_EXIT, status.detail(), now));
      case FAILED:
        return withoutReport(
            startedIfKnown(job, status)
                .failed(
                    status.failureReason().orElseThrow(),
                    status.detail(),
                    status.endedAt().orElse(now)));
      case CANCELLED:
        return withoutReport(
            startedIfKnown(job, status).cancelled(status.endedAt().orElse(now), status.detail()));
      case UNRESOLVED:
        return withoutReport(job);
      default:
        throw new IllegalStateException("unhandled executor status " + status.kind());
    }
  }

  private Result exited(Job job, ExecutorStatus status, Instant now) {
    Instant ended = status.endedAt().orElse(now);
    Job running =
        job.state() == JobState.SUBMITTED
            ? job.running(status.startedAt().orElse(ended), "started")
            : job;
    int code = status.exitCode().getAsInt();
    if (code != 0) {
      Path runDir = job.spec().runDir(job.attempt());
      return withoutReport(
          running.failed(
              FailureReason.forExitCode(code),
              "harness exited with code " + code + "; its output is in " + runDir,
              ended));
    }
    try {
      AcceptanceReport report =
          parser.parse(job.spec().reportPath(job.attempt()), job.spec().expectedReport());
      return new Result(running.succeeded(ended), Optional.of(report));
    } catch (ReportViolationException e) {
      return withoutReport(running.failed(FailureReason.INVALID_REPORT, e.getMessage(), ended));
    }
  }

  /** {@code job}, moved to RUNNING first if it is SUBMITTED and {@code status} has a start. */
  private static Job startedIfKnown(Job job, ExecutorStatus status) {
    if (job.state() == JobState.SUBMITTED && status.startedAt().isPresent()) {
      return job.running(status.startedAt().get(), "started");
    }
    return job;
  }

  private static Result withoutReport(Job job) {
    return new Result(job, Optional.empty());
  }
}
