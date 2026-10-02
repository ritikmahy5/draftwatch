package dev.draftwatch.detect;

import dev.draftwatch.config.CanonicalJson;
import dev.draftwatch.config.PairedBootstrapSpec;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.stats.BootstrapInterval;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.stats.PairedBootstrap;
import dev.draftwatch.stats.UndefinedStatisticException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * {@code paired_bootstrap}: a regression when the bootstrap confidence interval of
 * (current − baseline), resampling prompts with pairing, lies entirely below −min_effect
 * (SPEC.md F5). Counts are pooled over seeds and the metric is recomputed under the probe's
 * estimator on every resample (ARCHITECTURE.md, "Statistics").
 */
public final class PairedBootstrapDetector extends BaselineDetector {
  private final PairedBootstrapSpec spec;
  private final MetricCalculator calculator;

  public PairedBootstrapDetector(PairedBootstrapSpec spec, MetricCalculator calculator) {
    this.spec = Objects.requireNonNull(spec, "spec");
    this.calculator = Objects.requireNonNull(calculator, "calculator");
  }

  @Override
  public String describe() {
    return spec.describe();
  }

  @Override
  public Metric metric() {
    return spec.metric();
  }

  /** {@code 0.95} as {@code 95}, exactly (no binary floating-point artifacts). */
  static String percent(double fraction) {
    return CanonicalJson.canonicalDecimal(BigDecimal.valueOf(fraction).movePointRight(2));
  }

  @Override
  protected DetectorVerdict compare(Measurement current, Measurement baseline) {
    Estimator estimator = current.report().estimator();
    Function<List<PromptCounts>, OptionalDouble> statistic =
        spec.metric() == Metric.ALPHA
            ? prompts -> calculator.alpha(estimator, prompts)
            : prompts -> calculator.tau(estimator, prompts);
    BootstrapInterval interval;
    try {
      interval =
          PairedBootstrap.run(
              calculator.pooledPerPrompt(current.report().seeds()),
              calculator.pooledPerPrompt(baseline.report().seeds()),
              statistic,
              spec.resamples(),
              spec.confidence(),
              spec.bootstrapSeed());
    } catch (UndefinedStatisticException e) {
      return DetectorVerdict.builder(DetectorVerdict.Kind.ERROR, describe(), metric())
          .baselineJobId(baseline.jobId())
          .explanation(e.getMessage())
          .build();
    }
    double threshold = -spec.minEffect();
    boolean regression = interval.upper() < threshold;
    String text =
        percent(spec.confidence()) + "% interval of (current - baseline) "
            + metric().wireName() + " is [" + interval.lower() + ", " + interval.upper()
            + "], observed " + interval.observed() + "; "
            + (regression ? "entirely below " : "not entirely below ") + threshold;
    return DetectorVerdict.builder(
            regression ? DetectorVerdict.Kind.REGRESSION : DetectorVerdict.Kind.OK,
            describe(),
            metric())
        .observed(interval.observed())
        .threshold(threshold)
        .interval(interval.lower(), interval.upper())
        .baselineJobId(baseline.jobId())
        .explanation(text)
        .build();
  }
}
