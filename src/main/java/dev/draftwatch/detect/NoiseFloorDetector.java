package dev.draftwatch.detect;

import dev.draftwatch.config.NoiseFloorSpec;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import java.util.Objects;

/**
 * {@code noise_floor}: a regression when baseline − current > k · √2 · sigma (SPEC.md F5). √2
 * turns {@code sigma}, the standard deviation of one measurement, into that of a difference of
 * two.
 */
public final class NoiseFloorDetector extends BaselineDetector {
  private final NoiseFloorSpec spec;

  public NoiseFloorDetector(NoiseFloorSpec spec) {
    this.spec = Objects.requireNonNull(spec, "spec");
  }

  @Override
  public String describe() {
    return spec.describe();
  }

  @Override
  public Metric metric() {
    return spec.metric();
  }

  /** {@code k · √2 · sigma}. */
  double floor() {
    return spec.k() * Math.sqrt(2) * spec.sigma();
  }

  @Override
  protected DetectorVerdict compare(Measurement current, Measurement baseline) {
    return Thresholds.verdict(
        describe(),
        metric(),
        aggregate(current, metric()) - aggregate(baseline, metric()),
        -floor(),
        baseline.jobId(),
        "k * sqrt(2) * sigma = " + floor());
  }
}
