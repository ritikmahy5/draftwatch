package dev.draftwatch.exec;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * An executor's own view of one attempt, before {@link JobPoller} maps it onto engine states.
 * Times come from the executor when it knows them (the local executor knows when it launched the
 * process and when the exit code was written), so provenance does not depend on poll timing.
 */
public final class ExecutorStatus {
  /** The executor-neutral kinds of status. */
  public enum Kind {
    /** Accepted but not started. */
    QUEUED,
    /** Running now. */
    RUNNING,
    /** Finished with an exit code. */
    EXITED,
    /** Gone without an exit code. */
    LOST,
    /** Ended for a reason the executor itself reports, such as a time limit. */
    FAILED,
    /** Cancelled outside draftwatch. */
    CANCELLED,
    /** Not known yet; this poll changes nothing. */
    UNRESOLVED
  }

  private final Kind kind;
  private final Optional<FailureReason> failureReason;
  private final OptionalInt exitCode;
  private final Optional<Instant> startedAt;
  private final Optional<Instant> endedAt;
  private final String detail;

  private ExecutorStatus(
      Kind kind,
      Optional<FailureReason> failureReason,
      OptionalInt exitCode,
      Optional<Instant> startedAt,
      Optional<Instant> endedAt,
      String detail) {
    this.kind = kind;
    this.failureReason = failureReason;
    this.exitCode = exitCode;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
    this.detail = detail;
  }

  public static ExecutorStatus queued() {
    return new ExecutorStatus(
        Kind.QUEUED,
        Optional.empty(),
        OptionalInt.empty(),
        Optional.empty(),
        Optional.empty(),
        "queued");
  }

  public static ExecutorStatus running(Optional<Instant> startedAt) {
    return new ExecutorStatus(
        Kind.RUNNING,
        Optional.empty(),
        OptionalInt.empty(),
        Objects.requireNonNull(startedAt, "startedAt"),
        Optional.empty(),
        "running");
  }

  public static ExecutorStatus exited(
      int exitCode, Optional<Instant> startedAt, Optional<Instant> endedAt) {
    return new ExecutorStatus(
        Kind.EXITED,
        Optional.empty(),
        OptionalInt.of(exitCode),
        Objects.requireNonNull(startedAt, "startedAt"),
        Objects.requireNonNull(endedAt, "endedAt"),
        "exited with code " + exitCode);
  }

  public static ExecutorStatus lost(String detail) {
    return new ExecutorStatus(
        Kind.LOST,
        Optional.empty(),
        OptionalInt.empty(),
        Optional.empty(),
        Optional.empty(),
        Objects.requireNonNull(detail, "detail"));
  }

  /** Ended for {@code reason}, as the executor reports; never a success. */
  public static ExecutorStatus failed(
      FailureReason reason,
      Optional<Instant> startedAt,
      Optional<Instant> endedAt,
      String detail) {
    return new ExecutorStatus(
        Kind.FAILED,
        Optional.of(Objects.requireNonNull(reason, "reason")),
        OptionalInt.empty(),
        Objects.requireNonNull(startedAt, "startedAt"),
        Objects.requireNonNull(endedAt, "endedAt"),
        Objects.requireNonNull(detail, "detail"));
  }

  public static ExecutorStatus cancelled(
      Optional<Instant> startedAt, Optional<Instant> endedAt, String detail) {
    return new ExecutorStatus(
        Kind.CANCELLED,
        Optional.empty(),
        OptionalInt.empty(),
        Objects.requireNonNull(startedAt, "startedAt"),
        Objects.requireNonNull(endedAt, "endedAt"),
        Objects.requireNonNull(detail, "detail"));
  }

  /** Nothing can be concluded from this poll; {@code detail} says why. */
  public static ExecutorStatus unresolved(String detail) {
    return new ExecutorStatus(
        Kind.UNRESOLVED,
        Optional.empty(),
        OptionalInt.empty(),
        Optional.empty(),
        Optional.empty(),
        Objects.requireNonNull(detail, "detail"));
  }

  public Kind kind() {
    return kind;
  }

  /** Present exactly for {@link Kind#FAILED}. */
  public Optional<FailureReason> failureReason() {
    return failureReason;
  }

  /** Present exactly for {@link Kind#EXITED}. */
  public OptionalInt exitCode() {
    return exitCode;
  }

  public Optional<Instant> startedAt() {
    return startedAt;
  }

  public Optional<Instant> endedAt() {
    return endedAt;
  }

  public String detail() {
    return detail;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ExecutorStatus)) {
      return false;
    }
    ExecutorStatus that = (ExecutorStatus) o;
    return kind == that.kind
        && failureReason.equals(that.failureReason)
        && exitCode.equals(that.exitCode)
        && startedAt.equals(that.startedAt)
        && endedAt.equals(that.endedAt)
        && detail.equals(that.detail);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, failureReason, exitCode, startedAt, endedAt, detail);
  }

  @Override
  public String toString() {
    return kind + " (" + detail + ")";
  }
}
