package dev.draftwatch.stats;

import java.util.Objects;

/**
 * A paired bootstrap result for (current − baseline): the point estimate on the full data and the
 * percentile interval at the given confidence.
 */
public final class BootstrapInterval {
  private final double observed;
  private final double lower;
  private final double upper;
  private final double confidence;
  private final int resamples;
  private final long seed;

  BootstrapInterval(
      double observed, double lower, double upper, double confidence, int resamples, long seed) {
    this.observed = observed;
    this.lower = lower;
    this.upper = upper;
    this.confidence = confidence;
    this.resamples = resamples;
    this.seed = seed;
  }

  /** statistic(current) − statistic(baseline) on every prompt. */
  public double observed() {
    return observed;
  }

  public double lower() {
    return lower;
  }

  public double upper() {
    return upper;
  }

  public double confidence() {
    return confidence;
  }

  public int resamples() {
    return resamples;
  }

  public long seed() {
    return seed;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BootstrapInterval)) {
      return false;
    }
    BootstrapInterval that = (BootstrapInterval) o;
    return Double.compare(observed, that.observed) == 0
        && Double.compare(lower, that.lower) == 0
        && Double.compare(upper, that.upper) == 0
        && Double.compare(confidence, that.confidence) == 0
        && resamples == that.resamples
        && seed == that.seed;
  }

  @Override
  public int hashCode() {
    return Objects.hash(observed, lower, upper, confidence, resamples, seed);
  }

  @Override
  public String toString() {
    return observed + " [" + lower + ", " + upper + "]";
  }
}
