package dev.draftwatch.detect;

import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import java.util.List;
import java.util.Optional;

/**
 * A detector that compares the current measurement with the baseline's. Template Method: the
 * base class first calls the Comparability guard (SPEC.md F5: "every detector first calls the
 * Comparability guard") and handles a missing baseline, so subclasses cannot skip either.
 */
abstract class BaselineDetector implements RegressionDetector {
  @Override
  public final DetectorVerdict evaluate(
      Measurement current, Optional<Measurement> baseline, List<Measurement> history) {
    if (baseline.isEmpty()) {
      return DetectorVerdict.insufficient(
          describe(),
          metric(),
          "this is the first measurement of the baseline checkpoint; there is nothing to"
              + " compare it with");
    }
    Optional<String> mismatch = Comparability.mismatch(current, baseline.get());
    if (mismatch.isPresent()) {
      return DetectorVerdict.incomparable(
          describe(), metric(), mismatch.get(), baseline.get().jobId());
    }
    return compare(current, baseline.get());
  }

  /** Compares two comparable measurements. */
  protected abstract DetectorVerdict compare(Measurement current, Measurement baseline);

  /** The aggregate value of {@code metric}: the seed mean (SPEC.md F5). */
  static double aggregate(Measurement m, Metric metric) {
    AggregateMetrics a = m.report().aggregate();
    return metric == Metric.ALPHA ? a.alphaMean() : a.tauMean();
  }
}
