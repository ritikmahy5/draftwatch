package dev.draftwatch.store;

import dev.draftwatch.exec.Job;
import java.util.List;
import java.util.Optional;

/**
 * Persistent jobs (Repository: storage is swappable and tests use an in-memory fake). Saving
 * replaces the stored job with the new value; history is never lost because a job carries its
 * whole history.
 */
public interface JobRepository {
  void save(Job job);

  Optional<Job> find(String id);

  /** Every stored job, ordered by id. */
  List<Job> all();
}
