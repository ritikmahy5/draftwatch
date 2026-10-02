package dev.draftwatch.exec;

/**
 * Runs job attempts somewhere: on this machine or through a scheduler (Strategy, selected by
 * {@code executor.type}). Implementations keep no state that a later draftwatch process could
 * not recover from the {@link JobHandle} and the run directory.
 */
public interface Executor {
  /** The executor type name recorded in provenance, for example {@code local}. */
  String name();

  /**
   * Starts or enqueues one attempt.
   *
   * @throws ExecutorException if the attempt cannot be submitted
   */
  JobHandle submit(JobSpec spec);

  /** The attempt's current executor-native status. */
  ExecutorStatus status(JobHandle handle);

  /** Stops the attempt if it is still queued or running; does nothing otherwise. */
  void cancel(JobHandle handle);
}
