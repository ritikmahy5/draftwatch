package dev.draftwatch.exec;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One entry in a job's state history. A job's first entry has no {@code from} state. */
public final class StateChange {
  private final Optional<JobState> from;
  private final JobState to;
  private final Instant at;
  private final int attempt;
  private final String cause;
  private final Optional<FailureReason> failureReason;

  private StateChange(
      Optional<JobState> from,
      JobState to,
      Instant at,
      int attempt,
      String cause,
      Optional<FailureReason> failureReason) {
    this.from = from;
    this.to = to;
    this.at = at;
    this.attempt = attempt;
    this.cause = cause;
    this.failureReason = failureReason;
  }

  /**
   * Creates a history entry.
   *
   * @throws IllegalArgumentException if a failure reason is given for a non-FAILED state, or
   *     missing for FAILED
   */
  public static StateChange of(
      Optional<JobState> from,
      JobState to,
      Instant at,
      int attempt,
      String cause,
      Optional<FailureReason> failureReason) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(failureReason, "failureReason");
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
    }
    if ((to == JobState.FAILED) != failureReason.isPresent()) {
      throw new IllegalArgumentException("a failure reason is given exactly for FAILED");
    }
    return new StateChange(from, to, at, attempt, cause, failureReason);
  }

  public Optional<JobState> from() {
    return from;
  }

  public JobState to() {
    return to;
  }

  public Instant at() {
    return at;
  }

  public int attempt() {
    return attempt;
  }

  /** Human-readable cause, for example {@code harness exited with code 4}. */
  public String cause() {
    return cause;
  }

  /** Present exactly when {@link #to()} is FAILED. */
  public Optional<FailureReason> failureReason() {
    return failureReason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StateChange)) {
      return false;
    }
    StateChange that = (StateChange) o;
    return from.equals(that.from)
        && to == that.to
        && at.equals(that.at)
        && attempt == that.attempt
        && cause.equals(that.cause)
        && failureReason.equals(that.failureReason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(from, to, at, attempt, cause, failureReason);
  }

  @Override
  public String toString() {
    return from.map(Enum::name).orElse("(new)") + " -> " + to + " at " + at + " (attempt "
        + attempt + "): " + cause;
  }
}
