package dev.draftwatch.detect;

import dev.draftwatch.domain.Metric;

/** The shared rule of the threshold detectors: a regression when {@code observed < threshold}. */
final class Thresholds {
  private Thresholds() {}

  static DetectorVerdict verdict(
      String detector,
      Metric metric,
      double observed,
      double threshold,
      String baselineJobId,
      String thresholdSource) {
    boolean regression = observed < threshold;
    String text =
        "current - baseline " + metric.wireName() + " is " + observed
            + (regression ? ", below " : ", not below ") + threshold + " (" + thresholdSource + ")";
    return DetectorVerdict.builder(
            regression ? DetectorVerdict.Kind.REGRESSION : DetectorVerdict.Kind.OK,
            detector,
            metric)
        .observed(observed)
        .threshold(threshold)
        .baselineJobId(baselineJobId)
        .explanation(text)
        .build();
  }
}
