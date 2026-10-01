package dev.draftwatch.config;

import java.util.Objects;
import java.util.Optional;

/** {@code executor}: executor type, retry limit, and Slurm settings when the type is slurm. */
public final class ExecutorConfig {
  private final ExecutorType type;
  private final int maxRetries;
  private final Optional<SlurmConfig> slurm;

  private ExecutorConfig(ExecutorType type, int maxRetries, Optional<SlurmConfig> slurm) {
    this.type = type;
    this.maxRetries = maxRetries;
    this.slurm = slurm;
  }

  /**
   * Creates executor settings.
   *
   * @throws IllegalArgumentException if {@code maxRetries} is negative or {@code slurm} is
   *     absent for the slurm executor
   */
  public static ExecutorConfig of(
      ExecutorType type, int maxRetries, Optional<SlurmConfig> slurm) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(slurm, "slurm");
    if (maxRetries < 0) {
      throw new IllegalArgumentException("max_retries must be >= 0, was " + maxRetries);
    }
    if (type == ExecutorType.SLURM && slurm.isEmpty()) {
      throw new IllegalArgumentException("slurm settings are required for the slurm executor");
    }
    return new ExecutorConfig(type, maxRetries, slurm);
  }

  public ExecutorType type() {
    return type;
  }

  /** Retries after the first attempt, for failures {@code RetryPolicy} considers transient. */
  public int maxRetries() {
    return maxRetries;
  }

  /** Present when configured; always present for {@link ExecutorType#SLURM}. */
  public Optional<SlurmConfig> slurm() {
    return slurm;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ExecutorConfig)) {
      return false;
    }
    ExecutorConfig that = (ExecutorConfig) o;
    return type == that.type && maxRetries == that.maxRetries && slurm.equals(that.slurm);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, maxRetries, slurm);
  }
}
