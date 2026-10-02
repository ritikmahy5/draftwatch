package dev.draftwatch.trigger;

import dev.draftwatch.exec.Job;
import java.util.List;

/** What trigger rules may know about past and current work, read live from the stores. */
public interface History {
  /** True if a result is stored for this checkpoint and probe. */
  boolean hasResult(String fingerprint, String probeHash);

  /** Every job, in any state, that measures this checkpoint with this probe. */
  List<Job> jobs(String fingerprint, String probeHash);

  /** Jobs of {@code target} that are CREATED, SUBMITTED, or RUNNING. */
  int activeJobs(String target);
}
