package dev.draftwatch.events;

import dev.draftwatch.detect.DetectorVerdict;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** The detector cannot evaluate the measurement yet; not an error and not alerted. */
public final class DetectionInsufficientData extends DetectionEvent {
  private final DetectorVerdict verdict;

  public DetectionInsufficientData(Instant at, DetectionSubject subject, DetectorVerdict verdict) {
    super(at, subject);
    this.verdict = Objects.requireNonNull(verdict, "verdict");
    if (verdict.kind() != DetectorVerdict.Kind.INSUFFICIENT_DATA) {
      throw new IllegalArgumentException(
          "DetectionInsufficientData needs a INSUFFICIENT_DATA verdict, got " + verdict.kind());
    }
  }

  @Override
  public Optional<DetectorVerdict> verdict() {
    return Optional.of(verdict);
  }

  @Override
  public String kind() {
    return "INSUFFICIENT_DATA";
  }

  @Override
  public String explanation() {
    return verdict.detector() + ": " + verdict.explanation();
  }
}
