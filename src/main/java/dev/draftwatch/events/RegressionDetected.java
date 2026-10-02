package dev.draftwatch.events;

import dev.draftwatch.detect.DetectorVerdict;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** The detector found a regression. */
public final class RegressionDetected extends DetectionEvent {
  private final DetectorVerdict verdict;

  public RegressionDetected(Instant at, DetectionSubject subject, DetectorVerdict verdict) {
    super(at, subject);
    this.verdict = Objects.requireNonNull(verdict, "verdict");
    if (verdict.kind() != DetectorVerdict.Kind.REGRESSION) {
      throw new IllegalArgumentException(
          "RegressionDetected needs a REGRESSION verdict, got " + verdict.kind());
    }
  }

  @Override
  public Optional<DetectorVerdict> verdict() {
    return Optional.of(verdict);
  }

  @Override
  public String kind() {
    return "REGRESSION";
  }

  @Override
  public String explanation() {
    return verdict.detector() + ": " + verdict.explanation();
  }
}
