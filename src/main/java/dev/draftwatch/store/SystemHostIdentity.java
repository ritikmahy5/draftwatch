package dev.draftwatch.store;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** The real host name, PID, start time, and {@code SLURM_JOB_ID} of this JVM. */
public final class SystemHostIdentity implements HostIdentity {
  private final Map<String, String> environment;

  public SystemHostIdentity(Map<String, String> environment) {
    this.environment = Map.copyOf(environment);
  }

  /**
   * {@inheritDoc}
   *
   * @throws StateLockException if the host name cannot be determined; a lock without a reliable
   *     host cannot be checked for liveness, so locking fails loudly instead
   */
  @Override
  public String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      throw new StateLockException("cannot determine this host's name: " + e.getMessage(), e);
    }
  }

  @Override
  public long pid() {
    return ProcessHandle.current().pid();
  }

  @Override
  public Optional<Instant> processStart() {
    return ProcessHandle.current().info().startInstant();
  }

  @Override
  public Optional<String> slurmJobId() {
    return Optional.ofNullable(environment.get("SLURM_JOB_ID")).filter(id -> !id.isEmpty());
  }
}
