package dev.draftwatch.events;

import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobState;
import java.time.Instant;
import java.util.Objects;

/**
 * A measurement job failed for good: it is FAILED and the retry policy allows no further attempt,
 * so its checkpoint has no result for its probe until someone submits it again.
 */
public final class MeasurementFailed implements Event {
  private final Instant at;
  private final Job job;

  /** @throws IllegalArgumentException if {@code job} is not FAILED */
  public MeasurementFailed(Instant at, Job job) {
    this.at = Objects.requireNonNull(at, "at");
    this.job = Objects.requireNonNull(job, "job");
    if (job.state() != JobState.FAILED) {
      throw new IllegalArgumentException("job " + job.id() + " is " + job.state() + ", not FAILED");
    }
  }

  @Override
  public Instant at() {
    return at;
  }

  public Job job() {
    return job;
  }

  @Override
  public String toString() {
    return "MeasurementFailed{job " + job.id() + "}";
  }
}
