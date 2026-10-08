package dev.draftwatch.events;

import dev.draftwatch.detect.DetectorVerdict;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Detection for a measurement waits until the baseline checkpoint has a result for its probe
 * (SPEC.md F5, "Missing baseline measurement").
 */
public final class DetectionDeferred extends DetectionEvent {
  private final String reason;

  public DetectionDeferred(Instant at, DetectionSubject subject, String reason) {
    super(at, subject);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  @Override
  public Optional<DetectorVerdict> verdict() {
    return Optional.empty();
  }

  @Override
  public String kind() {
    return "DEFERRED";
  }

  @Override
  public String explanation() {
    return reason;
  }
}
