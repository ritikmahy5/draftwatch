package dev.draftwatch.config;

import dev.draftwatch.domain.Metric;
import java.util.Objects;

/**
 * {@code trend(metric, window, max_slope)}: regression when the least-squares slope of the
 * metric against checkpoint index over the last {@code window} comparable measurements is below
 * {@code max_slope} (metric per checkpoint).
 */
public final class TrendSpec implements DetectorSpec {
  private final Metric metric;
  private final int window;
  private final double maxSlope;

  private TrendSpec(Metric metric, int window, double maxSlope) {
    this.metric = metric;
    this.window = window;
    this.maxSlope = maxSlope;
  }

  /**
   * Creates the spec.
   *
   * @throws IllegalArgumentException unless {@code window >= 2} (a slope needs two points) and
   *     {@code maxSlope} is finite
   */
  public static TrendSpec of(Metric metric, int window, double maxSlope) {
    Objects.requireNonNull(metric, "metric");
    if (window < 2) {
      throw new IllegalArgumentException("window must be >= 2, was " + window);
    }
    if (Double.isNaN(maxSlope) || Double.isInfinite(maxSlope)) {
      throw new IllegalArgumentException("max_slope must be a finite number");
    }
    return new TrendSpec(metric, window, maxSlope);
  }

  @Override
  public Kind kind() {
    return Kind.TREND;
  }

  @Override
  public Metric metric() {
    return metric;
  }

  public int window() {
    return window;
  }

  public double maxSlope() {
    return maxSlope;
  }

  @Override
  public String describe() {
    return "trend(metric="
        + metric.wireName()
        + ", window="
        + window
        + ", max_slope="
        + SpecText.number(maxSlope)
        + ")";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TrendSpec)) {
      return false;
    }
    TrendSpec that = (TrendSpec) o;
    return metric == that.metric
        && window == that.window
        && Double.compare(maxSlope, that.maxSlope) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(metric, window, maxSlope);
  }

  @Override
  public String toString() {
    return describe();
  }
}
