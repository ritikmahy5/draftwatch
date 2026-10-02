package dev.draftwatch.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code executor.slurm}. Omitted {@code partition}, {@code gres}, and {@code time} mean the
 * corresponding sbatch option is not passed, so Slurm's site defaults apply (DECISIONS.md D65).
 */
public final class SlurmConfig {
  private final Optional<String> partition;
  private final Optional<String> gres;
  private final Optional<String> time;
  private final boolean requeueOnPreempt;
  private final List<String> extraSbatchArgs;
  private final List<String> scheduleSbatchArgs;

  /** {@code schedule_sbatch_args} when omitted: a time limit for one watch pass (D63). */
  public static final List<String> DEFAULT_SCHEDULE_SBATCH_ARGS = List.of("--time=00:30:00");

  private SlurmConfig(
      Optional<String> partition,
      Optional<String> gres,
      Optional<String> time,
      boolean requeueOnPreempt,
      List<String> extraSbatchArgs,
      List<String> scheduleSbatchArgs) {
    this.partition = partition;
    this.gres = gres;
    this.time = time;
    this.requeueOnPreempt = requeueOnPreempt;
    this.extraSbatchArgs = extraSbatchArgs;
    this.scheduleSbatchArgs = scheduleSbatchArgs;
  }

  public static SlurmConfig of(
      Optional<String> partition,
      Optional<String> gres,
      Optional<String> time,
      boolean requeueOnPreempt,
      List<String> extraSbatchArgs,
      List<String> scheduleSbatchArgs) {
    return new SlurmConfig(
        Objects.requireNonNull(partition, "partition"),
        Objects.requireNonNull(gres, "gres"),
        Objects.requireNonNull(time, "time"),
        requeueOnPreempt,
        List.copyOf(extraSbatchArgs),
        List.copyOf(scheduleSbatchArgs));
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

  /** Extra sbatch arguments for measurement jobs. */
  public List<String> extraSbatchArgs() {
    return extraSbatchArgs;
  }

  /** The sbatch arguments of the CPU-only schedule job, after the ones draftwatch sets (D63). */
  public List<String> scheduleSbatchArgs() {
    return scheduleSbatchArgs;
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
        && extraSbatchArgs.equals(that.extraSbatchArgs)
        && scheduleSbatchArgs.equals(that.scheduleSbatchArgs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        partition, gres, time, requeueOnPreempt, extraSbatchArgs, scheduleSbatchArgs);
  }
}
