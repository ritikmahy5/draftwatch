package dev.draftwatch.config;

import dev.draftwatch.domain.Metric;
import java.util.Objects;

/**
 * {@code paired_bootstrap(metric, confidence=0.95, min_effect=0.0, resamples=2000,
 * bootstrap_seed=0)}: regression when the bootstrap confidence interval of (current − baseline)
 * lies entirely below −min_effect. The default detector (SPEC.md F5).
 */
public final class PairedBootstrapSpec implements DetectorSpec {
  public static final double DEFAULT_CONFIDENCE = 0.95;
  public static final double DEFAULT_MIN_EFFECT = 0.0;
  public static final int DEFAULT_RESAMPLES = 2000;
  public static final long DEFAULT_BOOTSTRAP_SEED = 0;

  private final Metric metric;
  private final double confidence;
  private final double minEffect;
  private final int resamples;
  private final long bootstrapSeed;

  private PairedBootstrapSpec(
      Metric metric, double confidence, double minEffect, int resamples, long bootstrapSeed) {
    this.metric = metric;
    this.confidence = confidence;
    this.minEffect = minEffect;
    this.resamples = resamples;
    this.bootstrapSeed = bootstrapSeed;
  }

  /**
   * Creates the spec.
   *
   * @throws IllegalArgumentException unless {@code 0 < confidence < 1}, {@code minEffect >= 0},
   *     and {@code resamples >= 1}
   */
  public static PairedBootstrapSpec of(
      Metric metric, double confidence, double minEffect, int resamples, long bootstrapSeed) {
    Objects.requireNonNull(metric, "metric");
    if (!(confidence > 0 && confidence < 1)) {
      throw new IllegalArgumentException("confidence must be in (0, 1), was " + confidence);
    }
    if (!(minEffect >= 0) || Double.isInfinite(minEffect)) {
      throw new IllegalArgumentException("min_effect must be a finite number >= 0");
    }
    if (resamples < 1) {
      throw new IllegalArgumentException("resamples must be >= 1, was " + resamples);
    }
    return new PairedBootstrapSpec(metric, confidence, minEffect, resamples, bootstrapSeed);
  }

  /** The default detector used when a target configures none: all defaults on {@code metric}. */
  public static PairedBootstrapSpec withDefaults(Metric metric) {
    return of(
        metric, DEFAULT_CONFIDENCE, DEFAULT_MIN_EFFECT, DEFAULT_RESAMPLES, DEFAULT_BOOTSTRAP_SEED);
  }

  @Override
  public Kind kind() {
    return Kind.PAIRED_BOOTSTRAP;
  }

  @Override
  public Metric metric() {
    return metric;
  }

  public double confidence() {
    return confidence;
  }

  public double minEffect() {
    return minEffect;
  }

  public int resamples() {
    return resamples;
  }

  /** Seed of the bootstrap's RNG, so intervals are reproducible. */
  public long bootstrapSeed() {
    return bootstrapSeed;
  }

  @Override
  public String describe() {
    return "paired_bootstrap(metric="
        + metric.wireName()
        + ", confidence="
        + SpecText.number(confidence)
        + ", min_effect="
        + SpecText.number(minEffect)
        + ", resamples="
        + resamples
        + ", bootstrap_seed="
        + bootstrapSeed
        + ")";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PairedBootstrapSpec)) {
      return false;
    }
    PairedBootstrapSpec that = (PairedBootstrapSpec) o;
    return metric == that.metric
        && Double.compare(confidence, that.confidence) == 0
        && Double.compare(minEffect, that.minEffect) == 0
        && resamples == that.resamples
        && bootstrapSeed == that.bootstrapSeed;
  }

  @Override
  public int hashCode() {
    return Objects.hash(metric, confidence, minEffect, resamples, bootstrapSeed);
  }

  @Override
  public String toString() {
    return describe();
  }
}
