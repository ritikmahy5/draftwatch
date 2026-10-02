package dev.draftwatch.events;

import dev.draftwatch.detect.DetectorVerdict;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** The detector found no regression. */
public final class DetectionOk extends DetectionEvent {
  private final DetectorVerdict verdict;

  public DetectionOk(Instant at, DetectionSubject subject, DetectorVerdict verdict) {
    super(at, subject);
    this.verdict = Objects.requireNonNull(verdict, "verdict");
    if (verdict.kind() != DetectorVerdict.Kind.OK) {
      throw new IllegalArgumentException(
          "DetectionOk needs a OK verdict, got " + verdict.kind());
    }
  }

  @Override
  public Optional<DetectorVerdict> verdict() {
    return Optional.of(verdict);
  }

  @Override
  public String kind() {
    return "OK";
  }

  @Override
  public String explanation() {
    return verdict.detector() + ": " + verdict.explanation();
  }
}
