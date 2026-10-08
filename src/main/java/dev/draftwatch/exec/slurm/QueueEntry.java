package dev.draftwatch.exec.slurm;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One job as squeue lists it with the format {@code %i|%T|%S}. */
public final class QueueEntry {
  private final String jobId;
  private final String stateText;
  private final Optional<SlurmState> state;
  private final Optional<Instant> start;

  private QueueEntry(
      String jobId, String stateText, Optional<SlurmState> state, Optional<Instant> start) {
    this.jobId = jobId;
    this.stateText = stateText;
    this.state = state;
    this.start = start;
  }

  static QueueEntry of(String jobId, String stateText, Optional<Instant> start) {
    return new QueueEntry(
        Objects.requireNonNull(jobId, "jobId"),
        Objects.requireNonNull(stateText, "stateText"),
        SlurmState.parse(stateText),
        Objects.requireNonNull(start, "start"));
  }

  public String jobId() {
    return jobId;
  }

  /** The state exactly as squeue printed it. */
  public String stateText() {
    return stateText;
  }

  /** Empty for a state name {@link SlurmState} does not list. */
  public Optional<SlurmState> state() {
    return state;
  }

  /** {@code %S}: the actual start time once running; empty when squeue printed N/A. */
  public Optional<Instant> start() {
    return start;
  }

  /** True unless the state is known to be terminal; an unknown state counts as alive. */
  public boolean isAlive() {
    return state.map(s -> s.group() != SlurmState.Group.TERMINAL).orElse(true);
  }
}
