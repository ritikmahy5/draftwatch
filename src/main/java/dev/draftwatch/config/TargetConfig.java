package dev.draftwatch.config;

import dev.draftwatch.domain.Target;
import java.util.List;
import java.util.Objects;

/**
 * One entry of {@code targets}: the target itself plus how draftwatch watches it: completion
 * policy, probes to run, trigger chain, detectors, and regression actions.
 */
public final class TargetConfig {
  private final Target target;
  private final CompletionSpec completion;
  private final List<String> probeIds;
  private final List<TriggerSpec> triggers;
  private final List<DetectorSpec> detectors;
  private final List<ActionKind> onRegression;

  private TargetConfig(
      Target target,
      CompletionSpec completion,
      List<String> probeIds,
      List<TriggerSpec> triggers,
      List<DetectorSpec> detectors,
      List<ActionKind> onRegression) {
    this.target = target;
    this.completion = completion;
    this.probeIds = probeIds;
    this.triggers = triggers;
    this.detectors = detectors;
    this.onRegression = onRegression;
  }

  /**
   * Creates a target config. Cross-entry rules (probe ids exist, trigger order) are checked by
   * {@link ConfigValidator}, which reports them with field names.
   *
   * @throws IllegalArgumentException if {@code probeIds}, {@code triggers}, or
   *     {@code detectors} is empty
   */
  public static TargetConfig of(
      Target target,
      CompletionSpec completion,
      List<String> probeIds,
      List<TriggerSpec> triggers,
      List<DetectorSpec> detectors,
      List<ActionKind> onRegression) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(completion, "completion");
    List<String> probes = nonEmpty(probeIds, "probes");
    List<TriggerSpec> chain = nonEmpty(triggers, "triggers");
    List<DetectorSpec> dets = nonEmpty(detectors, "detectors");
    return new TargetConfig(
        target, completion, probes, chain, dets, List.copyOf(onRegression));
  }

  private static <T> List<T> nonEmpty(List<T> values, String field) {
    List<T> copy = List.copyOf(values);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
    return copy;
  }

  public Target target() {
    return target;
  }

  public String name() {
    return target.name();
  }

  public CompletionSpec completion() {
    return completion;
  }

  /** Ids of probes in {@link DraftwatchConfig#probes()}, in configured order. */
  public List<String> probeIds() {
    return probeIds;
  }

  /** The trigger chain in evaluation order; the default chain if none was configured. */
  public List<TriggerSpec> triggers() {
    return triggers;
  }

  /** Detectors; {@code paired_bootstrap} on {@code alpha} if none was configured. */
  public List<DetectorSpec> detectors() {
    return detectors;
  }

  public List<ActionKind> onRegression() {
    return onRegression;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TargetConfig)) {
      return false;
    }
    TargetConfig that = (TargetConfig) o;
    return target.equals(that.target)
        && completion.equals(that.completion)
        && probeIds.equals(that.probeIds)
        && triggers.equals(that.triggers)
        && detectors.equals(that.detectors)
        && onRegression.equals(that.onRegression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(target, completion, probeIds, triggers, detectors, onRegression);
  }

  @Override
  public String toString() {
    return "TargetConfig{" + target.name() + "}";
  }
}
