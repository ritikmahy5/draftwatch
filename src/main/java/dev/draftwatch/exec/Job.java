package dev.draftwatch.exec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A measurement job and its full state history. Immutable: every transition returns a new
 * {@code Job}, after checking it against the {@link JobState} table, so an illegal transition
 * can never be recorded.
 */
public final class Job {
  private final String id;
  private final MeasurementSpec spec;
  private final JobState state;
  private final int attempt;
  private final Optional<JobHandle> handle;
  private final List<StateChange> history;

  private Job(
      String id,
      MeasurementSpec spec,
      JobState state,
      int attempt,
      Optional<JobHandle> handle,
      List<StateChange> history) {
    this.id = id;
    this.spec = spec;
    this.state = state;
    this.attempt = attempt;
    this.handle = handle;
    this.history = history;
  }

  /** A new job in CREATED, attempt 1. */
  public static Job created(String id, MeasurementSpec spec, Instant at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(spec, "spec");
    StateChange first =
        StateChange.of(Optional.empty(), JobState.CREATED, at, 1, "created", Optional.empty());
    return new Job(id, spec, JobState.CREATED, 1, Optional.empty(), List.of(first));
  }

  /**
   * Rebuilds a stored job, replaying its history so that a history no legal sequence of
   * transitions could produce is rejected.
   *
   * @throws IllegalArgumentException if the history is empty, does not start with CREATED at
   *     attempt 1, links states inconsistently, or changes the attempt other than on a retry
   * @throws IllegalJobTransitionException if the history contains an illegal transition
   */
  public static Job restore(
      String id, MeasurementSpec spec, Optional<JobHandle> handle, List<StateChange> history) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(handle, "handle");
    List<StateChange> changes = List.copyOf(history);
    if (changes.isEmpty()) {
      throw new IllegalArgumentException("job " + id + " has no history");
    }
    StateChange first = changes.get(0);
    if (first.from().isPresent() || first.to() != JobState.CREATED || first.attempt() != 1) {
      throw new IllegalArgumentException("job " + id + " history must start CREATED, attempt 1");
    }
    for (int i = 1; i < changes.size(); i++) {
      StateChange previous = changes.get(i - 1);
      StateChange change = changes.get(i);
      if (change.from().orElse(null) != previous.to()) {
        throw new IllegalArgumentException(
            "job " + id + " history entry " + i + " starts from " + change.from()
                + ", previous entry ended in " + previous.to());
      }
      JobState.checkTransition(previous.to(), change.to());
      boolean retry = previous.to() == JobState.FAILED && change.to() == JobState.CREATED;
      int expectedAttempt = retry ? previous.attempt() + 1 : previous.attempt();
      if (change.attempt() != expectedAttempt) {
        throw new IllegalArgumentException(
            "job " + id + " history entry " + i + " has attempt " + change.attempt()
                + ", expected " + expectedAttempt);
      }
    }
    StateChange last = changes.get(changes.size() - 1);
    if (last.to() == JobState.CREATED && handle.isPresent()) {
      throw new IllegalArgumentException("job " + id + " is CREATED but has a handle");
    }
    return new Job(id, spec, last.to(), last.attempt(), handle, changes);
  }

  // --- transitions -------------------------------------------------------------------------

  /** CREATED → SUBMITTED: the executor accepted the attempt. */
  public Job submitted(JobHandle handle, Instant at) {
    Objects.requireNonNull(handle, "handle");
    return to(JobState.SUBMITTED, at, "submitted as " + handle, Optional.empty(), attempt,
        Optional.of(handle));
  }

  /** SUBMITTED → RUNNING. */
  public Job running(Instant at, String cause) {
    return to(JobState.RUNNING, at, cause, Optional.empty(), attempt, handle);
  }

  /** RUNNING → SUBMITTED: preempted and requeued by the scheduler. */
  public Job requeued(Instant at, String cause) {
    return to(JobState.SUBMITTED, at, cause, Optional.empty(), attempt, handle);
  }

  /** RUNNING → SUCCEEDED: exit 0 and the report passed validation. */
  public Job succeeded(Instant at) {
    return to(
        JobState.SUCCEEDED, at, "exit 0, report valid", Optional.empty(), attempt, handle);
  }

  /** CREATED, SUBMITTED, or RUNNING → FAILED. */
  public Job failed(FailureReason reason, String cause, Instant at) {
    Objects.requireNonNull(reason, "reason");
    return to(JobState.FAILED, at, cause, Optional.of(reason), attempt, handle);
  }

  /** CREATED, SUBMITTED, or RUNNING → CANCELLED. */
  public Job cancelled(Instant at, String cause) {
    return to(JobState.CANCELLED, at, cause, Optional.empty(), attempt, handle);
  }

  /**
   * FAILED → CREATED for the next attempt, which gets a new run directory and no handle yet.
   *
   * @throws IllegalJobTransitionException if {@code policy} does not allow a retry
   */
  public Job retried(RetryPolicy policy, Instant at) {
    if (state == JobState.FAILED && !policy.allowsRetry(this)) {
      throw new IllegalJobTransitionException(state, JobState.CREATED, policy.refusal(this));
    }
    return to(
        JobState.CREATED,
        at,
        "retry after " + failureReason().map(FailureReason::wireName).orElse("?"),
        Optional.empty(),
        attempt + 1,
        Optional.empty());
  }

  private Job to(
      JobState next,
      Instant at,
      String cause,
      Optional<FailureReason> reason,
      int nextAttempt,
      Optional<JobHandle> nextHandle) {
    JobState.checkTransition(state, next);
    List<StateChange> changes = new ArrayList<>(history);
    changes.add(StateChange.of(Optional.of(state), next, at, nextAttempt, cause, reason));
    return new Job(id, spec, next, nextAttempt, nextHandle, List.copyOf(changes));
  }

  // --- accessors ---------------------------------------------------------------------------

  public String id() {
    return id;
  }

  public MeasurementSpec spec() {
    return spec;
  }

  public JobState state() {
    return state;
  }

  /** 1 for the first attempt; incremented by each retry. */
  public int attempt() {
    return attempt;
  }

  /** The current attempt's handle; empty before submission and after a retry. */
  public Optional<JobHandle> handle() {
    return handle;
  }

  /** Every state change, oldest first. */
  public List<StateChange> history() {
    return history;
  }

  public StateChange lastChange() {
    return history.get(history.size() - 1);
  }

  /** The reason of the current FAILED state; empty in any other state. */
  public Optional<FailureReason> failureReason() {
    return state == JobState.FAILED ? lastChange().failureReason() : Optional.empty();
  }

  /** When the current attempt last entered RUNNING. */
  public Optional<Instant> runningSince() {
    for (int i = history.size() - 1; i >= 0; i--) {
      StateChange change = history.get(i);
      if (change.attempt() != attempt) {
        break;
      }
      if (change.to() == JobState.RUNNING) {
        return Optional.of(change.at());
      }
    }
    return Optional.empty();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Job)) {
      return false;
    }
    Job that = (Job) o;
    return id.equals(that.id)
        && spec.equals(that.spec)
        && state == that.state
        && attempt == that.attempt
        && handle.equals(that.handle)
        && history.equals(that.history);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, spec, state, attempt, handle, history);
  }

  @Override
  public String toString() {
    return "Job{" + id + " " + state + " attempt " + attempt + "}";
  }
}
