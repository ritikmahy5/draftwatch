package dev.draftwatch.trigger;

import dev.draftwatch.exec.Job;
import dev.draftwatch.store.JobRepository;
import dev.draftwatch.store.ResultRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link History} read from the job and result repositories on every call, so a job created
 * earlier in the same {@code watch} pass is already visible to the rules.
 */
public final class RepositoryHistory implements History {
  private final JobRepository jobs;
  private final ResultRepository results;

  public RepositoryHistory(JobRepository jobs, ResultRepository results) {
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.results = Objects.requireNonNull(results, "results");
  }

  @Override
  public boolean hasResult(String fingerprint, String probeHash) {
    return !results.find(fingerprint, probeHash).isEmpty();
  }

  @Override
  public List<Job> jobs(String fingerprint, String probeHash) {
    List<Job> out = new ArrayList<>();
    for (Job job : jobs.all()) {
      if (job.spec().checkpoint().fingerprint().equals(fingerprint)
          && job.spec().probe().hash().equals(probeHash)) {
        out.add(job);
      }
    }
    return out;
  }

  @Override
  public int activeJobs(String target) {
    int active = 0;
    for (Job job : jobs.all()) {
      if (job.spec().checkpoint().targetName().equals(target) && job.state().isActive()) {
        active++;
      }
    }
    return active;
  }
}
