package dev.draftwatch.scoring;

import dev.draftwatch.config.PairedBootstrapSpec;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.detect.PairedBootstrapDetector;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.stats.MetricCalculator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ground truth (docs/EVALUATION.md, "Ground truth"): Δ_ref and its 95% interval come from
 * draftwatch's own paired bootstrap (2,000 resamples, seed 0) on all of a reference probe's
 * prompts, and the label needs both the point estimate and the interval to agree, at δ = 0.02.
 */
public final class GroundTruth {
  /** The practical threshold δ in {@code alpha}. */
  public static final double DELTA = 0.02;

  private final PairedBootstrapDetector bootstrap;

  public GroundTruth(MetricCalculator calculator) {
    this.bootstrap =
        new PairedBootstrapDetector(
            PairedBootstrapSpec.withDefaults(Metric.ALPHA),
            Objects.requireNonNull(calculator, "calculator"));
  }

  /**
   * REGRESSED iff Δ ≤ −δ and U < −δ/2; NOT_REGRESSED iff Δ ≥ −δ/2 and L > −δ; otherwise
   * AMBIGUOUS.
   */
  public static Label label(double delta, double lower, double upper) {
    if (delta <= -DELTA && upper < -DELTA / 2) {
      return Label.REGRESSED;
    }
    if (delta >= -DELTA / 2 && lower > -DELTA) {
      return Label.NOT_REGRESSED;
    }
    return Label.AMBIGUOUS;
  }

  /**
   * Δ_ref of {@code current} against {@code baseline}.
   *
   * @throws IllegalStateException if the bootstrap gives no interval (incomparable inputs or an
   *     undefined statistic), which validated reference data never should
   */
  public ReferenceDifference reference(Measurement current, Measurement baseline) {
    DetectorVerdict v = bootstrap.evaluate(current, Optional.of(baseline), List.of());
    if (v.kind() != DetectorVerdict.Kind.OK && v.kind() != DetectorVerdict.Kind.REGRESSION) {
      throw new IllegalStateException("no reference interval for job " + current.jobId()
          + " against " + baseline.jobId() + ": " + v.kind() + ", " + v.explanation());
    }
    return ReferenceDifference.of(
        v.observed().getAsDouble(),
        v.intervalLower().getAsDouble(),
        v.intervalUpper().getAsDouble());
  }
}
