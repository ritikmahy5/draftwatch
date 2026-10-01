package dev.draftwatch.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code executor.slurm}. Omitted {@code partition}, {@code gres}, and {@code time} mean the
 * corresponding {@code #SBATCH} line is not written, so Slurm's site defaults apply.
 */
public final class SlurmConfig {
  private final Optional<String> partition;
  private final Optional<String> gres;
  private final Optional<String> time;
  private final boolean requeueOnPreempt;
  private final List<String> extraSbatchArgs;

  private SlurmConfig(
      Optional<String> partition,
      Optional<String> gres,
      Optional<String> time,
      boolean requeueOnPreempt,
      List<String> extraSbatchArgs) {
    this.partition = partition;
    this.gres = gres;
    this.time = time;
    this.requeueOnPreempt = requeueOnPreempt;
    this.extraSbatchArgs = extraSbatchArgs;
  }

  public static SlurmConfig of(
      Optional<String> partition,
      Optional<String> gres,
      Optional<String> time,
      boolean requeueOnPreempt,
      List<String> extraSbatchArgs) {
    return new SlurmConfig(
        Objects.requireNonNull(partition, "partition"),
        Objects.requireNonNull(gres, "gres"),
        Objects.requireNonNull(time, "time"),
        requeueOnPreempt,
        List.copyOf(extraSbatchArgs));
  }

  public Optional<String> partition() {
    return partition;
  }

  public Optional<String> gres() {
    return gres;
  }

  /** In one of the six formats {@code sbatch --time} accepts (DECISIONS.md D28). */
  public Optional<String> time() {
    return time;
  }

  public boolean requeueOnPreempt() {
    return requeueOnPreempt;
  }

  public List<String> extraSbatchArgs() {
    return extraSbatchArgs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SlurmConfig)) {
      return false;
    }
    SlurmConfig that = (SlurmConfig) o;
    return partition.equals(that.partition)
        && gres.equals(that.gres)
        && time.equals(that.time)
        && requeueOnPreempt == that.requeueOnPreempt
        && extraSbatchArgs.equals(that.extraSbatchArgs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(partition, gres, time, requeueOnPreempt, extraSbatchArgs);
  }
}
