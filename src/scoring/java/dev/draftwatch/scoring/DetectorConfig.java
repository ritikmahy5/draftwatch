package dev.draftwatch.scoring;

import dev.draftwatch.detect.RegressionDetector;
import java.util.Objects;

/** One scored detector configuration: its id in the write-up (B0, B1, A, T) and the detector. */
public final class DetectorConfig {
  private final String id;
  private final RegressionDetector detector;

  private DetectorConfig(String id, RegressionDetector detector) {
    this.id = Objects.requireNonNull(id, "id");
    this.detector = Objects.requireNonNull(detector, "detector");
  }

  public static DetectorConfig of(String id, RegressionDetector detector) {
    return new DetectorConfig(id, detector);
  }

  public String id() {
    return id;
  }

  public RegressionDetector detector() {
    return detector;
  }

  @Override
  public String toString() {
    return id + " = " + detector.describe();
  }
}
