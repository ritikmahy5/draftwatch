package dev.draftwatch.scoring;

import java.util.OptionalDouble;
import java.util.Set;

/** The cost of one measurement of {@code n} prompts, measured or extrapolated. */
public final class CostRow {
  private final int n;
  private final boolean extrapolated;
  private final OptionalDouble meanWallClockSeconds;
  private final OptionalDouble meanElapsedSeconds;
  private final int measurements;
  private final Set<String> harnessVersions;

  CostRow(
      int n,
      boolean extrapolated,
      OptionalDouble meanWallClockSeconds,
      OptionalDouble meanElapsedSeconds,
      int measurements,
      Set<String> harnessVersions) {
    this.n = n;
    this.extrapolated = extrapolated;
    this.meanWallClockSeconds = meanWallClockSeconds;
    this.meanElapsedSeconds = meanElapsedSeconds;
    this.measurements = measurements;
    this.harnessVersions = Set.copyOf(harnessVersions);
  }

  public int n() {
    return n;
  }

  /** True for sizes that were never measured, whose values come from a fitted line. */
  public boolean extrapolated() {
    return extrapolated;
  }

  /** The harness's {@code wall_clock_seconds}, averaged. */
  public OptionalDouble meanWallClockSeconds() {
    return meanWallClockSeconds;
  }

  /** Slurm's elapsed time of the job, averaged over the jobs in the dump. */
  public OptionalDouble meanElapsedSeconds() {
    return meanElapsedSeconds;
  }

  /** The number of measurements behind the row; 0 when extrapolated. */
  public int measurements() {
    return measurements;
  }

  /** The harness versions of those measurements; times are comparable only within one. */
  public Set<String> harnessVersions() {
    return harnessVersions;
  }
}
