package dev.draftwatch.events;

import dev.draftwatch.detect.DetectorVerdict;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A detection outcome for one measurement. Subclasses exist so subscribers can register for
 * exactly the outcomes they handle: notifiers for {@link RegressionDetected} and
 * {@link DetectionError}, the detection log for every {@code DetectionEvent}.
 */
public abstract class DetectionEvent implements Event {
  private final Instant at;
  private final DetectionSubject subject;

  DetectionEvent(Instant at, DetectionSubject subject) {
    this.at = Objects.requireNonNull(at, "at");
    this.subject = Objects.requireNonNull(subject, "subject");
  }

  /** The event for {@code verdict}'s kind. */
  public static DetectionEvent of(Instant at, DetectionSubject subject, DetectorVerdict verdict) {
    switch (verdict.kind()) {
      case OK:
        return new DetectionOk(at, subject, verdict);
      case REGRESSION:
        return new RegressionDetected(at, subject, verdict);
      case ERROR:
        return new DetectionError(at, subject, verdict);
      case INSUFFICIENT_DATA:
        return new DetectionInsufficientData(at, subject, verdict);
      default:
        throw new IllegalArgumentException("unhandled verdict kind " + verdict.kind());
    }
  }

  @Override
  public Instant at() {
    return at;
  }

  public DetectionSubject subject() {
    return subject;
  }

  /** The detector's verdict; empty only for {@link DetectionDeferred}. */
  public abstract Optional<DetectorVerdict> verdict();

  /**
   * {@code OK}, {@code REGRESSION}, {@code ERROR}, {@code INSUFFICIENT_DATA}, or
   * {@code DEFERRED}.
   */
  public abstract String kind();

  /** One line: what happened and why. */
  public abstract String explanation();

  @Override
  public String toString() {
    return kind() + " " + subject + ": " + explanation();
  }
}
