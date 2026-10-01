package dev.draftwatch.domain;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * A report's {@code aggregate}: the mean of each metric over seeds and its sample standard
 * deviation, which is empty when there is one seed.
 */
public final class AggregateMetrics {
  private final double alphaMean;
  private final OptionalDouble alphaStd;
  private final double tauMean;
  private final OptionalDouble tauStd;

  private AggregateMetrics(
      double alphaMean, OptionalDouble alphaStd, double tauMean, OptionalDouble tauStd) {
    this.alphaMean = alphaMean;
    this.alphaStd = alphaStd;
    this.tauMean = tauMean;
    this.tauStd = tauStd;
  }

  public static AggregateMetrics of(
      double alphaMean, OptionalDouble alphaStd, double tauMean, OptionalDouble tauStd) {
    return new AggregateMetrics(
        alphaMean,
        Require.nonNull(alphaStd, "alpha_std"),
        tauMean,
        Require.nonNull(tauStd, "tau_std"));
  }

  public double alphaMean() {
    return alphaMean;
  }

  public OptionalDouble alphaStd() {
    return alphaStd;
  }

  public double tauMean() {
    return tauMean;
  }

  public OptionalDouble tauStd() {
    return tauStd;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AggregateMetrics)) {
      return false;
    }
    AggregateMetrics that = (AggregateMetrics) o;
    return Double.compare(alphaMean, that.alphaMean) == 0
        && alphaStd.equals(that.alphaStd)
        && Double.compare(tauMean, that.tauMean) == 0
        && tauStd.equals(that.tauStd);
  }

  @Override
  public int hashCode() {
    return Objects.hash(alphaMean, alphaStd, tauMean, tauStd);
  }

  @Override
  public String toString() {
    return "AggregateMetrics{alpha_mean=" + alphaMean + ", tau_mean=" + tauMean + "}";
  }
}
