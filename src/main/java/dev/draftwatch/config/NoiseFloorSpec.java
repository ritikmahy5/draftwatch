package dev.draftwatch.config;

import dev.draftwatch.domain.Metric;
import java.util.Objects;

/**
 * {@code noise_floor(metric, k, sigma)}: regression when baseline − current > k · √2 · sigma.
 * {@code sigma} is the standard deviation of a single measurement from an external source, such
 * as replicate training runs; it cannot be estimated from one run (DECISIONS.md D10), so it has
 * no default.
 */
public final class NoiseFloorSpec implements DetectorSpec {
  private final Metric metric;
  private final double k;
  private final double sigma;

  private NoiseFloorSpec(Metric metric, double k, double sigma) {
    this.metric = metric;
    this.k = k;
    this.sigma = sigma;
  }

  /** @throws IllegalArgumentException unless {@code k} and {@code sigma} are finite and > 0 */
  public static NoiseFloorSpec of(Metric metric, double k, double sigma) {
    Objects.requireNonNull(metric, "metric");
    if (!(k > 0) || Double.isInfinite(k)) {
      throw new IllegalArgumentException("k must be a finite number > 0");
    }
    if (!(sigma > 0) || Double.isInfinite(sigma)) {
      throw new IllegalArgumentException("sigma must be a finite number > 0");
    }
    return new NoiseFloorSpec(metric, k, sigma);
  }

  @Override
  public Kind kind() {
    return Kind.NOISE_FLOOR;
  }

  @Override
  public Metric metric() {
    return metric;
  }

  public double k() {
    return k;
  }

  public double sigma() {
    return sigma;
  }

  @Override
  public String describe() {
    return "noise_floor(metric="
        + metric.wireName()
        + ", k="
        + SpecText.number(k)
        + ", sigma="
        + SpecText.number(sigma)
        + ")";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NoiseFloorSpec)) {
      return false;
    }
    NoiseFloorSpec that = (NoiseFloorSpec) o;
    return metric == that.metric
        && Double.compare(k, that.k) == 0
        && Double.compare(sigma, that.sigma) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(metric, k, sigma);
  }

  @Override
  public String toString() {
    return describe();
  }
}
