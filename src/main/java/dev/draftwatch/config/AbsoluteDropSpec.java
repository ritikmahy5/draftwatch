package dev.draftwatch.config;

import dev.draftwatch.domain.Metric;
import java.util.Objects;

/** {@code absolute_drop(metric, max_drop)}: regression when baseline − current &gt; max_drop. */
public final class AbsoluteDropSpec implements DetectorSpec {
  private final Metric metric;
  private final double maxDrop;

  private AbsoluteDropSpec(Metric metric, double maxDrop) {
    this.metric = metric;
    this.maxDrop = maxDrop;
  }

  /** @throws IllegalArgumentException unless {@code maxDrop} is finite and {@code >= 0} */
  public static AbsoluteDropSpec of(Metric metric, double maxDrop) {
    Objects.requireNonNull(metric, "metric");
    if (!(maxDrop >= 0) || Double.isInfinite(maxDrop)) {
      throw new IllegalArgumentException("max_drop must be a finite number >= 0");
    }
    return new AbsoluteDropSpec(metric, maxDrop);
  }

  @Override
  public Kind kind() {
    return Kind.ABSOLUTE_DROP;
  }

  @Override
  public Metric metric() {
    return metric;
  }

  public double maxDrop() {
    return maxDrop;
  }

  @Override
  public String describe() {
    return "absolute_drop(metric="
        + metric.wireName()
        + ", max_drop="
        + SpecText.number(maxDrop)
        + ")";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AbsoluteDropSpec)) {
      return false;
    }
    AbsoluteDropSpec that = (AbsoluteDropSpec) o;
    return metric == that.metric && Double.compare(maxDrop, that.maxDrop) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(metric, maxDrop);
  }

  @Override
  public String toString() {
    return describe();
  }
}
