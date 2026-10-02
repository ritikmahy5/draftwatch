package dev.draftwatch.testing;

import dev.draftwatch.exec.Job;
import dev.draftwatch.store.JobRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** A {@link JobRepository} in memory, for tests. */
public final class InMemoryJobRepository implements JobRepository {
  private final TreeMap<String, Job> jobs = new TreeMap<>();
  private int saves;

  @Override
  public void save(Job job) {
    jobs.put(job.id(), job);
    saves++;
  }

  @Override
  public Optional<Job> find(String id) {
    return Optional.ofNullable(jobs.get(id));
  }

  @Override
  public List<Job> all() {
    return new ArrayList<>(jobs.values());
  }

  public int saves() {
    return saves;
  }
}
