package dev.draftwatch.events;

import dev.draftwatch.detect.DetectorVerdict;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The detector could not evaluate the measurement, for example because the pair is incomparable;
 * always alerted.
 */
public final class DetectionError extends DetectionEvent {
  private final DetectorVerdict verdict;

  public DetectionError(Instant at, DetectionSubject subject, DetectorVerdict verdict) {
    super(at, subject);
    this.verdict = Objects.requireNonNull(verdict, "verdict");
    if (verdict.kind() != DetectorVerdict.Kind.ERROR) {
      throw new IllegalArgumentException(
          "DetectionError needs a ERROR verdict, got " + verdict.kind());
    }
  }

  @Override
  public Optional<DetectorVerdict> verdict() {
    return Optional.of(verdict);
  }

  @Override
  public String kind() {
    return "ERROR";
  }

  @Override
  public String explanation() {
    return verdict.detector() + ": " + verdict.explanation();
  }
}
