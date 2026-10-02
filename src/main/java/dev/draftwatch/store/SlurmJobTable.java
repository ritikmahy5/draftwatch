package dev.draftwatch.store;

/**
 * Whether a Slurm job is still alive, for taking over a lock held from inside one (DECISIONS.md
 * D62). An interface so tests can fake squeue, as {@link ProcessTable} fakes PIDs.
 */
public interface SlurmJobTable {
  /**
   * True unless squeue lists the job in a terminal state or does not list it. An unknown state
   * counts as alive.
   *
   * @throws RuntimeException if squeue cannot be asked; then nothing is proven
   */
  boolean isAlive(String slurmJobId);
}
