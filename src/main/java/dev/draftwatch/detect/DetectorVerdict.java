package dev.draftwatch.detect;

import dev.draftwatch.domain.Metric;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One detector's outcome for one measurement. Every detector reports
 * {@code observed} (current − baseline, or a slope) and flags a regression when
 * {@code observed < threshold}; the bootstrap compares its interval's upper bound instead.
 */
public final class DetectorVerdict {
  /** The outcomes of a detector (DECISIONS.md D43). */
  public enum Kind {
    /** Evaluated; no regression. */
    OK,
    /** Evaluated; a regression. */
    REGRESSION,
    /** Could not be evaluated because something is wrong (for example, incomparable inputs). */
    ERROR,
    /** Could not be evaluated yet because there is not enough data; not an error. */
    INSUFFICIENT_DATA
  }

  private final Kind kind;
  private final String detector;
  private final Metric metric;
  private final OptionalDouble observed;
  private final OptionalDouble threshold;
  private final OptionalDouble intervalLower;
  private final OptionalDouble intervalUpper;
  private final Optional<String> baselineJobId;
  private final String explanation;

  private DetectorVerdict(Builder b) {
    this.kind = b.kind;
    this.detector = b.detector;
    this.metric = b.metric;
    this.observed = b.observed;
    this.threshold = b.threshold;
    this.intervalLower = b.intervalLower;
    this.intervalUpper = b.intervalUpper;
    this.baselineJobId = b.baselineJobId;
    this.explanation = b.explanation;
  }

  /** A builder for {@code kind} from the detector described as {@code detector}. */
  public static Builder builder(Kind kind, String detector, Metric metric) {
    return new Builder(kind, detector, metric);
  }

  /** An ERROR verdict with just an explanation. */
  public static DetectorVerdict error(String detector, Metric metric, String explanation) {
    return builder(Kind.ERROR, detector, metric).explanation(explanation).build();
  }

  /** The ERROR for an incomparable pair: {@code incomparable: <field>} (SPEC.md F5). */
  public static DetectorVerdict incomparable(
      String detector, Metric metric, String field, String otherJobId) {
    return builder(Kind.ERROR, detector, metric)
        .baselineJobId(otherJobId)
        .explanation("incomparable: " + field)
        .build();
  }

  public static DetectorVerdict insufficient(String detector, Metric metric, String explanation) {
    return builder(Kind.INSUFFICIENT_DATA, detector, metric).explanation(explanation).build();
  }

  public Kind kind() {
    return kind;
  }

  /** The detector with its parameters, for example {@code absolute_drop(metric=alpha, …)}. */
  public String detector() {
    return detector;
  }

  public Metric metric() {
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

  /** The job of the measurement compared against, when there was one. */
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
    if (!(o instanceof DetectorVerdict)) {
      return false;
    }
    DetectorVerdict that = (DetectorVerdict) o;
    return kind == that.kind
        && detector.equals(that.detector)
        && metric == that.metric
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
        kind,
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
    return kind + " " + detector + ": " + explanation;
  }

  /** Builder for {@link DetectorVerdict}; an explanation is required. */
  public static final class Builder {
    private final Kind kind;
    private final String detector;
    private final Metric metric;
    private OptionalDouble observed = OptionalDouble.empty();
    private OptionalDouble threshold = OptionalDouble.empty();
    private OptionalDouble intervalLower = OptionalDouble.empty();
    private OptionalDouble intervalUpper = OptionalDouble.empty();
    private Optional<String> baselineJobId = Optional.empty();
    private String explanation;

    private Builder(Kind kind, String detector, Metric metric) {
      this.kind = Objects.requireNonNull(kind, "kind");
      this.detector = Objects.requireNonNull(detector, "detector");
      this.metric = Objects.requireNonNull(metric, "metric");
    }

    public Builder observed(double value) {
      this.observed = OptionalDouble.of(value);
      return this;
    }

    public Builder threshold(double value) {
      this.threshold = OptionalDouble.of(value);
      return this;
    }

    public Builder interval(double lower, double upper) {
      this.intervalLower = OptionalDouble.of(lower);
      this.intervalUpper = OptionalDouble.of(upper);
      return this;
    }

    public Builder baselineJobId(String jobId) {
      this.baselineJobId = Optional.of(jobId);
      return this;
    }

    public Builder explanation(String explanation) {
      this.explanation = explanation;
      return this;
    }

    /** @throws NullPointerException if no explanation was given */
    public DetectorVerdict build() {
      Objects.requireNonNull(explanation, "a verdict needs an explanation");
      return new DetectorVerdict(this);
    }
  }
}
