package dev.draftwatch.detect;

import dev.draftwatch.config.AbsoluteDropSpec;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import java.util.Objects;

/** {@code absolute_drop}: a regression when baseline − current > max_drop (SPEC.md F5). */
public final class AbsoluteDropDetector extends BaselineDetector {
  private final AbsoluteDropSpec spec;

  public AbsoluteDropDetector(AbsoluteDropSpec spec) {
    this.spec = Objects.requireNonNull(spec, "spec");
  }

  @Override
  public String describe() {
    return spec.describe();
  }

  @Override
  public Metric metric() {
    return spec.metric();
  }

  @Override
  protected DetectorVerdict compare(Measurement current, Measurement baseline) {
    return Thresholds.verdict(
        describe(),
        metric(),
        aggregate(current, metric()) - aggregate(baseline, metric()),
        Thresholds.below(spec.maxDrop()),
        baseline.jobId(),
        "max_drop " + spec.maxDrop());
  }
}
