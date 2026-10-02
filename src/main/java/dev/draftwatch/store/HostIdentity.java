package dev.draftwatch.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Who this process is, as recorded in a state lock. An interface so tests can impersonate other
 * hosts and processes.
 */
public interface HostIdentity {
  /** This machine's host name. */
  String hostname();

  long pid();

  /** This process's start time, when the platform reports it. */
  Optional<Instant> processStart();

  /** {@code SLURM_JOB_ID} when running inside a Slurm job. */
  Optional<String> slurmJobId();
}
