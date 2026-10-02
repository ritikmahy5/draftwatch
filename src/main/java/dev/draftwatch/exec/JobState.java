package dev.draftwatch.exec;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Engine job states and their transition table (ARCHITECTURE.md, "Job state machine"). The names
 * avoid Slurm's own {@code PENDING}. Every transition not in the table throws
 * {@link IllegalJobTransitionException}.
 */
public enum JobState {
  CREATED,
  SUBMITTED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  private static final Map<JobState, Set<JobState>> LEGAL = new EnumMap<>(JobState.class);

  static {
    LEGAL.put(CREATED, EnumSet.of(SUBMITTED, CANCELLED, FAILED));
    LEGAL.put(SUBMITTED, EnumSet.of(RUNNING, FAILED, CANCELLED));
    LEGAL.put(RUNNING, EnumSet.of(SUBMITTED, SUCCEEDED, FAILED, CANCELLED));
    LEGAL.put(SUCCEEDED, EnumSet.noneOf(JobState.class));
    LEGAL.put(FAILED, EnumSet.of(CREATED));
    LEGAL.put(CANCELLED, EnumSet.noneOf(JobState.class));
  }

  /** The states reachable from this one in one transition (unmodifiable). */
  public Set<JobState> successors() {
    return Collections.unmodifiableSet(LEGAL.get(this));
  }

  public boolean canTransitionTo(JobState next) {
    return LEGAL.get(this).contains(next);
  }

  /** CREATED, SUBMITTED, and RUNNING jobs still have work to do (they count for max_pending). */
  public boolean isActive() {
    return this == CREATED || this == SUBMITTED || this == RUNNING;
  }

  /**
   * Checks a transition against the table.
   *
   * @throws IllegalJobTransitionException if {@code from → to} is not in the table
   */
  public static void checkTransition(JobState from, JobState to) {
    if (!from.canTransitionTo(to)) {
      throw new IllegalJobTransitionException(from, to, "not in the transition table");
    }
  }
}
