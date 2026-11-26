package dev.draftwatch.scoring;

import dev.draftwatch.config.AbsoluteDropSpec;
import dev.draftwatch.config.PairedBootstrapSpec;
import dev.draftwatch.config.TrendSpec;
import dev.draftwatch.detect.AbsoluteDropDetector;
import dev.draftwatch.detect.PairedBootstrapDetector;
import dev.draftwatch.detect.TrendDetector;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.stats.MetricCalculator;
import java.util.List;

/** The detector configurations fixed in docs/EVALUATION.md, "Scoring the detectors". */
public final class DetectorConfigs {
  private DetectorConfigs() {}

  /** B0, B1, A, and T, in that order. */
  public static List<DetectorConfig> all(MetricCalculator calculator) {
    return List.of(
        DetectorConfig.of(
            "B0",
            new PairedBootstrapDetector(PairedBootstrapSpec.withDefaults(Metric.ALPHA), calculator)),
        DetectorConfig.of(
            "B1",
            new PairedBootstrapDetector(
                PairedBootstrapSpec.of(Metric.ALPHA, 0.95, 0.01, 2000, 0), calculator)),
        DetectorConfig.of("A", new AbsoluteDropDetector(AbsoluteDropSpec.of(Metric.ALPHA, 0.02))),
        DetectorConfig.of("T", new TrendDetector(TrendSpec.of(Metric.ALPHA, 4, -0.005))));
  }
}
