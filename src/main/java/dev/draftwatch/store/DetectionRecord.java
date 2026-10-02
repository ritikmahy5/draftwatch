package dev.draftwatch.store;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionSubject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One line of {@code detections.log}: a detection outcome with the result file its numbers come
 * from. Records are never edited; a later outcome for the same job is a new record.
 */
public final class DetectionRecord {
  private final Instant at;
  private final String kind;
  private final DetectionSubject subject;
  private final Optional<String> detector;
  private final Optional<String> metric;
  private final OptionalDouble observed;
  private final OptionalDouble threshold;
  private final OptionalDouble intervalLower;
  private final OptionalDouble intervalUpper;
  private final Optional<String> baselineJobId;
  private final String explanation;

  DetectionRecord(
      Instant at,
      String kind,
      DetectionSubject subject,
      Optional<String> detector,
      Optional<String> metric,
      OptionalDouble observed,
      OptionalDouble threshold,
      OptionalDouble intervalLower,
      OptionalDouble intervalUpper,
      Optional<String> baselineJobId,
      String explanation) {
    this.at = Objects.requireNonNull(at, "at");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.subject = Objects.requireNonNull(subject, "subject");
    this.detector = detector;
    this.metric = metric;
    this.observed = observed;
    this.threshold = threshold;
    this.intervalLower = intervalLower;
    this.intervalUpper = intervalUpper;
    this.baselineJobId = baselineJobId;
    this.explanation = Objects.requireNonNull(explanation, "explanation");
  }

  /** The record of {@code event}. */
  public static DetectionRecord of(DetectionEvent event) {
    Optional<DetectorVerdict> v = event.verdict();
    return new DetectionRecord(
        event.at(),
        event.kind(),
        event.subject(),
        v.map(DetectorVerdict::detector),
        v.map(x -> x.metric().wireName()),
        v.map(DetectorVerdict::observed).orElse(OptionalDouble.empty()),
        v.map(DetectorVerdict::threshold).orElse(OptionalDouble.empty()),
        v.map(DetectorVerdict::intervalLower).orElse(OptionalDouble.empty()),
        v.map(DetectorVerdict::intervalUpper).orElse(OptionalDouble.empty()),
        v.flatMap(DetectorVerdict::baselineJobId),
        v.map(DetectorVerdict::explanation).orElse(event.explanation()));
  }

  public Instant at() {
    return at;
  }

  /**
   * {@code OK}, {@code REGRESSION}, {@code ERROR}, {@code INSUFFICIENT_DATA}, or
   * {@code DEFERRED}.
   */
  public String kind() {
    return kind;
  }

  public DetectionSubject subject() {
    return subject;
  }

  public String target() {
    return subject.target();
  }

  public String probeHash() {
    return subject.probeHash();
  }

  public String jobId() {
    return subject.jobId();
  }

  public Path resultFile() {
    return subject.resultFile();
  }

  public Optional<String> detector() {
    return detector;
  }

  public Optional<String> metric() {
    return metric;
  }

  public OptionalDouble observed() {
    return observed;
  }

  public OptionalDouble threshold() {
    return threshold;
  }

  public OptionalDouble intervalLower() {
    return intervalLower;
  }

  public OptionalDouble intervalUpper() {
    return intervalUpper;
  }

  public Optional<String> baselineJobId() {
    return baselineJobId;
  }

  public String explanation() {
    return explanation;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DetectionRecord)) {
      return false;
    }
    DetectionRecord that = (DetectionRecord) o;
    return at.equals(that.at)
        && kind.equals(that.kind)
        && subject.equals(that.subject)
        && detector.equals(that.detector)
        && metric.equals(that.metric)
        && observed.equals(that.observed)
        && threshold.equals(that.threshold)
        && intervalLower.equals(that.intervalLower)
        && intervalUpper.equals(that.intervalUpper)
        && baselineJobId.equals(that.baselineJobId)
        && explanation.equals(that.explanation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        at,
        kind,
        subject,
        detector,
        metric,
        observed,
        threshold,
        intervalLower,
        intervalUpper,
        baselineJobId,
        explanation);
  }

  @Override
  public String toString() {
    return kind + " " + subject + detector.map(d -> " " + d).orElse("") + ": " + explanation;
  }
}
