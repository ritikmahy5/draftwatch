package dev.draftwatch.detect;

import dev.draftwatch.domain.Measurement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs a target's detectors on one measurement. Never throws: a detector that fails becomes an
 * ERROR verdict, so one broken detector cannot stop the others or the {@code watch} loop.
 */
public final class DetectorSuite {
  private final List<RegressionDetector> detectors;

  public DetectorSuite(List<RegressionDetector> detectors) {
    this.detectors = List.copyOf(detectors);
  }

  public List<RegressionDetector> detectors() {
    return detectors;
  }

  /** One verdict per detector, in configured order. */
  public List<DetectorVerdict> run(
      Measurement current, Optional<Measurement> baseline, List<Measurement> history) {
    List<DetectorVerdict> verdicts = new ArrayList<>();
    for (RegressionDetector detector : detectors) {
      try {
        verdicts.add(detector.evaluate(current, baseline, history));
      } catch (RuntimeException e) {
        verdicts.add(
            DetectorVerdict.error(
                detector.describe(),
                detector.metric(),
                "detector failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
      }
    }
    return verdicts;
  }
}
